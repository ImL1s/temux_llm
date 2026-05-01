package dev.temuxllm

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast

/**
 * Visible app icon → starts the foreground service → immediately finishes.
 *
 * The activity uses Theme.NoDisplay so the user never sees a window: tap icon
 * in the launcher, get a brief toast confirming service is running, done.
 */
class LauncherActivity : Activity() {
    private val tag = "LauncherActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val svc = Intent(this, LlmService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(svc)
            } else {
                startService(svc)
            }
            Toast.makeText(this, "temuxllm service running on 127.0.0.1:11434", Toast.LENGTH_SHORT).show()
            Log.i(tag, "LlmService start requested via launcher tap")
        } catch (t: Throwable) {
            Log.e(tag, "failed to start LlmService from launcher", t)
            Toast.makeText(this, "temuxllm: failed to start (${t.javaClass.simpleName})", Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
