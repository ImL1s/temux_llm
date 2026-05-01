package dev.temuxllm

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintWriter
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Localhost-only HTTP server. MUST bind to 127.0.0.1 (project constraint #1).
 *
 * As of v0.3.0 this server impersonates an Ollama server closely enough for
 * Claude Code, OpenCode, and OpenClaw to connect with no proxy. The four
 * envelope shapes (Ollama-native NDJSON, OpenAI SSE, Anthropic SSE, plus the
 * legacy temuxllm /api/generate JSON) all consume the same LlmEngine token
 * stream — only the framing and event vocabulary differ. See
 * [StreamEncoder] for the wire-byte tables.
 *
 * Endpoints:
 *   GET  /                         -> "Ollama is running"  (probe)
 *   GET  /healthz                  -> "ok"
 *   GET  /api/version              -> service metadata + Ollama-shaped version
 *   GET  /api/tags                 -> Ollama-shaped model list
 *   POST /api/show                 -> Ollama-shaped per-model metadata
 *   GET  /api/ps                   -> currently loaded model
 *   POST /api/generate             -> NDJSON stream (legacy + Ollama compat)
 *   POST /api/chat                 -> NDJSON stream (Ollama native)
 *   POST /api/pull                 -> stub OK (Codex --oss compat)
 *   GET  /v1/models                -> OpenAI list
 *   POST /v1/chat/completions      -> OpenAI SSE
 *   POST /v1/messages              -> Anthropic SSE
 */
class HttpServer(
    private val context: Context,
    private val engine: LlmEngine,
    private val registry: ModelRegistry,
) : NanoHTTPD(BIND, PORT) {

    companion object {
        const val BIND = "127.0.0.1"
        const val PORT = 11434
        // Advertise a pure SemVer version ≥ Ollama 0.13.4 so Codex CLI and
        // other clients' version checks pass. SemVer prerelease suffixes
        // (e.g. `0.13.4-temuxllm`) sort BELOW the same release without
        // suffix per spec §11, which Codex's `>= 0.13.4` check rejects.
        // The "this is temux_llm" flag lives in the `service` field instead.
        const val VERSION = "0.13.5"
        private const val TAG = "HttpServer"
        private const val MAX_BODY = 4 * 1024 * 1024
    }

    override fun serve(session: IHTTPSession): Response = try {
        when {
            session.uri == "/" && session.method == Method.GET ->
                text(Response.Status.OK, "Ollama is running")

            session.uri == "/healthz" && session.method == Method.GET ->
                text(Response.Status.OK, "ok\n")

            session.uri == "/api/version" && session.method == Method.GET ->
                json(Response.Status.OK, versionPayload())

            session.uri == "/api/temuxllm/info" && session.method == Method.GET ->
                json(Response.Status.OK, debugInfoPayload())

            session.uri == "/api/tags" && session.method == Method.GET ->
                json(Response.Status.OK, registry.ollamaTags())

            session.uri == "/api/ps" && session.method == Method.GET ->
                json(Response.Status.OK, registry.ollamaPs())

            session.uri == "/api/show" && session.method == Method.POST ->
                handleShow(session)

            session.uri == "/api/pull" && session.method == Method.POST ->
                json(Response.Status.OK, JSONObject().apply { put("status", "success") })

            session.uri == "/api/generate" && session.method == Method.POST ->
                handleGenerate(session)

            session.uri == "/api/chat" && session.method == Method.POST ->
                handleOllamaChat(session)

            session.uri == "/v1/models" && session.method == Method.GET ->
                json(Response.Status.OK, registry.openAiModels())

            session.uri == "/v1/chat/completions" && session.method == Method.POST ->
                handleOpenAiChat(session)

            session.uri == "/v1/messages" && session.method == Method.POST ->
                handleAnthropicMessages(session)

            session.uri == "/v1/responses" && session.method == Method.POST ->
                handleOpenAiResponses(session)

            else -> text(Response.Status.NOT_FOUND, "404 ${session.method} ${session.uri}\n")
        }
    } catch (_: BodyTooLarge) {
        // Spec'd: 413 with explicit error JSON so clients distinguish oversize
        // from "missing required field" 400s.
        json(
            Response.Status.lookup(413) ?: Response.Status.BAD_REQUEST,
            JSONObject().apply {
                put("error", "request_entity_too_large")
                put("message", "request body exceeds 4 MiB limit")
            },
        )
    } catch (t: Throwable) {
        // Keep the full exception in logcat; do NOT leak class names or stack
        // strings on the wire — any process on-device can read this response.
        Log.e(TAG, "serve failed", t)
        json(
            Response.Status.INTERNAL_ERROR,
            JSONObject().apply {
                put("error", "internal_error")
                put("message", "An internal error occurred. Check device logs.")
            },
        )
    }

    /* --------------------------------- Routes --------------------------------- */

    private fun versionPayload(): JSONObject = JSONObject().apply {
        // Public probe — keep this lean. Filesystem paths moved to
        // /api/temuxllm/info to match the no-path-exposure rationale we
        // applied to /api/tags. Any local process can read this; only
        // information that would aid privilege escalation got stripped.
        put("version", VERSION)
        put("service", "temuxllm")
        put("phase", "3a")
        put("runtime", "litertlm-android 0.11.0-rc1 (in-process Engine)")
        put("default_backend", "gpu")
        put("engine_loaded", engine.isLoaded())
    }

    /** Non-public debug endpoint with paths — for `bash scripts/install.sh`. */
    private fun debugInfoPayload(): JSONObject = JSONObject().apply {
        put("version", VERSION)
        put("service", "temuxllm")
        put("model_path", engine.activeModelPath().absolutePath)
        put("source_model_path", LlmEngine.SOURCE_MODEL_PATH)
        put("engine_loaded", engine.isLoaded())
    }

    private fun handleShow(session: IHTTPSession): Response {
        val body = readJsonBody(session) ?: return errorJson(Response.Status.BAD_REQUEST, "invalid JSON body")
        val name = body.optString("model").ifBlank { body.optString("name") }
        val entry = registry.resolve(name)
            ?: return errorJson(Response.Status.NOT_FOUND, "model '${name}' not found")
        return json(Response.Status.OK, registry.ollamaShow(entry))
    }

    private fun handleGenerate(session: IHTTPSession): Response {
        val body = readJsonBody(session) ?: return errorJson(Response.Status.BAD_REQUEST, "invalid JSON body")
        val prompt = body.optString("prompt").also {
            if (it.isBlank()) return errorJson(Response.Status.BAD_REQUEST, "prompt is required")
        }
        val backend = body.optString("backend", "gpu").lowercase()
        if (backend != "cpu" && backend != "gpu") {
            return errorJson(Response.Status.BAD_REQUEST, "backend must be cpu|gpu (got $backend)")
        }
        val requestedModel = body.optString("model").ifBlank { null }
        val resolved = if (requestedModel == null) {
            registry.active() ?: return errorJson(Response.Status.NOT_FOUND, "no model staged")
        } else {
            registry.resolve(requestedModel)
                ?: return errorJson(Response.Status.NOT_FOUND, "model '$requestedModel' not found")
        }
        // Ollama /api/generate convention: default stream=true.
        val stream = body.optBoolean("stream", true)
        return if (stream) {
            runStream(prompt, backend, NdjsonGenerateEncoder(resolved.name))
        } else {
            blockingGenerate(prompt, backend, resolved.name, ResponseShape.LegacyGenerate)
        }
    }

    private fun handleOllamaChat(session: IHTTPSession): Response {
        val body = readJsonBody(session) ?: return errorJson(Response.Status.BAD_REQUEST, "invalid JSON body")
        val requestedModel = body.optString("model").ifBlank { null }
        val resolved = if (requestedModel == null) {
            registry.active() ?: return errorJson(Response.Status.NOT_FOUND, "no model staged")
        } else {
            registry.resolve(requestedModel)
                ?: return errorJson(Response.Status.NOT_FOUND, "model '$requestedModel' not found")
        }
        val messages = body.optJSONArray("messages")
        val prompt = when (val r = ChatFormat.flatten(messages)) {
            is ChatFormat.Result.Ok -> r.prompt
            is ChatFormat.Result.Bad -> return errorJson(Response.Status.BAD_REQUEST, r.message)
        }
        val backend = body.optString("backend", "gpu").lowercase()
        if (backend != "cpu" && backend != "gpu") {
            return errorJson(Response.Status.BAD_REQUEST, "backend must be cpu|gpu (got $backend)")
        }
        // Ollama /api/chat convention: default stream=true.
        val stream = body.optBoolean("stream", true)
        return if (stream) {
            runStream(prompt, backend, NdjsonChatEncoder(resolved.name))
        } else {
            blockingGenerate(prompt, backend, resolved.name, ResponseShape.OllamaChat)
        }
    }

    private fun handleOpenAiChat(session: IHTTPSession): Response {
        val body = readJsonBody(session) ?: return errorJson(Response.Status.BAD_REQUEST, "invalid JSON body")
        val requestedModel = body.optString("model").ifBlank { null }
        val resolved = if (requestedModel == null) {
            registry.active() ?: return errorJson(Response.Status.NOT_FOUND, "no model staged")
        } else {
            registry.resolve(requestedModel)
                ?: return errorJson(Response.Status.NOT_FOUND, "model '$requestedModel' not found")
        }
        val messages = body.optJSONArray("messages")
        val prompt = when (val r = ChatFormat.flatten(messages)) {
            is ChatFormat.Result.Ok -> r.prompt
            is ChatFormat.Result.Bad -> return errorJson(Response.Status.BAD_REQUEST, r.message)
        }
        val backend = body.optString("backend", "gpu").lowercase()
        if (backend != "cpu" && backend != "gpu") {
            return errorJson(Response.Status.BAD_REQUEST, "backend must be cpu|gpu (got $backend)")
        }
        val stream = body.optBoolean("stream", false)
        return if (stream) {
            runStream(prompt, backend, OpenAiSseEncoder(resolved.name))
        } else {
            blockingGenerate(prompt, backend, resolved.name, ResponseShape.OpenAiChat)
        }
    }

    private fun handleAnthropicMessages(session: IHTTPSession): Response {
        val body = readJsonBody(session) ?: return errorJson(Response.Status.BAD_REQUEST, "invalid JSON body")
        val requestedModel = body.optString("model").ifBlank { null }
        val resolved = if (requestedModel == null) {
            registry.active() ?: return errorJson(Response.Status.NOT_FOUND, "no model staged")
        } else {
            registry.resolve(requestedModel)
                ?: return errorJson(Response.Status.NOT_FOUND, "model '$requestedModel' not found")
        }
        val messages = body.optJSONArray("messages")
        val systemRaw = body.opt("system")
        val systemText: String? = when (systemRaw) {
            null, JSONObject.NULL -> null
            is String -> systemRaw.takeIf { it.isNotBlank() }
            is JSONArray -> {
                val sb = StringBuilder()
                for (i in 0 until systemRaw.length()) {
                    val b = systemRaw.optJSONObject(i) ?: continue
                    if (b.optString("type") == "text") sb.append(b.optString("text"))
                }
                sb.toString().takeIf { it.isNotBlank() }
            }
            else -> systemRaw.toString().takeIf { it.isNotBlank() }
        }
        val prompt = when (val r = ChatFormat.flatten(messages, systemText)) {
            is ChatFormat.Result.Ok -> r.prompt
            is ChatFormat.Result.Bad -> return errorJson(Response.Status.BAD_REQUEST, r.message)
        }
        val backend = body.optString("backend", "gpu").lowercase()
        if (backend != "cpu" && backend != "gpu") {
            return errorJson(Response.Status.BAD_REQUEST, "backend must be cpu|gpu (got $backend)")
        }
        val stream = body.optBoolean("stream", false)
        return if (stream) {
            runStream(prompt, backend, AnthropicSseEncoder(resolved.name))
        } else {
            blockingGenerate(prompt, backend, resolved.name, ResponseShape.AnthropicMessages)
        }
    }

    private fun handleOpenAiResponses(session: IHTTPSession): Response {
        val body = readJsonBody(session) ?: return errorJson(Response.Status.BAD_REQUEST, "invalid JSON body")
        val requestedModel = body.optString("model").ifBlank { null }
        val resolved = if (requestedModel == null) {
            registry.active() ?: return errorJson(Response.Status.NOT_FOUND, "no model staged")
        } else {
            registry.resolve(requestedModel)
                ?: return errorJson(Response.Status.NOT_FOUND, "model '$requestedModel' not found")
        }
        // /v1/responses `input` is heterogeneous — string, conversation array,
        // or top-level Responses-item array (function_call, function_call_output,
        // reasoning, etc.). Delegate to the dedicated Responses-shape flattener
        // so Codex's agent loop with tool turns is parsed correctly.
        val input = body.opt("input")
        val instructions = body.optString("instructions").ifBlank { null }
        val prompt: String = when (val r = ChatFormat.flattenResponsesInput(input, instructions)) {
            is ChatFormat.Result.Ok -> r.prompt
            is ChatFormat.Result.Bad -> return errorJson(Response.Status.BAD_REQUEST, r.message)
        }
        val backend = body.optString("backend", "gpu").lowercase()
        if (backend != "cpu" && backend != "gpu") {
            return errorJson(Response.Status.BAD_REQUEST, "backend must be cpu|gpu (got $backend)")
        }
        val stream = body.optBoolean("stream", false)
        return if (stream) {
            runStream(prompt, backend, ResponsesSseEncoder(resolved.name))
        } else {
            blockingResponses(prompt, backend, resolved.name)
        }
    }

    private fun blockingResponses(prompt: String, backend: String, model: String): Response {
        val r = engine.generateBlocking(prompt, backend)
        if (r.error != null) {
            return json(Response.Status.INTERNAL_ERROR, JSONObject().apply {
                put("type", "error")
                put("error", JSONObject().apply { put("code", "server_error"); put("message", r.error) })
            })
        }
        val itemId = "msg_" + UUID.randomUUID().toString().replace("-", "").take(24)
        return json(
            Response.Status.OK,
            JSONObject().apply {
                put("id", "resp_" + UUID.randomUUID().toString().replace("-", "").take(24))
                put("object", "response")
                put("status", "completed")
                put("model", model)
                put("created_at", System.currentTimeMillis() / 1000)
                put(
                    "output",
                    JSONArray().put(
                        JSONObject().apply {
                            put("id", itemId)
                            put("type", "message")
                            put("role", "assistant")
                            put("status", "completed")
                            put(
                                "content",
                                JSONArray().put(
                                    JSONObject().apply {
                                        put("type", "output_text")
                                        put("text", r.text)
                                        put("annotations", JSONArray())
                                    },
                                ),
                            )
                        },
                    ),
                )
                put(
                    "usage",
                    JSONObject().apply {
                        put("input_tokens", 0)
                        put("output_tokens", r.outputTokens)
                        put("total_tokens", r.outputTokens)
                    },
                )
            },
        )
    }

    /* --------------------------- Shared streaming core --------------------------- */

    /**
     * Drive one inference and frame each token through [encoder]. Pattern:
     *   - Spawn a worker thread that calls engine.generate().collect{ ... }.
     *   - Pipe its writes into a PipedInputStream returned to NanoHTTPD as a
     *     chunked response. NanoHTTPD pulls bytes off the pipe back to the
     *     client as the worker produces them.
     *   - Errors emitted by LlmEngine become an envelope-specific error frame
     *     followed by clean stream close.
     */
    private fun runStream(prompt: String, backend: String, encoder: StreamEncoder): Response {
        val pipeIn = PipedInputStream(64 * 1024)
        val pipeOut = PipedOutputStream(pipeIn)
        thread(start = true, name = "litertlm-stream") {
            val writer = PrintWriter(pipeOut.writer(Charsets.UTF_8), false)
            // Defer emitStart() until the first token actually arrives. If the
            // engine errors during init (e.g. token-limit overflow or backend
            // failure) BEFORE producing any output, we emit only the error
            // event — no misleading message_start/content_block_start preamble
            // that would confuse Anthropic / OpenAI parsers into reporting an
            // "empty or malformed response" instead of the real error.
            var startEmitted = false
            var terminated = false
            try {
                val started = System.currentTimeMillis()
                runBlocking {
                    engine.generate(prompt, backend).collect { ev ->
                        when (ev) {
                            is LlmEngine.GenerateEvent.Token -> {
                                if (!startEmitted) {
                                    encoder.emitStart(writer)
                                    startEmitted = true
                                }
                                encoder.emitToken(writer, ev.text)
                            }
                            is LlmEngine.GenerateEvent.Done -> {
                                if (!startEmitted) {
                                    encoder.emitStart(writer)
                                    startEmitted = true
                                }
                                encoder.emitDone(writer, ev.totalDurationMs, ev.outputTokens, ev.outputChars, ev.backend)
                                terminated = true
                            }
                            is LlmEngine.GenerateEvent.Error -> {
                                encoder.emitError(writer, ev.message)
                                terminated = true
                            }
                        }
                    }
                }
                if (!terminated) {
                    if (!startEmitted) {
                        encoder.emitStart(writer)
                        startEmitted = true
                    }
                    encoder.emitDone(writer, System.currentTimeMillis() - started, 0, 0, backend)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "stream worker failed", t)
                if (!terminated) {
                    try { encoder.emitError(writer, t.message ?: t.javaClass.simpleName) } catch (_: Throwable) {}
                }
            } finally {
                try { writer.flush() } catch (_: Throwable) {}
                try { writer.close() } catch (_: Throwable) {}
            }
        }
        return newChunkedResponse(Response.Status.OK, encoder.contentType, pipeIn)
    }

    /* --------------------------- Non-streaming responses --------------------------- */

    private enum class ResponseShape { LegacyGenerate, OllamaChat, OpenAiChat, AnthropicMessages }

    private fun blockingGenerate(prompt: String, backend: String, model: String, shape: ResponseShape): Response {
        Log.i(TAG, "generate blocking shape=$shape backend=$backend model=$model prompt=${prompt.take(80)}…")
        val r = engine.generateBlocking(prompt, backend)
        if (r.error != null) {
            return when (shape) {
                ResponseShape.OpenAiChat ->
                    json(Response.Status.INTERNAL_ERROR, JSONObject().apply {
                        put("error", JSONObject().apply { put("message", r.error); put("type", "server_error") })
                    })
                ResponseShape.AnthropicMessages ->
                    json(Response.Status.INTERNAL_ERROR, JSONObject().apply {
                        put("type", "error")
                        put("error", JSONObject().apply {
                            put("type", "server_error"); put("message", r.error)
                        })
                    })
                else ->
                    json(Response.Status.INTERNAL_ERROR, JSONObject().apply {
                        put("error", r.error); put("done", true)
                    })
            }
        }
        val durationNs = r.totalDurationMs * 1_000_000L
        return when (shape) {
            ResponseShape.LegacyGenerate -> json(
                Response.Status.OK,
                JSONObject().apply {
                    put("model", model)
                    put("created_at", ModelRegistry.iso8601(System.currentTimeMillis()))
                    put("response", r.text)
                    put("done", true)
                    put("done_reason", "stop")
                    put("total_duration", durationNs)
                    put("eval_count", r.outputTokens)
                    // legacy fields:
                    put("backend", r.backend)
                    put("total_duration_ms", r.totalDurationMs)
                    put("output_tokens", r.outputTokens)
                },
            )
            ResponseShape.OllamaChat -> json(
                Response.Status.OK,
                JSONObject().apply {
                    put("model", model)
                    put("created_at", ModelRegistry.iso8601(System.currentTimeMillis()))
                    put(
                        "message",
                        JSONObject().apply { put("role", "assistant"); put("content", r.text) },
                    )
                    put("done", true)
                    put("done_reason", "stop")
                    put("total_duration", durationNs)
                    put("eval_count", r.outputTokens)
                    put("backend", r.backend)
                },
            )
            ResponseShape.OpenAiChat -> json(
                Response.Status.OK,
                JSONObject().apply {
                    put("id", "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(24))
                    put("object", "chat.completion")
                    put("created", System.currentTimeMillis() / 1000)
                    put("model", model)
                    put(
                        "choices",
                        JSONArray().put(
                            JSONObject().apply {
                                put("index", 0)
                                put(
                                    "message",
                                    JSONObject().apply {
                                        put("role", "assistant")
                                        put("content", r.text)
                                    },
                                )
                                put("finish_reason", "stop")
                            },
                        ),
                    )
                    put(
                        "usage",
                        JSONObject().apply {
                            put("prompt_tokens", 0)
                            put("completion_tokens", r.outputTokens)
                            put("total_tokens", r.outputTokens)
                        },
                    )
                },
            )
            ResponseShape.AnthropicMessages -> json(
                Response.Status.OK,
                JSONObject().apply {
                    put("id", "msg_" + UUID.randomUUID().toString().replace("-", "").take(24))
                    put("type", "message")
                    put("role", "assistant")
                    put(
                        "content",
                        JSONArray().put(
                            JSONObject().apply {
                                put("type", "text")
                                put("text", r.text)
                            },
                        ),
                    )
                    put("model", model)
                    put("stop_reason", "end_turn")
                    put("stop_sequence", JSONObject.NULL)
                    put(
                        "usage",
                        JSONObject().apply {
                            put("input_tokens", 0)
                            put("output_tokens", r.outputTokens)
                        },
                    )
                },
            )
        }
    }

    /* ---------------------------------- Helpers --------------------------------- */

    /** Sentinel returned by [readPostBodyAsUtf8] when Content-Length exceeds [MAX_BODY]. */
    private class BodyTooLarge : RuntimeException()

    /**
     * Returns the parsed JSON body, or null on a parse/missing-body failure
     * (caller handles as 400). Propagates [BodyTooLarge] up to the outer
     * `serve()` catch so the response can be a real 413, not a confusing
     * 400 "invalid JSON body".
     */
    private fun readJsonBody(session: IHTTPSession): JSONObject? {
        val raw = readPostBodyAsUtf8(session)
        if (raw.isBlank()) return JSONObject()
        return try { JSONObject(raw) } catch (_: Throwable) { null }
    }

    private fun readPostBodyAsUtf8(session: IHTTPSession): String {
        val cl = session.headers["content-length"]?.toLongOrNull() ?: 0L
        if (cl <= 0L) return ""
        if (cl > MAX_BODY) throw BodyTooLarge()
        val limit = cl.toInt()
        val buf = ByteArray(limit)
        var pos = 0
        val ins = session.inputStream
        while (pos < limit) {
            val n = ins.read(buf, pos, limit - pos)
            if (n <= 0) break
            pos += n
        }
        return String(buf, 0, pos, Charsets.UTF_8)
    }

    private fun text(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "text/plain; charset=utf-8", body)

    private fun json(status: Response.Status, obj: JSONObject): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", obj.toString() + "\n")

    private fun errorJson(status: Response.Status, msg: String): Response =
        json(status, JSONObject().apply { put("error", msg) })
}
