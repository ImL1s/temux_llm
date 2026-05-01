# Ollama compatibility — endpoint reference

`temux_llm` v0.3.0+ exposes Ollama-shaped endpoints on `127.0.0.1:11434` so
that off-the-shelf CLIs (Claude Code, Codex CLI, OpenCode, OpenClaw, Gemini
CLI via bridge) can connect to the on-device LiteRT-LM model without a
proxy. This file is the wire-level reference.

## TL;DR — which CLI hits which endpoint

| CLI | Wire protocol | Endpoint we serve | Launch command |
|---|---|---|---|
| Claude Code | Anthropic Messages | `POST /v1/messages` (SSE) | `temuxllm launch claude` |
| Codex CLI (≥0.80) | OpenAI Responses | `POST /v1/responses` (SSE) | `temuxllm launch codex` |
| OpenCode | OpenAI Chat Completions | `POST /v1/chat/completions` (SSE) | `temuxllm launch opencode` |
| OpenClaw | Ollama native | `POST /api/chat` (NDJSON) | `temuxllm launch openclaw` |
| Gemini CLI | Google `:generateContent` | requires LiteLLM bridge | `temuxllm launch gemini` (prints bridge instructions) |

Three streaming envelopes (Anthropic SSE, OpenAI SSE, Ollama NDJSON) are
implemented; the underlying LiteRT-LM token stream is the same — only the
framing and event vocabulary differ. See
[`StreamEncoders.kt`](../android/app/src/main/kotlin/dev/temuxllm/StreamEncoders.kt)
for the byte-level encoders.

## Endpoint inventory

### Probe / metadata

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/` | Returns `Ollama is running` (probe-safe) |
| `GET` | `/healthz` | Returns `ok` |
| `GET` | `/api/version` | `{ version, service, runtime, default_backend, model_path, engine_loaded }` |
| `GET` | `/api/tags` | Ollama-shaped model list with `name`, `model`, `modified_at`, `size`, `digest`, `details` |
| `POST` | `/api/show` | Per-model metadata + `capabilities` array (`["completion"]`, plus `"tools"` when enabled) |
| `GET` | `/api/ps` | Currently loaded models (we have at most one) |
| `GET` | `/v1/models` | OpenAI list (`{object:"list", data:[{id,object,created,owned_by}]}`) |

### Inference

| Method | Path | Wire format | Notes |
|---|---|---|---|
| `POST` | `/api/generate` | NDJSON | Legacy temuxllm shape (still supported); now accepts `model` field |
| `POST` | `/api/chat` | NDJSON | Ollama-native chat. OpenClaw uses this. |
| `POST` | `/v1/chat/completions` | SSE | OpenAI Chat Completions. OpenCode uses this. |
| `POST` | `/v1/messages` | SSE (named events) | Anthropic Messages. Claude Code uses this. |
| `POST` | `/v1/responses` | SSE (named events) | OpenAI Responses API. Codex CLI uses this. |
| `POST` | `/api/pull` | JSON | Stub: returns `{"status":"success"}` (Codex `--oss` calls this) |

### Out of scope (return 404)

`/api/create`, `/api/copy`, `/api/delete`, `/api/push`, `/api/blobs/*`,
`/v1/completions`, `/v1/embeddings`, `/v1/images/generations`,
`/v1beta/models/{m}:generateContent` (Gemini native — see bridge note below).

## Model identity & aliasing

We serve one model at a time (whatever `.litertlm` is staged at
`<filesDir>/litertlm/model.litertlm`). Most coding-agent CLIs send arbitrary
model names — we accept them all using this resolution policy
(see [`ModelRegistry.kt`](../android/app/src/main/kotlin/dev/temuxllm/ModelRegistry.kt)):

1. Wildcard (`""`, `"*"`, `"default"`, `"local"`) → active model.
2. Exact filename match (`gemma-4-E2B-it`) → that file.
3. Optional sidecar `<filesDir>/models.json` with explicit aliases
   (`{"aliases":{"claude-3-5-sonnet":"gemma-4-E2B-it"}}`).
4. Family-prefix (`gemma`, `qwen`) → first matching file.
5. Branded model names (`claude*`, `gpt*`, `gpt-oss*`, `o1`, `o3`,
   `anthropic*`, `openai*`) → active model. This is what makes
   `ANTHROPIC_MODEL=claude-3-5-sonnet` "just work".
6. Otherwise → `404 {"error":"model 'X' not found"}`.

## Authentication

We accept (and ignore) all of these — never reject a request based on auth:
`Authorization`, `X-Api-Key`, `anthropic-version`. Ollama itself documents
`api_key='ollama'` as "required but ignored". Same here.

## Streaming wire formats

### Ollama native NDJSON (`/api/generate`, `/api/chat`)

`Content-Type: application/x-ndjson; charset=utf-8`. One JSON document per
line, `\n`-terminated. Final line has `"done":true`. Mid-stream errors come
as `{"error":"...","done":true}\n`.

### OpenAI SSE (`/v1/chat/completions`)

`Content-Type: text/event-stream; charset=utf-8`. `data: {...}\n\n` lines,
followed by `data: [DONE]\n\n`. `chat.completion.chunk` event objects.

### Anthropic SSE (`/v1/messages`)

`Content-Type: text/event-stream; charset=utf-8`. Named events:
`message_start`, `content_block_start`, `ping`, `content_block_delta` (×N),
`content_block_stop`, `message_delta`, `message_stop`. Errors as `event: error`.

### OpenAI Responses SSE (`/v1/responses`)

Named events:
`response.created` → `response.in_progress` → `response.output_item.added` →
`response.content_part.added` → `response.output_text.delta` (×N) →
`response.output_text.done` → `response.content_part.done` →
`response.output_item.done` → `response.completed`. Failure as
`response.failed`. Stateless: `previous_response_id` and `conversation` are
not honored (matches Ollama's stateless Responses behavior).

## Known limitations

- **Single active model.** Hot-swap between `.litertlm` files requires
  pushing a new file and restarting the service; the engine's `xnnpack` and
  `mldrift_program_cache` files invalidate when the model changes.
- **No embeddings.** `/api/embed` and `/v1/embeddings` are not implemented.
- **No image inputs.** `image_url` and base64 image content blocks return
  HTTP 400 "image content is not supported on this model".
- **Tool calling is not implemented in v0.3.0.** `/api/show` reports
  `capabilities: ["completion"]` only. CLIs that depend on tools fall
  back to plain chat. The runtime substrate (Gemma 4 native
  `<|tool_call>` tokens + LiteRT-LM 0.11 `ConversationConfig.tools`) is
  ready, but the per-envelope translator (OpenAI `tool_calls`,
  Anthropic `tool_use`, Ollama `tool_calls`) is deferred to a follow-up
  release. The `TEMUXLLM_NO_TOOLS=1` env-var gate is wired so behavior
  remains forward-compatible.
- **Gemini CLI is not native.** Use LiteLLM as a bridge:
  ```sh
  pip install 'litellm[proxy]'
  litellm --model ollama/gemma-4-E2B-it --api_base http://127.0.0.1:11434 --port 4000 &
  GOOGLE_GEMINI_BASE_URL=http://127.0.0.1:4000 gemini
  ```

## Verification commands

```sh
# Probes
curl -s http://127.0.0.1:11434/                 # "Ollama is running"
curl -s http://127.0.0.1:11434/api/version | jq # {version,...}
curl -s http://127.0.0.1:11434/api/tags | jq    # Ollama-shaped models list

# Anthropic non-streaming
curl -s -X POST http://127.0.0.1:11434/v1/messages \
  -H 'content-type: application/json' \
  -H 'x-api-key: ollama' -H 'anthropic-version: 2023-06-01' \
  --data '{"model":"local","max_tokens":64,"messages":[{"role":"user","content":"hi"}],"stream":false}' \
  | jq

# OpenAI chat streaming
curl -sN -X POST http://127.0.0.1:11434/v1/chat/completions \
  -H 'content-type: application/json' \
  --data '{"model":"local","messages":[{"role":"user","content":"hi"}],"stream":true}'

# Ollama native chat
curl -s -X POST http://127.0.0.1:11434/api/chat \
  -H 'content-type: application/json' \
  --data '{"model":"local","messages":[{"role":"user","content":"hi"}],"stream":false}' \
  | jq

# OpenAI Responses (Codex CLI)
curl -sN -X POST http://127.0.0.1:11434/v1/responses \
  -H 'content-type: application/json' \
  --data '{"model":"local","input":"hi","stream":true}' | head -3
```

## Source pointers

- HTTP routing: [`HttpServer.kt`](../android/app/src/main/kotlin/dev/temuxllm/HttpServer.kt)
- Wire encoders: [`StreamEncoders.kt`](../android/app/src/main/kotlin/dev/temuxllm/StreamEncoders.kt)
- Model resolution: [`ModelRegistry.kt`](../android/app/src/main/kotlin/dev/temuxllm/ModelRegistry.kt)
- Messages → prompt: [`ChatFormat.kt`](../android/app/src/main/kotlin/dev/temuxllm/ChatFormat.kt)
- Launcher: [`scripts/temuxllm`](../scripts/temuxllm)
