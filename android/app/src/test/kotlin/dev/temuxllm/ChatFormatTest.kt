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

    @Test fun `image content block returns Bad result`() {
        val content = JSONArray()
            .put(JSONObject().apply { put("type", "text"); put("text", "describe") })
            .put(JSONObject().apply { put("type", "image_url"); put("image_url", "x") })
        val msgs = JSONArray().put(JSONObject().apply {
            put("role", "user")
            put("content", content)
        })
        val r = ChatFormat.flatten(msgs)
        assertTrue("expected Bad on image content but got $r", r is ChatFormat.Result.Bad)
        assertTrue((r as ChatFormat.Result.Bad).message.contains("image"))
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
}
