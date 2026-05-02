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
                    // v0.6.0 G2: unified prose. Same shape as the content-block
                    // tool_result branch in extractText() so models see ONE
                    // tool-result format across single-turn AND multi-turn,
                    // across all four wire envelopes.
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
                    "image", "image_url", "input_image" -> {
                        // v0.6.0 G3: image content is now extracted at the
                        // HttpServer level (extractFirstImage) and passed to
                        // engine.generate(... imageBytes = ...). The block here
                        // is a placeholder marker so the prompt text doesn't
                        // accidentally collapse multi-block content.
                        if (sb.isNotEmpty() && !sb.last().isWhitespace()) sb.append(' ')
                    }
                    // v0.6.0 G2: unified prose for all envelope tool-result blocks.
                    // Previously raw-JSON-stringified for Anthropic / OpenAI Chat /
                    // Ollama (only Responses path used prose), which Gemma 4 doesn't
                    // know how to follow on the second turn (template not in training
                    // distribution). All four envelopes now emit the same prose form
                    // so multi-turn tool flows work the same regardless of wire.
                    "tool_result" -> {
                        // Anthropic v1/messages — name lives on the originating
                        // tool_use block, not on the tool_result. We approximate
                        // with tool_use_id when name isn't carried.
                        val name = block.optString("name").ifBlank { block.optString("tool_use_id", "tool") }
                        val resultText = when (val r = block.opt("content")) {
                            is String -> r
                            is JSONArray -> {
                                val parts = StringBuilder()
                                for (j in 0 until r.length()) {
                                    val sub = r.optJSONObject(j) ?: continue
                                    if (sub.optString("type") == "text") parts.append(sub.optString("text"))
                                }
                                parts.toString()
                            }
                            null -> ""
                            else -> r.toString()
                        }
                        sb.append('\n').append("Tool[").append(name).append("]: ").append(resultText).append('\n')
                    }
                    "tool_use", "tool_call", "function_call" ->
                        // Caller's prior assistant turn carrying a tool call —
                        // serialize as text so flatten() can echo it back without
                        // confusing the model. The tool result follows in a
                        // subsequent block, picked up by the tool_result branch.
                        sb.append('\n').append(block.toString()).append('\n')
                    "function_call_output" -> {
                        // OpenAI Responses tool-result block — body is `output`.
                        val name = block.optString("name").ifBlank { "tool" }
                        val out = block.opt("output")?.toString().orEmpty()
                        sb.append('\n').append("Tool[").append(name).append("]: ").append(out).append('\n')
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

    fun parseToolCalls(raw: String, allowedNames: Set<String>? = null): ParsedOutput {
        if (raw.isBlank()) return ParsedOutput(raw, emptyList())
        val idx = findToolCallsObjectStart(raw)
        if (idx < 0) return ParsedOutput(raw.trim(), emptyList())
        // v0.6.0: try strict balance first; if model emitted unbalanced JSON
        // (the 7/30 Gemma 4 baseline failures we saw — `{"tool_calls":[...]]`
        // missing the outer `}`), fall through to repair-and-retry. Same
        // approach as Ollama's `repairGemma4ToolCallArgs` (`model/parsers/gemma4.go:485`).
        val end = findMatchingBrace(raw, idx)
        var parsed: JSONObject? = null
        var consumedEnd: Int = end  // index of last char consumed from raw
        if (end >= 0) {
            parsed = try { JSONObject(raw.substring(idx, end + 1)) } catch (_: Throwable) { null }
        }
        if (parsed == null) {
            // Repair attempt: take from idx to end of raw, balance braces/brackets.
            val tail = raw.substring(idx)
            val repaired = repairToolCallJson(tail)
            if (repaired != null) {
                parsed = try { JSONObject(repaired) } catch (_: Throwable) { null }
                if (parsed != null) {
                    consumedEnd = raw.length - 1   // repair consumed everything from idx
                    // Guard with try/catch — unit tests don't mock android.util.Log,
                    // and the parser is on the hot path; never let logging crash.
                    try {
                        android.util.Log.i("ChatFormat", "tool_call repair applied; tail_len=${tail.length} repaired_len=${repaired.length}")
                    } catch (_: Throwable) {}
                }
            }
        }
        if (parsed == null) return ParsedOutput(raw.trim(), emptyList())
        val arr = parsed.optJSONArray("tool_calls") ?: return ParsedOutput(raw.trim(), emptyList())
        val calls = mutableListOf<ToolCall>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val n = o.optString("name").ifBlank { o.optJSONObject("function")?.optString("name") ?: "" }
            if (n.isBlank()) continue
            // Defense-in-depth: cross-check the model's emitted name against
            // what the request actually advertised. Without this filter,
            // a prompt-injected user message could trick the model into
            // emitting a fake tool name (`exec`, `delete_repo`, ...) that
            // we would relay verbatim to the agent client. Same hardening
            // pattern Ollama's tools/tools.go uses (findTool()).
            if (allowedNames != null && n !in allowedNames) continue
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
        val text = (raw.substring(0, idx) + (if (consumedEnd + 1 <= raw.length) raw.substring(consumedEnd + 1) else "")).trim()
        return ParsedOutput(text, calls)
    }

    /**
     * Repair a Gemma 4-style malformed tool-call JSON tail. Walks the string
     * tracking string-quote / brace / bracket depth; on EOF, appends matching
     * closers in the right order (innermost first). Returns null if there's
     * nothing recognizable to repair.
     *
     * The 7/30 baseline failures (May 2026 v0.5.2 measurement) all share the
     * pattern `{"tool_calls":[{"name":"x","arguments":{"city":"Cairo"}]}` —
     * inner object close `}` missing, then bracket close, then outer close
     * also missing. This handler appends the missing closers deterministically.
     *
     * Inspired by Ollama's `model/parsers/gemma4.go:485 repairGemma4ToolCallArgs`
     * but only does the closing-brace/bracket pass that explains 100% of our
     * Gemma 4 E4B baseline failures. Trailing-comma / single-quote repairs
     * are deferred until empirical baseline shows we need them.
     *
     * @param tail string starting at the `{` of a tool-call object
     * @return repaired JSON string, or null if the input is too broken
     */
    fun repairToolCallJson(tail: String): String? {
        if (tail.isEmpty() || tail[0] != '{') return null
        // Defense-in-depth caps. Largest realistic Gemma output for a tool
        // call is ~2 KiB; 64 KiB is 30x that and still safely under any
        // memory pressure on a 12 GB device. Beyond this threshold the
        // input is almost certainly a probe / pathological payload and
        // not worth attempting repair on. (security review HIGH#1 +
        // code review HIGH#1)
        if (tail.length > 64 * 1024) return null
        val maxDepth = 128
        // Stack-based balanced rewriter. The 7/30 Gemma 4 E4B baseline failure
        // pattern (`{"tool_calls":[{"name":"x","arguments":{"k":"v"}]}`) drops
        // a `}` BEFORE the `]`, not at EOF — naive "append closers at EOF"
        // can't fix it. We track open brackets in a stack; when we see `]`
        // while the top is `{`, we insert `}` until top is `[`, then pop.
        // Stray closers are dropped (model occasionally over-closes).
        val out = StringBuilder()
        val stack = ArrayDeque<Char>()
        var inString = false
        var escaped = false
        var i = 0
        while (i < tail.length) {
            val c = tail[i]
            if (inString) {
                out.append(c)
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> { out.append(c); inString = true }
                    '{' -> {
                        if (stack.size >= maxDepth) return null   // bail on pathological nesting
                        out.append(c); stack.addLast('{')
                    }
                    '[' -> {
                        if (stack.size >= maxDepth) return null
                        out.append(c); stack.addLast('[')
                    }
                    '}' -> {
                        if (stack.isNotEmpty() && stack.last() == '{') {
                            stack.removeLast(); out.append(c)
                        }
                        // Stray `}` (over-close): drop silently.
                    }
                    ']' -> {
                        // If top is `{`, we have one or more missing `}` before this `]`.
                        while (stack.isNotEmpty() && stack.last() == '{') {
                            out.append('}'); stack.removeLast()
                        }
                        if (stack.isNotEmpty() && stack.last() == '[') {
                            stack.removeLast(); out.append(c)
                        }
                        // Stray `]`: drop.
                    }
                    else -> out.append(c)
                }
            }
            i += 1
        }
        if (inString) return null
        // EOF: pop any remaining open brackets, appending matching closers.
        while (stack.isNotEmpty()) {
            when (stack.removeLast()) {
                '{' -> out.append('}')
                '[' -> out.append(']')
            }
        }
        return out.toString()
    }

    /**
     * Extract the set of declared tool names from a request's tools array.
     * Accepts the same shapes [renderToolBlock] does (Anthropic, OpenAI,
     * Ollama). Returns an empty set when [tools] is null/empty so callers
     * can use `setOf().takeIf { it.isNotEmpty() }` to gate enforcement.
     */
    fun toolNamesFromRequest(tools: JSONArray?): Set<String> {
        if (tools == null) return emptySet()
        val s = mutableSetOf<String>()
        for (i in 0 until tools.length()) {
            val t = tools.optJSONObject(i) ?: continue
            val n = t.optJSONObject("function")?.optString("name").orEmpty().ifBlank { t.optString("name") }
            if (n.isNotBlank()) s += n
        }
        return s
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
