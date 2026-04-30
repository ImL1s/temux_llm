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

/**
 * Foreground service that owns the localhost HTTP server.
 *
 * Phase 2a: just /healthz and /api/version. No LiteRT-LM yet.
 */
class LlmService : Service() {
    private val tag = "LlmService"
    private val notifId = 11434
    private val channelId = "temuxllm"

    private var server: HttpServer? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        startHttpServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(tag, "onStartCommand intent=${intent?.action}")
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            server?.stop()
        } catch (t: Throwable) {
            Log.w(tag, "stopping server", t)
        }
        server = null
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

    private fun startHttpServer() {
        try {
            // jniLibs are extracted by Android at install time; just verify the
            // binary is reachable and executable.
            val ready = LiteRtLmRunner(applicationContext).checkReady()
            Log.i(tag, "litert_lm_main ready = $ready")

            val s = HttpServer(applicationContext)
            s.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = s
            Log.i(tag, "http server started on 127.0.0.1:${HttpServer.PORT}")
        } catch (t: Throwable) {
            Log.e(tag, "failed to start http server", t)
        }
    }
}
