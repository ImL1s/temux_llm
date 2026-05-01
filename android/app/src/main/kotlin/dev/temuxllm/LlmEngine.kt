package dev.temuxllm

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * In-process wrapper around `com.google.ai.edge.litertlm:litertlm-android` Engine.
 *
 * Replaces the v0.11.0-rc.1 CLI subprocess we shipped earlier. Same runtime, but the
 * model loads once at service startup and stays resident; each /api/generate just
 * opens a Conversation, streams a Kotlin Flow<Message>, and closes it. The first call
 * pays initialize() cost (kernel compile, weight cache build); subsequent calls are
 * close to instant.
 *
 * Single-Engine, single-backend per process. The first request after service start
 * fixes the backend (default: GPU — Adreno OpenCL kernel-compile takes 8–22 s on
 * first run, then `mldrift_program_cache.bin` is reused on subsequent starts);
 * a per-request `backend` override is honored by tearing down and rebuilding
 * the Engine (slow — several seconds re-init), so callers should pin to one
 * backend in steady-state use.
 *
 * Concurrency: Engine is not safe for parallel inference. We serialize generate()
 * calls with a single mutex. NanoHTTPD's per-request thread will block until prior
 * requests finish, which is correct behavior for a single-GPU device.
 */
class LlmEngine(private val context: Context) : LlmEngineApi {

    companion object {
        private const val TAG = "LlmEngine"
        const val SOURCE_MODEL_PATH = "/data/local/tmp/litertlm/model.litertlm"
    }

    @Volatile private var engine: Engine? = null
    @Volatile private var activeBackend: String = ""
    private val lock = Any()

    /**
     * Serializes inference requests across the entire generate() collect path.
     * The previous code only locked ensureEngine() — concurrent /api/chat
     * requests could call createConversation().sendMessageAsync() in parallel,
     * which the SDK's single-Engine + single-Conversation model does not
     * support (codex outside-review caught this race v0.4.0). All callers of
     * generate() / generateBlocking() pass through this lock so that NanoHTTPD's
     * per-request worker thread blocks until the prior inference finishes,
     * which is the correct behavior on a single-GPU device.
     */
    private val inferenceMutex = kotlinx.coroutines.sync.Mutex()

    override fun modelDir(): File = File(context.filesDir, "litertlm").apply { mkdirs() }
    override fun activeModelPath(): File = File(modelDir(), "model.litertlm")
    override fun isLoaded(): Boolean = engine != null

    /**
     * One-time model copy from /data/local/tmp/litertlm/model.litertlm into our
     * own filesDir so the SDK's internal caches (which it co-locates with the
     * model file) can persist. SELinux denies untrusted_app writes to
     * /data/local/tmp, so caching there silently fails.
     */
    @Synchronized
    fun ensureModelStaged(sourcePath: String = SOURCE_MODEL_PATH): String {
        val src = File(sourcePath)
        val dst = activeModelPath()
        if (!src.exists()) {
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
        Log.i(TAG, "model staged in $dt ms (${dst.length()} bytes)")
        return dst.absolutePath
    }

    /**
     * Lazy / cached engine. Tears down + rebuilds when the requested backend differs
     * from the active one. First call here pays full init cost.
     */
    @Synchronized
    fun ensureEngine(backend: String): Engine {
        require(backend == "cpu" || backend == "gpu") { "backend must be cpu|gpu (got $backend)" }
        val current = engine
        if (current != null && backend == activeBackend) return current
        if (current != null) {
            Log.i(TAG, "backend switch ${activeBackend} -> $backend; closing existing Engine")
            try { current.close() } catch (t: Throwable) { Log.w(TAG, "close failed", t) }
            engine = null
        }
        val modelPath = ensureModelStaged()
        val backendInst = if (backend == "gpu") Backend.GPU() else Backend.CPU()
        // EngineConfig.maxNumTokens caps the prefill+decode KV-cache size.
        // The SDK default is the model's compiled default (4096 for Gemma 4
        // .litertlm bundles) — too tight for Claude Code's ~16 k built-in
        // agent system prompt or Codex CLI's ~8 k. KV-cache scales linearly
        // with this value; on 12 GB devices (S25 / Adreno 830) 32 k OOMs
        // the foreground service via lowmemorykiller, while 16 k fits.
        //
        // Resolution order (highest priority first):
        //   1. Per-install override file: <filesDir>/temuxllm.conf
        //      with a line `max_tokens=<int>`. Lets power users (Fold7 /
        //      Tab S10 Ultra owners) bump to 24 576 / 32 768 without
        //      rebuilding the APK; engineers tuning a device push the
        //      file via `adb push` into /data/local/tmp and we copy it
        //      into filesDir on first staging.
        //   2. TEMUXLLM_MAX_TOKENS env var (only honored if Android
        //      somehow inherits it; foreground services usually don't).
        //   3. Auto-detect from /proc/meminfo MemTotal — the device tier
        //      table below is conservative and verified on real hardware.
        val maxTokens = computeMaxNumTokens()
        val cfg = EngineConfig(
            modelPath = modelPath,
            backend = backendInst,
            maxNumTokens = maxTokens,
        )
        Log.i(TAG, "Engine.initialize(backend=$backend) starting maxTokens=$maxTokens")
        val t0 = System.currentTimeMillis()
        val e = Engine(cfg)
        e.initialize()
        val dt = System.currentTimeMillis() - t0
        Log.i(TAG, "Engine.initialize(backend=$backend) done in $dt ms")
        engine = e
        activeBackend = backend
        // Stamp the running configuration into the process so a future LMK
        // post-mortem can recover it via getHistoricalProcessExitReasons.
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val packed = AutoFallback.packStateSummary(
                    maxNumTokens = maxTokens,
                    backend = backend,
                    modelName = activeModelPath().nameWithoutExtension,
                )
                am.setProcessStateSummary(packed)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "setProcessStateSummary failed", t)
        }
        return e
    }

    /**
     * Generate a response, returning a flow of token chunks plus a final
     * GenerateEvent.Done with metrics. Caller is expected to collect from a coroutine.
     *
     * The SDK's sendMessageAsync returns Flow<Message>; we re-emit each Message's
     * text into our own GenerateEvent stream and close with a Done summary.
     *
     * The full collect path is serialized on [inferenceMutex]: concurrent
     * /api/chat requests must NOT call createConversation().sendMessageAsync()
     * in parallel (the SDK's Engine is single-inference-at-a-time). Per
     * codex outside-review, the prior code only locked engine init, not
     * the whole inference path.
     */
    override fun generate(prompt: String, backend: String): Flow<GenerateEvent> = flow {
        inferenceMutex.withLock {
            val started = System.currentTimeMillis()
            val accum = StringBuilder()
            var tokens = 0
            try {
                val e = synchronized(lock) { ensureEngine(backend) }
                e.createConversation().use { conv ->
                    conv.sendMessageAsync(prompt).collect { msg: Message ->
                        val piece = msg.toString()
                        if (piece.isNotEmpty()) {
                            accum.append(piece)
                            tokens += 1
                            emit(GenerateEvent.Token(piece))
                        }
                    }
                }
                val total = System.currentTimeMillis() - started
                emit(
                    GenerateEvent.Done(
                        backend = backend,
                        totalDurationMs = total,
                        outputTokens = tokens,
                        outputChars = accum.length,
                    )
                )
            } catch (t: Throwable) {
                Log.e(TAG, "generate failed", t)
                emit(GenerateEvent.Error(t.message ?: t.javaClass.simpleName, t))
            }
        }
    }

    /**
     * Blocking convenience for non-streaming `/api/generate`. Drains the flow into
     * one final string + metrics.
     */
    override fun generateBlocking(prompt: String, backend: String): GenerateResult = runBlocking {
        val text = StringBuilder()
        var tokens = 0
        var doneEvent: GenerateEvent.Done? = null
        var errorMsg: String? = null
        generate(prompt, backend).collect { ev ->
            when (ev) {
                is GenerateEvent.Token -> { text.append(ev.text); tokens++ }
                is GenerateEvent.Done -> doneEvent = ev
                is GenerateEvent.Error -> errorMsg = ev.message
            }
        }
        GenerateResult(
            text = text.toString(),
            backend = backend,
            outputTokens = tokens,
            totalDurationMs = doneEvent?.totalDurationMs ?: (System.currentTimeMillis() - 0),
            error = errorMsg,
        )
    }

    fun close() {
        synchronized(lock) {
            try { engine?.close() } catch (t: Throwable) { Log.w(TAG, "close failed", t) }
            engine = null
            activeBackend = ""
        }
    }

    /**
     * Variant of [close] that also acquires [inferenceMutex] so concurrent
     * generate() flows finish (or fail safely) before we tear down the
     * JNI handle. Called by LlmService.onTrimMemory(RUNNING_CRITICAL) per
     * codex outside-review v0.4.0 (close was racing with in-flight work).
     */
    fun closeUnderInferenceLock() {
        runBlocking { inferenceMutex.withLock { close() } }
    }

    /**
     * Pick a KV-cache size based on (in priority order) a config sidecar,
     * an inheritable env var, or auto-detection from /proc/meminfo.
     *
     * Tiers (verified or conservatively projected):
     *   ≤ 6 GB  → 4096   (E2B-only territory; Gemma 4 default)
     *   ≤ 9 GB  → 8192   (8 GB phones; safe headroom for E2B)
     *   ≤ 13 GB → 16384  (S25 / 12 GB / Adreno 830 — verified works)
     *   ≤ 18 GB → 24576  (Fold7 / 16 GB — projected ceiling)
     *   else    → 32768  (Tab S10 Ultra and similar 24+ GB devices)
     */
    private fun computeMaxNumTokens(): Int {
        // Priority chain (highest first):
        //   1)  <filesDir>/temuxllm.conf            user-owned, app-private
        //   1b) /data/local/tmp/litertlm/temuxllm.conf  user-owned, adb-pushable
        //   2)  <filesDir>/auto_max_tokens.conf     post-LMK auto-downshift
        //   3)  TEMUXLLM_MAX_TOKENS env var         rarely propagated
        //   4)  /proc/meminfo tier table            auto-detect
        //
        // codex outside-review v0.4.0 caught that the auto-downshift file
        // must NOT take precedence over the manual /data/local/tmp path —
        // that's the existing supported override channel for users who
        // can't write into the app's filesDir.
        try {
            val cfg = File(context.filesDir, "temuxllm.conf")
            if (cfg.exists()) {
                val line = cfg.readText(Charsets.UTF_8).lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.startsWith("max_tokens=") }
                if (line != null) {
                    val v = line.removePrefix("max_tokens=").trim().toIntOrNull()
                    if (v != null && v > 0) {
                        Log.i(TAG, "computeMaxNumTokens: $v from $cfg override")
                        return v
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "computeMaxNumTokens: temuxllm.conf read failed", t)
        }
        // 1b) Mirror in /data/local/tmp so users pushing config without app
        // running can take effect on next service start.
        try {
            val tmpCfg = File("/data/local/tmp/litertlm/temuxllm.conf")
            if (tmpCfg.exists()) {
                val line = tmpCfg.readText(Charsets.UTF_8).lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.startsWith("max_tokens=") }
                if (line != null) {
                    val v = line.removePrefix("max_tokens=").trim().toIntOrNull()
                    if (v != null && v > 0) {
                        Log.i(TAG, "computeMaxNumTokens: $v from $tmpCfg override")
                        return v
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "computeMaxNumTokens: /data/local/tmp/.../temuxllm.conf read failed", t)
        }
        // 2) auto_max_tokens.conf — populated by AutoFallback after a
        //    previous LMK kill. Below both manual override paths so a
        //    user adb-push to /data/local/tmp/.../temuxllm.conf always
        //    wins. Fingerprint check invalidates the value if hardware
        //    has changed between the recorded LMK and now.
        try {
            val fp = AutoFallback.deviceFingerprint(context)
            val auto = AutoFallback.readPersistedFallback(context, fp)
            if (auto != null && auto > 0) {
                Log.i(TAG, "computeMaxNumTokens: $auto from auto_max_tokens.conf (post-LMK fallback)")
                return auto
            }
        } catch (t: Throwable) {
            Log.w(TAG, "computeMaxNumTokens: auto_max_tokens.conf read failed", t)
        }
        // 3) Env var (rarely honored by Android service inheritance).
        val ctxEnv = System.getenv("TEMUXLLM_MAX_TOKENS")?.toIntOrNull()
        if (ctxEnv != null && ctxEnv > 0) {
            Log.i(TAG, "computeMaxNumTokens: $ctxEnv from TEMUXLLM_MAX_TOKENS env")
            return ctxEnv
        }
        // 3) Auto-detect from /proc/meminfo MemTotal.
        val memKb = try {
            File("/proc/meminfo").readLines()
                .firstOrNull { it.startsWith("MemTotal:") }
                ?.trim()
                ?.split(Regex("\\s+"))
                ?.getOrNull(1)
                ?.toLongOrNull() ?: 0L
        } catch (_: Throwable) { 0L }
        val memGb = (memKb + 524288L) / 1024L / 1024L  // round up to nearest GB
        val auto = when {
            memGb <= 0L -> 4096
            memGb <= 6L -> 4096
            memGb <= 9L -> 8192
            memGb <= 13L -> 16384
            memGb <= 18L -> 24576
            else -> 32768
        }
        Log.i(TAG, "computeMaxNumTokens: $auto auto-selected (MemTotal≈${memGb} GB)")
        return auto
    }
}
