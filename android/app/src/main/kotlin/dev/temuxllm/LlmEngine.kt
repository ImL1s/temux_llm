package dev.temuxllm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
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
 * fixes the backend (default: GPU); a per-request `backend` override is honored by
 * tearing down and rebuilding the Engine (slow — ~10 s GPU re-init), so callers
 * should pin to one backend in steady-state use.
 *
 * Concurrency: Engine is not safe for parallel inference. We serialize generate()
 * calls with a single mutex. NanoHTTPD's per-request thread will block until prior
 * requests finish, which is correct behavior for a single-GPU device.
 */
class LlmEngine(private val context: Context) {

    companion object {
        private const val TAG = "LlmEngine"
        const val SOURCE_MODEL_PATH = "/data/local/tmp/litertlm/model.litertlm"
    }

    @Volatile private var engine: Engine? = null
    @Volatile private var activeBackend: String = ""
    private val lock = Any()

    fun modelDir(): File = File(context.filesDir, "litertlm").apply { mkdirs() }
    fun activeModelPath(): File = File(modelDir(), "model.litertlm")
    fun isLoaded(): Boolean = engine != null

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
        val cfg = EngineConfig(modelPath = modelPath, backend = backendInst)
        Log.i(TAG, "Engine.initialize(backend=$backend) starting")
        val t0 = System.currentTimeMillis()
        val e = Engine(cfg)
        e.initialize()
        val dt = System.currentTimeMillis() - t0
        Log.i(TAG, "Engine.initialize(backend=$backend) done in $dt ms")
        engine = e
        activeBackend = backend
        return e
    }

    /**
     * Generate a response, returning a flow of token chunks plus a final
     * GenerateEvent.Done with metrics. Caller is expected to collect from a coroutine.
     *
     * The SDK's sendMessageAsync returns Flow<Message>; we re-emit each Message's
     * text into our own GenerateEvent stream and close with a Done summary.
     */
    sealed class GenerateEvent {
        data class Token(val text: String) : GenerateEvent()
        data class Done(
            val backend: String,
            val totalDurationMs: Long,
            val outputTokens: Int,
            val outputChars: Int,
        ) : GenerateEvent()
        data class Error(val message: String, val cause: Throwable? = null) : GenerateEvent()
    }

    fun generate(prompt: String, backend: String): Flow<GenerateEvent> = flow {
        val started = System.currentTimeMillis()
        val accum = StringBuilder()
        var tokens = 0
        try {
            val e = synchronized(lock) { ensureEngine(backend) }
            // Single-conversation per request — fresh state, deterministic.
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

    /**
     * Blocking convenience for non-streaming `/api/generate`. Drains the flow into
     * one final string + metrics.
     */
    fun generateBlocking(prompt: String, backend: String): GenerateResult = runBlocking {
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

    data class GenerateResult(
        val text: String,
        val backend: String,
        val outputTokens: Int,
        val totalDurationMs: Long,
        val error: String?,
    )

    fun close() {
        synchronized(lock) {
            try { engine?.close() } catch (t: Throwable) { Log.w(TAG, "close failed", t) }
            engine = null
            activeBackend = ""
        }
    }
}
