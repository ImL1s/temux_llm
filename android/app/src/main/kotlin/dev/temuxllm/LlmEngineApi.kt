package dev.temuxllm

import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * The minimal surface HttpServer needs from LlmEngine. Extracted as a
 * separate interface so unit tests can fake it without touching the
 * `com.google.ai.edge.litertlm` SDK at all (no Engine, no Conversation,
 * no JNI). Per codex outside-review: the events / results live at the
 * package level, NOT nested inside the concrete LlmEngine class —
 * otherwise a "fake engine" still drags in the real one.
 */
interface LlmEngineApi {
    /** True iff the underlying Engine has been initialized. */
    fun isLoaded(): Boolean

    /** Where on disk the active `.litertlm` lives. */
    fun activeModelPath(): File

    /** Directory the active model lives in. Used by ModelRegistry to enumerate. */
    fun modelDir(): File

    /**
     * Stream tokens for [prompt] on the chosen [backend] (`cpu` | `gpu`).
     * Emits Token events, terminated by either Done or Error.
     */
    fun generate(prompt: String, backend: String): Flow<GenerateEvent>

    /** Drain [generate] into a single result; convenience for non-stream callers. */
    fun generateBlocking(prompt: String, backend: String): GenerateResult
}

/** Token / lifecycle event from the engine. Top-level so fakes don't import LlmEngine. */
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

/** Drained result for non-stream [LlmEngineApi.generateBlocking]. */
data class GenerateResult(
    val text: String,
    val backend: String,
    val outputTokens: Int,
    val totalDurationMs: Long,
    val error: String?,
)
