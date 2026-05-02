package dev.temuxllm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * v0.8.0 G4: one-shot exemplar injection.
 *
 * Loads tool_exemplars.json from assets once on construction, then provides
 * [renderForTools] to build a 3-message sequence per tool (user / assistant
 * with tool_call / tool result) that is spliced into the messages array
 * BEFORE the first user turn. LangChain's empirical study showed +30-64 pp
 * tool-call reliability on small models (exactly the Gemma 4 E4B class we
 * run) with 1-3 canonical exemplar pairs.
 *
 * Opt-out: set env var TEMUXLLM_EXEMPLARS=0 (or "off"/"false") to disable.
 * Cap: first 2 tools only, to limit context bloat.
 *
 * The primary constructor is internal so tests can inject a pre-parsed map
 * without needing a real Android Context or env var.
 */
class ExemplarBank internal constructor(
    internal val exemplars: Map<String, Exemplar>,
    private val disabled: Boolean,
) {

    data class Exemplar(
        val user: String,
        val assistantToolCall: JSONObject,
        val toolResult: String,
    )

    /** Android constructor: loads from assets, reads env var. */
    constructor(context: Context) : this(
        exemplars = parseJson(loadAssetText(context)),
        disabled = isDisabledByEnv(),
    )

    /**
     * Returns a JSONArray of 3 × min(toolNames.size, 2) OpenAI-message-format
     * message objects: user / assistant-with-tool_call / tool-result for each
     * matched tool name. Returns an empty array if disabled, no names given,
     * or no exemplars match (after falling back to "_default").
     */
    fun renderForTools(toolNames: List<String>): JSONArray {
        if (disabled || toolNames.isEmpty() || exemplars.isEmpty()) return JSONArray()

        val out = JSONArray()
        var rendered = 0
        for (name in toolNames) {
            if (rendered >= 2) break
            val isDefault = exemplars[name] == null
            val exemplar = exemplars[name] ?: exemplars["_default"] ?: continue
            val callId = "call_ex_" + UUID.randomUUID().toString().replace("-", "").take(16)

            // v0.8.0 code review MEDIUM #2: when falling back to _default,
            // override the rendered tool name with the actual loop tool
            // name. tool_exemplars.json has `"name":"tool_name"` for
            // _default and `optString("name", name)` returned that string
            // verbatim, injecting a non-existent tool into the prompt and
            // confusing the model on real tool registry.
            val emittedName = if (isDefault) name
                else exemplar.assistantToolCall.optString("name", name)

            // 1) user turn
            out.put(JSONObject().apply {
                put("role", "user")
                put("content", exemplar.user)
            })

            // 2) assistant turn with tool_call — id is round-tripped into
            // the tool turn below so model can correlate call to result.
            val toolCallArg = JSONObject().apply {
                put("id", callId)
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", emittedName)
                    put("arguments", exemplar.assistantToolCall.optJSONObject("arguments")?.toString() ?: "{}")
                })
            }
            out.put(JSONObject().apply {
                put("role", "assistant")
                put("content", JSONObject.NULL)
                put("tool_calls", JSONArray().put(toolCallArg))
            })

            // 3) tool result turn
            out.put(JSONObject().apply {
                put("role", "tool")
                put("tool_call_id", callId)
                put("name", emittedName)
                put("content", exemplar.toolResult)
            })

            rendered++
        }
        return out
    }

    companion object {
        private fun loadAssetText(context: Context): String = try {
            context.assets.open("tool_exemplars.json").use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (t: Throwable) {
            // v0.8.0 code review MEDIUM #2: surface missing/unreadable
            // tool_exemplars.json so build misconfigurations don't silently
            // disable the +30-64 pp exemplar-injection win.
            android.util.Log.w("ExemplarBank", "tool_exemplars.json missing or unreadable; exemplar injection disabled", t)
            ""
        }

        /** Parse a JSON string (the content of tool_exemplars.json) into an Exemplar map. */
        internal fun parseJson(text: String): Map<String, Exemplar> {
            if (text.isBlank()) return emptyMap()
            return try {
                val root = JSONObject(text)
                val map = mutableMapOf<String, Exemplar>()
                for (key in root.keys()) {
                    val entry = root.optJSONObject(key) ?: continue
                    val userText = entry.optString("user")
                    val toolCall = entry.optJSONObject("assistant_tool_call") ?: continue
                    val result = entry.optString("tool_result")
                    map[key] = Exemplar(userText, toolCall, result)
                }
                map
            } catch (_: Throwable) { emptyMap() }
        }

        private fun isDisabledByEnv(): Boolean {
            val v = System.getenv("TEMUXLLM_EXEMPLARS")?.trim()?.lowercase()
            return v == "0" || v == "off" || v == "false"
        }
    }
}
