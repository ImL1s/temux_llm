package dev.temuxllm

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * Periodic memory sampler. Reads RSS / PSS / oom_score_adj /
 * summary.graphics every [intervalMs] while inference is active and writes
 * structured logcat lines (tag `MemProbe`) plus a CSV at
 * `<filesDir>/memory.csv` for off-device analysis.
 *
 * Why: when Android's lowmemorykiller terminates the foreground service
 * (`prcp FGS`), there's no live trace of how much memory we held. Pairing
 * a 5-second sampler with [setProcessStateSummary] (handled in
 * [AutoFallback]) means the *next* startup can read
 * `getHistoricalProcessExitReasons` and downshift `maxNumTokens` to a tier
 * the device demonstrably survives.
 *
 * Probe rules to avoid the probe driving PSS up itself:
 *   - One coroutine on Dispatchers.IO with SupervisorJob.
 *   - Reuse a single BufferedWriter — open once, flush per line.
 *   - Reuse the pid IntArray for getProcessMemoryInfo.
 *   - Sample only while [start] / [stop] window is active (during
 *     inference), not idle. The service starts the probe just before
 *     `engine.generate` and stops it in the finally{} of the worker.
 */
class MemoryProbe(
    private val context: Context,
    private val intervalMs: Long = 5_000L,
    private val writeCsv: Boolean = true,
) {
    companion object {
        const val TAG = "MemProbe"
        private val SIZE_RE = Regex("""^(VmRSS|VmHWM|VmSize|VmSwap):\s+(\d+)\s+kB""")
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null
    private val pidArray = intArrayOf(Process.myPid())
    private var csvWriter: BufferedWriter? = null
    private var label: String = ""

    /**
     * Begin sampling. [tag] is included in every log line / CSV row so
     * different inference runs can be filtered after-the-fact.
     */
    @Synchronized
    fun start(tag: String) {
        if (job?.isActive == true) return
        label = tag
        if (writeCsv) {
            try {
                val f = File(context.filesDir, "memory.csv")
                val isNew = !f.exists() || f.length() == 0L
                csvWriter = BufferedWriter(FileWriter(f, /* append = */ true))
                if (isNew) {
                    csvWriter!!.write(
                        "ts,label,vmRssKb,vmHwmKb,vmSizeKb,vmSwapKb," +
                            "totalPssKb,nativePssKb,gfxKb,oomAdj,oomScore," +
                            "availMemKb,lowMem\n",
                    )
                    csvWriter!!.flush()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "csv writer init failed", t)
                csvWriter = null
            }
        }
        job = scope.launch {
            while (isActive) {
                try { sample() } catch (t: Throwable) { Log.w(TAG, "sample failed", t) }
                delay(intervalMs)
            }
        }
        Log.i(TAG, "probe start tag=\"$tag\"")
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        try { csvWriter?.flush(); csvWriter?.close() } catch (_: Throwable) {}
        csvWriter = null
        Log.i(TAG, "probe stop")
    }

    /** Reads /proc/self/status and returns (VmRSS, VmHWM, VmSize, VmSwap) in kB. */
    internal fun readProcStatus(): IntArray {
        val out = intArrayOf(0, 0, 0, 0)
        val text = try { File("/proc/self/status").readText() } catch (_: Throwable) { return out }
        for (line in text.lineSequence()) {
            val m = SIZE_RE.matchEntire(line) ?: continue
            val v = m.groupValues[2].toIntOrNull() ?: continue
            when (m.groupValues[1]) {
                "VmRSS" -> out[0] = v
                "VmHWM" -> out[1] = v
                "VmSize" -> out[2] = v
                "VmSwap" -> out[3] = v
            }
        }
        return out
    }

    /** Reads /proc/self/oom_score_adj. Returns 0 if unreadable. */
    internal fun readOomAdj(): Int =
        try { File("/proc/self/oom_score_adj").readText().trim().toInt() } catch (_: Throwable) { 0 }

    /** Reads /proc/self/oom_score (computed kernel score). Returns 0 if unreadable. */
    internal fun readOomScore(): Int =
        try { File("/proc/self/oom_score").readText().trim().toInt() } catch (_: Throwable) { 0 }

    private fun sample() {
        val ts = System.currentTimeMillis()
        val (rss, hwm, vsize, vswap) = readProcStatus().toTypedArray()

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val procInfo = try { am.getProcessMemoryInfo(pidArray) } catch (_: Throwable) { null }
        val mi = procInfo?.firstOrNull()
        val totalPss = mi?.totalPss ?: 0
        val nativePss = mi?.nativePss ?: 0
        val gfx = mi?.getMemoryStat("summary.graphics")?.toIntOrNull() ?: 0

        val oomAdj = readOomAdj()
        val oomScore = readOomScore()

        val devMi = ActivityManager.MemoryInfo()
        try { am.getMemoryInfo(devMi) } catch (_: Throwable) {}
        val availMemKb = (devMi.availMem / 1024L).toInt()
        val lowMem = devMi.lowMemory

        Log.i(
            TAG,
            "rss=${rss}kB hwm=${hwm}kB pss=${totalPss}kB nativePss=${nativePss}kB " +
                "gfx=${gfx}kB adj=$oomAdj score=$oomScore avail=${availMemKb}kB lowMem=$lowMem " +
                "label=\"$label\"",
        )
        csvWriter?.let { w ->
            try {
                w.write("$ts,${label.replace(',', ' ')},$rss,$hwm,$vsize,$vswap,$totalPss,$nativePss,$gfx,$oomAdj,$oomScore,$availMemKb,$lowMem\n")
                w.flush()
            } catch (t: Throwable) {
                Log.w(TAG, "csv write failed", t)
            }
        }
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }
}
