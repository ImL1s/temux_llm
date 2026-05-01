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

    /**
     * Synthesize a full envelope from a buffered, post-parsed result. Used
     * when `tools` is present in the request: streaming raw tokens would
     * spray the client with the prefix of a tool-call JSON object before
     * we can parse it, so we collect the whole output, parse for
     * `{"tool_calls":[...]}`, and emit the wire-correct frames at once.
     *
     * Default impl preserves text-only behavior for envelopes that don't
     * model tool calls (NdjsonGenerateEncoder).
     */
    fun emitBuffered(w: PrintWriter, result: BufferedResult) {
        emitStart(w)
        if (result.text.isNotEmpty()) emitToken(w, result.text)
        emitDone(w, result.durationMs, result.outputTokens, result.outputChars, result.backend)
    }
}

/**
 * Aggregated result from a non-tool-aware run that's been collected for
 * tool-call parsing. [text] is the leftover prose with any
 * `{"tool_calls":[...]}` JSON object removed; [toolCalls] is the parsed
 * list (may be empty when the model decided to chat without tooling).
 */
data class BufferedResult(
    val text: String,
    val toolCalls: List<ChatFormat.ToolCall>,
    val durationMs: Long,
    val outputTokens: Int,
    val outputChars: Int,
    val backend: String,
)

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

    /**
     * Single NDJSON line carrying both text content (if any) and tool_calls
     * (if any). `done_reason` flips to "tool_calls" when we surface a tool
     * invocation per Ollama 0.13+ convention.
     */
    override fun emitBuffered(w: PrintWriter, result: BufferedResult) {
        val msg = JSONObject().apply {
            put("role", "assistant")
            put("content", result.text)
            if (result.toolCalls.isNotEmpty()) {
                val arr = JSONArray()
                for (tc in result.toolCalls) {
                    arr.put(
                        JSONObject().apply {
                            put(
                                "function",
                                JSONObject().apply {
                                    put("name", tc.name)
                                    // Ollama uses object args, not stringified.
                                    put("arguments", tc.arguments)
                                },
                            )
                        },
                    )
                }
                put("tool_calls", arr)
            }
        }
        val o = JSONObject().apply {
            put("model", model)
            put("created_at", ModelRegistry.iso8601(System.currentTimeMillis()))
            put("message", msg)
            put("done", true)
            put("done_reason", if (result.toolCalls.isNotEmpty()) "tool_calls" else "stop")
            put("total_duration", result.durationMs * 1_000_000L)
            put("eval_count", result.outputTokens)
            put("backend", result.backend)
        }
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

    /**
     * Emit synthesized OpenAI Chat SSE: text content (if any) as a normal
     * delta, then a tool_calls delta per tool call (each with stringified
     * `arguments` per OpenAI spec, NOT object), finally finish_reason
     * `tool_calls` or `stop`.
     */
    override fun emitBuffered(w: PrintWriter, result: BufferedResult) {
        val finishReason = if (result.toolCalls.isNotEmpty()) "tool_calls" else "stop"
        // 1) Optional text delta with role.
        if (result.text.isNotEmpty()) {
            val delta = JSONObject().apply {
                put("role", "assistant")
                put("content", result.text)
            }
            val chunk = chatChunk(delta, JSONObject.NULL)
            w.print("data: "); w.print(chunk.toString()); w.print("\n\n"); w.flush()
        } else if (result.toolCalls.isNotEmpty()) {
            // role-only opener so the client knows the assistant turn started.
            val delta = JSONObject().apply { put("role", "assistant") }
            val chunk = chatChunk(delta, JSONObject.NULL)
            w.print("data: "); w.print(chunk.toString()); w.print("\n\n"); w.flush()
        }
        // 2) Tool call deltas — emit ALL args in one chunk per call (no
        // streaming partial JSON; that's a v0.4.0 enhancement).
        for ((index, tc) in result.toolCalls.withIndex()) {
            val tcObj = JSONObject().apply {
                put("index", index)
                put("id", tc.id)
                put("type", "function")
                put(
                    "function",
                    JSONObject().apply {
                        put("name", tc.name)
                        // OpenAI requires `arguments` to be a STRING.
                        put("arguments", tc.arguments.toString())
                    },
                )
            }
            val delta = JSONObject().apply { put("tool_calls", JSONArray().put(tcObj)) }
            val chunk = chatChunk(delta, JSONObject.NULL)
            w.print("data: "); w.print(chunk.toString()); w.print("\n\n"); w.flush()
        }
        // 3) Final chunk with finish_reason + usage.
        val finalChunk = JSONObject().apply {
            put("id", id)
            put("object", "chat.completion.chunk")
            put("created", created)
            put("model", model)
            put(
                "choices",
                JSONArray().put(
                    JSONObject().apply {
                        put("index", 0)
                        put("delta", JSONObject())
                        put("finish_reason", finishReason)
                    },
                ),
            )
            put(
                "usage",
                JSONObject().apply {
                    put("prompt_tokens", 0)
                    put("completion_tokens", result.outputTokens)
                    put("total_tokens", result.outputTokens)
                },
            )
        }
        w.print("data: "); w.print(finalChunk.toString()); w.print("\n\n")
        w.print("data: [DONE]\n\n")
        w.flush()
    }

    private fun chatChunk(delta: JSONObject, finishReason: Any?): JSONObject = JSONObject().apply {
        put("id", id)
        put("object", "chat.completion.chunk")
        put("created", created)
        put("model", model)
        put(
            "choices",
            JSONArray().put(
                JSONObject().apply {
                    put("index", 0)
                    put("delta", delta)
                    put("finish_reason", finishReason ?: JSONObject.NULL)
                },
            ),
        )
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

    /**
     * Anthropic streaming with tool calls = sequential content blocks:
     *   - block 0: type:"text" with the text content (if any)
     *   - block 1..N: type:"tool_use" each with id/name/input
     * Then `message_delta` carries `stop_reason:"tool_use"` (or `end_turn`
     * if no tools), and `message_stop` closes the stream.
     */
    override fun emitBuffered(w: PrintWriter, result: BufferedResult) {
        // 1) message_start
        sse(
            w, "message_start",
            JSONObject().apply {
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
            },
        )

        var nextBlockIndex = 0

        // 2) Optional text block.
        if (result.text.isNotEmpty()) {
            sse(
                w, "content_block_start",
                JSONObject().apply {
                    put("type", "content_block_start")
                    put("index", nextBlockIndex)
                    put(
                        "content_block",
                        JSONObject().apply { put("type", "text"); put("text", "") },
                    )
                },
            )
            sse(
                w, "content_block_delta",
                JSONObject().apply {
                    put("type", "content_block_delta")
                    put("index", nextBlockIndex)
                    put(
                        "delta",
                        JSONObject().apply { put("type", "text_delta"); put("text", result.text) },
                    )
                },
            )
            sse(
                w, "content_block_stop",
                JSONObject().apply {
                    put("type", "content_block_stop")
                    put("index", nextBlockIndex)
                },
            )
            nextBlockIndex += 1
        }

        // 3) Tool-use blocks. Anthropic encodes each tool call as its own
        // content_block of type:"tool_use" with id/name/input. The input
        // JSON streams as input_json_delta partials — we emit it as one
        // chunk for the buffered case (simpler; clients handle it fine).
        for (tc in result.toolCalls) {
            val toolUseId = "toolu_" + UUID.randomUUID().toString().replace("-", "").take(20)
            sse(
                w, "content_block_start",
                JSONObject().apply {
                    put("type", "content_block_start")
                    put("index", nextBlockIndex)
                    put(
                        "content_block",
                        JSONObject().apply {
                            put("type", "tool_use")
                            put("id", toolUseId)
                            put("name", tc.name)
                            put("input", JSONObject())
                        },
                    )
                },
            )
            sse(
                w, "content_block_delta",
                JSONObject().apply {
                    put("type", "content_block_delta")
                    put("index", nextBlockIndex)
                    put(
                        "delta",
                        JSONObject().apply {
                            put("type", "input_json_delta")
                            put("partial_json", tc.arguments.toString())
                        },
                    )
                },
            )
            sse(
                w, "content_block_stop",
                JSONObject().apply {
                    put("type", "content_block_stop")
                    put("index", nextBlockIndex)
                },
            )
            nextBlockIndex += 1
        }

        // 4) message_delta + message_stop.
        sse(
            w, "message_delta",
            JSONObject().apply {
                put("type", "message_delta")
                put(
                    "delta",
                    JSONObject().apply {
                        put(
                            "stop_reason",
                            if (result.toolCalls.isNotEmpty()) "tool_use" else "end_turn",
                        )
                        put("stop_sequence", JSONObject.NULL)
                    },
                )
                put(
                    "usage",
                    JSONObject().apply { put("output_tokens", result.outputTokens) },
                )
            },
        )
        sse(w, "message_stop", JSONObject().apply { put("type", "message_stop") })
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

    /**
     * OpenAI Responses with tools: emit a `message` output item for the
     * text portion (if any), plus one `function_call` output item per
     * tool call. Codex CLI parses these into its agent loop. Buffered
     * single-shot per item — no `function_call_arguments.delta` streaming
     * (deferred enhancement; Codex handles single-shot fine for non-stream
     * conversion).
     */
    override fun emitBuffered(w: PrintWriter, result: BufferedResult) {
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

        var outputIndex = 0
        val outputItems = JSONArray()

        // Text message item (if any).
        if (result.text.isNotEmpty()) {
            sse(
                w, "response.output_item.added",
                JSONObject().apply {
                    put("type", "response.output_item.added")
                    put("output_index", outputIndex)
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
                    put("output_index", outputIndex)
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
            sse(
                w, "response.output_text.delta",
                JSONObject().apply {
                    put("type", "response.output_text.delta")
                    put("item_id", itemId)
                    put("output_index", outputIndex)
                    put("content_index", 0)
                    put("delta", result.text)
                },
            )
            sse(
                w, "response.output_text.done",
                JSONObject().apply {
                    put("type", "response.output_text.done")
                    put("item_id", itemId)
                    put("output_index", outputIndex)
                    put("content_index", 0)
                    put("text", result.text)
                },
            )
            sse(
                w, "response.content_part.done",
                JSONObject().apply {
                    put("type", "response.content_part.done")
                    put("item_id", itemId)
                    put("output_index", outputIndex)
                    put("content_index", 0)
                    put(
                        "part",
                        JSONObject().apply {
                            put("type", "output_text")
                            put("text", result.text)
                            put("annotations", JSONArray())
                        },
                    )
                },
            )
            val msgItem = JSONObject().apply {
                put("id", itemId)
                put("type", "message")
                put("role", "assistant")
                put("status", "completed")
                put(
                    "content",
                    JSONArray().put(
                        JSONObject().apply {
                            put("type", "output_text")
                            put("text", result.text)
                            put("annotations", JSONArray())
                        },
                    ),
                )
            }
            sse(
                w, "response.output_item.done",
                JSONObject().apply {
                    put("type", "response.output_item.done")
                    put("output_index", outputIndex)
                    put("item", msgItem)
                },
            )
            outputItems.put(msgItem)
            outputIndex += 1
        }

        // Function-call items, one per tool call.
        for (tc in result.toolCalls) {
            val callItemId = "fc_" + UUID.randomUUID().toString().replace("-", "").take(20)
            sse(
                w, "response.output_item.added",
                JSONObject().apply {
                    put("type", "response.output_item.added")
                    put("output_index", outputIndex)
                    put(
                        "item",
                        JSONObject().apply {
                            put("id", callItemId)
                            put("type", "function_call")
                            put("call_id", tc.id)
                            put("name", tc.name)
                            put("arguments", "")
                            put("status", "in_progress")
                        },
                    )
                },
            )
            sse(
                w, "response.function_call_arguments.delta",
                JSONObject().apply {
                    put("type", "response.function_call_arguments.delta")
                    put("item_id", callItemId)
                    put("output_index", outputIndex)
                    put("delta", tc.arguments.toString())
                },
            )
            sse(
                w, "response.function_call_arguments.done",
                JSONObject().apply {
                    put("type", "response.function_call_arguments.done")
                    put("item_id", callItemId)
                    put("output_index", outputIndex)
                    put("arguments", tc.arguments.toString())
                },
            )
            val fcItem = JSONObject().apply {
                put("id", callItemId)
                put("type", "function_call")
                put("call_id", tc.id)
                put("name", tc.name)
                put("arguments", tc.arguments.toString())
                put("status", "completed")
            }
            sse(
                w, "response.output_item.done",
                JSONObject().apply {
                    put("type", "response.output_item.done")
                    put("output_index", outputIndex)
                    put("item", fcItem)
                },
            )
            outputItems.put(fcItem)
            outputIndex += 1
        }

        // response.completed
        sse(
            w, "response.completed",
            JSONObject().apply {
                put("type", "response.completed")
                put(
                    "response",
                    baseResponse(status = "completed").apply {
                        put("output", outputItems)
                        put(
                            "usage",
                            JSONObject().apply {
                                put("input_tokens", 0)
                                put("output_tokens", result.outputTokens)
                                put("total_tokens", result.outputTokens)
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
