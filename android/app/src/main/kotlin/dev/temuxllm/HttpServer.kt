package dev.temuxllm

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.File

/**
 * Localhost-only HTTP server. MUST bind to 127.0.0.1 (brief constraint #1).
 *
 * Phase 2b endpoints:
 *   GET  /healthz       -> 200 "ok"
 *   GET  /api/version   -> JSON describing the service
 *   GET  /api/tags      -> JSON listing models known to be on disk
 *   POST /api/generate  -> spawn litert_lm_main, return one JSON document with text + metrics
 *
 * /api/generate is non-streaming for the MVP (the v0.9.0 binary buffers output
 * until completion anyway). NDJSON streaming is Phase 2c.
 */
class HttpServer(private val context: Context) : NanoHTTPD(BIND, PORT) {

    companion object {
        const val BIND = "127.0.0.1"
        const val PORT = 11434
        private const val TAG = "HttpServer"
    }

    private val runner = LiteRtLmRunner(context)

    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.uri == "/healthz" && session.method == Method.GET ->
                    text(Response.Status.OK, "ok\n")

                session.uri == "/api/version" && session.method == Method.GET ->
                    json(Response.Status.OK, JSONObject().apply {
                        put("service", "temuxllm")
                        put("phase", "2b")
                        put("binary", "litert_lm_main v0.9.0 (android arm64)")
                        put("default_backend", "gpu")
                        put("model_path", runner.activeModelPath().absolutePath)
                        put("source_model_path", LiteRtLmRunner.SOURCE_MODEL_PATH)
                    })

                session.uri == "/api/tags" && session.method == Method.GET ->
                    json(Response.Status.OK, listModels())

                session.uri == "/api/generate" && session.method == Method.POST ->
                    handleGenerate(session)

                else -> text(Response.Status.NOT_FOUND, "404 ${session.method} ${session.uri}\n")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "serve failed", t)
            json(
                Response.Status.INTERNAL_ERROR,
                JSONObject().apply {
                    put("error", t.javaClass.simpleName)
                    put("message", t.message ?: "")
                }
            )
        }
    }

    private fun handleGenerate(session: IHTTPSession): Response {
        // NanoHTTPD 2.3.1 parseBody decodes the raw POST bytes via the request's
        // Content-Type charset, defaulting to ISO-8859-1 — which silently mangles
        // any UTF-8 multi-byte sequence (e.g. CJK prompts). Bypass it: read the
        // raw stream ourselves and decode as UTF-8.
        val raw = readPostBodyAsUtf8(session)
        val body = if (raw.isBlank()) JSONObject() else JSONObject(raw)
        val prompt = body.optString("prompt").also {
            if (it.isBlank()) return errorJson(Response.Status.BAD_REQUEST, "prompt is required")
        }
        val backend = body.optString("backend", "gpu").lowercase()
        if (backend != "cpu" && backend != "gpu") {
            return errorJson(Response.Status.BAD_REQUEST, "backend must be cpu|gpu (got $backend)")
        }
        val modelPath = body.optString("model_path", "").ifBlank { runner.ensureModelStaged() }
        val timeoutMs = body.optLong("timeout_ms", LiteRtLmRunner.DEFAULT_TIMEOUT_MS)

        Log.i(TAG, "generate backend=$backend prompt=${prompt.take(80)}…")
        val result = runner.generate(prompt, backend = backend, modelPath = modelPath, timeoutMs = timeoutMs)
        Log.i(TAG, "generate done exit=${result.exitCode} dur=${result.totalDurationMs}ms text-len=${result.text.length}")

        val obj = JSONObject().apply {
            put("model", File(modelPath).nameWithoutExtension)
            put("backend", result.backend)
            put("response", result.text)
            put("done", true)
            put("exit_code", result.exitCode)
            put("total_duration_ms", result.totalDurationMs)
            result.initTotalMs?.let { put("init_total_ms", it) }
            result.ttftSeconds?.let { put("ttft_seconds", it) }
            result.prefillTokensPerSec?.let { put("prefill_tokens_per_sec", it) }
            result.decodeTokensPerSec?.let { put("decode_tokens_per_sec", it) }
            result.prefillTokens?.let { put("prefill_tokens", it) }
            result.decodeTokens?.let { put("decode_tokens", it) }
            put("raw_stdout_lines", result.rawStdoutLines)
        }
        val statusCode = if (result.exitCode == 0) Response.Status.OK else Response.Status.INTERNAL_ERROR
        return json(statusCode, obj)
    }

    private fun listModels(): JSONObject {
        val tags = JSONObject()
        val arr = org.json.JSONArray()
        // Active model lives in our app's filesDir; we also list any host-pushed
        // copy in /data/local/tmp/ for transparency.
        val candidates = listOf(
            runner.modelDir(),
            File("/data/local/tmp/litertlm"),
            File(context.filesDir, "models")
        )
        val seen = mutableSetOf<String>()
        for (dir in candidates) {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".litertlm") } ?: continue
            for (f in files) {
                if (!seen.add(f.absolutePath)) continue
                val item = JSONObject()
                item.put("name", f.nameWithoutExtension)
                item.put("path", f.absolutePath)
                item.put("size_bytes", f.length())
                arr.put(item)
            }
        }
        tags.put("models", arr)
        return tags
    }

    /**
     * Read POST body bytes from the session input stream and decode as UTF-8 —
     * regardless of whether the client supplied "; charset=utf-8" on Content-Type.
     * NanoHTTPD's own `parseBody` defaults to ISO-8859-1 which corrupts CJK input.
     *
     * We honor an optional "Content-Length" to bound the read; if absent we cap at
     * 4 MiB which is plenty for prompts.
     */
    private fun readPostBodyAsUtf8(session: IHTTPSession): String {
        val cl = session.headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val cap = 4 * 1024 * 1024
        val limit = if (cl in 1..cap) cl else cap
        val buf = ByteArray(limit)
        var pos = 0
        val ins = session.inputStream
        while (pos < limit) {
            val n = ins.read(buf, pos, limit - pos)
            if (n <= 0) break
            pos += n
        }
        return String(buf, 0, pos, Charsets.UTF_8)
    }

    private fun text(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "text/plain; charset=utf-8", body)

    private fun json(status: Response.Status, obj: JSONObject): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", obj.toString() + "\n")

    private fun errorJson(status: Response.Status, msg: String): Response =
        json(status, JSONObject().apply { put("error", msg) })
}
