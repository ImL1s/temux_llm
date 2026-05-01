package dev.temuxllm

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlin.concurrent.thread

/**
 * Foreground service that owns the localhost HTTP server and the in-process
 * LiteRT-LM Engine.
 *
 * v0.4.0 wires in three memory-hygiene additions:
 *
 *  - On startup, reads getHistoricalProcessExitReasons. If the previous
 *    exit was lowmemorykiller-class, [AutoFallback.persistFallback] writes
 *    a downshifted maxNumTokens to auto_max_tokens.conf so the next engine
 *    init uses a tier the device demonstrably survives.
 *  - [setProcessStateSummary] is called with a 14-byte packed payload on
 *    engine init so the *next* post-mortem can identify what was loaded.
 *  - [onTrimMemory] handles RUNNING_CRITICAL by stopping the memory probe
 *    and closing the engine, in that order, before the kernel kills us.
 */
class LlmService : Service() {
    private val tag = "LlmService"
    private val notifId = 11434
    private val channelId = "temuxllm"

    private var engine: LlmEngine? = null
    private var server: HttpServer? = null
    private var memoryProbe: MemoryProbe? = null

    override fun onCreate() {
        super.onCreate()
        autoFallbackOnStart()
        startInForeground()
        startEngineAndServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(tag, "onStartCommand intent=${intent?.action}")
        return START_STICKY
    }

    /**
     * Shutdown order matters (codex outside-review): cancel any in-flight
     * inference work first, close the probe and HTTP server, THEN close
     * the engine. Closing the engine while the worker thread still owns
     * a Conversation handle leaves a dangling JNI reference.
     */
    override fun onDestroy() {
        try { memoryProbe?.shutdown() } catch (t: Throwable) { Log.w(tag, "stop probe", t) }
        try { server?.stop() } catch (t: Throwable) { Log.w(tag, "stop server", t) }
        try { engine?.close() } catch (t: Throwable) { Log.w(tag, "close engine", t) }
        memoryProbe = null
        server = null
        engine = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Called by the system when memory pressure rises. Level
     * RUNNING_CRITICAL means the kernel is one decision away from killing
     * us; we close the engine immediately so the LMK has less to reclaim.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.i(tag, "onTrimMemory level=$level")
        // Android's TRIM_MEMORY_* constants are NOT linearly ordered:
        //   RUNNING_MODERATE=5, RUNNING_LOW=10, RUNNING_CRITICAL=15,
        //   UI_HIDDEN=20, BACKGROUND=40, MODERATE=60, COMPLETE=80
        // `>= RUNNING_CRITICAL` would catch UI_HIDDEN, which means the
        // service tab got swiped away — NOT a memory emergency. codex
        // outside-review v0.4.0. Match only the actual urgency signals.
        val isUrgent = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        if (isUrgent) {
            // Engine close races with in-flight generate() flows. Bridge
            // through the engine's inference mutex so any active
            // Conversation finishes (or its caller catches the
            // CancellationException) before we tear down the JNI handle.
            try { memoryProbe?.stop() } catch (_: Throwable) {}
            try { engine?.closeUnderInferenceLock() } catch (t: Throwable) {
                Log.w(tag, "engine close on trim", t)
            }
            // Keep the engine instance alive so the next request triggers
            // ensureEngine() rebuild. Don't null it out.
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    /**
     * If the previous run was killed by lowmemorykiller, downshift the
     * maxNumTokens ceiling for this run. Persists to auto_max_tokens.conf
     * with the device fingerprint — independent of user-owned
     * temuxllm.conf so a manual override is never clobbered.
     */
    private fun autoFallbackOnStart() {
        try {
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val packed = AutoFallback.lastExitWasLmk(am, packageName) ?: return
            val unpacked = AutoFallback.unpackStateSummary(packed)
            val current = unpacked?.first ?: 16384
            val next = AutoFallback.nextLowerTier(current)
            if (next < current) {
                AutoFallback.persistFallback(
                    applicationContext, next,
                    AutoFallback.deviceFingerprint(applicationContext),
                    "previous_exit_lmk_at_$current",
                )
            }
        } catch (t: Throwable) {
            Log.w(tag, "autoFallbackOnStart failed", t)
        }
    }

    private fun startInForeground() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            mgr.createNotificationChannel(
                NotificationChannel(channelId, "LiteRT-LM service", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif: Notification = Notification.Builder(this, channelId)
            .setContentTitle("LiteRT-LM running")
            .setContentText("listening on 127.0.0.1:11434")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(notifId, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(notifId, notif)
        }
        Log.i(tag, "service in foreground")
    }

    private fun startEngineAndServer() {
        val e = LlmEngine(applicationContext).also { engine = it }
        val registry = ModelRegistry(applicationContext, e)
        memoryProbe = MemoryProbe(applicationContext)

        // Start HTTP first so /healthz responds quickly. Engine init is heavy
        // (model staging copy + first inference still cold) — defer to a worker
        // so the service doesn't ANR if the staging copy of a 2-3 GB model
        // takes 10-30 s on first launch.
        val s = HttpServer(applicationContext, e, registry).also { server = it }
        s.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        Log.i(tag, "http server started on 127.0.0.1:${HttpServer.PORT}")

        thread(start = true, name = "litertlm-warmup") {
            try {
                e.ensureModelStaged()
                Log.i(tag, "model staged; first /api/generate will lazily initialize the Engine")
            } catch (t: Throwable) {
                Log.w(tag, "model not yet staged; push it from host first", t)
            }
        }
    }
}
