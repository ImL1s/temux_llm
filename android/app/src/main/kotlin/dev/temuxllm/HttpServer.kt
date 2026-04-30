package dev.temuxllm

import fi.iki.elonen.NanoHTTPD

/**
 * Localhost-only HTTP server. MUST bind to 127.0.0.1 (brief constraint #1).
 */
class HttpServer : NanoHTTPD(BIND, PORT) {

    companion object {
        const val BIND = "127.0.0.1"
        const val PORT = 11434
    }

    override fun serve(session: IHTTPSession): Response {
        return when (session.uri) {
            "/healthz" -> newFixedLengthResponse(
                Response.Status.OK, "text/plain", "ok\n"
            )
            "/api/version" -> newFixedLengthResponse(
                Response.Status.OK, "application/json",
                """{"service":"temuxllm","phase":"2a","binary":"litert_lm_main v0.9.0 (not wired yet)"}""" + "\n"
            )
            else -> newFixedLengthResponse(
                Response.Status.NOT_FOUND, "text/plain", "404 ${session.uri}\n"
            )
        }
    }
}
