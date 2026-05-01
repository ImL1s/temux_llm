package dev.temuxllm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlin.concurrent.thread

/**
 * Foreground service that owns the localhost HTTP server and the in-process
 * LiteRT-LM Engine.
 */
class LlmService : Service() {
    private val tag = "LlmService"
    private val notifId = 11434
    private val channelId = "temuxllm"

    private var engine: LlmEngine? = null
    private var server: HttpServer? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        startEngineAndServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(tag, "onStartCommand intent=${intent?.action}")
        return START_STICKY
    }

    override fun onDestroy() {
        try { server?.stop() } catch (t: Throwable) { Log.w(tag, "stop server", t) }
        try { engine?.close() } catch (t: Throwable) { Log.w(tag, "close engine", t) }
        server = null
        engine = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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

        // Start HTTP first so /healthz responds quickly. Engine init is heavy
        // (model staging copy + first inference still cold) — defer to a worker
        // so the service doesn't ANR if the staging copy of a 2-3 GB model
        // takes 10-30 s on first launch.
        val s = HttpServer(applicationContext, e).also { server = it }
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
