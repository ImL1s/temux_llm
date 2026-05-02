package dev.temuxllm

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Memory-aware degradation utilities.
 *
 * Two responsibilities:
 *
 *   1) Read `getHistoricalProcessExitReasons` on service start. If the
 *      previous exit was Android's lowmemorykiller (`REASON_LOW_MEMORY` or
 *      a SIGKILL whose [setProcessStateSummary] payload says we were
 *      mid-inference), step `maxNumTokens` down one tier and persist to
 *      `<filesDir>/auto_max_tokens.conf` (NOT `temuxllm.conf`, which is
 *      user-owned per codex outside-review v0.4.0). The persisted value
 *      includes a device fingerprint so a hardware change invalidates it.
 *
 *   2) Pack inference state into <= 128 bytes for [setProcessStateSummary]
 *      so the post-mortem on the next start can identify which
 *      `maxNumTokens` setting was loaded when we died. Layout (14 bytes):
 *
 *        version : u8  (= 1)
 *        maxTokens : i32 (BE)
 *        backend : u8  (0 = cpu, 1 = gpu)
 *        modelHash : i64 (BE; java hashCode of model name as long)
 */
object AutoFallback {

    private const val TAG = "AutoFallback"
    private const val SUMMARY_VERSION: Byte = 1
    private const val SUMMARY_BYTES = 14

    /**
     * Return the largest rung STRICTLY less than [current]. Floor = 4096.
     * Codex outside-review v0.4.0: a 20 000-token killed run should drop
     * to 16 384 (the rung just below 20 000), not to 8 192 — one step,
     * not two. For values that ARE on a rung, `current = 16384` lands
     * at 8192 (the next-lower rung) as expected.
     */
    fun nextLowerTier(current: Int): Int {
        val ladder = intArrayOf(4096, 8192, 16384, 24576, 32768)
        return ladder.lastOrNull { it < current } ?: ladder.first()
    }

    fun packStateSummary(maxNumTokens: Int, backend: String, modelName: String): ByteArray {
        val buf = ByteBuffer.allocate(SUMMARY_BYTES)
        buf.put(SUMMARY_VERSION)
        buf.putInt(maxNumTokens)
        buf.put(if (backend.equals("gpu", ignoreCase = true)) 1.toByte() else 0.toByte())
        buf.putLong(modelName.hashCode().toLong())
        return buf.array()
    }

    /** Returns (maxNumTokens, backend, modelHash) or null on parse failure. */
    fun unpackStateSummary(bytes: ByteArray?): Triple<Int, String, Long>? {
        if (bytes == null || bytes.size < SUMMARY_BYTES) return null
        if (bytes[0] != SUMMARY_VERSION) return null
        val buf = ByteBuffer.wrap(bytes)
        buf.get() // version
        val max = buf.int
        val backend = if (buf.get().toInt() == 1) "gpu" else "cpu"
        val modelHash = buf.long
        return Triple(max, backend, modelHash)
    }

    /**
     * Read the most-recent exit reason. Returns the packed
     * [setProcessStateSummary] payload that was active at death, or null
     * if the last exit was not LMK-class.
     */
    fun lastExitWasLmk(am: ActivityManager, packageName: String): ByteArray? {
        if (Build.VERSION.SDK_INT < 30) return null
        // Pull only ONE record — most recent first (per AOSP docs). codex
        // outside-review caught a bug where scanning 5 records would
        // re-trigger downshift on every restart even after a clean exit
        // long after the original LMK.
        val reasons = try {
            am.getHistoricalProcessExitReasons(packageName, 0, 1)
        } catch (t: Throwable) {
            Log.w(TAG, "getHistoricalProcessExitReasons failed", t)
            return null
        }
        val r = reasons.firstOrNull() ?: return null
        // REASON_LOW_MEMORY (10) is the explicit LMK signal — but many
        // OEMs report SIGKILL (REASON_SIGNALED + signal=9) instead.
        // v0.7.1 (codex PR review P2#4): use ApplicationExitInfo.status
        // for the signal number (Android docs: "for REASON_SIGNALED,
        // this field is the signal number"). The previous heuristic of
        // `description.contains("9")` produced false positives (any
        // description like "killed at 19:00" matched) and false
        // negatives (null / empty descriptions on stock AOSP).
        val isLikelyLmk = r.reason == ApplicationExitInfo.REASON_LOW_MEMORY ||
            (r.reason == ApplicationExitInfo.REASON_SIGNALED && r.status == 9)
        if (!isLikelyLmk) return null
        Log.i(TAG, "previous exit was LMK-class: ${r.reason} ${r.description}")
        return r.processStateSummary
    }

    /**
     * Read the auto-fallback override (if any) plus its device fingerprint.
     * Returns null if absent or fingerprint mismatches the current device.
     */
    fun readPersistedFallback(context: Context, currentFingerprint: String): Int? {
        val f = File(context.filesDir, "auto_max_tokens.conf")
        if (!f.exists()) return null
        return try {
            val map = mutableMapOf<String, String>()
            for (line in f.readText(Charsets.UTF_8).lineSequence()) {
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#")) continue
                val ix = t.indexOf('=')
                if (ix > 0) map[t.substring(0, ix).trim()] = t.substring(ix + 1).trim()
            }
            val fp = map["device_fingerprint"]
            if (fp != null && fp != currentFingerprint) {
                Log.i(TAG, "auto-fallback fingerprint mismatch ($fp vs $currentFingerprint), ignoring")
                null
            } else {
                map["max_tokens"]?.toIntOrNull()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "auto_max_tokens.conf read failed", t)
            null
        }
    }

    /**
     * Persist a downshifted ceiling under a device fingerprint. NOT writing
     * to temuxllm.conf — that file is user-owned and would be clobbered.
     */
    fun persistFallback(
        context: Context,
        newMaxTokens: Int,
        currentFingerprint: String,
        reason: String,
    ) {
        val f = File(context.filesDir, "auto_max_tokens.conf")
        try {
            f.writeText(
                """
                |# Auto-generated by AutoFallback. Do NOT hand-edit — overwritten
                |# next time the foreground service is killed by lowmemorykiller.
                |# To override permanently, set max_tokens=<n> in temuxllm.conf
                |# (user-owned file). User overrides take precedence.
                |max_tokens=$newMaxTokens
                |device_fingerprint=$currentFingerprint
                |reason=$reason
                |timestamp=${System.currentTimeMillis()}
                |""".trimMargin(),
                Charsets.UTF_8,
            )
            Log.i(TAG, "persisted auto-fallback max_tokens=$newMaxTokens reason=$reason")
        } catch (t: Throwable) {
            Log.w(TAG, "persist failed", t)
        }
    }

    /** Stable per-device fingerprint based on Build + total RAM tier. */
    fun deviceFingerprint(context: Context): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val gb = (mi.totalMem + 512L * 1024 * 1024) / (1024L * 1024 * 1024)
        return "${Build.MODEL}|${Build.SOC_MODEL}|${gb}gb"
    }
}
