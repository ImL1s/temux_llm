package dev.temuxllm

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintWriter
import kotlin.concurrent.thread

/**
 * Localhost-only HTTP server. MUST bind to 127.0.0.1 (brief constraint #1).
 *
 * Endpoints:
 *   GET  /healthz       -> 200 "ok"
 *   GET  /api/version   -> JSON describing the service
 *   GET  /api/tags      -> JSON listing models known to be on disk
 *   POST /api/generate  -> NDJSON stream of {response: "..."} chunks + final
 *                          {done:true,...metrics} (or single document if stream=false)
 */
class HttpServer(private val context: Context, private val engine: LlmEngine) :
    NanoHTTPD(BIND, PORT) {

    companion object {
        const val BIND = "127.0.0.1"
        const val PORT = 11434
        private const val TAG = "HttpServer"
    }

    override fun serve(session: IHTTPSession): Response = try {
        when {
            session.uri == "/healthz" && session.method == Method.GET ->
                text(Response.Status.OK, "ok\n")

            session.uri == "/api/version" && session.method == Method.GET ->
                json(Response.Status.OK, JSONObject().apply {
                    put("service", "temuxllm")
                    put("phase", "2c")
                    put("runtime", "litertlm-android 0.11.0-rc1 (in-process Engine)")
                    put("default_backend", "cpu")
                    put("model_path", engine.activeModelPath().absolutePath)
                    put("source_model_path", LlmEngine.SOURCE_MODEL_PATH)
                    put("engine_loaded", engine.isLoaded())
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

    private fun handleGenerate(session: IHTTPSession): Response {
        val raw = readPostBodyAsUtf8(session)
        val body = if (raw.isBlank()) JSONObject() else JSONObject(raw)
        val prompt = body.optString("prompt").also {
            if (it.isBlank()) return errorJson(Response.Status.BAD_REQUEST, "prompt is required")
        }
        val backend = body.optString("backend", "cpu").lowercase()
        if (backend != "cpu" && backend != "gpu") {
            return errorJson(Response.Status.BAD_REQUEST, "backend must be cpu|gpu (got $backend)")
        }
        val stream = body.optBoolean("stream", true)

        return if (stream) streamGenerate(prompt, backend) else blockingGenerate(prompt, backend)
    }

    /** Streaming response: NDJSON, one JSON object per line. */
    private fun streamGenerate(prompt: String, backend: String): Response {
        val pipeIn = PipedInputStream(64 * 1024)
        val pipeOut = PipedOutputStream(pipeIn)
        thread(start = true, name = "litertlm-stream") {
            val writer = PrintWriter(pipeOut.writer(Charsets.UTF_8), false)
            try {
                runBlocking {
                    engine.generate(prompt, backend).collect { ev ->
                        val line = when (ev) {
                            is LlmEngine.GenerateEvent.Token -> JSONObject().apply {
                                put("response", ev.text)
                                put("done", false)
                            }
                            is LlmEngine.GenerateEvent.Done -> JSONObject().apply {
                                put("response", "")
                                put("done", true)
                                put("backend", ev.backend)
                                put("total_duration_ms", ev.totalDurationMs)
                                put("output_tokens", ev.outputTokens)
                                put("output_chars", ev.outputChars)
                            }
                            is LlmEngine.GenerateEvent.Error -> JSONObject().apply {
                                put("error", ev.message)
                                put("done", true)
                            }
                        }
                        writer.println(line.toString())
                        writer.flush()
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "stream worker failed", t)
                writer.println(JSONObject().apply { put("error", t.message ?: ""); put("done", true) })
                writer.flush()
            } finally {
                writer.close()
            }
        }
        return newChunkedResponse(Response.Status.OK, "application/x-ndjson; charset=utf-8", pipeIn)
    }

    /** Non-streaming convenience: block for the full response, return one JSON. */
    private fun blockingGenerate(prompt: String, backend: String): Response {
        Log.i(TAG, "generate blocking backend=$backend prompt=${prompt.take(80)}…")
        val r = engine.generateBlocking(prompt, backend)
        if (r.error != null) {
            return json(
                Response.Status.INTERNAL_ERROR,
                JSONObject().apply { put("error", r.error); put("done", true) }
            )
        }
        val obj = JSONObject().apply {
            put("model", File(engine.activeModelPath().absolutePath).nameWithoutExtension)
            put("backend", r.backend)
            put("response", r.text)
            put("done", true)
            put("total_duration_ms", r.totalDurationMs)
            put("output_tokens", r.outputTokens)
        }
        return json(Response.Status.OK, obj)
    }

    private fun listModels(): JSONObject {
        val tags = JSONObject()
        val arr = JSONArray()
        val candidates = listOf(
            engine.modelDir(),
            File("/data/local/tmp/litertlm"),
            File(context.filesDir, "models")
        )
        val seen = mutableSetOf<String>()
        for (dir in candidates) {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".litertlm") } ?: continue
            for (f in files) {
                if (!seen.add(f.absolutePath)) continue
                arr.put(JSONObject().apply {
                    put("name", f.nameWithoutExtension)
                    put("path", f.absolutePath)
                    put("size_bytes", f.length())
                })
            }
        }
        tags.put("models", arr)
        return tags
    }

    /**
     * Read POST body as UTF-8. Short-circuits on Content-Length: 0 (otherwise the
     * blocking read would wait forever). Caps at 4 MiB.
     */
    private fun readPostBodyAsUtf8(session: IHTTPSession): String {
        val cap = 4 * 1024 * 1024
        val cl = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (cl <= 0) return ""
        val limit = cl.coerceAtMost(cap)
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
