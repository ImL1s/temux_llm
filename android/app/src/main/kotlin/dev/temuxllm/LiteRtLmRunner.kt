package dev.temuxllm

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Wraps a subprocess call to the v0.9.0 litert_lm_main binary.
 *
 * The binary is shipped as `liblitert_lm_main.so` under `src/main/jniLibs/arm64-v8a/`,
 * so Android extracts it to `applicationInfo.nativeLibraryDir` at install time. This
 * directory has mode 0555 and *permits execve from the app sandbox*, unlike `filesDir`
 * which is rejected with EPERM under Android 10+ W^X. The 6 LiteRT-LM `.so` accelerators
 * live alongside it in the same directory and are reachable via LD_LIBRARY_PATH.
 *
 * The model file is read from /data/local/tmp/litertlm/model.litertlm by default
 * (push it from host with scripts/setup_litertlm_android.sh first). Override via
 * the model_path field on each /api/generate request.
 *
 * The v0.9.0 binary auto-prints a "BenchmarkInfo:" block; we parse it from stdout
 * along with the generated text (which sits between "input_prompt:" and
 * "BenchmarkInfo:" in the captured stream).
 */
class LiteRtLmRunner(private val context: Context) {

    companion object {
        private const val TAG = "LiteRtLmRunner"

        const val DEFAULT_MODEL_PATH = "/data/local/tmp/litertlm/model.litertlm"

        // Default timeout per generate(): 120 s. Phase-1 worst case was ~12 s init +
        // a few seconds generation, so 120 s is plenty even with a long prompt.
        const val DEFAULT_TIMEOUT_MS = 120_000L

        // Renamed CLI binary inside jniLibs. Filename must start with `lib` and end
        // with `.so` so Android Gradle Plugin packages it correctly.
        const val BINARY_FILENAME = "liblitert_lm_main.so"
    }

    data class GenerateResult(
        val text: String,
        val backend: String,
        val initTotalMs: Double?,
        val ttftSeconds: Double?,
        val prefillTokensPerSec: Double?,
        val decodeTokensPerSec: Double?,
        val prefillTokens: Long?,
        val decodeTokens: Long?,
        val totalDurationMs: Long,
        val rawStdoutLines: Int,
        val exitCode: Int,
    )

    fun runDir(): File = File(context.applicationInfo.nativeLibraryDir)

    fun binPath(): File = File(runDir(), BINARY_FILENAME)

    /**
     * Verify the binary is in place and executable. Useful as a startup probe.
     */
    fun checkReady(): Boolean {
        val bin = binPath()
        val ok = bin.exists() && bin.canExecute()
        Log.i(TAG, "checkReady: $bin exists=${bin.exists()} canExecute=${bin.canExecute()}")
        return ok
    }

    /**
     * Spawn litert_lm_main; collect stdout+stderr; parse BenchmarkInfo + output text.
     * Blocking call; returns when the process finishes (or timeout).
     */
    fun generate(
        prompt: String,
        backend: String = "gpu",
        modelPath: String = DEFAULT_MODEL_PATH,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): GenerateResult {
        require(backend == "cpu" || backend == "gpu") { "backend must be cpu|gpu (got $backend)" }
        if (!checkReady()) {
            throw IllegalStateException("binary not ready at ${binPath()}; check jniLibs packaging")
        }
        val dir = runDir()

        val pb = ProcessBuilder(
            binPath().absolutePath,
            "--backend=$backend",
            "--model_path=$modelPath",
            "--input_prompt=$prompt",
        ).apply {
            environment()["LD_LIBRARY_PATH"] = dir.absolutePath
            redirectErrorStream(true)
            directory(dir)
        }

        val started = System.currentTimeMillis()
        val process = pb.start()
        val stdoutLines = mutableListOf<String>()
        val reader = process.inputStream.bufferedReader()

        // Drain stdout line by line on a worker thread. Streaming on top of this
        // is Phase 2c.
        val drainerThread = Thread({
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    stdoutLines.add(line)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "stdout drain", t)
            }
        }, "litertlm-stdout")
        drainerThread.start()

        val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            Log.w(TAG, "process timed out after ${timeoutMs}ms; killed")
        }
        drainerThread.join(2000)
        val exitCode = if (finished) process.exitValue() else -1
        val totalMs = System.currentTimeMillis() - started

        val parsed = parseStdout(stdoutLines)
        return GenerateResult(
            text = parsed.text,
            backend = backend,
            initTotalMs = parsed.initTotalMs,
            ttftSeconds = parsed.ttftSeconds,
            prefillTokensPerSec = parsed.prefillTokensPerSec,
            decodeTokensPerSec = parsed.decodeTokensPerSec,
            prefillTokens = parsed.prefillTokens,
            decodeTokens = parsed.decodeTokens,
            totalDurationMs = totalMs,
            rawStdoutLines = stdoutLines.size,
            exitCode = exitCode,
        )
    }

    private data class Parsed(
        val text: String,
        val initTotalMs: Double?,
        val ttftSeconds: Double?,
        val prefillTokensPerSec: Double?,
        val decodeTokensPerSec: Double?,
        val prefillTokens: Long?,
        val decodeTokens: Long?,
    )

    /**
     * Extract the generated text and BenchmarkInfo metrics from the binary's stdout.
     */
    private fun parseStdout(lines: List<String>): Parsed {
        var inOutput = false      // between "input_prompt:" line and "BenchmarkInfo:" line
        val out = StringBuilder()
        var initMs: Double? = null
        var ttft: Double? = null
        var prefillSpeed: Double? = null
        var decodeSpeed: Double? = null
        var prefillTok: Long? = null
        var decodeTok: Long? = null

        val initRe = Regex("""\bInit Total:\s*([0-9.]+)\s*ms""")
        val ttftRe = Regex("""\bTime to first token:\s*([0-9.]+)\s*s""")
        val pSpeedRe = Regex("""\bPrefill Speed:\s*([0-9.]+)\s*tokens/sec""")
        val dSpeedRe = Regex("""\bDecode Speed:\s*([0-9.]+)\s*tokens/sec""")
        val pTokRe = Regex("""Prefill Turn 1: Processed\s+([0-9]+)\s+tokens""")
        val dTokRe = Regex("""Decode Turn 1: Processed\s+([0-9]+)\s+tokens""")

        for (line in lines) {
            val l = line.trimEnd()
            if (!inOutput && l.contains("input_prompt:")) {
                inOutput = true
                continue
            }
            if (l.startsWith("BenchmarkInfo:")) {
                inOutput = false
            }
            if (inOutput) {
                if (l.startsWith("INFO:") || l.startsWith("VERBOSE:") ||
                    l.startsWith("WARNING:") || l.startsWith("ERROR:") ||
                    l.startsWith("I0000") || l.startsWith("F0000") ||
                    l.startsWith("Replacing")
                ) continue
                if (l.isEmpty() && out.isEmpty()) continue
                out.appendLine(l)
            }
            initRe.find(l)?.let { initMs = it.groupValues[1].toDoubleOrNull() ?: initMs }
            ttftRe.find(l)?.let { ttft = it.groupValues[1].toDoubleOrNull() ?: ttft }
            pSpeedRe.find(l)?.let { prefillSpeed = it.groupValues[1].toDoubleOrNull() ?: prefillSpeed }
            dSpeedRe.find(l)?.let { decodeSpeed = it.groupValues[1].toDoubleOrNull() ?: decodeSpeed }
            pTokRe.find(l)?.let { prefillTok = it.groupValues[1].toLongOrNull() ?: prefillTok }
            dTokRe.find(l)?.let { decodeTok = it.groupValues[1].toLongOrNull() ?: decodeTok }
        }
        return Parsed(out.toString().trimEnd(), initMs, ttft, prefillSpeed, decodeSpeed, prefillTok, decodeTok)
    }
}
