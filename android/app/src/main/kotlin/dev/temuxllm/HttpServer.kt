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
    private val engine: LlmEngineApi,
    private val registry: ModelRegistry,
    private val memoryProbe: MemoryProbe? = null,
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

    // v0.5.1: default backend is read at construction time from
    // /data/local/tmp/litertlm/temuxllm.conf (key: default_backend=cpu|gpu)
    // OR per-app filesDir/temuxllm.conf, OR TEMUXLLM_DEFAULT_BACKEND env.
    // Memory-constrained devices (12 GB / 16 k context) need CPU as the
    // request default — clients like Codex / OpenCode don't send a
    // `backend` field, so without this their /v1 calls re-init GPU and
    // get LMK-killed under the prompt prefill. Falls back to "gpu" when
    // no override is set so 16 GB+ devices keep the fast path.
    private val defaultBackend: String = resolveDefaultBackend(context)

    // v0.6.0 G7-alt: opt-in native SDK tool API (Option B).
    // STATUS in v0.6.0: this flag (a) gates the /api/probe/native_tools
    // endpoint and (b) is exposed via /api/version. It does NOT yet route
    // /v1/* tool requests through the native path — that wiring lands in
    // v0.7.0 once streaming-with-tools delta semantics are sorted. The
    // probe endpoint is the only consumer right now.
    // Empirical: Galaxy S25 + Gemma 4 E4B + 0.11.0-rc1 = 30/30 tool calls
    // pass via SDK native path (vs 23/30 = 77% via prompt-injection
    // baseline; v0.6.0 G1 repair lifts that to 30/30 too). Native is ~30 %
    // faster (5.7 s vs 7-8 s/call) and emits typed `tool_calls`, no
    // brace-balance parser needed. Disabled by default because:
    //   - upstream LiteRT-LM has 5 OPEN tool-API issues for OTHER models
    //     (Qwen3 / Gemma 3n / FunctionGemma) that may bite users on
    //     non-Gemma-4 models.
    //   - streaming-with-tools surface isn't yet wired to native path
    //     (MessageCallback.onMessage delivery semantics differ from our
    //     NDJSON delta flow).
    //   - we want the Option A repair path as a safety net for any future
    //     SDK regression.
    // Override via env var or /data/local/tmp/litertlm/temuxllm.conf:
    //   native_tools=on
    private val nativeToolsEnabled: Boolean = run {
        val fromConf = try {
            listOfNotNull(
                File(context.filesDir, "temuxllm.conf"),
                File("/data/local/tmp/litertlm/temuxllm.conf"),
            ).firstOrNull { it.canRead() }
                ?.readLines()?.map { it.trim() }
                ?.firstOrNull { it.startsWith("native_tools=", ignoreCase = true) }
                ?.substringAfter('=')?.trim()?.lowercase()
        } catch (_: Throwable) { null }
        val raw = fromConf ?: System.getenv("TEMUXLLM_NATIVE_TOOLS")?.trim()?.lowercase()
        raw == "on" || raw == "1" || raw == "true"
    }

    /**
     * v0.6.0 G3: extract first image bytes from a request body.
     * Walks `messages[].content[]` looking for the first OpenAI/Anthropic
     * image content block. Returns the decoded bytes plus a 4xx error
     * shorthand if the block was malformed.
     *
     * Accepted shapes (data-URI base64 only — http(s) URLs rejected per
     * Ollama precedent, since fetching arbitrary remote URLs from a
     * localhost service is a clear SSRF risk):
     *   Anthropic: { type:"image", source:{type:"base64",media_type,data} }
     *   OpenAI:    { type:"image_url", image_url:{url:"data:image/...;base64,..."} }
     *   OpenAI Responses: { type:"input_image", image_url:"data:..." }
     *   Ollama:    top-level field `images: ["base64,...", ...]` on user message
     *
     * Caps: 2 MiB raw bytes after base64 decode. Larger -> 413.
     */
    private data class ImageOrError(val bytes: ByteArray?, val error: Response?)

    private fun extractFirstImage(body: JSONObject): ImageOrError {
        val maxBytes = 2 * 1024 * 1024
        // Ollama-style top-level images on the last user message
        val msgs = body.optJSONArray("messages")
        if (msgs != null) {
            for (i in 0 until msgs.length()) {
                val m = msgs.optJSONObject(i) ?: continue
                if (m.optString("role") != "user") continue
                val imgs = m.optJSONArray("images")
                if (imgs != null && imgs.length() > 0) {
                    val raw = imgs.optString(0)
                    return decodeImagePayload(raw, maxBytes)
                }
                val content = m.opt("content") as? JSONArray ?: continue
                for (j in 0 until content.length()) {
                    val block = content.optJSONObject(j) ?: continue
                    val parsed = parseImageBlock(block, maxBytes)
                    if (parsed != null) return parsed
                }
            }
        }
        // Responses input array
        val input = body.opt("input")
        if (input is JSONArray) {
            for (i in 0 until input.length()) {
                val item = input.optJSONObject(i) ?: continue
                val content = item.opt("content") as? JSONArray ?: continue
                for (j in 0 until content.length()) {
                    val block = content.optJSONObject(j) ?: continue
                    val parsed = parseImageBlock(block, maxBytes)
                    if (parsed != null) return parsed
                }
            }
        }
        return ImageOrError(null, null)   // no image found, OK
    }

    private fun parseImageBlock(block: JSONObject, maxBytes: Int): ImageOrError? {
        return when (block.optString("type")) {
            "image" -> {
                // Anthropic: source.type = "base64", source.data
                val src = block.optJSONObject("source") ?: return null
                if (src.optString("type") != "base64") {
                    return ImageOrError(null, errorJson(Response.Status.BAD_REQUEST, "image source.type must be base64 (got ${src.optString("type")})"))
                }
                decodeImagePayload(src.optString("data"), maxBytes)
            }
            "image_url" -> {
                val urlObj = block.opt("image_url")
                val url = when (urlObj) {
                    is JSONObject -> urlObj.optString("url")
                    is String -> urlObj
                    else -> return null
                }
                decodeImagePayload(url, maxBytes)
            }
            "input_image" -> {
                // OpenAI Responses: image_url is a string here
                decodeImagePayload(block.optString("image_url"), maxBytes)
            }
            else -> null
        }
    }

    private fun decodeImagePayload(raw: String, maxBytes: Int): ImageOrError {
        if (raw.isBlank()) return ImageOrError(null, errorJson(Response.Status.BAD_REQUEST, "image payload empty"))
        // data:image/...;base64,<...>  OR raw base64 (Ollama).
        val b64 = if (raw.startsWith("data:")) {
            val comma = raw.indexOf(',')
            if (comma < 0) return ImageOrError(null, errorJson(Response.Status.BAD_REQUEST, "data URI missing payload"))
            // Reject http(s) URLs: they'd be the rare data: URL with http inside, but we don't fetch.
            raw.substring(comma + 1)
        } else if (raw.contains("://")) {
            // Reject every URL scheme other than data: — http(s) (SSRF),
            // file:// (LFI), content:// (Android content provider), and any
            // future scheme. We never fetch; bare base64 or data: only.
            // (security review HIGH#1)
            return ImageOrError(null, errorJson(Response.Status.BAD_REQUEST,
                "image URL scheme '${raw.substringBefore("://")}' is rejected; inline as data: URI or bare base64 only"))
        } else {
            raw   // bare base64 (Ollama-style)
        }
        val bytes = try {
            android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        } catch (_: Throwable) {
            return ImageOrError(null, errorJson(Response.Status.BAD_REQUEST, "image base64 decode failed"))
        }
        if (bytes.size > maxBytes) {
            return ImageOrError(null, json(
                Response.Status.lookup(413) ?: Response.Status.BAD_REQUEST,
                JSONObject().apply {
                    put("error", "image_too_large")
                    put("message", "image bytes exceed 2 MiB cap (got ${bytes.size})")
                },
            ))
        }
        return ImageOrError(bytes, null)
    }

    /**
     * v0.6.0 G4: filter codex's hosted `web_search` / `web_search_preview`
     * tool definitions out of the inbound tools array. Returns the filtered
     * array (or null if no tools remain) plus an optional system-prompt
     * sentence to inject when filtering removed at least one entry.
     *
     * The hint says we don't have web access so the model is steered against
     * pretending to use a search tool. Without the hint the model sometimes
     * confabulates web_search_call items that codex silently treats as
     * "server already ran this" — see protocol/src/models.rs:854.
     */
    private fun filterWebSearchTools(tools: JSONArray?): Pair<JSONArray?, String?> {
        if (tools == null || tools.length() == 0) return Pair(tools, null)
        val allow = System.getenv("TEMUXLLM_ALLOW_WEB_SEARCH") == "1"
        if (allow) return Pair(tools, null)
        val filtered = JSONArray()
        var stripped = 0
        for (i in 0 until tools.length()) {
            val t = tools.optJSONObject(i) ?: continue
            val type = t.optString("type")
            if (type == "web_search" || type == "web_search_preview") {
                stripped += 1
                continue
            }
            filtered.put(t)
        }
        val out = if (filtered.length() == 0) null else filtered
        val hint = if (stripped > 0) {
            "You do not have web access; respond without referencing real-time information."
        } else null
        return Pair(out, hint)
    }

    private fun resolveDefaultBackend(ctx: Context): String {
        fun readKey(file: File): String? = try {
            if (!file.canRead()) null
            else file.readLines()
                .map { it.trim() }
                .firstOrNull { it.startsWith("default_backend=", ignoreCase = true) }
                ?.substringAfter('=')
                ?.trim()
                ?.lowercase()
        } catch (_: Throwable) { null }
        val candidates = listOfNotNull(
            File(ctx.filesDir, "temuxllm.conf"),
            File("/data/local/tmp/litertlm/temuxllm.conf"),
        )
        val fromFile = candidates.firstNotNullOfOrNull(::readKey)
        val raw = fromFile ?: System.getenv("TEMUXLLM_DEFAULT_BACKEND")?.trim()?.lowercase()
        return when (raw) {
            "cpu", "gpu" -> raw
            else -> "gpu"
        }
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

            // v0.6.0 probe (Option B empirical test): native SDK tool API.
            // Gated behind nativeToolsEnabled flag — even though the service
            // binds 127.0.0.1, any app on-device can POST here and a malformed
            // tool_description triggers JNI parsing in the SDK that may
            // SIGSEGV (security review MEDIUM#3). Off by default; turn on with
            // `native_tools=on` in temuxllm.conf or TEMUXLLM_NATIVE_TOOLS=1.
            session.uri == "/api/probe/native_tools" && session.method == Method.POST ->
                if (nativeToolsEnabled) handleProbeNativeTools(session)
                else text(Response.Status.NOT_FOUND, "404 ${session.method} ${session.uri}\n")

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
        put("default_backend", defaultBackend)
        put("native_tools_enabled", nativeToolsEnabled)
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
        val backend = body.optString("backend", defaultBackend).lowercase()
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
        val tools = body.optJSONArray("tools")
        val toolBlock = ChatFormat.renderToolBlock(tools)
        val prompt = when (val r = ChatFormat.flatten(messages, toolBlock)) {
            is ChatFormat.Result.Ok -> r.prompt
            is ChatFormat.Result.Bad -> return errorJson(Response.Status.BAD_REQUEST, r.message)
        }
        // v0.6.0 G3: vision input — extracted before backend so 4xx returns
        // arrive without engine init.
        val img = extractFirstImage(body)
        if (img.error != null) return img.error
        val backend = body.optString("backend", defaultBackend).lowercase()
        if (backend != "cpu" && backend != "gpu") {
            return errorJson(Response.Status.BAD_REQUEST, "backend must be cpu|gpu (got $backend)")
        }
        val stream = body.optBoolean("stream", true)
        val hasTools = toolBlock != null
        val allowedToolNames = ChatFormat.toolNamesFromRequest(tools)
        return when {
            stream && hasTools -> runStreamBuffered(prompt, backend, NdjsonChatEncoder(resolved.name), allowedToolNames)
            stream -> runStream(prompt, backend, NdjsonChatEncoder(resolved.name), img.bytes)
            else -> blockingGenerate(prompt, backend, resolved.name, ResponseShape.OllamaChat, parseTools = hasTools, allowedToolNames = allowedToolNames, imageBytes = img.bytes)
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
        val tools = body.optJSONArray("tools")
        val toolBlock = ChatFormat.renderToolBlock(tools)
        val prompt = when (val r = ChatFormat.flatten(messages, toolBlock)) {
            is ChatFormat.Result.Ok -> r.prompt
            is ChatFormat.Result.Bad -> return errorJson(Response.Status.BAD_REQUEST, r.message)
        }
        val img = extractFirstImage(body)
        if (img.error != null) return img.error
        val backend = body.optString("backend", defaultBackend).lowercase()
        if (backend != "cpu" && backend != "gpu") {
            return errorJson(Response.Status.BAD_REQUEST, "backend must be cpu|gpu (got $backend)")
        }
        val stream = body.optBoolean("stream", false)
        val hasTools = toolBlock != null
        val allowedToolNames = ChatFormat.toolNamesFromRequest(tools)
        return when {
            stream && hasTools -> runStreamBuffered(prompt, backend, OpenAiSseEncoder(resolved.name), allowedToolNames)
            stream -> runStream(prompt, backend, OpenAiSseEncoder(resolved.name), img.bytes)
            else -> blockingGenerate(prompt, backend, resolved.name, ResponseShape.OpenAiChat, parseTools = hasTools, allowedToolNames = allowedToolNames, imageBytes = img.bytes)
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
        val tools = body.optJSONArray("tools")
        val toolBlock = ChatFormat.renderToolBlock(tools)
        // Combine Anthropic top-level system text with the tool definitions
        // block. If both are present the tool block goes first so the model
        // sees its constraints up front.
        val combinedSystem = listOfNotNull(toolBlock, systemText).filter { it.isNotBlank() }.let {
            if (it.isEmpty()) null else it.joinToString("\n")
        }
        val prompt = when (val r = ChatFormat.flatten(messages, combinedSystem)) {
            is ChatFormat.Result.Ok -> r.prompt
            is ChatFormat.Result.Bad -> return errorJson(Response.Status.BAD_REQUEST, r.message)
        }
        val img = extractFirstImage(body)
        if (img.error != null) return img.error
        val backend = body.optString("backend", defaultBackend).lowercase()
        if (backend != "cpu" && backend != "gpu") {
            return errorJson(Response.Status.BAD_REQUEST, "backend must be cpu|gpu (got $backend)")
        }
        val stream = body.optBoolean("stream", false)
        val hasTools = toolBlock != null
        val allowedToolNames = ChatFormat.toolNamesFromRequest(tools)
        return when {
            stream && hasTools -> runStreamBuffered(prompt, backend, AnthropicSseEncoder(resolved.name), allowedToolNames)
            stream -> runStream(prompt, backend, AnthropicSseEncoder(resolved.name), img.bytes)
            else -> blockingGenerate(prompt, backend, resolved.name, ResponseShape.AnthropicMessages, parseTools = hasTools, allowedToolNames = allowedToolNames, imageBytes = img.bytes)
        }
    }

    // v0.6.0 probe (Option B empirical test): native SDK tool API.
    //
    // POST body schema: prompt (string), tool_description (JSON-string of the
    // single OpenAI/Anthropic tool def), backend (cpu|gpu, default = defaultBackend).
    //
    // Response schema: backend, duration_ms, role, raw_text, tool_calls (array
    // of name+arguments), error (nullable string).
    //
    // Hidden from /v1 paths because LiteRT-LM 0.11.0-rc1's native tool API
    // has 5 OPEN upstream issues (#1539 #1859 #1027 #1181 #1874). Probe is
    // for empirical comparison vs Option A (prompt-injection + JSON repair).
    private fun handleProbeNativeTools(session: IHTTPSession): Response {
        val body = readJsonBody(session) ?: return errorJson(Response.Status.BAD_REQUEST, "invalid JSON body")
        val prompt = body.optString("prompt").ifBlank {
            return errorJson(Response.Status.BAD_REQUEST, "prompt is required")
        }
        val toolDesc = body.optString("tool_description").ifBlank {
            return errorJson(Response.Status.BAD_REQUEST, "tool_description (JSON string) is required")
        }
        val backend = body.optString("backend", defaultBackend).lowercase()
        if (backend != "cpu" && backend != "gpu") {
            return errorJson(Response.Status.BAD_REQUEST, "backend must be cpu|gpu (got $backend)")
        }
        // Only LlmEngine has the SDK probe surface; LlmEngineApi (interface used
        // for testing) does not. Cast or 500.
        val real = engine as? LlmEngine
            ?: return errorJson(Response.Status.INTERNAL_ERROR, "native probe requires LlmEngine instance (got ${engine.javaClass.simpleName})")
        val result = real.probeNativeToolCall(backend, prompt, toolDesc)
        val payload = JSONObject().apply {
            put("backend", result.backend)
            put("duration_ms", result.durationMs)
            put("role", result.role)
            put("raw_text", result.rawText)
            val callsArr = JSONArray()
            result.toolCallNames.forEachIndexed { i, name ->
                val argsObj = JSONObject()
                result.toolCallArgs.getOrNull(i)?.forEach { (k, v) ->
                    when (v) {
                        null -> argsObj.put(k, JSONObject.NULL)
                        is String, is Number, is Boolean -> argsObj.put(k, v)
                        else -> argsObj.put(k, v.toString())
                    }
                }
                callsArr.put(JSONObject().apply {
                    put("name", name); put("arguments", argsObj)
                })
            }
            put("tool_calls", callsArr)
            put("error", result.error)
        }
        return json(Response.Status.OK, payload)
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
        val rawTools = body.optJSONArray("tools")
        // v0.6.0 G4: codex web_search ingress filter.
        // Codex 0.125's `web_search` is a hosted OpenAI tool — codex never
        // dispatches `web_search_call` items locally (verified codex-rs
        // protocol/src/models.rs:854, event_mapping.rs:182-192). If our
        // model emits one, codex silently treats it as "the server already
        // ran it, here's the result" and the assistant turn continues with
        // a hallucinated answer. We strip the tool def and warn the model.
        // Override: TEMUXLLM_ALLOW_WEB_SEARCH=1 (caller will pipe their own
        // search tool through MCP and route to web_search themselves).
        val (tools, webSearchHint) = filterWebSearchTools(rawTools)
        val toolBlock = ChatFormat.renderToolBlock(tools)
        val combinedInstructions = listOfNotNull(toolBlock, instructions, webSearchHint).filter { it.isNotBlank() }.let {
            if (it.isEmpty()) null else it.joinToString("\n")
        }
        val prompt: String = when (val r = ChatFormat.flattenResponsesInput(input, combinedInstructions)) {
            is ChatFormat.Result.Ok -> r.prompt
            is ChatFormat.Result.Bad -> return errorJson(Response.Status.BAD_REQUEST, r.message)
        }
        val backend = body.optString("backend", defaultBackend).lowercase()
        if (backend != "cpu" && backend != "gpu") {
            return errorJson(Response.Status.BAD_REQUEST, "backend must be cpu|gpu (got $backend)")
        }
        val stream = body.optBoolean("stream", false)
        val hasTools = toolBlock != null
        val allowedToolNames = ChatFormat.toolNamesFromRequest(tools)
        return when {
            stream && hasTools -> runStreamBuffered(prompt, backend, ResponsesSseEncoder(resolved.name), allowedToolNames)
            stream -> runStream(prompt, backend, ResponsesSseEncoder(resolved.name))
            hasTools -> blockingResponsesWithTools(prompt, backend, resolved.name, allowedToolNames)
            else -> blockingResponses(prompt, backend, resolved.name)
        }
    }

    /**
     * Non-streaming /v1/responses with tool parsing. Same wire shape as
     * Codex expects: an `output` array containing either a `message` item
     * (text) or `function_call` items (tool calls), or both.
     */
    private fun blockingResponsesWithTools(
        prompt: String,
        backend: String,
        model: String,
        allowedToolNames: Set<String>,
    ): Response {
        val r = engine.generateBlocking(prompt, backend)
        if (r.error != null) {
            return json(Response.Status.INTERNAL_ERROR, JSONObject().apply {
                put("type", "error")
                put("error", JSONObject().apply { put("code", "server_error"); put("message", r.error) })
            })
        }
        val parsed = ChatFormat.parseToolCalls(r.text, allowedToolNames.takeIf { it.isNotEmpty() })
        val outputItems = JSONArray()
        if (parsed.text.isNotEmpty()) {
            val msgId = "msg_" + UUID.randomUUID().toString().replace("-", "").take(20)
            outputItems.put(
                JSONObject().apply {
                    put("id", msgId)
                    put("type", "message")
                    put("role", "assistant")
                    put("status", "completed")
                    put(
                        "content",
                        JSONArray().put(
                            JSONObject().apply {
                                put("type", "output_text")
                                put("text", parsed.text)
                                put("annotations", JSONArray())
                            },
                        ),
                    )
                },
            )
        }
        for (tc in parsed.toolCalls) {
            val callItemId = "fc_" + UUID.randomUUID().toString().replace("-", "").take(20)
            outputItems.put(
                JSONObject().apply {
                    put("id", callItemId)
                    put("type", "function_call")
                    put("call_id", tc.id)
                    put("name", tc.name)
                    put("arguments", tc.arguments.toString())
                    put("status", "completed")
                },
            )
        }
        return json(
            Response.Status.OK,
            JSONObject().apply {
                put("id", "resp_" + UUID.randomUUID().toString().replace("-", "").take(24))
                put("object", "response")
                put("status", "completed")
                put("model", model)
                put("created_at", System.currentTimeMillis() / 1000)
                put("output", outputItems)
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
     * Buffered streaming path. Used when the request includes `tools` and we
     * therefore must NOT spray the model's tool-call JSON to the client one
     * token at a time. Instead: run the engine to completion, parse for
     * `{"tool_calls":[...]}`, then synthesize the wire-correct streaming
     * frames via encoder.emitBuffered (which knows how to encode each
     * envelope's tool_use / tool_calls / function_call shape).
     *
     * Trade-off: the client doesn't see partial deltas during the model's
     * generation. For tool-driven turns this is fine — the agent is waiting
     * for the tool selection, not displaying partial thoughts.
     */
    private fun runStreamBuffered(
        prompt: String,
        backend: String,
        encoder: StreamEncoder,
        allowedToolNames: Set<String>,
    ): Response {
        val pipeIn = PipedInputStream(64 * 1024)
        val pipeOut = PipedOutputStream(pipeIn)
        thread(start = true, name = "litertlm-buffered") {
            val writer = PrintWriter(pipeOut.writer(Charsets.UTF_8), false)
            memoryProbe?.start("buffered backend=$backend tools=${allowedToolNames.size}")
            try {
                val r = engine.generateBlocking(prompt, backend)
                if (r.error != null) {
                    encoder.emitError(writer, r.error!!)
                } else {
                    // Cross-check parsed tool names against the request's
                    // declared tools. Drops hallucinated / prompt-injected
                    // names before they reach the agent client.
                    val parsed = ChatFormat.parseToolCalls(r.text, allowedToolNames)
                    encoder.emitBuffered(
                        writer,
                        BufferedResult(
                            text = parsed.text,
                            toolCalls = parsed.toolCalls,
                            durationMs = r.totalDurationMs,
                            outputTokens = r.outputTokens,
                            outputChars = r.text.length,
                            backend = r.backend,
                        ),
                    )
                }
            } catch (t: Throwable) {
                Log.e(TAG, "buffered worker failed", t)
                try { encoder.emitError(writer, t.message ?: t.javaClass.simpleName) } catch (_: Throwable) {}
            } finally {
                try { writer.flush() } catch (_: Throwable) {}
                try { writer.close() } catch (_: Throwable) {}
                try { memoryProbe?.stop() } catch (_: Throwable) {}
            }
        }
        return newChunkedResponse(Response.Status.OK, encoder.contentType, pipeIn)
    }

    /**
     * Drive one inference and frame each token through [encoder]. Pattern:
     *   - Spawn a worker thread that calls engine.generate().collect{ ... }.
     *   - Pipe its writes into a PipedInputStream returned to NanoHTTPD as a
     *     chunked response. NanoHTTPD pulls bytes off the pipe back to the
     *     client as the worker produces them.
     *   - Errors emitted by LlmEngine become an envelope-specific error frame
     *     followed by clean stream close.
     */
    private fun runStream(prompt: String, backend: String, encoder: StreamEncoder, imageBytes: ByteArray? = null): Response {
        val pipeIn = PipedInputStream(64 * 1024)
        val pipeOut = PipedOutputStream(pipeIn)
        thread(start = true, name = "litertlm-stream") {
            val writer = PrintWriter(pipeOut.writer(Charsets.UTF_8), false)
            memoryProbe?.start("stream backend=$backend img=${imageBytes != null}")
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
                    engine.generate(prompt, backend, imageBytes).collect { ev ->
                        when (ev) {
                            is GenerateEvent.Token -> {
                                if (!startEmitted) {
                                    encoder.emitStart(writer)
                                    startEmitted = true
                                }
                                encoder.emitToken(writer, ev.text)
                            }
                            is GenerateEvent.Done -> {
                                if (!startEmitted) {
                                    encoder.emitStart(writer)
                                    startEmitted = true
                                }
                                encoder.emitDone(writer, ev.totalDurationMs, ev.outputTokens, ev.outputChars, ev.backend)
                                terminated = true
                            }
                            is GenerateEvent.Error -> {
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
                try { memoryProbe?.stop() } catch (_: Throwable) {}
            }
        }
        return newChunkedResponse(Response.Status.OK, encoder.contentType, pipeIn)
    }

    /* --------------------------- Non-streaming responses --------------------------- */

    private enum class ResponseShape { LegacyGenerate, OllamaChat, OpenAiChat, AnthropicMessages }

    private fun blockingGenerate(
        prompt: String,
        backend: String,
        model: String,
        shape: ResponseShape,
        parseTools: Boolean = false,
        allowedToolNames: Set<String> = emptySet(),
        imageBytes: ByteArray? = null,
    ): Response {
        Log.i(TAG, "generate blocking shape=$shape backend=$backend model=$model tools=$parseTools img=${imageBytes != null} prompt=${prompt.take(80)}…")
        memoryProbe?.start("blocking shape=$shape backend=$backend tools=$parseTools img=${imageBytes != null}")
        val r = try { engine.generateBlocking(prompt, backend, imageBytes) } finally { memoryProbe?.stop() }
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
        // When tools are advertised, look in the model's output for the
        // `{"tool_calls":[...]}` JSON object and split it out from any
        // surrounding chatter. parsed.text is the prose remnant; parsed.toolCalls
        // are the structured calls (may be empty if the model just chatted).
        // The tool name cross-check (allowedToolNames) drops any call whose
        // name wasn't in the request's tools array — defense against prompt
        // injection that fakes a tool invocation.
        val parsed = if (parseTools) ChatFormat.parseToolCalls(r.text, allowedToolNames.takeIf { it.isNotEmpty() })
        else ChatFormat.ParsedOutput(r.text, emptyList())
        val text = parsed.text
        val toolCalls = parsed.toolCalls
        val hasTools = toolCalls.isNotEmpty()
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
                        JSONObject().apply {
                            put("role", "assistant")
                            put("content", text)
                            if (hasTools) {
                                val arr = JSONArray()
                                for (tc in toolCalls) {
                                    arr.put(
                                        JSONObject().apply {
                                            put(
                                                "function",
                                                JSONObject().apply {
                                                    put("name", tc.name)
                                                    put("arguments", tc.arguments)
                                                },
                                            )
                                        },
                                    )
                                }
                                put("tool_calls", arr)
                            }
                        },
                    )
                    put("done", true)
                    put("done_reason", if (hasTools) "tool_calls" else "stop")
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
                                        put("content", if (text.isEmpty() && hasTools) JSONObject.NULL else text)
                                        if (hasTools) {
                                            val arr = JSONArray()
                                            for (tc in toolCalls) {
                                                arr.put(
                                                    JSONObject().apply {
                                                        put("id", tc.id)
                                                        put("type", "function")
                                                        put(
                                                            "function",
                                                            JSONObject().apply {
                                                                put("name", tc.name)
                                                                // OpenAI requires `arguments` as a string.
                                                                put("arguments", tc.arguments.toString())
                                                            },
                                                        )
                                                    },
                                                )
                                            }
                                            put("tool_calls", arr)
                                        }
                                    },
                                )
                                put("finish_reason", if (hasTools) "tool_calls" else "stop")
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
                        JSONArray().apply {
                            // Anthropic content array: text block (if any),
                            // followed by one tool_use block per parsed call.
                            if (text.isNotEmpty()) {
                                put(
                                    JSONObject().apply {
                                        put("type", "text")
                                        put("text", text)
                                    },
                                )
                            }
                            for (tc in toolCalls) {
                                val toolUseId = "toolu_" + UUID.randomUUID().toString().replace("-", "").take(20)
                                put(
                                    JSONObject().apply {
                                        put("type", "tool_use")
                                        put("id", toolUseId)
                                        put("name", tc.name)
                                        put("input", tc.arguments)
                                    },
                                )
                            }
                            // If neither text nor tools, emit an empty text block
                            // so clients with strict shape parsers don't choke.
                            if (this.length() == 0) {
                                put(
                                    JSONObject().apply {
                                        put("type", "text")
                                        put("text", "")
                                    },
                                )
                            }
                        },
                    )
                    put("model", model)
                    put("stop_reason", if (hasTools) "tool_use" else "end_turn")
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
        // Two paths:
        //   1) Content-Length header set -> short-circuit: reject early with
        //      413 if larger than MAX_BODY, otherwise bounded read.
        //   2) Chunked / unknown-length body -> stream up to MAX_BODY+1 bytes.
        //      If we read past MAX_BODY without EOF, throw BodyTooLarge so
        //      the outer serve() catch returns a real 413 instead of silently
        //      truncating (codex outside-review v0.4.0 caught the silent
        //      truncation case).
        val cl = session.headers["content-length"]?.toLongOrNull()
        val ins = session.inputStream
        if (cl != null) {
            // Content-Length: 0 -> empty body. Don't fall through to the
            // streaming path (codex review v0.4.0: would tie up a worker
            // thread blocked on a keep-alive connection).
            if (cl == 0L) return ""
            if (cl > 0L) {
                if (cl > MAX_BODY) throw BodyTooLarge()
                val limit = cl.toInt()
                val buf = ByteArray(limit)
                var pos = 0
                while (pos < limit) {
                    val n = ins.read(buf, pos, limit - pos)
                    if (n <= 0) break
                    pos += n
                }
                return String(buf, 0, pos, Charsets.UTF_8)
            }
            // Negative Content-Length is malformed; treat as empty.
            return ""
        }
        // No Content-Length header at all -> chunked / unknown-length body.
        // Read defensively: as soon as we exceed MAX_BODY by even one
        // byte, abort with 413 so an attacker can't drive memory by
        // streaming a multi-GB body. codex review v0.4.0 caught the
        // off-by-one (`total > MAX_BODY+1` would silently accept exactly
        // MAX_BODY+1 bytes); now we throw the moment we cross MAX_BODY.
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val n = ins.read(chunk)
            if (n <= 0) break
            total += n
            if (total > MAX_BODY) throw BodyTooLarge()
            out.write(chunk, 0, n)
        }
        if (total == 0) return ""
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun text(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "text/plain; charset=utf-8", body)

    private fun json(status: Response.Status, obj: JSONObject): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", obj.toString() + "\n")

    private fun errorJson(status: Response.Status, msg: String): Response =
        json(status, JSONObject().apply { put("error", msg) })
}
