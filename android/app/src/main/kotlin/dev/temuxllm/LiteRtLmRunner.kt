package dev.temuxllm

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Wraps a subprocess call to the v0.9.0 litert_lm_main binary.
 *
 * The binary ships as `liblitert_lm_main.so` under `src/main/jniLibs/arm64-v8a/`, so
 * Android extracts it to `applicationInfo.nativeLibraryDir` at install time. That
 * directory has mode 0555 and *permits execve from the app sandbox*, unlike `filesDir`
 * which is rejected with EPERM under Android 10+ W^X. The 6 LiteRT-LM `.so` accelerators
 * live alongside it and are reachable via LD_LIBRARY_PATH.
 *
 * The model is staged from `/data/local/tmp/litertlm/model.litertlm` (host-side push)
 * into our app's filesDir on first use; see [ensureModelStaged]. Callers may override
 * via the model_path field on each /api/generate request.
 *
 * The binary auto-prints a "BenchmarkInfo:" block; [parseStdout] extracts both the
 * generated text (between "input_prompt:" and "BenchmarkInfo:") and the numeric metrics.
 */
class LiteRtLmRunner(private val context: Context) {

    companion object {
        private const val TAG = "LiteRtLmRunner"

        // Source location pushed by host-side scripts/setup_litertlm_android.sh.
        const val SOURCE_MODEL_PATH = "/data/local/tmp/litertlm/model.litertlm"

        // The active model path must live inside our app's filesDir so the binary's
        // XNNPack weight cache + OpenCL kernel cache (written next to the model) can
        // persist. SELinux denies untrusted_app writes to /data/local/tmp
        // (shell_data_file), so caching there silently fails and every /api/generate
        // would pay full cold-start init. See [activeModelPath].

        const val DEFAULT_TIMEOUT_MS = 120_000L

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

    /** filesDir/litertlm/ — owned by us, writeable by us, exec-readable by us. */
    fun modelDir(): File = File(context.filesDir, "litertlm").apply { mkdirs() }

    /** filesDir/litertlm/model.litertlm — XNNPack cache will land alongside. */
    fun activeModelPath(): File = File(modelDir(), "model.litertlm")

    /**
     * Make sure the model is copied into our app's filesDir so the runtime's
     * weight cache can persist next to it. Idempotent: skips when sizes match.
     * Returns the absolute path the binary should load.
     */
    @Synchronized
    fun ensureModelStaged(sourcePath: String = SOURCE_MODEL_PATH): String {
        val src = File(sourcePath)
        val dst = activeModelPath()
        if (!src.exists()) {
            Log.w(TAG, "source model missing at $sourcePath; using filesDir copy if any")
            if (dst.exists() && dst.length() > 0L) return dst.absolutePath
            throw java.io.FileNotFoundException("no model at $sourcePath and none staged in $dst")
        }
        if (dst.exists() && dst.length() == src.length()) {
            Log.i(TAG, "model already staged at $dst (${dst.length()} bytes)")
            return dst.absolutePath
        }
        Log.i(TAG, "staging model: $src (${src.length()} bytes) -> $dst")
        val t0 = System.currentTimeMillis()
        src.inputStream().use { input ->
            dst.outputStream().use { output -> input.copyTo(output, bufferSize = 1024 * 1024) }
        }
        val dt = System.currentTimeMillis() - t0
        Log.i(TAG, "model staged in ${dt} ms (${dst.length()} bytes)")
        return dst.absolutePath
    }

    /**
     * Verify the binary is in place and executable AND the model is staged. Used
     * as a startup probe in onCreate so the first /api/generate doesn't pay the
     * model-copy cost on the request path.
     */
    fun checkReady(): Boolean {
        val bin = binPath()
        val binOk = bin.exists() && bin.canExecute()
        Log.i(TAG, "checkReady: $bin exists=${bin.exists()} canExecute=${bin.canExecute()}")
        if (!binOk) return false
        return try {
            ensureModelStaged()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "model not yet staged (host scripts/setup_litertlm_android.sh required first)", t)
            false
        }
    }

    /**
     * Spawn litert_lm_main; collect stdout+stderr; parse BenchmarkInfo + output text.
     * Blocking call; returns when the process finishes (or timeout).
     */
    fun generate(
        prompt: String,
        backend: String = "gpu",
        modelPath: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): GenerateResult {
        require(backend == "cpu" || backend == "gpu") { "backend must be cpu|gpu (got $backend)" }
        if (!checkReady()) {
            throw IllegalStateException("binary not ready at ${binPath()}; check jniLibs packaging")
        }
        val dir = runDir()
        val effectiveModelPath = modelPath ?: ensureModelStaged()

        val pb = ProcessBuilder(
            binPath().absolutePath,
            "--backend=$backend",
            "--model_path=$effectiveModelPath",
            "--input_prompt=$prompt",
        ).apply {
            environment()["LD_LIBRARY_PATH"] = dir.absolutePath
            redirectErrorStream(true)
            // Run from the model's directory so the binary's relative-path cache
            // writes (XNNPack weight cache, OpenCL program cache) land in a place
            // we own and can persist for warm-cache reuse on subsequent calls.
            directory(File(effectiveModelPath).parentFile ?: dir)
        }

        val started = System.currentTimeMillis()
        val process = pb.start()
        val stdoutLines = mutableListOf<String>()
        val reader = process.inputStream.bufferedReader()

        // Drain stdout on a worker thread so waitFor and the reader don't deadlock.
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
