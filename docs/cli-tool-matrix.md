# CLI tool calling, MCP, skills, web search — what works in bare mode

This is the honest accounting of what *tools* (function-calling, MCP servers,
built-in web search, skills) survive when each supported CLI is launched in its
"bare / lightweight" mode against `temux_llm`. Everything in this file was
verified against locally-installed CLI versions in May 2026:

- `claude` 2.1.126
- `codex-cli` 0.125.0
- `llxprt` 0.9.3
- `opencode` 1.14.31
- our service (APK 0.5.1, wire version 0.13.5)

## TL;DR

| CLI | Bare-mode tool surface | MCP add-back | Skills | Web search |
|---|---|---|---|---|
| **`claude --bare`** | Bash + Read + Edit (only these are guaranteed) | ✅ via `--mcp-config <file>` (+`--strict-mcp-config`) | ❌ auto-discovery off; `/skill-name` slash dispatch only works in interactive mode | ❌ `WebSearch` / `WebFetch` not in the bare allowlist; ship as MCP if you want it |
| **`codex exec` (minimal)** | All 29 built-in tools still sent — `reasoning=minimal` only changes thinking-trace size, NOT the tool registry | ✅ `[mcp_servers.*]` in `~/.codex/config.toml` works regardless of reasoning effort | ✅ `~/.codex/skills/` loaded as a `<skills_instructions>` developer-message block (not wire tools) | ⚠ `-c web_search="live"` sends an OpenAI **hosted** `{type:"web_search"}` tool — small local model receives the definition, but Codex's local runtime never dispatches it (hosted tool only fires server-side at OpenAI). Effectively unusable on-device. |
| **`llxprt`** | All built-in tools + `tools[]` always sent over wire — no bare-mode knob. The only mode that strips tools is `--experimental-acp` | ✅ `llxprt mcp add/list/remove`; settings under `~/.llxprt/settings.json` | ✅ `--extensions`, `~/.llxprt/commands/`, subagent system | ✅ ships `exa-web-search` + `direct-web-fetch` in `~/.llxprt/prompts/tools/`; Exa needs `EXA_API_KEY` |
| **`opencode run --agent bare`** (our launcher's default) | **All 14 tools off** — `webfetch=false`, `task=false`, `bash=false`, etc. `tools: []` is sent (or omitted), zero tool definitions reach the model | Conditional — MCP tools are filtered through the same `tools` / `permission` system, so our blanket `tools.*=false` strips them too. Use `--agent build` to keep them | Conditional — skills go through the single `skill` tool; bare's `tools.skill` doesn't exist as a key, but skills are unreachable when MCP/all tools are off | ❌ in bare; ✅ if you swap to `--agent build` AND set `OPENCODE_ENABLE_EXA=1 + EXA_API_KEY` |

## Service side: do we actually translate?

`HttpServer.kt` + `ChatFormat.kt` + `StreamEncoders.kt` together do this:

1. Every envelope EXCEPT `/api/generate` reads the request's `tools[]` field.
2. Tools are flattened to a plaintext system block via
   `ChatFormat.renderToolBlock` (`ChatFormat.kt:143-178`). Format is the
   lowest-common-denominator JSON envelope, NOT Gemma's native
   `<|tool_call|>` tokens (deliberate; the model emits cleaner JSON than
   it does its own native format with prompt injection).
3. The model's response is brace-balance-parsed for `{"tool_calls": [...]}`
   (`ChatFormat.kt:245-284`).
4. `StreamEncoders.kt` translates parsed calls back into wire-correct frames
   per envelope:
   - `/api/chat` → `tool_calls[]` array + `done_reason:"tool_calls"`
     (line 152)
   - `/v1/chat/completions` → `tool_calls` delta + `finish_reason:"tool_calls"`
     (line 266)
   - `/v1/messages` → `content_block` of `type:"tool_use"` + `stop_reason:"tool_use"`
     (line 462)
   - `/v1/responses` → `function_call` output items + `function_call_arguments.delta/.done`
     (line 822)
5. Streaming-with-tools goes through `runStreamBuffered`
   (`HttpServer.kt:507-549`): the entire response is buffered, parsed, and
   then synthesized as wire-correct frames in one shot.

So: **structurally, every envelope translates prompt-injected JSON back to
proper wire tool_calls.** What it does NOT do:

- **Multi-turn `tool_result` is partial.** `ChatFormat.flatten` accepts
  role `"tool"` (`ChatFormat.kt:67-70`) but stringifies `tool_use` /
  `tool_result` blocks as raw JSON for `/v1/messages` and `/api/chat`.
  Only the Responses path does friendlier `"Tool result: ..."` prose
  (`ChatFormat.kt:327-335`). Small models (Gemma 4 E2B/E4B) get confused
  by the raw JSON form and often emit garbage on the second turn.
- **No `tool_choice` honouring.** `forced` / `none` / `specific tool` are
  ignored.
- **Two-pass parser only consumes the FIRST `{...}` containing `"tool_calls"`**
  in the response (`ChatFormat.kt:194-200`). A response with two parallel
  tool calls in separate JSON objects loses the second.
- **No KV reuse across turns.** `Conversation::Clone()` JNI is unbound
  (`CHANGELOG.md:120,171,284,289`). Every multi-turn request re-prefills
  the entire transcript from scratch — slow, and on 12 GB devices the
  prefill cost climbs fast as the conversation grows. This is the single
  biggest reason agent loops fall over after 2-3 turns. The v0.6.0 fork
  plan addresses this (`.omc/research/23-fork-litertlm-plan.md`).
- **`/api/generate` is always bare.** It doesn't read `tools[]` at all.

## Concrete recipes

### Single-turn tool call works on every envelope

The structural happy path — model emits clean prompt-injection JSON, we
translate to wire-correct tool_calls, CLI dispatches:

```bash
# Anthropic: claude --bare with one MCP server
cat > /tmp/mcp.json <<'JSON'
{
  "mcpServers": {
    "fetch": {
      "command": "uvx",
      "args": ["mcp-server-fetch"]
    }
  }
}
JSON
ANTHROPIC_BASE_URL=http://127.0.0.1:11434 \
ANTHROPIC_AUTH_TOKEN=ollama \
ANTHROPIC_MODEL=model \
  claude --bare --mcp-config /tmp/mcp.json --strict-mcp-config \
    --allowedTools "Bash,Read,Edit,mcp__fetch__*" \
    -p "fetch https://example.com and summarize"

# llxprt: tools always on; web_search via Exa key
EXA_API_KEY=... llxprt --provider openai \
  --baseurl http://127.0.0.1:11434/v1 --key ollama --model model \
  --yolo --prompt "search the web for X and summarize"

# OpenCode: must NOT use bare; switch to the `build` agent
OPENCODE_ENABLE_EXA=1 EXA_API_KEY=... \
  opencode run --agent build -m temuxllm/local-cpu \
    "search for X and summarize"
```

### Codex web_search is a trap

`-c web_search="live"` does send the tool definition over the wire, but
`{type:"web_search"}` is OpenAI's *hosted* tool — meant to fire on
OpenAI's side. Codex's local runtime never dispatches it. Don't bother;
add a function-tool MCP server instead:

```bash
codex exec --skip-git-repo-check \
  -c project_doc_max_bytes=0 \
  -c 'model_reasoning_effort="minimal"' \
  -c 'model_instructions_file="/tmp/tiny.md"' \
  -c 'model_provider="temuxllm"' \
  -c 'model_providers.temuxllm.name="temuxllm"' \
  -c 'model_providers.temuxllm.base_url="http://127.0.0.1:11434/v1"' \
  -c 'model_providers.temuxllm.wire_api="responses"' \
  -c 'mcp_servers.fetch.command="uvx"' \
  -c 'mcp_servers.fetch.args=["mcp-server-fetch"]' \
  -c 'model="model"' \
  "fetch https://example.com and summarize"
```

The MCP fetch tool gets sent as a regular function in `tools[]`; Codex
dispatches it locally when the model emits a function_call.

### OpenCode bare needs to NOT be bare

If you want web search through OpenCode, drop our launcher's `--agent bare`
and use `--agent build` (or define your own agent that keeps `webfetch`
+ MCP enabled but bans destructive tools):

```jsonc
// opencode.json — bare-with-net agent
{
  "agent": {
    "bare-with-net": {
      "mode": "primary",
      "model": "temuxllm/local-cpu",
      "tools": {
        "webfetch": true,
        "websearch": true,
        "bash": false, "edit": false, "write": false,
        "patch": false, "todoread": false, "todowrite": false,
        "task": false
      }
    }
  }
}
```

## Real-world reliability vs structural support

The structural pipeline is fine. The model is the bottleneck:

- **Gemma 4 E4B** with our prompt-injection format produces clean
  `{"tool_calls":[...]}` for **single tools** with simple schemas
  (string args). Reliability ≈ 80-90 %.
- **Multi-tool / nested-arg schemas** drop to ~50 %; the model often
  emits the outer array but mangles enums, missing required fields.
- **Multi-turn tool_result follow-ups** are unreliable (~20 %) because
  the raw-JSON template isn't in Gemma's training distribution. The
  Responses-path "Tool result: ..." prose is better but still fragile.
- **Concurrent CLI agent loops with hooks/skills/MCP** routinely
  exceed our 16 k context; even when they fit, the no-Clone() re-prefill
  cost makes a 5-turn agent loop take 30-60 s per turn on the S25.

Conclusion: **temux_llm is a usable single-shot tool-call back-end and
a marginal multi-turn agent back-end on 12 GB devices.** The v0.6.0 fork
plan adds `Conversation::Clone()` and INT8 KV — that's the change that
makes multi-turn agent loops practical on-device.

## What changes for v0.6.0

- **`Conversation::Clone()`** — KV cache reuse across turns. Pre-fills
  the system prompt + tool definitions ONCE, clones for each turn.
  Expected: 5-turn agent loop drops from 30-60 s/turn to 3-5 s/turn.
- **INT8 KV** — halves KV memory footprint, lets us push the context
  ceiling to 24-32 k on 12 GB devices.
- **Native `<|tool_call|>` Gemma tokens** as an opt-in on `/v1/messages`
  + `/v1/responses` for callers that explicitly request it. Should
  raise multi-tool reliability from ~50 % to ~80 %.
- **`tool_choice` honouring** + multi-call parser.

Until then, the recipes above (single-shot tool calls + MCP function
servers + Exa keys for built-in web search) are the recommended pattern.

## See also

- [`docs/ollama-compat.md`](ollama-compat.md) — full endpoint matrix,
  wire byte tables.
- [README.md](../README.md) — install + per-CLI manual configs.
- [`.omc/research/23-fork-litertlm-plan.md`](../.omc/research/23-fork-litertlm-plan.md)
  — v0.6.0 fork plan with kill-switch criteria.
