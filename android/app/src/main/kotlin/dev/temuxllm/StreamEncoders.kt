package dev.temuxllm

import org.json.JSONArray
import org.json.JSONObject
import java.io.PrintWriter
import java.util.UUID

/**
 * Stream encoders for the four wire envelopes we expose:
 *   - Ollama-native /api/generate  -> NDJSON (response field)
 *   - Ollama-native /api/chat      -> NDJSON (message field)
 *   - OpenAI-compat /v1/chat/completions -> SSE (data: ... [DONE])
 *   - Anthropic-compat /v1/messages -> SSE (named events)
 *
 * All four encoders consume the same LlmEngine.GenerateEvent stream; the
 * difference is purely framing + final/error event vocabulary.
 *
 * Convention: encoders flush after every frame so the client sees tokens as
 * they are produced. They swallow IOException on write — a broken pipe means
 * the client disconnected, and the upstream worker should observe it on the
 * next write attempt and bail out.
 */
interface StreamEncoder {
    val contentType: String
    fun emitStart(w: PrintWriter)
    fun emitToken(w: PrintWriter, piece: String)
    fun emitDone(w: PrintWriter, durationMs: Long, outputTokens: Int, outputChars: Int, backend: String)
    fun emitError(w: PrintWriter, message: String)
}

/* ----------------------------- Ollama /api/generate ---------------------------- */

class NdjsonGenerateEncoder(private val model: String) : StreamEncoder {
    override val contentType = "application/x-ndjson; charset=utf-8"

    override fun emitStart(w: PrintWriter) { /* generate has no preamble */ }

    override fun emitToken(w: PrintWriter, piece: String) {
        val o = JSONObject().apply {
            put("model", model)
            put("created_at", ModelRegistry.iso8601(System.currentTimeMillis()))
            put("response", piece)
            put("done", false)
        }
        w.print(o.toString()); w.print('\n'); w.flush()
    }

    override fun emitDone(w: PrintWriter, durationMs: Long, outputTokens: Int, outputChars: Int, backend: String) {
        val o = JSONObject().apply {
            put("model", model)
            put("created_at", ModelRegistry.iso8601(System.currentTimeMillis()))
            put("response", "")
            put("done", true)
            put("done_reason", "stop")
            put("total_duration", durationMs * 1_000_000L)
            put("eval_count", outputTokens)
            // Non-Ollama extension fields kept for backward compat with v0.2.x clients.
            put("backend", backend)
            put("total_duration_ms", durationMs)
            put("output_tokens", outputTokens)
            put("output_chars", outputChars)
        }
        w.print(o.toString()); w.print('\n'); w.flush()
    }

    override fun emitError(w: PrintWriter, message: String) {
        val o = JSONObject().apply { put("error", message); put("done", true) }
        w.print(o.toString()); w.print('\n'); w.flush()
    }
}

/* ------------------------------- Ollama /api/chat ------------------------------ */

class NdjsonChatEncoder(private val model: String) : StreamEncoder {
    override val contentType = "application/x-ndjson; charset=utf-8"

    override fun emitStart(w: PrintWriter) { /* chat has no preamble */ }

    override fun emitToken(w: PrintWriter, piece: String) {
        val msg = JSONObject().apply {
            put("role", "assistant")
            put("content", piece)
        }
        val o = JSONObject().apply {
            put("model", model)
            put("created_at", ModelRegistry.iso8601(System.currentTimeMillis()))
            put("message", msg)
            put("done", false)
        }
        w.print(o.toString()); w.print('\n'); w.flush()
    }

    override fun emitDone(w: PrintWriter, durationMs: Long, outputTokens: Int, outputChars: Int, backend: String) {
        val msg = JSONObject().apply {
            put("role", "assistant")
            put("content", "")
        }
        val o = JSONObject().apply {
            put("model", model)
            put("created_at", ModelRegistry.iso8601(System.currentTimeMillis()))
            put("message", msg)
            put("done", true)
            put("done_reason", "stop")
            put("total_duration", durationMs * 1_000_000L)
            put("eval_count", outputTokens)
            put("backend", backend)
        }
        w.print(o.toString()); w.print('\n'); w.flush()
    }

    override fun emitError(w: PrintWriter, message: String) {
        val o = JSONObject().apply { put("error", message); put("done", true) }
        w.print(o.toString()); w.print('\n'); w.flush()
    }
}

/* ---------------------- OpenAI-compat /v1/chat/completions --------------------- */

class OpenAiSseEncoder(private val model: String) : StreamEncoder {
    override val contentType = "text/event-stream; charset=utf-8"

    private val id: String = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(24)
    private val created: Long = System.currentTimeMillis() / 1000
    private var firstTokenEmitted = false

    override fun emitStart(w: PrintWriter) {
        // OpenAI streams begin with the first token chunk; no preamble frame.
    }

    override fun emitToken(w: PrintWriter, piece: String) {
        val delta = JSONObject().apply {
            if (!firstTokenEmitted) put("role", "assistant")
            put("content", piece)
        }
        val choice = JSONObject().apply {
            put("index", 0)
            put("delta", delta)
            put("finish_reason", JSONObject.NULL)
        }
        val chunk = JSONObject().apply {
            put("id", id)
            put("object", "chat.completion.chunk")
            put("created", created)
            put("model", model)
            put("choices", JSONArray().put(choice))
        }
        w.print("data: "); w.print(chunk.toString()); w.print("\n\n"); w.flush()
        firstTokenEmitted = true
    }

    override fun emitDone(w: PrintWriter, durationMs: Long, outputTokens: Int, outputChars: Int, backend: String) {
        val choice = JSONObject().apply {
            put("index", 0)
            put("delta", JSONObject())
            put("finish_reason", "stop")
        }
        val usage = JSONObject().apply {
            put("prompt_tokens", 0)
            put("completion_tokens", outputTokens)
            put("total_tokens", outputTokens)
        }
        val chunk = JSONObject().apply {
            put("id", id)
            put("object", "chat.completion.chunk")
            put("created", created)
            put("model", model)
            put("choices", JSONArray().put(choice))
            put("usage", usage)
        }
        w.print("data: "); w.print(chunk.toString()); w.print("\n\n")
        w.print("data: [DONE]\n\n")
        w.flush()
    }

    override fun emitError(w: PrintWriter, message: String) {
        val err = JSONObject().apply {
            put(
                "error",
                JSONObject().apply {
                    put("message", message)
                    put("type", "server_error")
                },
            )
        }
        w.print("data: "); w.print(err.toString()); w.print("\n\n"); w.flush()
    }
}

/* ----------------------- Anthropic-compat /v1/messages ------------------------ */

class AnthropicSseEncoder(private val model: String) : StreamEncoder {
    override val contentType = "text/event-stream; charset=utf-8"

    private val id: String = "msg_" + UUID.randomUUID().toString().replace("-", "").take(24)

    override fun emitStart(w: PrintWriter) {
        val msgStart = JSONObject().apply {
            put("type", "message_start")
            put(
                "message",
                JSONObject().apply {
                    put("id", id)
                    put("type", "message")
                    put("role", "assistant")
                    put("content", JSONArray())
                    put("model", model)
                    put("stop_reason", JSONObject.NULL)
                    put("stop_sequence", JSONObject.NULL)
                    put(
                        "usage",
                        JSONObject().apply {
                            put("input_tokens", 0)
                            put("output_tokens", 0)
                        },
                    )
                },
            )
        }
        val cbStart = JSONObject().apply {
            put("type", "content_block_start")
            put("index", 0)
            put(
                "content_block",
                JSONObject().apply {
                    put("type", "text")
                    put("text", "")
                },
            )
        }
        sse(w, "message_start", msgStart)
        sse(w, "content_block_start", cbStart)
        sse(w, "ping", JSONObject().apply { put("type", "ping") })
    }

    override fun emitToken(w: PrintWriter, piece: String) {
        val o = JSONObject().apply {
            put("type", "content_block_delta")
            put("index", 0)
            put(
                "delta",
                JSONObject().apply {
                    put("type", "text_delta")
                    put("text", piece)
                },
            )
        }
        sse(w, "content_block_delta", o)
    }

    override fun emitDone(w: PrintWriter, durationMs: Long, outputTokens: Int, outputChars: Int, backend: String) {
        sse(
            w, "content_block_stop",
            JSONObject().apply {
                put("type", "content_block_stop")
                put("index", 0)
            },
        )
        sse(
            w, "message_delta",
            JSONObject().apply {
                put("type", "message_delta")
                put(
                    "delta",
                    JSONObject().apply {
                        put("stop_reason", "end_turn")
                        put("stop_sequence", JSONObject.NULL)
                    },
                )
                put(
                    "usage",
                    JSONObject().apply { put("output_tokens", outputTokens) },
                )
            },
        )
        sse(w, "message_stop", JSONObject().apply { put("type", "message_stop") })
    }

    override fun emitError(w: PrintWriter, message: String) {
        sse(
            w, "error",
            JSONObject().apply {
                put("type", "error")
                put(
                    "error",
                    JSONObject().apply {
                        put("type", "server_error")
                        put("message", message)
                    },
                )
            },
        )
    }

    private fun sse(w: PrintWriter, event: String, data: JSONObject) {
        w.print("event: "); w.print(event); w.print('\n')
        w.print("data: "); w.print(data.toString()); w.print("\n\n")
        w.flush()
    }
}

/* ----------------------- OpenAI Responses /v1/responses ------------------------ */

/**
 * Codex CLI 0.80+ talks to Ollama only via /v1/responses (verified against
 * codex-rs/model-provider-info source — `WireApi::Responses` is the only
 * variant; chat path was removed). Stateless streaming Responses API: we
 * emit response.created -> response.output_text.delta* -> response.completed.
 */
class ResponsesSseEncoder(private val model: String) : StreamEncoder {
    override val contentType = "text/event-stream; charset=utf-8"

    private val respId: String = "resp_" + UUID.randomUUID().toString().replace("-", "").take(24)
    private val itemId: String = "msg_" + UUID.randomUUID().toString().replace("-", "").take(24)
    private val accum = StringBuilder()
    private var sequenceNumber: Int = 0

    override fun emitStart(w: PrintWriter) {
        sse(
            w, "response.created",
            JSONObject().apply {
                put("type", "response.created")
                put("response", baseResponse(status = "in_progress"))
            },
        )
        sse(
            w, "response.in_progress",
            JSONObject().apply {
                put("type", "response.in_progress")
                put("response", baseResponse(status = "in_progress"))
            },
        )
        sse(
            w, "response.output_item.added",
            JSONObject().apply {
                put("type", "response.output_item.added")
                put("output_index", 0)
                put(
                    "item",
                    JSONObject().apply {
                        put("id", itemId)
                        put("type", "message")
                        put("role", "assistant")
                        put("content", JSONArray())
                        put("status", "in_progress")
                    },
                )
            },
        )
        sse(
            w, "response.content_part.added",
            JSONObject().apply {
                put("type", "response.content_part.added")
                put("item_id", itemId)
                put("output_index", 0)
                put("content_index", 0)
                put(
                    "part",
                    JSONObject().apply {
                        put("type", "output_text")
                        put("text", "")
                        put("annotations", JSONArray())
                    },
                )
            },
        )
    }

    override fun emitToken(w: PrintWriter, piece: String) {
        accum.append(piece)
        sse(
            w, "response.output_text.delta",
            JSONObject().apply {
                put("type", "response.output_text.delta")
                put("item_id", itemId)
                put("output_index", 0)
                put("content_index", 0)
                put("delta", piece)
            },
        )
    }

    override fun emitDone(w: PrintWriter, durationMs: Long, outputTokens: Int, outputChars: Int, backend: String) {
        val fullText = accum.toString()
        sse(
            w, "response.output_text.done",
            JSONObject().apply {
                put("type", "response.output_text.done")
                put("item_id", itemId)
                put("output_index", 0)
                put("content_index", 0)
                put("text", fullText)
            },
        )
        sse(
            w, "response.content_part.done",
            JSONObject().apply {
                put("type", "response.content_part.done")
                put("item_id", itemId)
                put("output_index", 0)
                put("content_index", 0)
                put(
                    "part",
                    JSONObject().apply {
                        put("type", "output_text")
                        put("text", fullText)
                        put("annotations", JSONArray())
                    },
                )
            },
        )
        sse(
            w, "response.output_item.done",
            JSONObject().apply {
                put("type", "response.output_item.done")
                put("output_index", 0)
                put(
                    "item",
                    JSONObject().apply {
                        put("id", itemId)
                        put("type", "message")
                        put("role", "assistant")
                        put("status", "completed")
                        put(
                            "content",
                            JSONArray().put(
                                JSONObject().apply {
                                    put("type", "output_text")
                                    put("text", fullText)
                                    put("annotations", JSONArray())
                                },
                            ),
                        )
                    },
                )
            },
        )
        sse(
            w, "response.completed",
            JSONObject().apply {
                put("type", "response.completed")
                put(
                    "response",
                    baseResponse(status = "completed").apply {
                        put(
                            "output",
                            JSONArray().put(
                                JSONObject().apply {
                                    put("id", itemId)
                                    put("type", "message")
                                    put("role", "assistant")
                                    put("status", "completed")
                                    put(
                                        "content",
                                        JSONArray().put(
                                            JSONObject().apply {
                                                put("type", "output_text")
                                                put("text", fullText)
                                                put("annotations", JSONArray())
                                            },
                                        ),
                                    )
                                },
                            ),
                        )
                        put(
                            "usage",
                            JSONObject().apply {
                                put("input_tokens", 0)
                                put("output_tokens", outputTokens)
                                put("total_tokens", outputTokens)
                            },
                        )
                    },
                )
            },
        )
    }

    override fun emitError(w: PrintWriter, message: String) {
        // OpenAI Responses spec uses `event: error` for inline mid-stream errors;
        // `response.failed` is the terminal-status shape but Codex CLI parsers
        // sometimes wait specifically for the `error` event name.
        sse(
            w, "error",
            JSONObject().apply {
                put("type", "error")
                put("code", "server_error")
                put("message", message)
            },
        )
        // Then signal terminal failure for clients that key off the response state.
        sse(
            w, "response.failed",
            JSONObject().apply {
                put("type", "response.failed")
                put(
                    "response",
                    baseResponse(status = "failed").apply {
                        put(
                            "error",
                            JSONObject().apply {
                                put("code", "server_error")
                                put("message", message)
                            },
                        )
                    },
                )
            },
        )
    }

    private fun baseResponse(status: String): JSONObject = JSONObject().apply {
        put("id", respId)
        put("object", "response")
        put("status", status)
        put("model", model)
        put("output", JSONArray())
        put("created_at", System.currentTimeMillis() / 1000)
    }

    private fun sse(w: PrintWriter, event: String, data: JSONObject) {
        // Inject sequence_number into every data payload — OpenAI's Responses
        // streaming spec includes it on every event so clients can detect drops.
        sequenceNumber += 1
        data.put("sequence_number", sequenceNumber)
        w.print("event: "); w.print(event); w.print('\n')
        w.print("data: "); w.print(data.toString()); w.print("\n\n")
        w.flush()
    }
}
