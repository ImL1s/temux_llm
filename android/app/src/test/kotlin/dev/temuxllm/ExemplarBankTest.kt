package dev.temuxllm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for ExemplarBank (v0.8.0 G4).
 *
 * These tests run on the JVM without a real Android Context: they either
 * read the asset JSON directly from disk (for the "loading" tests) or
 * inject a pre-parsed map via the internal constructor (for the rendering
 * and opt-out tests).
 */
class ExemplarBankTest {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Read the actual tool_exemplars.json from the classpath (test resources). */
    private fun loadExemplarJson(): String {
        val stream = javaClass.classLoader!!.getResourceAsStream("tool_exemplars.json")
            ?: error("tool_exemplars.json not found on test classpath")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** Build a minimal single-exemplar bank for unit testing. */
    private fun bankWith(vararg pairs: Pair<String, ExemplarBank.Exemplar>): ExemplarBank =
        ExemplarBank(mapOf(*pairs), false)

    private fun exemplar(toolName: String) = ExemplarBank.Exemplar(
        user = "Call $toolName please.",
        assistantToolCall = JSONObject().apply {
            put("name", toolName)
            put("arguments", JSONObject().apply { put("input", "value") })
        },
        toolResult = "Result of $toolName",
    )

    // -----------------------------------------------------------------------
    // Test 1: loading JSON from the asset file returns 8 named exemplars + _default
    // -----------------------------------------------------------------------

    @Test fun `parseJson from asset file returns 8 tool exemplars plus _default`() {
        val json = loadExemplarJson()
        val map = ExemplarBank.parseJson(json)

        val expectedKeys = setOf(
            "get_weather", "read_file", "write_file", "glob", "grep",
            "bash", "direct_web_fetch", "list_directory", "_default",
        )
        // 8 named tools + 1 _default = 9 entries
        assertEquals("expected 9 entries (8 tools + _default)", 9, map.size)
        for (key in expectedKeys) {
            assertNotNull("missing key: $key", map[key])
        }
        // Spot-check one exemplar has all required fields
        val weather = map["get_weather"]!!
        assertTrue("user must not be blank", weather.user.isNotBlank())
        assertTrue("toolResult must not be blank", weather.toolResult.isNotBlank())
        assertNotNull("assistantToolCall must not be null", weather.assistantToolCall)
    }

    // -----------------------------------------------------------------------
    // Test 2: renderForTools(["get_weather"]) returns 3 messages
    // -----------------------------------------------------------------------

    @Test fun `renderForTools single tool returns 3 messages in correct roles`() {
        val bank = bankWith("get_weather" to exemplar("get_weather"))
        val result = bank.renderForTools(listOf("get_weather"))

        assertEquals("expect 3 messages for one tool", 3, result.length())

        val userMsg = result.optJSONObject(0)!!
        assertEquals("user", userMsg.optString("role"))

        val assistantMsg = result.optJSONObject(1)!!
        assertEquals("assistant", assistantMsg.optString("role"))
        val toolCalls = assistantMsg.optJSONArray("tool_calls")
        assertNotNull("assistant must have tool_calls", toolCalls)
        assertEquals(1, toolCalls!!.length())
        val fn = toolCalls.optJSONObject(0)!!.optJSONObject("function")!!
        assertEquals("get_weather", fn.optString("name"))

        val toolResultMsg = result.optJSONObject(2)!!
        assertEquals("tool", toolResultMsg.optString("role"))
        val toolCallId = toolResultMsg.optString("tool_call_id")
        assertTrue("tool_call_id must not be blank", toolCallId.isNotBlank())
        // Verify round-trip: tool_call_id on result matches id on the call
        val callId = toolCalls.optJSONObject(0)!!.optString("id")
        assertEquals("tool_call_id must match assistant call id", callId, toolCallId)
    }

    // -----------------------------------------------------------------------
    // Test 3: renderForTools(["unknown"]) uses _default exemplar
    // -----------------------------------------------------------------------

    @Test fun `renderForTools unknown tool name falls back to _default exemplar`() {
        val default = exemplar("tool_name").copy(
            assistantToolCall = JSONObject().apply {
                put("name", "tool_name")
                put("arguments", JSONObject().apply { put("input", "value") })
            }
        )
        val bank = bankWith("_default" to default)
        val result = bank.renderForTools(listOf("does_not_exist"))

        // _default should have been used. v0.8.0 code review MEDIUM #2:
        // when falling back to _default, the rendered tool name is the
        // CALLER'S tool name (the loop variable), not the placeholder
        // "tool_name" string baked into _default. This avoids injecting
        // a fictional tool name into the prompt that the model would
        // then try to call back.
        assertEquals("expect 3 messages via _default fallback", 3, result.length())
        val assistantMsg = result.optJSONObject(1)!!
        val fn = assistantMsg.optJSONArray("tool_calls")!!.optJSONObject(0)!!.optJSONObject("function")!!
        assertEquals("does_not_exist", fn.optString("name"))
        // The tool-result turn must carry the same name so the caller's
        // request doesn't get a "tool_name" reference.
        val toolMsg = result.optJSONObject(2)!!
        assertEquals("does_not_exist", toolMsg.optString("name"))
    }

    // -----------------------------------------------------------------------
    // Test 4: renderForTools([]) returns empty array
    // -----------------------------------------------------------------------

    @Test fun `renderForTools empty list returns empty array`() {
        val bank = bankWith("get_weather" to exemplar("get_weather"))
        val result = bank.renderForTools(emptyList())
        assertEquals("empty input must yield empty array", 0, result.length())
    }

    // -----------------------------------------------------------------------
    // Test 5: disabled=true returns empty even for known tools
    // -----------------------------------------------------------------------

    @Test fun `disabled bank returns empty array for known tools`() {
        val bank = ExemplarBank(
            exemplars = mapOf("get_weather" to exemplar("get_weather")),
            disabled = true,
        )
        val result = bank.renderForTools(listOf("get_weather"))
        assertEquals("disabled bank must return empty array", 0, result.length())
    }
}
