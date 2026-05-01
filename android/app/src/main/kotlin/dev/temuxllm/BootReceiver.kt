package dev.temuxllm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Auto-start the LiteRT-LM foreground service after device reboot.
 *
 * Android delivers BOOT_COMPLETED only to apps that the user has launched at
 * least once after install (this is the "broadcasts blocked until first run"
 * rule that lives in package state). So this only kicks in on second boot
 * onwards — exactly the right semantics for "I started it on device once,
 * keep it running across reboots."
 */
class BootReceiver : BroadcastReceiver() {
    private val tag = "BootReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON") {
            Log.w(tag, "ignoring unexpected action: $action")
            return
        }
        Log.i(tag, "$action received; starting LlmService")
        val svc = Intent(context, LlmService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
        } catch (t: Throwable) {
            // If the system blocks us (background-start restrictions on some
            // OEMs / power modes), there is nothing we can do here — the user
            // can always tap the launcher icon, which goes through
            // LauncherActivity instead.
            Log.w(tag, "failed to start LlmService from boot receiver", t)
        }
    }
}
