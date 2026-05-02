package dev.temuxllm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Regression tests for the four wire-format encoders. JSONAssert.LENIENT
 * checks shape conformance without locking us in on synthesized fields
 * (id, created_at, sequence_number) that change per call. We capture the
 * raw stream into a StringWriter so streaming framing is visible.
 */
class StreamEncodersTest {

    private fun capture(block: (PrintWriter) -> Unit): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw, false)
        block(pw)
        pw.flush()
        return sw.toString()
    }

    /* ----------------------- NdjsonChatEncoder ----------------------- */

    @Test fun `Ollama chat token frame matches wire spec`() {
        val enc = NdjsonChatEncoder("test-model")
        val out = capture { enc.emitToken(it, "hi") }
        val expected = """{"model":"test-model","message":{"role":"assistant","content":"hi"},"done":false}"""
        JSONAssert.assertEquals(expected, out.trim(), JSONCompareMode.LENIENT)
    }

    @Test fun `Ollama chat done frame includes done_reason and total_duration in nanoseconds`() {
        val enc = NdjsonChatEncoder("m")
        val out = capture { enc.emitDone(it, durationMs = 250, outputTokens = 7, outputChars = 20, backend = "gpu") }
        val obj = JSONObject(out.trim())
        assertEquals(true, obj.optBoolean("done"))
        assertEquals("stop", obj.optString("done_reason"))
        // total_duration is nanoseconds per Ollama convention; we use ms*1_000_000
        assertEquals(250_000_000L, obj.optLong("total_duration"))
        assertEquals(7, obj.optInt("eval_count"))
    }

    @Test fun `Ollama chat emitBuffered with tool_calls flips done_reason`() {
        val enc = NdjsonChatEncoder("m")
        val tc = ChatFormat.ToolCall(
            id = "call_x", name = "get_weather",
            arguments = JSONObject().apply { put("city", "Tokyo") },
        )
        val out = capture {
            enc.emitBuffered(
                it,
                BufferedResult(
                    text = "",
                    toolCalls = listOf(tc),
                    durationMs = 100, outputTokens = 5, outputChars = 0, backend = "gpu",
                ),
            )
        }
        val obj = JSONObject(out.trim())
        assertEquals("tool_calls", obj.optString("done_reason"))
        val toolCalls = obj.optJSONObject("message")!!.optJSONArray("tool_calls")!!
        assertEquals(1, toolCalls.length())
        // Ollama: arguments stays as object, NOT stringified
        val args = toolCalls.optJSONObject(0)!!.optJSONObject("function")!!.opt("arguments")
        assertTrue("Ollama tool_calls.arguments must be object", args is JSONObject)
    }

    /* ------------------------ OpenAiSseEncoder ------------------------ */

    @Test fun `OpenAI Chat first delta carries role assistant`() {
        val enc = OpenAiSseEncoder("m")
        val out = capture { enc.emitToken(it, "hi") }
        // Format: data: {...}\n\n
        assertTrue(out.startsWith("data: "))
        val payload = out.removePrefix("data: ").trim()
        val obj = JSONObject(payload)
        val delta = obj.getJSONArray("choices").getJSONObject(0).getJSONObject("delta")
        assertEquals("assistant", delta.optString("role"))
        assertEquals("hi", delta.optString("content"))
    }

    @Test fun `OpenAI Chat subsequent token deltas omit role`() {
        val enc = OpenAiSseEncoder("m")
        capture { enc.emitToken(it, "first") } // first
        val out = capture { enc.emitToken(it, "second") } // second
        val payload = out.removePrefix("data: ").trim()
        val delta = JSONObject(payload).getJSONArray("choices").getJSONObject(0).getJSONObject("delta")
        assertFalse("subsequent delta should not include role", delta.has("role"))
        assertEquals("second", delta.optString("content"))
    }

    @Test fun `OpenAI Chat done emits final chunk plus DONE sentinel`() {
        val enc = OpenAiSseEncoder("m")
        val out = capture { enc.emitDone(it, durationMs = 100, outputTokens = 3, outputChars = 0, backend = "gpu") }
        assertTrue("must end with [DONE] sentinel", out.contains("data: [DONE]"))
        // Final chat.completion.chunk has finish_reason="stop"
        val firstFrame = out.lineSequence().first { it.startsWith("data: {") }.removePrefix("data: ")
        val finish = JSONObject(firstFrame).getJSONArray("choices").getJSONObject(0).optString("finish_reason")
        assertEquals("stop", finish)
    }

    @Test fun `OpenAI Chat emitBuffered tool_calls flip finish_reason and stringify arguments`() {
        val enc = OpenAiSseEncoder("m")
        val tc = ChatFormat.ToolCall(
            id = "call_y", name = "x", arguments = JSONObject().apply { put("k", "v") },
        )
        val out = capture {
            enc.emitBuffered(
                it,
                BufferedResult(
                    text = "", toolCalls = listOf(tc),
                    durationMs = 1, outputTokens = 1, outputChars = 0, backend = "cpu",
                ),
            )
        }
        // Find the tool_calls delta chunk
        val toolFrame = out.lineSequence().first { it.startsWith("data: {") && it.contains("tool_calls") }
        val payload = JSONObject(toolFrame.removePrefix("data: "))
        val toolCalls = payload.getJSONArray("choices").getJSONObject(0)
            .getJSONObject("delta").getJSONArray("tool_calls").getJSONObject(0)
        // OpenAI requires arguments to be a STRING, not object
        val args = toolCalls.getJSONObject("function").opt("arguments")
        assertTrue("OpenAI tool_calls.function.arguments must be String", args is String)
        // Final chunk has finish_reason="tool_calls"
        val finalFrame = out.lineSequence().filter { it.startsWith("data: {") }.last()
        val finish = JSONObject(finalFrame.removePrefix("data: "))
            .getJSONArray("choices").getJSONObject(0).optString("finish_reason")
        assertEquals("tool_calls", finish)
    }

    /* --------------------- AnthropicSseEncoder ------------------------ */

    @Test fun `Anthropic emitStart sends message_start, content_block_start, ping in order`() {
        val enc = AnthropicSseEncoder("m")
        val out = capture { enc.emitStart(it) }
        val events = out.lines().filter { it.startsWith("event: ") }.map { it.removePrefix("event: ") }
        assertEquals(listOf("message_start", "content_block_start", "ping"), events)
    }

    @Test fun `Anthropic emitToken sends content_block_delta with text_delta`() {
        val enc = AnthropicSseEncoder("m")
        val out = capture { enc.emitToken(it, "hi") }
        val firstEvent = out.lines().first { it.startsWith("event:") }
        assertEquals("event: content_block_delta", firstEvent)
        val payloadLine = out.lines().first { it.startsWith("data: ") }
        val obj = JSONObject(payloadLine.removePrefix("data: "))
        val delta = obj.getJSONObject("delta")
        assertEquals("text_delta", delta.optString("type"))
        assertEquals("hi", delta.optString("text"))
    }

    @Test fun `Anthropic emitBuffered tool_use renders content_block_start with tool_use type`() {
        val enc = AnthropicSseEncoder("m")
        val tc = ChatFormat.ToolCall(
            id = "ignored", name = "search",
            arguments = JSONObject().apply { put("q", "k8s") },
        )
        val out = capture {
            enc.emitBuffered(
                it,
                BufferedResult(
                    text = "",
                    toolCalls = listOf(tc),
                    durationMs = 1, outputTokens = 1, outputChars = 0, backend = "cpu",
                ),
            )
        }
        // Find content_block_start with type:"tool_use"
        val toolBlockStart = out.lines().filter { it.startsWith("data: ") }
            .map { JSONObject(it.removePrefix("data: ")) }
            .firstOrNull {
                it.optString("type") == "content_block_start" &&
                    it.optJSONObject("content_block")?.optString("type") == "tool_use"
            }
        assertTrue("missing content_block_start tool_use", toolBlockStart != null)
        assertEquals("search", toolBlockStart!!.getJSONObject("content_block").optString("name"))
        // input_json_delta with the args
        val inputDelta = out.lines().filter { it.startsWith("data: ") }
            .map { JSONObject(it.removePrefix("data: ")) }
            .firstOrNull {
                it.optJSONObject("delta")?.optString("type") == "input_json_delta"
            }
        assertTrue("missing input_json_delta", inputDelta != null)
        // Final message_delta has stop_reason="tool_use"
        val msgDelta = out.lines().filter { it.startsWith("data: ") }
            .map { JSONObject(it.removePrefix("data: ")) }
            .firstOrNull { it.optString("type") == "message_delta" }
        assertEquals("tool_use", msgDelta!!.optJSONObject("delta")!!.optString("stop_reason"))
    }

    @Test fun `Anthropic emitError emits clean error event`() {
        val enc = AnthropicSseEncoder("m")
        val out = capture { enc.emitError(it, "context overflow") }
        val firstEvent = out.lines().first { it.startsWith("event:") }
        assertEquals("event: error", firstEvent)
        val obj = JSONObject(out.lines().first { it.startsWith("data:") }.removePrefix("data: "))
        assertEquals("error", obj.optString("type"))
        assertEquals("context overflow", obj.optJSONObject("error")?.optString("message"))
    }

    /* ---------------------- ResponsesSseEncoder ----------------------- */

    @Test fun `Responses encoder includes sequence_number on every event`() {
        val enc = ResponsesSseEncoder("m")
        val out = capture { enc.emitStart(it) }
        val payloads = out.lines().filter { it.startsWith("data: ") }
            .map { JSONObject(it.removePrefix("data: ")) }
        assertTrue("emitStart must produce >= 1 event", payloads.isNotEmpty())
        for (p in payloads) {
            assertTrue("missing sequence_number on $p", p.has("sequence_number"))
        }
    }

    @Test fun `Responses error emits both error event and response_failed`() {
        val enc = ResponsesSseEncoder("m")
        val out = capture { enc.emitError(it, "boom") }
        val events = out.lines().filter { it.startsWith("event:") }.map { it.removePrefix("event: ") }
        assertEquals(listOf("error", "response.failed"), events)
    }

    @Test fun `Responses emitBuffered with function_call emits arguments delta and done`() {
        val enc = ResponsesSseEncoder("m")
        val tc = ChatFormat.ToolCall(
            id = "call_z", name = "x",
            arguments = JSONObject().apply { put("p", 1) },
        )
        val out = capture {
            enc.emitBuffered(
                it,
                BufferedResult(
                    text = "",
                    toolCalls = listOf(tc),
                    durationMs = 1, outputTokens = 1, outputChars = 0, backend = "cpu",
                ),
            )
        }
        val events = out.lines().filter { it.startsWith("event:") }.map { it.removePrefix("event: ") }
        assertTrue("expected response.created", events.contains("response.created"))
        assertTrue("expected function_call_arguments.delta", events.contains("response.function_call_arguments.delta"))
        assertTrue("expected function_call_arguments.done", events.contains("response.function_call_arguments.done"))
        assertTrue("expected response.completed", events.contains("response.completed"))
    }
}
