package dev.temuxllm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Model registry: scans the same .litertlm directories as the legacy listModels(),
 * surfaces an Ollama-shaped /api/tags payload, and resolves a requested model name
 * to a real file using an alias-or-route policy.
 *
 * Policy:
 *   - Empty / null name -> active model.
 *   - Exact match against any model's name (filename without .litertlm) -> that model.
 *   - One of the "wildcard" aliases ("default","local","*") -> active model.
 *   - Family-prefix match (e.g. "gemma" -> "gemma-4-E2B-it") -> first match.
 *   - Optional models.json sidecar with explicit aliases (e.g. mapping
 *     "claude-3-5-sonnet" -> "gemma-4-E2B-it") -> mapped model.
 *   - Otherwise null (caller should 404).
 *
 * Capabilities live here because they are model-level metadata. Tool support is
 * gated: it stays false until a probe (or admin) flips it on, because LiteRT-LM
 * 0.11's tool-calling support depends on the loaded model and the SDK's
 * ConversationConfig.tools API path. Default ["completion"].
 */
class ModelRegistry(private val context: Context, private val engine: LlmEngine) {

    data class Entry(
        val name: String,
        val path: String,
        val sizeBytes: Long,
        val modifiedAtMs: Long,
    ) {
        val digest: String by lazy {
            val md = MessageDigest.getInstance("SHA-256")
            md.update("$path|$sizeBytes|$modifiedAtMs".toByteArray(Charsets.UTF_8))
            "sha256:" + md.digest().joinToString("") { "%02x".format(it) }
        }
        val family: String = guessFamily(name)
        val parameterSize: String = guessParameterSize(name)
    }

    @Volatile var toolsVerified: Boolean = false

    private val wildcardAliases = setOf("default", "local", "*", "")
    private val sidecarAliases: Map<String, String> by lazy { loadSidecarAliases() }

    /** Live disk scan; cheap (handful of stat() calls). */
    fun list(): List<Entry> {
        val candidates = listOf(
            engine.modelDir(),
            File("/data/local/tmp/litertlm"),
            File(context.filesDir, "models"),
        )
        val seen = mutableSetOf<String>()
        val out = mutableListOf<Entry>()
        for (dir in candidates) {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".litertlm") } ?: continue
            for (f in files) {
                if (!seen.add(f.absolutePath)) continue
                out += Entry(
                    name = f.nameWithoutExtension,
                    path = f.absolutePath,
                    sizeBytes = f.length(),
                    modifiedAtMs = f.lastModified(),
                )
            }
        }
        return out
    }

    /** The currently active model — the file LlmEngine will load. */
    fun active(): Entry? {
        val active = engine.activeModelPath()
        if (!active.exists()) {
            // No staged model yet; fall back to first listed (may also not exist).
            return list().firstOrNull()
        }
        return Entry(
            name = active.nameWithoutExtension,
            path = active.absolutePath,
            sizeBytes = active.length(),
            modifiedAtMs = active.lastModified(),
        )
    }

    fun activeName(): String = active()?.name ?: "unknown"

    /**
     * alias-or-route. Returns null if the requested name is not recognized AND
     * not eligible for default-routing.
     */
    fun resolve(requested: String?): Entry? {
        val name = requested?.trim() ?: ""
        val active = active() ?: return null
        if (name in wildcardAliases) return active
        val all = list()
        // Exact filename match
        all.firstOrNull { it.name == name }?.let { return it }
        // Optional sidecar mapping (e.g. "claude-3-5-sonnet" -> "gemma-4-E2B-it")
        sidecarAliases[name]?.let { mapped ->
            all.firstOrNull { it.name == mapped }?.let { return it }
        }
        // Family-prefix (lowercased)
        val lower = name.lowercase(Locale.ROOT)
        all.firstOrNull { it.family == lower || it.name.lowercase(Locale.ROOT).startsWith(lower) }
            ?.let { return it }
        // Coding-agent brand-name heuristic: any "claude*" / "gpt*" / "anthropic*" / "openai*"
        // routes to the active model so Claude Code / Codex CLI can send their default
        // model strings without the user pre-aliasing them.
        if (looksLikeBrandedModel(lower)) return active
        return null
    }

    /** Capabilities advertised on /api/show. */
    fun capabilities(): List<String> {
        val out = mutableListOf("completion")
        val noTools = !System.getenv("TEMUXLLM_NO_TOOLS").isNullOrBlank()
        if (toolsVerified && !noTools) out += "tools"
        return out
    }

    /** Full /api/tags payload, Ollama 0.13+ shape. */
    fun ollamaTags(): JSONObject {
        val arr = JSONArray()
        for (e in list()) {
            arr.put(
                JSONObject().apply {
                    // Ollama-shaped fields. We deliberately omit the `path`
                    // field that earlier temuxllm versions returned — it
                    // exposes /data paths to any local process and Ollama's
                    // own /api/tags doesn't include them.
                    put("name", e.name)
                    put("model", e.name)
                    put("modified_at", iso8601(e.modifiedAtMs))
                    put("size", e.sizeBytes)
                    put("digest", e.digest)
                    put(
                        "details",
                        JSONObject().apply {
                            put("format", "litertlm")
                            put("family", e.family)
                            put("families", JSONArray().put(e.family))
                            put("parameter_size", e.parameterSize)
                            put("quantization_level", "unknown")
                        },
                    )
                    // Legacy v0.2.x field for backward compat with smoke scripts:
                    put("size_bytes", e.sizeBytes)
                },
            )
        }
        return JSONObject().apply { put("models", arr) }
    }

    /** Single-model /api/show shape. */
    fun ollamaShow(entry: Entry): JSONObject = JSONObject().apply {
        put("modelfile", "")
        put("parameters", "")
        put("template", "")
        put(
            "details",
            JSONObject().apply {
                put("format", "litertlm")
                put("family", entry.family)
                put("families", JSONArray().put(entry.family))
                put("parameter_size", entry.parameterSize)
                put("quantization_level", "unknown")
            },
        )
        put("model_info", JSONObject())
        put("capabilities", JSONArray(capabilities()))
    }

    /** /api/ps: currently loaded models (we have at most one). */
    fun ollamaPs(): JSONObject {
        val arr = JSONArray()
        if (engine.isLoaded()) {
            active()?.let { e ->
                arr.put(
                    JSONObject().apply {
                        put("name", e.name)
                        put("model", e.name)
                        put("size", e.sizeBytes)
                        put("digest", e.digest)
                        put(
                            "details",
                            JSONObject().apply {
                                put("format", "litertlm")
                                put("family", e.family)
                                put("families", JSONArray().put(e.family))
                                put("parameter_size", e.parameterSize)
                                put("quantization_level", "unknown")
                            },
                        )
                        put("expires_at", "2099-01-01T00:00:00Z")
                        put("size_vram", 0L)
                    },
                )
            }
        }
        return JSONObject().apply { put("models", arr) }
    }

    /** OpenAI-compat /v1/models. */
    fun openAiModels(): JSONObject {
        val arr = JSONArray()
        val createdEpoch = System.currentTimeMillis() / 1000
        for (e in list()) {
            arr.put(
                JSONObject().apply {
                    put("id", e.name)
                    put("object", "model")
                    put("created", createdEpoch)
                    put("owned_by", "temuxllm")
                },
            )
        }
        return JSONObject().apply {
            put("object", "list")
            put("data", arr)
        }
    }

    private fun loadSidecarAliases(): Map<String, String> {
        val f = File(context.filesDir, "models.json")
        if (!f.exists()) return emptyMap()
        return try {
            val j = JSONObject(f.readText(Charsets.UTF_8))
            val aliases = j.optJSONObject("aliases") ?: return emptyMap()
            buildMap {
                aliases.keys().forEach { k ->
                    val v = aliases.optString(k, "")
                    if (v.isNotBlank()) put(k, v)
                }
            }
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    companion object {
        private val ISO_FMT: SimpleDateFormat by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        }

        fun iso8601(epochMs: Long): String = synchronized(ISO_FMT) { ISO_FMT.format(Date(epochMs)) }

        private fun guessFamily(name: String): String {
            val n = name.lowercase(Locale.ROOT)
            return when {
                n.startsWith("gemma") -> "gemma"
                n.startsWith("qwen") -> "qwen"
                n.startsWith("llama") -> "llama"
                n.startsWith("phi") -> "phi"
                n.startsWith("mistral") -> "mistral"
                else -> "unknown"
            }
        }

        private fun guessParameterSize(name: String): String {
            val n = name.uppercase(Locale.ROOT)
            // gemma-4-E2B-it -> "2B"; gemma-4-E4B-it -> "4B"; Qwen3-0.6B -> "0.6B"
            val re = Regex("""(\d+(?:\.\d+)?)B""")
            return re.find(n)?.groupValues?.get(1)?.let { "${it}B" } ?: "unknown"
        }

        private fun looksLikeBrandedModel(lower: String): Boolean {
            // Tight prefix tests: `claude-*`, `gpt-*`, etc. Avoid matching
            // legitimate non-branded names like `orca3-7b` (which would have
            // matched a naive `startsWith("o3")`).
            return lower.startsWith("claude-") || lower == "claude" ||
                lower.startsWith("anthropic-") || lower == "anthropic" ||
                lower.startsWith("gpt-") || lower == "gpt" ||
                lower.startsWith("openai-") || lower == "openai" ||
                lower == "o1" || lower.startsWith("o1-") ||
                lower == "o3" || lower.startsWith("o3-") ||
                lower == "o4" || lower.startsWith("o4-")
        }
    }
}
