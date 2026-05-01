package dev.temuxllm

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Flatten a chat-style messages array (OpenAI / Ollama / Anthropic shape) into a
 * single prompt string for LlmEngine.generate().
 *
 * The LiteRT-LM SDK's Conversation.sendMessageAsync applies Gemma 4's chat
 * template internally (the new <|turn>...<|tool> tokens), so we do NOT emit
 * those tokens here — we just produce a human-readable transcript and let the
 * SDK wrap it.
 *
 * Single-message fast path: a lone {role:"user", content:"..."} flattens to the
 * raw content string, matching the legacy /api/generate behavior.
 *
 * Anthropic content can be an array of content blocks; OpenAI and Ollama use a
 * plain string. We accept both. Image / tool_use content blocks are rejected
 * as 400 with an explanatory message rather than silently dropped.
 */
object ChatFormat {

    sealed class Result {
        data class Ok(val prompt: String) : Result()
        data class Bad(val message: String) : Result()
    }

    /**
     * @param messages the full messages array per the request body.
     * @param system   optional Anthropic-style top-level system prompt; if null,
     *                 we look for a {role:"system"} message instead.
     */
    fun flatten(messages: JSONArray?, system: String? = null): Result {
        if (messages == null || messages.length() == 0) {
            return Result.Bad("messages must be a non-empty array")
        }

        // Single user message = raw content. Preserves legacy /api/generate shape.
        if (messages.length() == 1 && system.isNullOrBlank()) {
            val only = messages.optJSONObject(0) ?: return Result.Bad("messages[0] must be an object")
            if (only.optString("role") == "user") {
                val text = extractText(only.opt("content"))
                if (text is Result.Ok) return text
                if (text is Result.Bad) return text
            }
        }

        val sb = StringBuilder()
        // Prepend top-level system (Anthropic).
        if (!system.isNullOrBlank()) {
            sb.append("System: ").append(system).append("\n\n")
        }
        for (i in 0 until messages.length()) {
            val msg = messages.optJSONObject(i)
                ?: return Result.Bad("messages[$i] must be an object")
            val role = msg.optString("role").ifBlank { "user" }
            val text = when (val r = extractText(msg.opt("content"))) {
                is Result.Ok -> r.prompt
                is Result.Bad -> return r
            }
            when (role) {
                "system" -> sb.append("System: ").append(text).append("\n\n")
                "user" -> sb.append("User: ").append(text).append('\n')
                "assistant" -> sb.append("Assistant: ").append(text).append('\n')
                "tool" -> {
                    val toolName = msg.optString("name", "tool")
                    sb.append("Tool[").append(toolName).append("]: ").append(text).append('\n')
                }
                else -> sb.append(role).append(": ").append(text).append('\n')
            }
        }
        // Final cue for the model to continue as assistant.
        if (!sb.endsWith("Assistant: ") && !sb.endsWith("Assistant:")) {
            sb.append("Assistant: ")
        }
        return Result.Ok(sb.toString())
    }

    /**
     * Extract a plain-text payload from a content field that may be:
     *   - a plain string (OpenAI/Ollama)
     *   - an array of {type:"text",text:"..."} blocks (Anthropic / multi-modal OpenAI)
     *   - null or missing -> empty string
     */
    private fun extractText(content: Any?): Result {
        if (content == null || content == JSONObject.NULL) return Result.Ok("")
        if (content is String) return Result.Ok(content)
        if (content is JSONArray) {
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.optJSONObject(i) ?: continue
                when (block.optString("type")) {
                    // OpenAI Chat / Anthropic basic text block.
                    "text" -> sb.append(block.optString("text"))
                    // OpenAI Responses input shape.
                    "input_text" -> sb.append(block.optString("text"))
                    // OpenAI Responses output shape — Codex echoes prior
                    // assistant turns back into `input` as `output_text` items.
                    "output_text" -> sb.append(block.optString("text"))
                    "image", "image_url", "input_image" ->
                        return Result.Bad("image content is not supported on this model")
                    // Anthropic and OpenAI Responses tool blocks.
                    "tool_use", "tool_result", "tool_call", "function_call" ->
                        sb.append('\n').append(block.toString()).append('\n')
                    "function_call_output" -> {
                        // OpenAI Responses tool-result block — body is `output`.
                        val out = block.opt("output")?.toString().orEmpty()
                        sb.append('\n').append("Tool result: ").append(out).append('\n')
                    }
                    "" -> { if (sb.isNotEmpty()) sb.append(' ') }
                    else -> {
                        // Unknown content-block type — skip rather than fail.
                        // Some clients emit `type:"thinking"` or vendor-specific blocks
                        // we don't model yet; dropping them is safer than 400.
                    }
                }
            }
            return Result.Ok(sb.toString())
        }
        return Result.Ok(content.toString())
    }

    /**
     * Render a list of tool definitions into a system-prompt instruction
     * block. Accepts the three input shapes used by clients:
     *
     *   Anthropic   { name, description, input_schema:{type,properties,...} }
     *   OpenAI Chat { type:"function", function:{ name, description, parameters } }
     *   Ollama      { type:"function", function:{ name, description, parameters } }
     *
     * The block tells the model to emit a `{"tool_calls":[...]}` JSON object
     * when it wants to invoke a tool — this is the lowest-common-denominator
     * format we can reliably round-trip through any sufficiently capable
     * model without depending on Gemma 4's native `<|tool_call>` tokens or
     * model-specific chat templates. The HTTP layer parses the JSON back
     * out and re-encodes per envelope (Anthropic tool_use, OpenAI tool_calls,
     * Ollama tool_calls).
     *
     * Returns null if [tools] is null/empty (caller skips injection).
     */
    fun renderToolBlock(tools: JSONArray?): String? {
        if (tools == null || tools.length() == 0) return null
        val sb = StringBuilder()
        sb.append("You have access to the following tools. ")
        sb.append("If a tool is useful for the user's request, respond with ONLY a JSON object on its own — no prose, no markdown — in this exact format:\n\n")
        sb.append("{\"tool_calls\":[{\"name\":\"<tool_name>\",\"arguments\":{<key>:<value>, ...}}]}\n\n")
        sb.append("Use this format only when calling a tool. Otherwise respond normally with plain text.\n\n")
        sb.append("Available tools:\n\n")
        for (i in 0 until tools.length()) {
            val t = tools.optJSONObject(i) ?: continue
            val name: String
            val desc: String
            val params: Any?
            val fn = t.optJSONObject("function")
            if (fn != null) {
                // OpenAI / Ollama style.
                name = fn.optString("name", "")
                desc = fn.optString("description", "")
                params = fn.opt("parameters")
            } else {
                // Anthropic style.
                name = t.optString("name", "")
                desc = t.optString("description", "")
                params = t.opt("input_schema") ?: t.opt("parameters")
            }
            if (name.isBlank()) continue
            sb.append("- ").append(name)
            if (desc.isNotBlank()) sb.append(": ").append(desc)
            sb.append('\n')
            if (params != null && params != JSONObject.NULL) {
                sb.append("  schema: ").append(params.toString()).append('\n')
            }
        }
        sb.append('\n')
        return sb.toString()
    }

    /**
     * Parse the model's raw output for tool-call JSON.
     *
     * Strategy: scan for the FIRST top-level JSON object that contains a
     * `tool_calls` array. The model's surrounding chatter (if any) becomes
     * `text`; the JSON itself is consumed. Tool calls without a `name` field
     * are dropped silently. Argument-object parsing tolerates string keys
     * with non-string values and is forgiving on whitespace.
     */
    data class ToolCall(val id: String, val name: String, val arguments: JSONObject)
    data class ParsedOutput(val text: String, val toolCalls: List<ToolCall>)

    fun parseToolCalls(raw: String): ParsedOutput {
        if (raw.isBlank()) return ParsedOutput(raw, emptyList())
        // Look for { ... "tool_calls" ... } anywhere in the buffer. Use a
        // brace-balance walker (regex won't reliably balance nested braces).
        val idx = findToolCallsObjectStart(raw)
        if (idx < 0) return ParsedOutput(raw.trim(), emptyList())
        val end = findMatchingBrace(raw, idx)
        if (end < 0) return ParsedOutput(raw.trim(), emptyList())
        val candidate = raw.substring(idx, end + 1)
        val parsed = try { JSONObject(candidate) } catch (_: Throwable) { return ParsedOutput(raw.trim(), emptyList()) }
        val arr = parsed.optJSONArray("tool_calls") ?: return ParsedOutput(raw.trim(), emptyList())
        val calls = mutableListOf<ToolCall>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val n = o.optString("name").ifBlank { o.optJSONObject("function")?.optString("name") ?: "" }
            if (n.isBlank()) continue
            val a = when {
                o.opt("arguments") is JSONObject -> o.optJSONObject("arguments")!!
                o.opt("arguments") is String -> try { JSONObject(o.optString("arguments")) } catch (_: Throwable) { JSONObject() }
                o.optJSONObject("function")?.opt("arguments") is JSONObject -> o.optJSONObject("function")!!.optJSONObject("arguments")!!
                o.optJSONObject("function")?.opt("arguments") is String ->
                    try { JSONObject(o.optJSONObject("function")!!.optString("arguments")) } catch (_: Throwable) { JSONObject() }
                else -> JSONObject()
            }
            val id = "call_" + UUID.randomUUID().toString().replace("-", "").take(20)
            calls += ToolCall(id = id, name = n, arguments = a)
        }
        val text = (raw.substring(0, idx) + raw.substring(end + 1)).trim()
        return ParsedOutput(text, calls)
    }

    private fun findToolCallsObjectStart(s: String): Int {
        // Scan for `"tool_calls"` (with quotes) and back up to the enclosing `{`.
        val key = "\"tool_calls\""
        val i = s.indexOf(key)
        if (i < 0) return -1
        var j = i - 1
        while (j >= 0) {
            if (s[j] == '{') return j
            j -= 1
        }
        return -1
    }

    /** Returns the index of the `}` that closes the `{` at [start], or -1. */
    private fun findMatchingBrace(s: String, start: Int): Int {
        if (start < 0 || start >= s.length || s[start] != '{') return -1
        var depth = 0
        var inString = false
        var escaped = false
        var i = start
        while (i < s.length) {
            val c = s[i]
            if (inString) {
                if (escaped) { escaped = false }
                else if (c == '\\') { escaped = true }
                else if (c == '"') { inString = false }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth += 1
                    '}' -> {
                        depth -= 1
                        if (depth == 0) return i
                    }
                }
            }
            i += 1
        }
        return -1
    }

    /**
     * OpenAI Responses input flattener. The `input` field on POST
     * /v1/responses is heterogeneous: it can be a plain string, an array of
     * messages-with-content-blocks (regular conversation), OR an array of
     * top-level Responses items like `{"type":"message"...}`,
     * `{"type":"function_call"...}`, `{"type":"function_call_output"...}`,
     * `{"type":"reasoning"...}`. Codex's agent loop sends the third form on
     * subsequent turns (per OpenAI's "Unrolling the Codex agent loop" post),
     * and dropping those items silently makes multi-turn inference incoherent.
     */
    fun flattenResponsesInput(input: Any?, instructions: String? = null): Result {
        if (input == null || input == JSONObject.NULL) {
            return Result.Bad("input is required")
        }
        if (input is String) {
            val sb = StringBuilder()
            if (!instructions.isNullOrBlank()) sb.append("System: ").append(instructions).append("\n\n")
            sb.append("User: ").append(input).append('\n').append("Assistant: ")
            return Result.Ok(sb.toString())
        }
        if (input is JSONArray) {
            val sb = StringBuilder()
            if (!instructions.isNullOrBlank()) sb.append("System: ").append(instructions).append("\n\n")
            for (i in 0 until input.length()) {
                val item = input.optJSONObject(i) ?: continue
                when (val type = item.optString("type")) {
                    // Conversation messages — has role + content. Empty type
                    // is the legacy chat-completions shape with no top-level type.
                    "", "message" -> {
                        val role = item.optString("role").ifBlank { "user" }
                        val text = when (val r = extractText(item.opt("content"))) {
                            is Result.Ok -> r.prompt
                            is Result.Bad -> return r
                        }
                        when (role) {
                            "system" -> sb.append("System: ").append(text).append("\n\n")
                            "user" -> sb.append("User: ").append(text).append('\n')
                            "assistant" -> sb.append("Assistant: ").append(text).append('\n')
                            else -> sb.append(role).append(": ").append(text).append('\n')
                        }
                    }
                    "function_call", "tool_call" -> {
                        val name = item.optString("name", "tool")
                        val args = item.opt("arguments")?.toString().orEmpty()
                        sb.append("Assistant: [calling tool ").append(name).append("(").append(args).append(")]\n")
                    }
                    "function_call_output", "tool_result" -> {
                        val out = item.opt("output")?.toString().orEmpty()
                        sb.append("Tool result: ").append(out).append('\n')
                    }
                    "reasoning" -> {
                        // Skip — internal model state, not for the next turn's prompt.
                    }
                    else -> {
                        sb.append('[').append(type).append("]\n")
                    }
                }
            }
            if (!sb.endsWith("Assistant: ") && !sb.endsWith("Assistant:")) {
                sb.append("Assistant: ")
            }
            return Result.Ok(sb.toString())
        }
        return Result.Ok(input.toString())
    }
}
