package dev.temuxllm

import org.json.JSONArray
import org.json.JSONObject

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
                when (val type = block.optString("type")) {
                    "text" -> sb.append(block.optString("text"))
                    "input_text" -> sb.append(block.optString("text"))
                    "image", "image_url", "input_image" ->
                        return Result.Bad("image content is not supported on this model")
                    "tool_use", "tool_result" ->
                        // Best-effort: serialize tool blocks as JSON so the model can see them.
                        sb.append('\n').append(block.toString()).append('\n')
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
        // Unknown shape — coerce to string rather than 400.
        return Result.Ok(content.toString())
    }
}
