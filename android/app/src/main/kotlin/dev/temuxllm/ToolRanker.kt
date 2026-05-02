package dev.temuxllm

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ln

/**
 * Top-K tool retrieval using BM25-style scoring.
 *
 * Motivation (v0.8.0 G2): CLI agents (Claude Code, llxprt, opencode) send their
 * full tool registry (10-14 tools). With Gemma 4 E4B that creates context
 * pollution that drops tool-call reliability from ~100 % (single tool) to ~40 %.
 * Anthropic's production fix and the Toolshed paper (BM25 +46pp Recall@5) both
 * converge on the same idea: embed the user message, retrieve top-K relevant
 * tools, inject only those into the prompt.
 *
 * Algorithm:
 *   - Tokenize each tool's name + description + parameter keys (lowercase, split
 *     on non-alphanumeric). Build a corpus DF table.
 *   - For each query term (from the user message) in a tool's term-frequency map,
 *     accumulate score += tf * idf.
 *   - Return top-K tools sorted descending by score. Ties are broken by original
 *     array order (stable sort).
 *
 * Pure function; no Android dependencies; fully unit-testable.
 */
object ToolRanker {

    /**
     * Return at most [k] tools most relevant to [userMessage].
     *
     * Fast paths:
     *   - k >= tools.length() → return all tools unchanged.
     *   - userMessage is blank → return first K tools unchanged.
     *   - No query terms match any tool → return first K tools unchanged.
     */
    fun topK(tools: JSONArray, userMessage: String, k: Int = 3): JSONArray {
        val n = tools.length()
        if (k <= 0 || n == 0) return JSONArray()
        if (k >= n) return tools

        val queryTerms = tokenize(userMessage)
        if (queryTerms.isEmpty()) return firstK(tools, k)

        // Build per-tool term-frequency maps and collect corpus.
        data class ToolEntry(val obj: JSONObject, val tf: Map<String, Int>)

        val entries = (0 until n).mapNotNull { i ->
            val obj = tools.optJSONObject(i) ?: return@mapNotNull null
            val text = toolText(obj)
            val tf = buildTf(text)
            ToolEntry(obj, tf)
        }

        if (entries.isEmpty()) return firstK(tools, k)

        // Document frequency: how many tools contain each term.
        val df = mutableMapOf<String, Int>()
        for (e in entries) {
            for (term in e.tf.keys) {
                df[term] = (df[term] ?: 0) + 1
            }
        }

        val numDocs = entries.size.toDouble()

        // Score each tool by BM25-lite: sum tf * log((N+1)/(df+1)) for query terms.
        val scores = entries.map { e ->
            var score = 0.0
            for (qt in queryTerms) {
                val termTf = e.tf[qt] ?: continue
                val termDf = df[qt] ?: 1
                val idf = ln((numDocs + 1.0) / (termDf + 1.0))
                score += termTf * idf
            }
            score
        }

        // If no tool scored anything, return first K unchanged.
        if (scores.all { it == 0.0 }) return firstK(tools, k)

        // Stable sort descending — entries keep their original relative order on ties.
        val ranked = entries.indices.sortedWith(compareByDescending<Int> { scores[it] })
        val result = JSONArray()
        for (i in ranked.take(k)) {
            result.put(entries[i].obj)
        }
        return result
    }

    // ---------- internal helpers ----------

    private fun firstK(tools: JSONArray, k: Int): JSONArray {
        val result = JSONArray()
        val limit = minOf(k, tools.length())
        for (i in 0 until limit) {
            tools.optJSONObject(i)?.let { result.put(it) }
        }
        return result
    }

    /** Extract scoreable text from a tool JSONObject (Anthropic, OpenAI, Ollama shapes). */
    private fun toolText(t: JSONObject): String {
        val sb = StringBuilder()
        val fn = t.optJSONObject("function")
        val name: String
        val desc: String
        val params: Any?
        if (fn != null) {
            // OpenAI / Ollama style: {type:"function", function:{name, description, parameters}}
            name = fn.optString("name", "")
            desc = fn.optString("description", "")
            params = fn.opt("parameters")
        } else {
            // Anthropic style: {name, description, input_schema}
            name = t.optString("name", "")
            desc = t.optString("description", "")
            params = t.opt("input_schema") ?: t.opt("parameters")
        }
        if (name.isNotBlank()) sb.append(name).append(' ')
        if (desc.isNotBlank()) sb.append(desc).append(' ')
        // Extract parameter keys to boost relevance (e.g. "query", "path", "url").
        if (params is JSONObject) {
            val props = params.optJSONObject("properties")
            if (props != null) {
                for (key in props.keys()) sb.append(key).append(' ')
            }
        }
        return sb.toString()
    }

    /** Split on non-alphanumeric, lowercase, filter blanks. v0.8.0 sec
     *  review MEDIUM #2: include Unicode letters and digits so CJK /
     *  accented tool names score correctly instead of always falling
     *  through to first-K. */
    private fun tokenize(text: String): List<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }

    /** Count term occurrences in the tool text. */
    private fun buildTf(text: String): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        for (token in tokenize(text)) {
            map[token] = (map[token] ?: 0) + 1
        }
        return map
    }
}
