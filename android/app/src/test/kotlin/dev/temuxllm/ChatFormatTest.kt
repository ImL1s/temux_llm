package dev.temuxllm

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode

/**
 * Regression tests for the ChatFormat layer (pure Kotlin, no Context, no LiteRT-LM).
 * Locks in the v0.3.3 behavior: messages -> prompt flattening, tool-block
 * rendering, tool-call parsing with allowed-name validation.
 */
class ChatFormatTest {

    /* ----------------------------- flatten ----------------------------- */

    @Test fun `single user message flattens to raw content (legacy compat)`() {
        val msgs = JSONArray().put(JSONObject().apply { put("role", "user"); put("content", "hi") })
        val r = ChatFormat.flatten(msgs)
        assertTrue(r is ChatFormat.Result.Ok)
        assertEquals("hi", (r as ChatFormat.Result.Ok).prompt)
    }

    @Test fun `multi-message conversation builds transcript with assistant cue`() {
        val msgs = JSONArray()
            .put(JSONObject().apply { put("role", "user"); put("content", "Q1") })
            .put(JSONObject().apply { put("role", "assistant"); put("content", "A1") })
            .put(JSONObject().apply { put("role", "user"); put("content", "Q2") })
        val r = ChatFormat.flatten(msgs) as ChatFormat.Result.Ok
        assertTrue(r.prompt.contains("User: Q1"))
        assertTrue(r.prompt.contains("Assistant: A1"))
        assertTrue(r.prompt.contains("User: Q2"))
        assertTrue(r.prompt.endsWith("Assistant: "))
    }

    @Test fun `system parameter prepends System block`() {
        val msgs = JSONArray().put(JSONObject().apply { put("role", "user"); put("content", "hi") })
        val r = ChatFormat.flatten(msgs, system = "You are X") as ChatFormat.Result.Ok
        assertTrue(r.prompt.startsWith("System: You are X"))
    }

    @Test fun `image content block is no longer rejected (v0_6_0 G3)`() {
        // v0.6.0: image extraction lives at HttpServer level (extractFirstImage);
        // ChatFormat.flatten() should pass through with text content only.
        // The image block is silently consumed (caller will pass bytes to engine).
        val content = JSONArray()
            .put(JSONObject().apply { put("type", "text"); put("text", "describe") })
            .put(JSONObject().apply { put("type", "image_url"); put("image_url", "data:image/png;base64,iVBORw0KGgo=") })
        val msgs = JSONArray().put(JSONObject().apply {
            put("role", "user")
            put("content", content)
        })
        val r = ChatFormat.flatten(msgs)
        assertTrue("expected Ok now that image is HttpServer-handled, got $r", r is ChatFormat.Result.Ok)
        assertTrue((r as ChatFormat.Result.Ok).prompt.contains("describe"))
    }

    @Test fun `empty messages array returns Bad`() {
        val r = ChatFormat.flatten(JSONArray())
        assertTrue(r is ChatFormat.Result.Bad)
    }

    /* ---------------------- flattenResponsesInput ---------------------- */

    @Test fun `Responses input as plain string wraps with assistant cue`() {
        val r = ChatFormat.flattenResponsesInput("hello") as ChatFormat.Result.Ok
        assertTrue(r.prompt.contains("User: hello"))
        assertTrue(r.prompt.endsWith("Assistant: "))
    }

    @Test fun `Responses input array with function_call item renders tool call line`() {
        val input = JSONArray()
            .put(JSONObject().apply {
                put("type", "message"); put("role", "user")
                put("content", JSONArray().put(JSONObject().apply {
                    put("type", "input_text"); put("text", "weather?")
                }))
            })
            .put(JSONObject().apply {
                put("type", "function_call")
                put("name", "get_weather")
                put("arguments", JSONObject().apply { put("city", "Tokyo") })
            })
            .put(JSONObject().apply {
                put("type", "function_call_output")
                put("output", "sunny, 25°C")
            })
        val r = ChatFormat.flattenResponsesInput(input) as ChatFormat.Result.Ok
        assertTrue(r.prompt.contains("[calling tool get_weather"))
        assertTrue(r.prompt.contains("Tool result: sunny, 25°C"))
    }

    @Test fun `Responses null input returns Bad`() {
        val r = ChatFormat.flattenResponsesInput(null)
        assertTrue(r is ChatFormat.Result.Bad)
    }

    /* --------------------------- renderToolBlock --------------------------- */

    @Test fun `renderToolBlock null returns null (no injection)`() {
        assertEquals(null, ChatFormat.renderToolBlock(null))
        assertEquals(null, ChatFormat.renderToolBlock(JSONArray()))
    }

    @Test fun `renderToolBlock recognizes Anthropic shape`() {
        val tools = JSONArray().put(JSONObject().apply {
            put("name", "search")
            put("description", "Search the web")
            put("input_schema", JSONObject().apply { put("type", "object") })
        })
        val block = ChatFormat.renderToolBlock(tools)!!
        assertTrue(block.contains("search"))
        assertTrue(block.contains("Search the web"))
    }

    @Test fun `renderToolBlock recognizes OpenAI function shape`() {
        val tools = JSONArray().put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "get_weather")
                put("description", "Lookup weather")
                put("parameters", JSONObject().apply { put("type", "object") })
            })
        })
        val block = ChatFormat.renderToolBlock(tools)!!
        assertTrue(block.contains("get_weather"))
        assertTrue(block.contains("Lookup weather"))
    }

    /* ---------------------- toolNamesFromRequest ---------------------- */

    @Test fun `toolNamesFromRequest extracts both shapes`() {
        val tools = JSONArray()
            .put(JSONObject().apply { put("name", "anthropic_tool") })
            .put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply { put("name", "openai_tool") })
            })
        val names = ChatFormat.toolNamesFromRequest(tools)
        assertEquals(setOf("anthropic_tool", "openai_tool"), names)
    }

    @Test fun `toolNamesFromRequest empty for null`() {
        assertEquals(emptySet<String>(), ChatFormat.toolNamesFromRequest(null))
    }

    /* ------------------------- parseToolCalls -------------------------- */

    @Test fun `parseToolCalls extracts simple call`() {
        val raw = """{"tool_calls":[{"name":"search","arguments":{"q":"k8s"}}]}"""
        val parsed = ChatFormat.parseToolCalls(raw)
        assertEquals(1, parsed.toolCalls.size)
        assertEquals("search", parsed.toolCalls[0].name)
        assertEquals("k8s", parsed.toolCalls[0].arguments.optString("q"))
        assertEquals("", parsed.text)
    }

    @Test fun `parseToolCalls strips JSON from surrounding chatter`() {
        val raw = "Sure, calling: {\"tool_calls\":[{\"name\":\"x\",\"arguments\":{}}]} now"
        val parsed = ChatFormat.parseToolCalls(raw)
        assertEquals(1, parsed.toolCalls.size)
        assertEquals("x", parsed.toolCalls[0].name)
        // text remnant is the prose around the JSON, trimmed
        assertTrue(parsed.text.contains("Sure"))
        assertTrue(parsed.text.contains("now"))
    }

    @Test fun `parseToolCalls returns plain text when no tool_calls JSON`() {
        val parsed = ChatFormat.parseToolCalls("just a normal reply")
        assertEquals(0, parsed.toolCalls.size)
        assertEquals("just a normal reply", parsed.text)
    }

    @Test fun `parseToolCalls drops names not in allowedNames (codex review fix)`() {
        // Prompt-injection scenario: model emits a tool name that wasn't in
        // the request's tools array. Without the cross-check, we'd relay
        // shell_exec or rm_rf to the agent client.
        val raw = """{"tool_calls":[{"name":"shell_exec","arguments":{"cmd":"rm -rf /"}}]}"""
        val parsed = ChatFormat.parseToolCalls(raw, allowedNames = setOf("get_weather"))
        assertEquals("shell_exec must NOT pass when not in allowedNames", 0, parsed.toolCalls.size)
    }

    @Test fun `parseToolCalls allows names in allowedNames`() {
        val raw = """{"tool_calls":[{"name":"get_weather","arguments":{"city":"Tokyo"}}]}"""
        val parsed = ChatFormat.parseToolCalls(raw, allowedNames = setOf("get_weather"))
        assertEquals(1, parsed.toolCalls.size)
        assertEquals("get_weather", parsed.toolCalls[0].name)
    }

    @Test fun `parseToolCalls null allowedNames means accept anything (back-compat)`() {
        val raw = """{"tool_calls":[{"name":"x","arguments":{}}]}"""
        val parsed = ChatFormat.parseToolCalls(raw, allowedNames = null)
        assertEquals(1, parsed.toolCalls.size)
    }

    @Test fun `parseToolCalls brace balance handles nested braces in string values`() {
        // String value contains `{` and `}` — naive regex would mis-balance.
        val raw = """{"tool_calls":[{"name":"x","arguments":{"q":"a {nested} value"}}]}"""
        val parsed = ChatFormat.parseToolCalls(raw)
        assertEquals(1, parsed.toolCalls.size)
        assertEquals("a {nested} value", parsed.toolCalls[0].arguments.optString("q"))
    }

    @Test fun `parseToolCalls malformed JSON returns plain text`() {
        val raw = """ramble {"tool_calls":[{"name":"x" garbled"""
        val parsed = ChatFormat.parseToolCalls(raw)
        assertEquals(0, parsed.toolCalls.size)
        assertTrue(parsed.text.contains("ramble"))
    }

    @Test fun `parseToolCalls handles arguments as stringified JSON`() {
        // OpenAI Chat tool_calls use stringified arguments.
        val raw = """{"tool_calls":[{"name":"x","arguments":"{\"k\":\"v\"}"}]}"""
        val parsed = ChatFormat.parseToolCalls(raw)
        assertEquals(1, parsed.toolCalls.size)
        assertEquals("v", parsed.toolCalls[0].arguments.optString("k"))
    }

    // v0.6.0: repair pass for Gemma 4 E4B malformed tool calls.
    // 7/30 baseline failures (May 2026) all share this exact pattern —
    // the inner object close `}` is missing BEFORE the array close `]`.

    @Test fun `parseToolCalls repairs Gemma E4B baseline failure (missing inner brace)`() {
        // Verbatim from /tmp/optA_results/log.txt sample [1]
        val raw = """{"tool_calls":[{"name":"get_weather","arguments":{"city":"Paris"}]}"""
        val parsed = ChatFormat.parseToolCalls(raw)
        assertEquals("repair should recover the call", 1, parsed.toolCalls.size)
        assertEquals("get_weather", parsed.toolCalls[0].name)
        assertEquals("Paris", parsed.toolCalls[0].arguments.optString("city"))
    }

    @Test fun `parseToolCalls repairs missing outer close at EOF`() {
        // Variant: trailing `}` also missing, only `]`.
        val raw = """{"tool_calls":[{"name":"f","arguments":{"k":"v"}]"""
        val parsed = ChatFormat.parseToolCalls(raw)
        assertEquals(1, parsed.toolCalls.size)
        assertEquals("v", parsed.toolCalls[0].arguments.optString("k"))
    }

    @Test fun `parseToolCalls repairs raw stream with trailing prose`() {
        val raw = """{"tool_calls":[{"name":"f","arguments":{"k":"v"}]}  Hope that helps!"""
        val parsed = ChatFormat.parseToolCalls(raw)
        assertEquals(1, parsed.toolCalls.size)
    }

    @Test fun `parseToolCalls repairs nested args missing closer`() {
        val raw = """{"tool_calls":[{"name":"f","arguments":{"a":{"b":1}]}"""
        val parsed = ChatFormat.parseToolCalls(raw)
        assertEquals(1, parsed.toolCalls.size)
        assertEquals(1, parsed.toolCalls[0].arguments.optJSONObject("a")?.optInt("b"))
    }

    @Test fun `parseToolCalls already-balanced JSON unchanged by repair path`() {
        // Sanity: 23/30 baseline passes must remain passing after repair landed.
        val raw = """{"tool_calls":[{"name":"get_weather","arguments":{"city":"Tokyo"}}]}"""
        val parsed = ChatFormat.parseToolCalls(raw)
        assertEquals(1, parsed.toolCalls.size)
        assertEquals("Tokyo", parsed.toolCalls[0].arguments.optString("city"))
    }

    @Test fun `parseToolCalls truly garbled returns plain text (repair fail-open)`() {
        val raw = """{"tool_calls":[{"name": "broken without quote close"""
        val parsed = ChatFormat.parseToolCalls(raw)
        assertEquals(0, parsed.toolCalls.size)
    }

    // v0.8.0 G6 — tool-name dictionary harden (prefix + edit distance)

    @Test fun `resolveToolName exact match`() {
        val r = ChatFormat.resolveToolName("get_weather", setOf("get_weather", "read_file"))
        assertEquals("get_weather", r)
    }

    @Test fun `resolveToolName prefix - emitted is prefix of registered`() {
        // Real Gemma failure: model emits "direct_name" (just first key)
        // when intended "direct_web_fetch".
        val r = ChatFormat.resolveToolName("direct_name", setOf("direct_web_fetch", "read_file"))
        assertEquals(null, r)   // "direct_name" is NOT a prefix of "direct_web_fetch", so not matched
    }

    @Test fun `resolveToolName prefix - registered is prefix of emitted`() {
        // Mangled-suffix case: model emits "web_fetch_v2" but registry has "web_fetch".
        val r = ChatFormat.resolveToolName("web_fetch_v2", setOf("web_fetch", "read_file"))
        assertEquals("web_fetch", r)
    }

    @Test fun `resolveToolName edit-distance fixes typo`() {
        val r = ChatFormat.resolveToolName("get_weatehr", setOf("get_weather", "read_file"))
        assertEquals("get_weather", r)
    }

    @Test fun `resolveToolName edit-distance off-by-one`() {
        val r = ChatFormat.resolveToolName("direcct_web_fetch", setOf("direct_web_fetch"))
        assertEquals("direct_web_fetch", r)
    }

    @Test fun `resolveToolName drops totally unknown name`() {
        val r = ChatFormat.resolveToolName("delete_repo", setOf("get_weather", "read_file"))
        assertEquals(null, r)
    }

    @Test fun `resolveToolName ambiguous prefix picks shortest length-diff`() {
        // v0.8.0 sec/code review MEDIUM #2: when multiple registered names
        // qualify as prefix matches (both pass the length-ratio guard),
        // the original code returned whichever came first in Set
        // iteration order — non-deterministic with respect to client
        // tool-array order. Post-pass fix picks the candidate whose
        // length is closest to the emitted name (most-specific match).
        // Emitted "web_fetch" (9), registered {"web_fetcher" (11), "web_fetcher_v2" (14)}.
        // Both pass guard: lenRatio = 9/11 = 0.82, 9/14 = 0.64 (≥ 0.5).
        // Length diffs: 11-9=2 vs 14-9=5 → "web_fetcher" wins.
        val r = ChatFormat.resolveToolName("web_fetch", setOf("web_fetcher_v2", "web_fetcher"))
        assertEquals("web_fetcher", r)
        // Same set in opposite insertion order — answer must be the same.
        val r2 = ChatFormat.resolveToolName("web_fetch", setOf("web_fetcher", "web_fetcher_v2"))
        assertEquals("web_fetcher", r2)
    }

    @Test fun `parseToolCalls resolves mangled name with edit distance`() {
        val raw = """{"tool_calls":[{"name":"get_weatehr","arguments":{"city":"Tokyo"}}]}"""
        val parsed = ChatFormat.parseToolCalls(raw, allowedNames = setOf("get_weather"))
        assertEquals(1, parsed.toolCalls.size)
        assertEquals("get_weather", parsed.toolCalls[0].name)
    }

    @Test fun `repairToolCallJson is idempotent on valid input`() {
        val valid = """{"tool_calls":[{"name":"f","arguments":{"k":"v"}}]}"""
        val repaired = ChatFormat.repairToolCallJson(valid)
        // For already-valid input, repair pass should produce equivalent JSON
        // (string equality not required — JSON equality is).
        assertNotNull(repaired)
        val a = org.json.JSONObject(valid).toString()
        val b = org.json.JSONObject(repaired!!).toString()
        assertEquals(a, b)
    }

    // v0.8.0 G5: multi-pass schema-gated repair

    @Test fun `repairs single-quoted argument values`() {
        // Model emits 'Tokyo' with single quotes instead of double quotes.
        val raw = """{"tool_calls":[{"name":"get_weather","arguments":{"city":'Tokyo'}}]}"""
        val parsed = ChatFormat.parseToolCalls(raw)
        assertEquals("single-quoted repair should recover the call", 1, parsed.toolCalls.size)
        assertEquals("get_weather", parsed.toolCalls[0].name)
        assertEquals("Tokyo", parsed.toolCalls[0].arguments.optString("city"))
    }

    @Test fun `repairs missing closing quote`() {
        // Model emits "Tokyo without the closing quote — structural `}` closes it.
        val raw = """{"tool_calls":[{"name":"get_weather","arguments":{"city":"Tokyo}}]}"""
        val parsed = ChatFormat.parseToolCalls(raw)
        assertEquals("missing-close-quote repair should recover the call", 1, parsed.toolCalls.size)
        assertEquals("Tokyo", parsed.toolCalls[0].arguments.optString("city"))
    }

    @Test fun `pass 4 does not corrupt legitimate string with structural chars`() {
        // v0.8.0 sec/code review: pass 4 used to insert " before the first
        // `,`/`}`/`]` while inside a string, which corrupted long
        // arguments containing those chars (shell commands, regex, JSON
        // literals). The post-pass guard requires content < 32 chars
        // AND alphanumeric prev — a long sentence trips both gates and
        // leaves the string intact for downstream parsing.
        val raw = """{"tool_calls":[{"name":"bash","arguments":{"command":"ls -la /a/b/c/d/e | grep something, something else"}}]}"""
        val parsed = ChatFormat.parseToolCalls(raw)
        // The original input is already valid JSON; should parse one call.
        assertEquals("legitimate JSON should parse cleanly", 1, parsed.toolCalls.size)
        assertEquals(
            "ls -la /a/b/c/d/e | grep something, something else",
            parsed.toolCalls[0].arguments.optString("command"),
        )
    }

    @Test fun `repairs raw terminal string when schema says string`() {
        // Model emits Tokyo as a bare token; schema declares city as string type.
        val raw = """{"tool_calls":[{"name":"get_weather","arguments":{"city":Tokyo}}]}"""
        val schema = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("city", JSONObject().apply { put("type", "string") })
            })
        }
        val schemas = mapOf("get_weather" to schema)
        val parsed = ChatFormat.parseToolCalls(raw, toolSchemas = schemas)
        assertEquals("raw-token repair should recover the call", 1, parsed.toolCalls.size)
        assertEquals("Tokyo", parsed.toolCalls[0].arguments.optString("city"))
    }

    @Test fun `does NOT wrap raw terminal when schema says integer`() {
        // Schema says count is an integer — bare 42 should not get quoted.
        val raw = """{"tool_calls":[{"name":"count_tool","arguments":{"count":42}}]}"""
        val schema = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("count", JSONObject().apply { put("type", "integer") })
            })
        }
        val schemas = mapOf("count_tool" to schema)
        val parsed = ChatFormat.parseToolCalls(raw, toolSchemas = schemas)
        assertEquals(1, parsed.toolCalls.size)
        assertEquals(42, parsed.toolCalls[0].arguments.optInt("count"))
    }

    @Test fun `strips trailing prose after final brace`() {
        // Model appends a sentence after the JSON object.
        val raw = """{"tool_calls":[{"name":"f","arguments":{"k":"v"}}]} Hope that helps!"""
        val parsed = ChatFormat.parseToolCalls(raw)
        assertEquals(1, parsed.toolCalls.size)
        assertEquals("v", parsed.toolCalls[0].arguments.optString("k"))
    }

    @Test fun `chains all repair passes when needed`() {
        // Combines missing inner brace (pass 2) + trailing prose (pass 6).
        val raw = """{"tool_calls":[{"name":"get_weather","arguments":{"city":"Cairo"}]} Great!"""
        val parsed = ChatFormat.parseToolCalls(raw)
        assertEquals("chained repair should recover the call", 1, parsed.toolCalls.size)
        assertEquals("Cairo", parsed.toolCalls[0].arguments.optString("city"))
    }
}
