package dev.temuxllm

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for ToolRanker (v0.8.0 G2 — Top-K BM25 tool retrieval).
 *
 * All tools use Anthropic shape ({name, description, input_schema}) for brevity.
 * ToolRanker also accepts OpenAI/Ollama shape; that is verified in the
 * multi-domain test which mixes shapes.
 */
class ToolRankerTest {

    // ---------- helpers ----------

    private fun tool(name: String, desc: String, paramKeys: List<String> = emptyList()): JSONObject =
        JSONObject().apply {
            put("name", name)
            put("description", desc)
            if (paramKeys.isNotEmpty()) {
                put("input_schema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        for (k in paramKeys) put(k, JSONObject().put("type", "string"))
                    })
                })
            }
        }

    private fun toolOpenAi(name: String, desc: String, paramKeys: List<String> = emptyList()): JSONObject =
        JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("description", desc)
                if (paramKeys.isNotEmpty()) {
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            for (k in paramKeys) put(k, JSONObject().put("type", "string"))
                        })
                    })
                }
            })
        }

    private fun names(arr: JSONArray): List<String> {
        val result = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val fn = obj.optJSONObject("function")
            val n = if (fn != null) fn.optString("name") else obj.optString("name")
            if (n.isNotBlank()) result.add(n)
        }
        return result
    }

    // ---------- tests ----------

    @Test fun `weather query ranks get_weather above file tools`() {
        val tools = JSONArray().apply {
            put(tool("get_weather", "Get current weather for a location", listOf("location", "unit")))
            put(tool("read_file", "Read contents of a file", listOf("path")))
            put(tool("write_file", "Write data to a file", listOf("path", "content")))
            put(tool("grep", "Search text patterns in files", listOf("pattern", "path")))
        }
        val top = ToolRanker.topK(tools, "what is the weather in Tokyo", k = 1)
        assertEquals(1, top.length())
        assertEquals("get_weather", names(top).first())
    }

    @Test fun `code search query ranks grep and glob above weather and fetch`() {
        val tools = JSONArray().apply {
            put(tool("get_weather", "Get current weather for a location", listOf("location")))
            put(tool("grep", "Search code patterns in files using regex", listOf("pattern", "path")))
            put(tool("glob", "Find files matching a glob pattern", listOf("pattern", "path")))
            put(tool("fetch", "Fetch a URL from the internet", listOf("url")))
        }
        // Query contains "pattern", "files" — overlaps with both grep and glob.
        // weather and fetch have zero term overlap → must score 0 and fall below.
        val top = ToolRanker.topK(tools, "find pattern in files", k = 2)
        val topNames = names(top)
        assertEquals(2, top.length())
        assertTrue("grep should be in top-2 for code search query; got $topNames",
            "grep" in topNames)
        assertTrue("glob should be in top-2 for code search query; got $topNames",
            "glob" in topNames)
        // weather and fetch have zero overlap → must not rank top-2.
        assertTrue("weather should not rank top-2 for code search; got $topNames",
            "get_weather" !in topNames)
        assertTrue("fetch should not rank top-2 for code search; got $topNames",
            "fetch" !in topNames)
    }

    @Test fun `multi-domain query includes both read_file and direct_web_fetch in top-3`() {
        val tools = JSONArray().apply {
            // OpenAI shape for variety
            put(toolOpenAi("get_weather", "Get weather forecast for a city", listOf("location")))
            put(toolOpenAi("read_file", "Read the contents of a file from disk", listOf("path")))
            put(toolOpenAi("write_file", "Write content to a file on disk", listOf("path", "content")))
            put(toolOpenAi("direct_web_fetch", "Fetch the contents of a URL", listOf("url")))
            put(toolOpenAi("list_dir", "List directory contents", listOf("path")))
        }
        val top = ToolRanker.topK(tools, "read foo.txt then fetch bar", k = 3)
        val topNames = names(top)
        assertEquals(3, top.length())
        assertTrue("read_file should be in top-3 for 'read ... fetch' query; got $topNames",
            "read_file" in topNames)
        assertTrue("direct_web_fetch should be in top-3 for 'read ... fetch' query; got $topNames",
            "direct_web_fetch" in topNames)
    }

    @Test fun `k greater than or equal tools length returns all tools unchanged`() {
        val tools = JSONArray().apply {
            put(tool("tool_a", "Do thing A"))
            put(tool("tool_b", "Do thing B"))
        }
        // k == length
        val same = ToolRanker.topK(tools, "some query", k = 2)
        assertEquals(2, same.length())
        // k > length
        val bigger = ToolRanker.topK(tools, "some query", k = 5)
        assertEquals(2, bigger.length())
        val allNames = names(bigger)
        assertTrue("tool_a" in allNames)
        assertTrue("tool_b" in allNames)
    }

    @Test fun `blank user message returns first K tools without crash`() {
        val tools = JSONArray().apply {
            put(tool("alpha", "Alpha tool"))
            put(tool("beta", "Beta tool"))
            put(tool("gamma", "Gamma tool"))
            put(tool("delta", "Delta tool"))
        }
        val top = ToolRanker.topK(tools, "", k = 2)
        assertEquals(2, top.length())
        // First-K order preserved.
        val topNames = names(top)
        assertEquals(listOf("alpha", "beta"), topNames)
    }
}
