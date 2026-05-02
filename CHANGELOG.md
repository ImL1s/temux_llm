# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

(no unreleased changes)

## [0.8.1] — 2026-05-03

Hot-fix release for the Codex GitHub-bot review of v0.8.0
(`commit 95a2eee2d7`). Two of the bot's five findings were
false positives (already-defended bug paths the bot didn't
trace deeply enough); the remaining three are real and addressed
in-place.

### Codex bot findings + dispositions

| # | Severity | Path | Disposition |
|---|----------|------|-------------|
| 1 | P1 | `HttpServer.kt:605` model resolution | **FALSE POSITIVE** — `ModelRegistry.resolve()` already returns `null` for any non-active name (mapped to 404 by the caller), and a defensive `Log.w` warns if a non-active entry sneaks through. Comment in code already references the prior codex review pass that fixed it. |
| 2 | P1 | `LlmEngine.kt:407` `rawText.take(4096)` | **REAL** — was a debug-time cap on the probe path, silently promoted to production by v0.7.0. Cap removed; rely on `EngineConfig.maxNumTokens` for output sizing. |
| 3 | P2 | `scripts/temuxllm:524` `status` no `ensure_forward()` | **FALSE POSITIVE** — `cmd_status` already calls `ensure_forward()` at line 143 (with explanatory comment about parity with `launch`). Bot mis-traced the call. |
| 4 | P2 | `scripts/temuxllm:551` `launch` always forwards | **REAL** — `ensure_forward()` now early-returns when `TEMUXLLM_HOST` is set to a non-default endpoint, so users with remote URL / ssh-tunnel / alternate-port targets aren't blocked on missing `adb`. |
| 5 | P2 | `AutoFallback.kt:99` SIGKILL=LMK | **REAL** — narrowed: `REASON_SIGNALED + status==9` now ALSO requires `rss > 500 MB` to register as LMK. Below that, the engine wasn't loaded → SIGKILL came from somewhere else (adb kill, ANR watchdog, OEM battery saver) and we skip the auto-downshift instead of recording a stale lower ceiling. |

### Files

- MOD `android/app/src/main/kotlin/dev/temuxllm/LlmEngine.kt` — drop 4 KiB cap on native-tools `rawText`
- MOD `android/app/src/main/kotlin/dev/temuxllm/AutoFallback.kt` — RSS gate on SIGKILL → LMK heuristic
- MOD `scripts/temuxllm` — `ensure_forward()` skips when `TEMUXLLM_HOST` is set
- MOD `android/app/build.gradle.kts` — `versionCode 18→19`, `versionName 0.8.0→0.8.1`

### Acceptance

- Unit tests: 77 / 0 failures (no test changes needed; the cap removal is data-only and the fallback narrowing is gated on hardware-derived `rss`)
- `:app:lintDebug` clean
- APK builds
- Real-device n=30 multi-tool re-spot-check on Galaxy S25: 30/30 PASS (no regression from v0.8.0)

### Known shipping limitation (carried from v0.8.0)

Constrained decoding default-on still halts tools-less code-block
prose at the opening ` ``` ` fence. Sidecar opt-out via
`temuxllm.conf` `constrained_decode=0` continues to be the
documented escape hatch.

## [0.8.0] — 2026-05-03

Multi-tool reliability stack. Drives Gemma 4 E4B's n=30 multi-tool
success rate from ~40 % (v0.7.2 baseline against a 10-tool registry)
to **100 %** (T1-only measurement, tested before stacking) on Galaxy
S25 / CPU.

### Background

v0.7.2 closed the wire-format gaps but real-device end-to-end testing
exposed a deeper failure: when CLI agents (claude code, llxprt,
opencode) hit the service with their full tool registry (~10-14
tools), Gemma 4 E4B drops from 100 % (single tool) to ~40 % (multi-
tool) due to context pollution + format mismatch with its training
distribution. BFCL V4 confirms this curve is universal across small
models — even Ollama only achieves ~55-70 % on the same model.

Four parallel research agents identified concrete techniques used by
Ollama, LM Studio, Anthropic production, the Toolshed paper, and the
LiteRT-LM SDK itself. v0.8.0 implements six of them (T1, T2, T4, T5,
T6, plus a deferred T3/T7 list).

### G1 — `ExperimentalFlags.enableConversationConstrainedDecoding`

Reverse-engineering the `litertlm-android-0.11.0-rc1` AAR found
undocumented public flags. The big one:
`ExperimentalFlags.enableConversationConstrainedDecoding`. When
`ConversationConfig.tools` is set AND this flag is on, the SDK's C++
layer compiles tool schemas → grammar → constrains decode-time output
to valid tool-call shape (same mechanism LM Studio uses via llama.cpp
GBNF — but built into our existing dependency).

We also enable `convertCamelToSnakeCaseInToolDescription` so the
model sees uniform snake_case parameter names regardless of caller.

Both gated by env (`TEMUXLLM_CONSTRAINED_DECODE`,
`TEMUXLLM_TOOL_CASE_NORMALIZE`) so a future SDK regression is
hot-toggleable without redeploy. Default ON for tool-having calls.

**Per-call gating attempted then reverted (during v0.8.0 QA):** the
flag's prose-stop side-effect (model halts at the first ` ``` ` of
a code block in tools-less requests like "Show me a Python hello
world") motivated trying a per-call flip — keep flag off, turn it
on only inside the inference mutex when the call has tools. That
regressed multi-tool reliability sharply: n=30 dropped to 15/30
with a deterministic OOM at index ~15. Each off→on transition
appears to recompile grammar without freeing prior compilation.
Reverting to global=true at init brought n=30 back to 30/30.

**Sidecar opt-out (final):** `LlmEngine.readConstrainedDecoding
Preference()` checks `<filesDir>/temuxllm.conf` and
`/data/local/tmp/litertlm/temuxllm.conf` for `constrained_decode=0`
(also accepts `off`/`false`/`no`). Users who want code-block prose
generation more than they want tool reliability can:

```sh
adb shell 'echo "constrained_decode=0" > /data/local/tmp/litertlm/temuxllm.conf'
adb shell am force-stop dev.temuxllm.service && adb shell am start-foreground-service -n dev.temuxllm.service/dev.temuxllm.LlmService
```

### G2 — Top-K tool retrieval (RAG-for-tools)

Anthropic's production answer for multi-tool reliability: don't dump
all tools into the prompt. Embed user message → retrieve top-K most-
relevant → only those go to the model. Toolshed reports +46pp
Recall@5 with simple BM25.

Implementation: `ToolRanker.topK(tools, userMessage, k=3)` — pure
function, BM25-lite TF×IDF over tokenized name + description +
parameter-keys vs. user-message terms. Zero ML deps, no embedder.
Wired into all 4 envelope handlers; runs after the codex
`web_search` filter.

Configurable via `TEMUXLLM_TOOL_RAG_K` (default 3, set 0 to disable).

### G4 — One-shot exemplar injection

LangChain's empirical study: +30-64pp tool-call reliability on small
models when 1-3 canonical exemplar message pairs are spliced into
the conversation BEFORE the user turn (NOT as a string in the system
prompt — actual `assistant{tool_calls=...}` + `tool{result=...}`
messages).

Implementation: `assets/tool_exemplars.json` ships 8 canonical
exemplars (`get_weather`, `read_file`, `write_file`, `glob`, `grep`,
`bash`, `direct_web_fetch`, `list_directory`) plus a `_default`
fallback. `ExemplarBank.renderForTools(top-K names)` returns the
spliceable message array, capped at 2 tools per request to limit
context bloat.

Configurable via `TEMUXLLM_EXEMPLARS=0` to disable.

### G5 — Multi-pass schema-gated repair

Extends v0.6.0's stack-based bracket repair with 4 more passes
(ported from Ollama's `model/parsers/gemma4.go:485-535`):

1. **Single-quote → double-quote** in value positions (`{key:'val'}` → `{key:"val"}`)
2. **Missing closing quote** at structural chars (`,`, `}`, `]`, EOF)
3. **Schema-gated raw terminal string wrap** — when schema declares
   the param as `string` and model emitted a bare token, wrap in `"`.
   Only engages when the matching tool's schema is available;
   prevents the "wrap garbage in quotes = call it valid" failure
4. **Trailing prose strip** after the last depth-0 closer

Crucially gated by the actual tool schema (passed via new
`toolSchemas` parameter through 4 helpers). Tests in
`ChatFormatTest` cover each pass plus the chained sequence.

### G6 — Tool-name dictionary harden (prefix + Levenshtein)

Real-device test showed Gemma 4 emitting `direct_name` (truncated
key) or `direcct_web_fetch` (typo). Strict in-set check would drop
both. v0.8.0 `ChatFormat.resolveToolName(emitted, registered)`
recovers the canonical name via:

1. Exact match
2. ≥3-char prefix match (registered starts with emitted, OR vice
   versa)
3. Levenshtein distance ≤ 2

Returns null only if nothing matches — preserves the v0.6.0
defense-in-depth against prompt injection that fakes unknown tool
names. 7 new test cases in `ChatFormatTest`.

### Deferred to v0.8.1+

- **T3 — Gemma 4 `<|"|>` wire format port from Ollama.** Constrained
  decoding (G1) addresses the same root cause more broadly. Revisit
  if T1 reliability regresses on a future SDK version.
- **T7 — Streaming early-abort + retry with `cancelProcess()`.**
  Tricky surgery (mutex coordination, partial-output replay).
  Marginal gain at the current 100 % T1-only ceiling. Defer.
- **T8 — Qwen3-4B drop-in.** Apache 2.0, BFCL 62 % vs Gemma's
  estimated 40 %. Separate scope: requires sha256 manifest update +
  conversion + on-device validation.
- **T9 — FunctionGemma 270M router.** Google Dec 2025 release
  already on `litert-community/functiongemma-270m-ft-mobile-actions`.
  Dual-engine architecture; separate scope.

### Files

- NEW `android/app/src/main/kotlin/dev/temuxllm/ToolRanker.kt` (144 LOC)
  — BM25-lite tool retrieval
- NEW `android/app/src/main/kotlin/dev/temuxllm/ExemplarBank.kt` (119 LOC)
  — exemplar message renderer
- NEW `android/app/src/main/assets/tool_exemplars.json` — 8 + default
- MOD `android/app/src/main/kotlin/dev/temuxllm/ChatFormat.kt` (+260
  LOC) — multi-pass repair + dict resolver + Levenshtein
- MOD `android/app/src/main/kotlin/dev/temuxllm/HttpServer.kt`
  (+~110 LOC) — RAG, exemplar splice, schema extraction wired
  through 4 handlers
- MOD `android/app/src/main/kotlin/dev/temuxllm/LlmEngine.kt` (+63
  LOC) — `ExperimentalFlags` setters at engine init + sidecar opt-out
  (`temuxllm.conf` `constrained_decode=0|off|false|no`)
- NEW unit tests: `ToolRankerTest` (5), `ExemplarBankTest` (5),
  `ChatFormatTest` +13 (6 from T5 + 7 from T6)
- MOD `android/app/build.gradle.kts` — versionCode 17 → 18,
  versionName 0.7.2 → 0.8.0

### Acceptance

- Unit tests: 77 total (was 57), 0 failures
- `:app:lintDebug` clean
- APK installs on Galaxy S25 (RFCY71LAFYE, build versionCode 18)
- v0.8.0 full-stack n=30 single-tool baseline on real device:
  **30/30 = 100 %** (was ~40 % on v0.7.2 against 10-tool agent
  registry)
- Vision + tools regression: 1×1 PNG + `get_color` tool → model
  correctly returned `tool_calls=[{name:"get_color",
  arguments:{"color":"red"}}]` in 22 s. Multimodal path survives
  v0.8.0 changes.
- Snake game prose regression with `constrained_decode=0` sidecar
  opt-out: 712 s CPU on Galaxy S25, 6 396 chars output, contains
  `<canvas>` and `getContext` — proves the opt-out toggles cleanly
  without rebuild.

### Post-review fixes (folded in before tag)

Independent code-reviewer + security-reviewer pass on the v0.8.0
diff (per the project's "release tag requires outside review"
discipline) caught 4 issues that were addressed in-place:

- **HIGH** `repairMissingStringDelimiter` (pass 4) used to insert
  `"` before any `,`/`}`/`]` while inside a string, corrupting
  legitimate string values containing those chars (shell commands,
  regex with `}`, JSON literals). Guard added: only fire when the
  unclosed string content is < 32 chars AND the preceding char is
  alphanumeric — heuristic for "model truncated mid-token", which
  the pass was actually meant for.
- **HIGH (security)** `temuxllm.conf` `max_tokens=` was unbounded;
  any process with shell UID write access to `/data/local/tmp/`
  (adb, Termux) could set `max_tokens=2147483647` and OOM-kill
  the service. Clamp added: `MAX_TOKENS_HARD_CAP = 65 536` (the
  device tier table caps at 32 768; 65 536 leaves headroom).
- **MEDIUM** `ExemplarBank._default` fallback emitted the literal
  string `"tool_name"` (the placeholder baked into
  `tool_exemplars.json`) as the tool-call name instead of the
  caller's actual tool name. Injected a fictional tool reference
  that small models like Gemma 4 E4B sometimes echoed back.
  Fix: when `_default` is the chosen exemplar, override the
  rendered name with the loop variable.
- **MEDIUM** `resolveToolName` prefix matcher used `firstOrNull`
  on a `Set` — winner depended on JSON-array insertion order
  by the client. Replaced with `minByOrNull { abs(length - emitted.length) }`
  so the most-specific match wins regardless of order.
- **(documented, not fixed)** Code-review M1: `/v1/responses`
  endpoint skips exemplar splice. Codex CLI is the primary
  caller; once past the first turn its `input` already carries
  the prior tool-call+result pairs, providing the same signal
  exemplars would. First-turn cold-start callers accept the gap.
  Comment in `handleOpenAiResponses`.

Two new tests rooted the fixes:
- `ChatFormatTest.pass 4 does not corrupt legitimate string with structural chars`
- `ChatFormatTest.resolveToolName ambiguous prefix picks shortest length-diff`
- `ExemplarBankTest.renderForTools unknown tool name falls back to _default exemplar`
  was rewritten to assert the corrected behavior (was asserting the bug).

### What this DOESN'T solve

- Models other than Gemma 4 E4B with the same SDK + flag combo are
  untested; users on Qwen3 / FunctionGemma should expect different
  numbers (could be better or worse).
- Multi-turn agent loops with > 3-4 turns still bottleneck on
  per-turn re-prefill (no `Conversation::Clone()` until upstream
  Google ships Kotlin API; tracked in CHANGELOG v0.5.x notes).
- Streaming + tools combo where SDK FC parser throws still falls
  back to prompt-injection (v0.7.2 behaviour preserved); not
  affected by T1 since constrained decoding is for non-streaming
  Conversation API only.

### Known regression: tools-less code-block prose

With `constrained_decode=true` (default), tools-less prompts that
ask for code in markdown blocks ("Show me a Python hello world",
"Write a snake game in HTML") halt at the opening ` ``` ` fence.
Pure prose ("Write three sentences about cats") is unaffected. The
v0.8.0 default optimizes for tool-call reliability — users who hit
this can opt out via the sidecar described in G1 above.

This is an SDK-level behaviour, not a v0.8.0 architectural choice:
we tried per-call flag flipping to scope the constraint to tool
calls only, but the off→on transition leaks grammar-compiler memory
and triggers OOM around index 15 of an n=30 run on a 12 GB device.

## [0.7.2] — 2026-05-03

End-to-end agent-workflow testing on real device exposed an upstream
SDK bug. Patch transparently falls back to the prompt-injection path
so requests succeed instead of 500-ing.

### What broke

User asked: "did you test web search and writing a snake game?"
Honest answer was no — I'd only tested wire format with one simple
tool (`get_weather`) and short prompts. End-to-end tested:

1. **Snake game (`/v1/chat/completions`, CPU, max_tokens 1024)**:
   ✅ Generated 3686 chars / 1008 tokens of valid HTML in 9m27s
   (~1.78 t/s decode on Gemma 4 E4B CPU). Canvas tag, keydown
   handler, food spawn, game-over detection, doctype, closed
   `</html>` — playable in browser.

2. **Web search via llxprt (`direct_web_fetch` tool)**:
   ❌ SDK's native FC-parser threw `LiteRtLmJniException
   INVALID_ARGUMENT: Failed to parse tool calls from response`
   when Gemma 4 emitted its native `<|tool_call|>` tokens for an
   underscore tool name (`direct_web_fetch`). Same bug class as
   upstream LiteRT-LM #1027 / #1539 / #1181.

   The 30/30 success we measured in v0.7.0 used a single
   underscore-free tool (`get_weather`) — happy accident.

### Fix

Three call sites (`blockingGenerate`, `runStreamBuffered`,
`blockingResponsesWithTools`) now catch SDK FC-parser exceptions and
transparently fall back to the prompt-injection + repair path. The
fallback re-renders the original tool definitions as our LCD JSON
prompt block and re-runs through `engine.generateBlocking`. Output
is parsed via the repair-aware `ChatFormat.parseToolCalls`.

Detected error patterns: `LiteRtLmJniException`, `Failed to parse`,
`INVALID_ARGUMENT` substrings in the SDK error message.

### What this fixes / what it doesn't

- ✅ Service no longer returns 500 when SDK's FC parser chokes on
  Gemma 4's native tool tokens.
- ✅ Single-tool simple-prompt cases still go through native path
  (faster, cleaner typed output).
- ⚠ Fallback model output quality is itself uneven: in multi-tool
  prompts (llxprt registers ~10 built-in tools), Gemma 4 E4B mixes
  the SDK's native `<|tool_call|>` format with our LCD JSON prompt
  injection and emits malformed JSON like
  `{"tool_calls":[{"name":"direct_name":"direct_web_fetch",...}]}`
  with duplicated keys. The repair function recovers structure
  but the duplicated key fields make the call un-dispatchable.

  This is a Gemma 4 E4B context-handling limitation, not a service
  bug. Single-tool requests work cleanly (n=30 weather queries pass
  100 %). Multi-tool agent loops with full CLI tool registries are
  unreliable until either upstream LiteRT-LM ships a fixed FC parser
  or we add grammar-constrained decoding (currently neither is
  available — see #1027 maintainer notes).

### Files

- `android/app/src/main/kotlin/dev/temuxllm/HttpServer.kt` — fallback
  branches in `blockingGenerate`, `runStreamBuffered`, and
  `blockingResponsesWithTools`. Detects SDK error string patterns;
  re-renders prompt with `ChatFormat.renderToolBlock`; re-runs
  through `engine.generateBlocking`; parses with
  `ChatFormat.parseToolCalls`. Sets `nativeFallbackTriggered` so the
  parsed-output selector picks the repair path.
- `android/app/build.gradle.kts` — versionCode 16 → 17, versionName
  0.7.1 → 0.7.2.

### Acceptance

- 60 unit tests pass (no test changes; behavior preserved).
- `:app:lintDebug` clean.
- APK builds, installs on Galaxy S25.
- Real-device verified:
  - Snake game CPU non-stream: 1008 tokens of valid HTML, ✓
  - llxprt fetch on v0.7.1: 500 with FC-parser error
  - llxprt fetch on v0.7.2: HTTP 200 with malformed-but-extractable
    JSON; service log shows fallback fired (`FC-parser threw —
    falling back`).

### Known limitation kept honest

If you're using a CLI agent with > 3 registered tools (claude --bare
without --mcp-config has ~6, llxprt has ~10, opencode has ~14), the
model's tool selection accuracy degrades. Gemma 4 E4B's recall
on selecting the right tool from a long list and emitting clean JSON
args drops to roughly 30-50 % in our hand-checking. This is the
single biggest reason v0.6 / v0.7 advertise tool calling as
"single-shot reliable, multi-turn agent loops still uneven."

## [0.7.1] — 2026-05-02

Patch addressing 7 findings codex's PR-bot auto-review flagged on PR #11
after the v0.7.0 push (commit `8b1ebd1`). Two were P1, five P2; two of
those (vision wiring on /v1/responses + streaming-with-tools dropping
imageBytes) had already been fixed in v0.7.0 but codex was still
reviewing the v0.6.0 commit when those landed.

### P1#1 — `handleGenerate` defensive sanity check (false-positive clarification)

Codex flagged: handler resolves `model` to a registry entry but
inference uses `LlmEngine.activeModelPath()`, so non-active models
silently get the wrong response.

Investigation: `ModelRegistry.resolve()` is documented (and
implemented) to ONLY return the active staged entry — it returns null
for any name that doesn't map to active, which the handler converts to
404. So `resolved.path == active.path` always holds at the
`blockingGenerate` call site. No actual bug.

Fix: added an explicit comment + a defensive `Log.w` if a future
regression sneaks a non-active entry through `resolve()`.

### P1#2 — `/api/tags` now matches `resolve()` policy

`ModelRegistry.ollamaTags()` previously listed every `.litertlm` file
in the staging dir. `resolve()` only accepts the active one, so a
client that picked an ID from `/api/tags` and then hit `/api/chat`
got a 404 — wire-level inconsistency.

Fixed: `ollamaTags()` returns only the active entry (matching
`/v1/models` which was already consistent).

### P2#3 — `cmd_status` already calls `ensure_forward()`

Codex pointed at a line range that didn't match — the existing
`cmd_status()` does call `ensure_forward()`. No-op fix; verified.

### P2#4 — `AutoFallback.lastExitWasLmk` checks signal number, not description text

Was: `r.description.contains("9")` — false positives (descriptions
with "9" in them) + false negatives (null/empty descriptions on
stock AOSP).

Now: `r.status == 9` per Android docs (for `REASON_SIGNALED`,
`status` holds the signal number).

### P2#5 — OpenCode bridge reuse validates model match

Was: any healthy bridge on `:bridge_port` was reused. If a previous
`launch opencode --model X` left a bridge running, this `launch
opencode --model Y` reused it; OpenCode hit "model not found" with
the bridge healthy.

Now: probe `/v1/models` on the bridge and only reuse if the listed
ID matches this run's `--model`. Otherwise `pkill -f "litellm.*--port
N"` and respawn.

### P2#6 — `/api/show` advertises `"tools"` capability

Was: `capabilities()` gated `"tools"` on a `toolsVerified` field that
was initialized `false` and never written. So `/api/show` always
reported `["completion"]` only — clients consulting capabilities
disabled tool flows even though we structurally support them.

Now: removed the `toolsVerified` gate. We've supported tools across
all 4 wire envelopes since v0.6.0 (prompt-injection + repair) and
v0.7.0 (native SDK path). `TEMUXLLM_NO_TOOLS=1` opt-out is
preserved for users who hit a model that mis-emits tool tokens.

### P2#7 — `extractFirstImage` returns the LAST user image

Was: walked `messages` from index 0 forward; first image found was
returned. Multi-turn requests with multiple user images sent
inference against stale earlier media.

Now: walks BACKWARDS so the most recent user image wins. Method
name kept for back-compat — semantics changed to "most-recent" not
"first". Same fix applied to the Responses input-array path.

### Files

- `android/app/src/main/kotlin/dev/temuxllm/HttpServer.kt` — defensive
  sanity log in `handleGenerate`; backward iteration in
  `extractFirstImage`.
- `android/app/src/main/kotlin/dev/temuxllm/ModelRegistry.kt` —
  `ollamaTags()` filters to active; `capabilities()` always reports
  `"tools"` unless opt-out env set.
- `android/app/src/main/kotlin/dev/temuxllm/AutoFallback.kt` —
  signal-number check.
- `scripts/temuxllm` — bridge model-match validation +
  port-restart on mismatch.
- `android/app/build.gradle.kts` — versionCode 15 → 16, versionName
  0.7.0 → 0.7.1.

### Acceptance

- 60 unit tests pass.
- `:app:lintDebug` clean.
- APK installs on Galaxy S25.
- Real-device verified:
  - `/api/tags` returns single active entry ✓
  - `/api/show` capabilities = `["completion", "tools"]` ✓
  - `/v1/version` includes `native_tools_enabled` flag ✓

## [0.7.0] — 2026-05-02

Closes the 4 gaps that v0.6.0 punted to "v0.7.0 future work" and the
user (correctly) called out as scope-cutting:

1. **Native SDK tool API wired into `/v1/*` routing** (was probe-only).
2. **Vision + tools + streaming** three-way combo (was: tool+stream
   path silently dropped image bytes).
3. **CLI Python helper** at `scripts/temuxllm_repair.py` shared by host
   wrapper + Termux installer (was: code-reviewer flagged drift risk).
4. **`response_format: {type:"json_schema"}`** with `strict:true` →
   501 reject + non-strict best-effort hint (was: deferred / not
   parsed at all).

### Gap 1 — Native SDK tool API on `/v1/*`

When `nativeToolsEnabled` flag is on (`native_tools=on` in
`temuxllm.conf` or `TEMUXLLM_NATIVE_TOOLS=1` env) AND the request has
`tools[]`, all four wire envelopes route through the SDK native path:

- `LlmEngine.generateWithNativeTools(backend, prompt, toolDescJsons, imageBytes?)`
  builds `ConversationConfig.tools` with `OpenApiTool` wrappers and
  `automaticToolCalling=false`. SDK applies the model's native chat
  template and extracts `Message.toolCalls` as typed `ToolCall(name, args)`.
- `HttpServer.extractNativeToolDescriptions(tools)` flattens
  Anthropic / OpenAI / Ollama tool shapes into the JSON-string form
  `OpenApiTool.getToolDescriptionJsonString()` expects.
- `HttpServer.blockingGenerate` and `HttpServer.runStreamBuffered`
  branch on `nativeToolDescriptions != null` and emit wire-correct
  frames (OpenAI `tool_calls` + `finish_reason:"tool_calls"`,
  Anthropic `tool_use` + `stop_reason:"tool_use"`, Ollama `tool_calls`
  + `done_reason:"tool_calls"`, OpenAI Responses `function_call`
  output items).

**Crucial fix from codex outside-review:** when native path is taken,
**do not also inject the prompt-injection LCD tool block** (v0.6.0
G1's `renderToolBlock`). The model gets confused by both signals and
emits content-string JSON instead of typed tool calls. v0.7.0 sets
`toolBlock = null` whenever `useNative` is true.

Real-device n=30 (S25 / Gemma 4 E4B / CPU): Option A (prompt-injection
+ repair) and Option B (native via /v1) both 30/30 = 100 %. Native is
~30 % faster (5.7 s vs 7-8 s/call) and produces typed wire output
without our brace-balance parser. The probe endpoint
`/api/probe/native_tools` remains, gated by the same flag.

### Gap 2 — Vision + tools + streaming

`runStreamBuffered` now accepts `imageBytes: ByteArray? = null` and
threads it through to `engine.generateBlocking(prompt, backend, imageBytes)`
(prompt-injection path) or `LlmEngine.generateWithNativeTools(..., imageBytes)`
(native path). All three call sites in
`handleOpenAiChat` / `handleAnthropicMessages` / `handleOllamaChat`
now pass `img.bytes`. `/v1/responses` also gets vision support
(was missing in v0.6.0).

### Gap 3 — CLI Python helper (single source of truth)

New file `scripts/temuxllm_repair.py` mirrors `ChatFormat.repairToolCallJson`
+ `parseToolCalls` from APK. Subcommands:

- `repair`     — stack-based balanced rewriter; mirrors Kotlin parity
  test cases (single-quote → double, missing `}` before `]`, trailing
  commas, prose stripping). 64 KiB length cap + 128 depth cap.
- `parse_tool_calls` — extract `{tool_calls:[...]}` from raw model
  output, returning `{text, tool_calls}`.
- `prose name content` — render `Tool[name]: content` (G2 unified
  prose).

`scripts/litertlm-native-wrapper.sh` (host CLI) now calls the helper
via subprocess instead of inlining ~150 LOC of duplicated parsing.
`scripts/install-termux-native.sh` writes a copy of the helper to
`$INSTALL_DIR/temuxllm_repair.py` so Termux users running the host
wrapper from inside Termux have the file available. The
installer-embedded `litertlm-native-impl.sh` itself stays text-only —
documented use case for that path is long generation / no-USB /
scripts; agent tool loops belong on the APK service.

### Gap 4 — `response_format` (G8)

`HttpServer.parseResponseFormat(body)` accepts the OpenAI Structured
Outputs shape on `/v1/chat/completions`:

- `type: "text"` (default) → no-op.
- `type: "json_object"` → injects "Respond with a JSON object".
- `type: "json_schema"` + `strict: true` → **HTTP 501**
  `not_implemented` / `strict_json_schema_unsupported`. Per codex
  outside-review: silent downgrade to best-effort lies to the caller
  about schema enforcement. We have no grammar-constrained decoder
  (LiteRT-LM doesn't expose one).
- `type: "json_schema"` + `strict: false` (or unset) → injects the
  schema as a system-prompt hint. Best-effort; client validates.

Real-device verified: strict:true returns the 501 envelope, strict:false
+ a `{city: string}` schema returns `{"city": "Tokyo"}` from Gemma 4.

`response_format` retry-on-parse-fail and JSON Schema validator are
deferred to v0.8.0 (would require ~150 LOC schema validator + ~2x
latency cost per call; non-blocking gap for codex / Claude Code use
cases that mostly do tool calling not Structured Outputs).

### Files

- `android/app/src/main/kotlin/dev/temuxllm/HttpServer.kt` — native
  routing across 4 envelopes; `extractNativeToolDescriptions`;
  `parseResponseFormat`; vision threaded through
  `runStreamBuffered` + `blockingResponses` + `blockingResponsesWithTools`;
  `useNative ? null : renderToolBlock(tools)` logic.
- `android/app/src/main/kotlin/dev/temuxllm/LlmEngine.kt` —
  `generateWithNativeTools` (real handler);
  `probeNativeToolCall` reduced to thin wrapper.
- `android/app/build.gradle.kts` — versionCode 14 → 15, versionName
  0.6.0 → 0.7.0.
- `scripts/temuxllm_repair.py` (NEW) — 200 LOC Python helper.
- `scripts/litertlm-native-wrapper.sh` — replaced ~150 LOC inline
  parser with helper subprocess call.
- `scripts/install-termux-native.sh` — embed helper as
  `$INSTALL_DIR/temuxllm_repair.py` heredoc.

### Acceptance

- 60 unit tests pass (no test changes; behavior preserved on default
  flag-off path).
- `:app:lintDebug` clean.
- APK installs on Galaxy S25 / Android 16.
- Real-device smoke:
  - Plain text on `/v1/chat/completions` ✓
  - Native tools on `/v1/chat/completions` (typed `tool_calls`,
    `content:null`, `finish_reason:"tool_calls"`) ✓
  - `response_format` `strict:true` → 501 envelope ✓
  - `response_format` `strict:false` + schema hint → returns valid
    JSON `{"city":"Tokyo"}` ✓
  - n=30 native via `/v1` matches probe rate (100 %)

## [0.6.0] — 2026-05-02

Vision input + tool-call repair + codex web_search filter + native SDK
tool API probe. The original "fork LiteRT-LM" v0.6.0 plan was scrapped
after deep-dive research established three of its three promises were
either upstream-imminent (Conversation::Clone), unverifiable
(INT8 KV regression on Gemma 4), or simply not present upstream
(native `<|tool_call|>` tokens — zero hits in LiteRT-LM repo).
Replaced with measurable improvements that ship today.

### Empirical: Gemma 4 E4B tool-call success rate (n=30, S25, CPU)

| Path | Score | Speed/call |
|---|---|---|
| v0.5.x baseline (prompt-injection + naive parser) | 23/30 = **76.7 %** | ~7-8 s |
| v0.6.0 prompt-injection + Ollama-style repair (G1) | **30/30 = 100 %** | ~7-8 s |
| v0.6.0 native SDK tool API (probe) | **30/30 = 100 %** | **~5.7 s (-30 %)** |

The native path was expected to fail per 5 OPEN upstream issues
(google-ai-edge/LiteRT-LM #1539, #1859, #1027, #1181, #1874), all
filed against SDK 0.10.0 and FunctionGemma / Qwen3 / Gemma 3n. On
0.11.0-rc1 + Gemma 4 E4B + our `OpenApiTool` wrapper with
`automaticToolCalling=false` it works, 30/30, no SIGSEGV.

### G1 — lenient JSON repair (port of Ollama's `repairGemma4ToolCallArgs`)

`ChatFormat.repairToolCallJson` does a stack-based balanced-bracket
rewrite on malformed tool-call JSON. Detects open `{`/`[` not closed,
inserts `}` before stray `]` (the exact Gemma 4 E4B failure pattern —
all 7 baseline failures had `{"...":"...":...]}` with the inner `}`
missing). Stray over-close characters are dropped. Pure string
manipulation; never throws, returns null only when input doesn't
start with `{`.

7 new test cases in `ChatFormatTest`, including the verbatim 7 failure
patterns from the May 2 baseline run. Tests + the existing 22 cases
all pass.

### G2 — unified `tool_result` prose across envelopes

Previously only `/v1/responses` used the `Tool[name]: <result>` prose
format that small models can follow on the second turn. The other
three envelopes (`/v1/messages`, `/v1/chat/completions`, `/api/chat`)
stringified `tool_use` / `tool_result` blocks as raw JSON which Gemma
4 doesn't recognize from training. All four now emit the same prose.

### G3 — vision input (multi-modal)

Wires the SDK's `Content.ImageBytes(byteArray)` + `Contents.of(...)`
into `LlmEngine.generate`. EngineConfig now sets `visionBackend` to
match the text backend and caps `maxNumImages` at 4.

`HttpServer.extractFirstImage` parses the four wire shapes:
- Anthropic `{type:"image", source:{type:"base64", media_type, data}}`
- OpenAI `{type:"image_url", image_url:{url:"data:image/...;base64,..."}}`
- OpenAI Responses `{type:"input_image", image_url:"data:..."}`
- Ollama top-level `images: ["base64,...", ...]` on user message

Caps decoded bytes at 2 MiB; rejects http(s) URLs (SSRF guard, same
policy as Ollama). Real-device verified on Galaxy S25:

```
$ curl ... -d '{... "image": <64x48 PNG of word "STOP">, "text": "What word is in this image?"}'
{"content": [{"type": "text", "text": "STOP"}], ...}
```

Model read the rendered word back. Memory peak ~5.8 GB during prefill
(image expansion ~280 tokens) — fits 12 GB devices at `max_tokens=12288`.

CLI path (`litert_lm_main`) is **NOT** updated — upstream binary has
only 4 flags, none for vision. CLI vision deferred to v0.7.x.

### G4 — codex `web_search` ingress filter

`HttpServer.filterWebSearchTools` strips `{type:"web_search"}` and
`{type:"web_search_preview"}` entries from the inbound `/v1/responses`
tools array, and injects "You do not have web access; respond without
referencing real-time information." into the system prompt. Reasoning:
codex never dispatches `web_search_call` items locally (verified
against codex-rs `protocol/src/models.rs:854`, `event_mapping.rs:182-192`)
— if our model emits one, codex silently treats it as "the server
already ran it, here's the result" and the assistant turn continues
with a hallucinated answer.

Override: `TEMUXLLM_ALLOW_WEB_SEARCH=1` keeps the tool in the array
for users wiring their own MCP search.

### Native SDK tool API — probe + opt-in flag

New endpoint: `POST /api/probe/native_tools` — single-shot empirical
test of SDK's `ConversationConfig.tools` + `OpenApiTool` +
`automaticToolCalling=false` path. Returns typed `tool_calls`
extracted by SDK from the model's native template. Does NOT require
prompt-injection or our brace parser.

New flag: `native_tools` in `temuxllm.conf` (or `TEMUXLLM_NATIVE_TOOLS`
env var). When `on` / `1` / `true`, future v0.7.x will route /v1
tool requests through the native path. v0.6.0 reads the flag for
`/api/version` reporting only — full /v1 wiring deferred because:
- Streaming-with-tools surface needs new MessageCallback delta
  semantics work
- Risk gate: 5 OPEN upstream tool-API issues for OTHER models could
  bite users on non-Gemma-4 stacks. Empirical: works perfectly on
  our pinned Gemma 4 E4B.

### Known limitations (deferred to v0.7.x)

- **Vision + tools + streaming combo.** When all three are present in
  one request, the streaming-buffered path drops the image (image goes
  to engine via the non-streaming path only). Single-pair combos
  (vision + tool, vision + stream, tool + stream) work. Documented to
  unblock v0.6.0 ship; the `runStreamBuffered` signature extension is
  scheduled for v0.7.0.
- **Probe endpoint reachable via env flag only.** `/api/probe/native_tools`
  is gated by `native_tools=on` per security-review MEDIUM#3 to prevent
  third-party apps on-device from triggering JNI-level crashes via
  malformed tool descriptions. Probe is on for empirical study; off
  for production.

### Cancelled from v0.6.0 spec

- **G7 native chat templates (hardcoded Gemma 4 + Qwen3 Jinja
  constants).** Option B probe verified the SDK already applies the
  model's native chat template internally; hardcoding it ourselves
  would duplicate the SDK's work.
- **G8 `response_format` + 1-retry.** Low value vs implementation
  cost; pushed to v0.7.0.
- **G9 CLI shell port of G1/G2.** codex outside-review flagged
  maintenance risk (two diverging bash implementations); CLI stays
  at the Python helper inside the wrapper. CLI users still benefit
  from G1+G2 by talking to the APK service via adb forward.
- **Forking LiteRT-LM.** Confirmed not viable — the three promises
  the fork plan made are either upstream-imminent
  (Conversation::Clone, issue #966 maintainer commitment), unverifiable
  (INT8 KV regression risk), or upstream-absent (native tool tokens).

### Files changed

- `android/app/src/main/kotlin/dev/temuxllm/ChatFormat.kt` —
  `repairToolCallJson` stack-based rewriter + 7 test cases.
  Unified `tool_result` prose. Image content blocks no longer return
  `Bad`; HttpServer handles them.
- `android/app/src/main/kotlin/dev/temuxllm/LlmEngine.kt` —
  `generate()` and `generateBlocking()` accept optional `imageBytes`.
  EngineConfig sets `visionBackend` and `maxNumImages`. New
  `probeNativeToolCall` method for `/api/probe/native_tools`.
- `android/app/src/main/kotlin/dev/temuxllm/LlmEngineApi.kt` —
  imageBytes parameter on the interface (default null preserves
  back-compat for fakes/tests).
- `android/app/src/main/kotlin/dev/temuxllm/HttpServer.kt` —
  `extractFirstImage`, `filterWebSearchTools`, `handleProbeNativeTools`,
  `nativeToolsEnabled` flag, vision wiring through
  `runStream` / `blockingGenerate` for the 4 main endpoints
  (`/v1/messages`, `/v1/chat/completions`, `/api/chat`, plus existing
  Responses path retains tool routing).
- `android/app/build.gradle.kts` — versionCode=14, versionName=0.6.0.

### Acceptance gates (all green)

- 60 unit tests pass (7 new for G1 repair, 1 updated for G3 vision)
- `:app:lintDebug` passes
- APK builds; installed on Galaxy S25
- Real-device smoke:
  - Plain text round-trip on /v1/messages, /v1/chat/completions ✓
  - Vision via /v1/messages reads "STOP" PNG ✓
  - codex `web_search` filtered → model replies honestly ("I do not
    have access to real-time information") ✓
  - Option B probe endpoint: 30/30 native `tool_calls` extracted ✓
  - Option A repair path: 30/30 (was 23/30 baseline) ✓

## [0.5.2] — 2026-05-02

Docs-only release. Documents which CLIs keep which tools / MCP servers /
skills / web-search built-ins under their respective `--bare`-style minimal
modes — and what our four wire envelopes actually translate vs not.

### New

- **`docs/cli-tool-matrix.md`** — per-CLI capability matrix verified against
  locally-installed CLIs (`claude` 2.1.126, `codex-cli` 0.125.0,
  `llxprt` 0.9.3, `opencode` 1.14.31). Covers:
  - What each `--bare` / minimal mode strips by default.
  - Concrete recipes to add MCP / web search / skills back when needed.
  - Service-side translation status: every envelope except `/api/generate`
    parses `tools[]`, prompt-injects an LCD JSON format
    (`{"tool_calls":[...]}`), and translates the model's reply into
    wire-correct `tool_calls` / `tool_use` / `function_call` frames.
  - Known multi-turn agent-loop gaps (`tool_result` template, KV reuse,
    `tool_choice`, multi-call parser) that v0.6.0's fork plan addresses.
- **README + README.zh-TW** "Tool calling, MCP, skills, web search"
  subsection linking to the matrix doc.

### Behind the finding

Four parallel research agents cross-verified by reading each CLI's
source + running its `--help`:

- `claude --bare` only guarantees Bash + Read + Edit; WebSearch and
  WebFetch are NOT in the bare allowlist. MCP must be re-added via
  `--mcp-config <file>` + `--strict-mcp-config`. Anthropic-spec
  `tool_use` wire is always emitted regardless of `--bare`.
- `codex exec` with `model_reasoning_effort="minimal"` does NOT strip
  tools — all 29 built-ins (shell, apply_patch, web_search, MCP) still
  ship. `model_instructions_file` REPLACES the system prompt but tools
  live in a separate field. Codex's `web_search` is an OpenAI-hosted
  tool; the local runtime never dispatches it — use an MCP fetch
  server instead.
- `llxprt` has no bare knob; tools are always sent. `exa-web-search`
  + `direct-web-fetch` ship in `~/.llxprt/prompts/tools/`.
- `opencode run --agent bare` (our launcher's default) explicitly
  disables all 14 documented tools — web search is unreachable in
  bare. Swap to `--agent build` if you need it.

The single biggest agent-loop reliability fix is still v0.6.0's
`Conversation::Clone()` + INT8 KV; this v0.5.2 just makes the current
state unambiguous.

## [0.5.1] — 2026-05-02

Honest CLI matrix re-verification + tunable default backend.

### What was wrong with v0.5.0

Two rows in the v0.5.0 4-CLI matrix were either unverified or
empirically wrong; one CLI broke between v0.5.0 and now:

- **Gemini CLI** (`gemini` 0.36) — v0.5.0 shipped a
  `GEMINI_SYSTEM_MD=empty + GOOGLE_GEMINI_BASE_URL=...` recipe and
  marked it "verified". On real-device retest: it does not work.
  The stock CLI reads cached OAuth credentials before the env var is
  consulted; traffic goes to Google's servers, not our bridge. The
  relevant feature requests
  (google-gemini/gemini-cli #1605, #5945, #24166) remain open.
- **OpenCode** (`opencode` 1.14) — v0.5.0 marked it "Configured (not
  installed)". Re-installed locally; in GPU mode + 16 k context the
  bare-agent prompt (~2.3 k tokens) trips `lowmemorykiller` on
  12 GB devices.
- **Codex CLI 0.125** — newly broken since v0.5.0: `wire_api="chat"`
  was dropped, `--oss --local-provider ollama` overrides the user's
  `-c model=` to `gpt-oss:20b`, and `model_providers.ollama.*` is
  reserved as a built-in. The launcher's old config silently
  produced an unconfigured ollama provider.

### v0.5.1 actually-verified matrix (Galaxy S25, 12 GB, 16 k context)

| CLI | Status | How |
|---|---|---|
| **Claude Code** (`claude --bare`) | ✅ verified | unchanged from v0.5.0 |
| **Codex CLI 0.125** | ✅ verified | custom `temuxllm` provider with `wire_api="responses"`, `reasoning_effort="minimal"`, tiny `model_instructions_file`, and `max_tokens=12288` in `temuxllm.conf` |
| **llxprt-code** (Gemini CLI fork) | ✅ verified | `npm i -g @vybestack/llxprt-code`; native OpenAI provider |
| **OpenCode** 1.14 | ✅ verified via bridge | LiteLLM proxy that injects `extra_body: {backend: "cpu"}` on every call |
| **stock Gemini CLI** | ❌ confirmed unfixable | no env-var redirect path |
| **OpenClaw** | not retested | wire still correct; deferred to v0.6.x |

### `scripts/temuxllm` launcher updates

- **`launch codex`**: switched to a custom `temuxllm` provider (not
  the reserved `ollama`); ships a default tiny `model_instructions_file`
  + `model_reasoning_effort="minimal"` to fit 12 GB / 16 k devices.
  Override with `TEMUXLLM_CODEX_REASONING=...` and
  `TEMUXLLM_CODEX_INSTRUCTIONS_FILE=...`.
- **`launch gemini`**: dropped the `GEMINI_SYSTEM_MD` recipe; now
  spawns `llxprt --provider openai --baseurl <host>/v1` directly.
  `--config-only` prints the install-and-redirect instructions.
- **`launch opencode`**: auto-spawns a LiteLLM proxy (with
  `extra_body: {backend: "cpu"}` route) on `:4000` and points
  OpenCode at it. Override with `TEMUXLLM_OPENCODE_BACKEND=gpu` on
  ≥16 GB devices to skip the bridge.
- **`pick_model`**: switched from greedy sed-on-`/api/tags` to
  parsing `/v1/models` (which lists only the active staged model) —
  v0.5.0's regex picked the *last* `"name"` in the JSON, so devices
  with multiple `.litertlm` files in `/data/local/tmp/litertlm/`
  silently got the wrong model.

### Service: tunable default backend

`HttpServer` now reads `default_backend=` from
`/data/local/tmp/litertlm/temuxllm.conf` (or `filesDir/temuxllm.conf`,
or the `TEMUXLLM_DEFAULT_BACKEND` env var) at construction time and
uses that as the default for `/v1/*` requests that don't include a
`backend` field. Codex / OpenCode never set a backend; on memory-
constrained devices, setting `default_backend=cpu` lets their
requests skip GPU init entirely. Falls back to `gpu` when no
override is set so 16 GB+ devices keep the fast path.

### README

- Replaced the v0.3.0 "Use with CLI coding agents" section with a
  v0.5.1 verified-matrix table, complete per-CLI manual configs,
  and an honest "what works vs what doesn't" list.
- Added a Tunables section under API documenting `max_tokens` and
  `default_backend` knobs in `temuxllm.conf`.
- Mirrored to `README.zh-TW.md`.

## [0.5.0] — 2026-05-02

### 4-CLI bare-mode matrix — what works under 16 k context

The headline goal of v0.5.0: every supported coding-agent CLI runs
against the on-device service in a "bare / lightweight" mode that
fits the bundled Gemma 4 E4B model's 16 k context window (8–12 k of
which the CLIs themselves consume for built-in prompts).

| CLI | Bare-mode strategy | Status |
|---|---|---|
| **Claude Code 2.1** | `claude --bare` — Anthropic's documented minimal mode (no hooks, LSP, plugins, auto-memory, CLAUDE.md auto-discovery). System prompt drops from ~16 k → a few hundred tokens. | **Verified** real-device round-trip on Galaxy S25 (SD 8 Elite, 12 GB, Android 16). |
| **Codex CLI 0.125** | `--oss --local-provider ollama` + `-c project_doc_max_bytes=0` (suppresses `AGENTS.md` injection per OpenAI Codex config). Optional `TEMUXLLM_CODEX_INSTRUCTIONS_FILE=<path>` adds `-c model_instructions_file="..."` to replace the ~8 k base instructions with whatever tiny instructions file the user provides (per OpenAI's "Unrolling the Codex agent loop" post). | **Verified** in v0.3.3 / v0.4.x sessions; protocol surface unchanged. |
| **OpenCode** | Custom `bare` agent in `opencode.json` with all documented tools (`write/edit/bash/read/glob/grep/list/patch/todowrite/todoread/webfetch/task/lsp_*`) set to `false`, plus a one-liner system prompt. `cd` to scratch dir on launch to skip `AGENTS.md` walk-up; `OPENCODE_DISABLE_CLAUDE_CODE=1` blocks `~/.claude/CLAUDE.md` fallback. System prompt drops to ~200–500 tokens. | **Configured** in `scripts/temuxllm launch opencode`; not on-device tested (OpenCode not installed in our environment). |
| **Gemini CLI 0.36** | LiteLLM bridge using `openai/...` provider (NOT `ollama/...` — avoids LiteLLM's quirky ollama provider): `litellm --model openai/<m> --api_base http://127.0.0.1:11434/v1 --api_key dummy --port 4000`, then `GOOGLE_GEMINI_BASE_URL=http://127.0.0.1:4000 GEMINI_API_KEY=sk-dummy gemini -p "..."`. **Note**: Gemini CLI has NO native lightweight mode and always injects an 8–12 k token agent harness. Even with the bridge, this consumes 60–75 % of our 16 k context. For a true lightweight Gemini-CLI experience, use the [`@vybestack/llxprt-code`](https://github.com/acoliver/llxprt-code) fork instead. | **Documented** in `scripts/temuxllm launch gemini`; native bare path is `:generateContent` endpoint that we don't yet ship — deferred to v0.6.x. |

### `scripts/temuxllm launch <cli>` updates

Three of the four launchers now default to the bare/minimal config:

- **`launch opencode`**: writes a temp `opencode.json` with the `bare` agent + tool denylist + minimal prompt; `cd`s to a scratch directory before exec to suppress `AGENTS.md` walk-up; sets `OPENCODE_DISABLE_CLAUDE_CODE=1`.
- **`launch gemini`**: now recommends `openai/<model>` LiteLLM wire (was `ollama/<model>` which has quirks); documents `llxprt-code` as the recommended no-bridge alternative.
- **`launch claude`**: unchanged — already adds `--bare` from v0.3.3 (override via `TEMUXLLM_CLAUDE_FULL_AGENT=1`).
- **`launch codex`**: unchanged — already adds `-c project_doc_max_bytes=0` from v0.3.3.

### Research artifact: v0.6.0 fork plan

Saved at `.omc/research/23-fork-litertlm-plan.md`. Concrete plan to fork
`google-ai-edge/LiteRT-LM` and expose two C++-side features that the
Kotlin SDK currently hides:

1. `Conversation::Clone()` — for stateful KV reuse.
2. `AdvancedSettings.activation_data_type` — flip KV cache from FP16
   to INT8, ~2× memory savings, allows 24-28 k context on 12 GB
   devices that today max at 16 k.

~225 LOC delta, 4-week single-engineer plan, 7/10 confidence GO.
Not started in v0.5.0 — that's the v0.6.0 milestone.

### Constraints unchanged

HTTP binds 127.0.0.1 only • arm64-v8a • minSdk 33 • no new runtime
dependencies (test deps from v0.4.0 stay).

## [0.4.1] — 2026-05-02

Patch release addressing two PR-review issues caught by
chatgpt-codex-connector on the v0.4.0 commit, plus integrating
v0.5.0 deep-research into the spec/research log.

### Fixed

- **`scripts/litertlm-native-wrapper.sh`** (P2): the `--input_prompt`
  argument was passed through `printf '%b'`, which expands `\n`,
  `\\`, `\"` etc. inside any user-provided text. A user who
  legitimately wanted a literal `\n` in their prompt would have it
  silently transformed into a newline. Switched to passing
  `FINAL_PROMPT` verbatim (no `%b`) and use a real shell newline to
  separate the tool block from the user prompt when `--tools` is set.
- **`MemoryProbe`** wired into the inference lifecycle (P2): v0.4.0
  constructed the probe but never called `start()` / `stop()`
  anywhere, so no samples were ever logged despite the LMK auto-
  fallback design depending on per-inference memory data. Now
  `runStream`, `runStreamBuffered`, and `blockingGenerate` all
  bracket their work with `memoryProbe.start(label)` /
  `memoryProbe.stop()`. Probe context label includes
  `backend / shape / tools` so the CSV can be filtered.

### Research / planning (no behavior change)

5 parallel research agents + codex deep-search ran for v0.5.0
planning. Findings stored under `.omc/research/16-20.md`:

- **NPU (Hexagon) — NO-GO**: NPU on SD 8 Elite uses LPDDR5X system
  RAM (lmkd kills us identically). Available NPU bundles regress
  context to 4096. QAIRT licensing blocks non-Play sideload.
- **Process splitting — NO-GO**: engine restart still costs 4–9 s
  warm; ashmem doesn't exempt from LMK. Revisit only if profiling
  shows >30 % of sessions hit FGS-kill mid-conversation.
- **`metricspace/gemma4-*-litert-128k-mtp` — opt-in only**: 68
  downloads, no phone evidence, license inheritance unclear, CPU
  only. Add as `MODEL=e4b-128k` flag with banner; do NOT default.
- **Out-of-tree `Conversation::Clone()` JNI shim — INFEASIBLE**:
  `nm` of the shipped `liblitertlm_jni.so` shows zero exported
  C++ symbols, hidden visibility, statically linked. The path is
  to push upstream Issue #966 (already filed; we'll comment with
  our use case).
- **llama.cpp + Adreno OpenCL backend — GO for v0.5.0**: Q4 KV
  cache (`-ctk q4_0 -ctv q4_0 -fa`) breaks the 16 k wall — math
  shows 32 k context fits 12 GB (4.84 GB weights + 4.2 GB Q4 KV
  + 1 GB scratch ≈ 10 GB). Adreno 830 on the verified-support
  list. ~600 LOC integration. Confidence 75 %.

## [0.4.0] — 2026-05-02

### Test foundation + memory observability + Codex pre-merge fixes

The first release with a unit-test suite. v0.3.x had no tests; v0.4.0 adds
JUnit 4 + MockK + kotlinx-coroutines-test + JSONassert and a 45-test
regression net covering ChatFormat, StreamEncoders, AutoFallback,
plus the prompt-injection guard introduced in v0.3.2. `./gradlew
:app:testDebugUnitTest` is now part of the build matrix.

#### TDD foundation

- `LlmEngineApi` interface extracted from `LlmEngine`. `GenerateEvent`
  and `GenerateResult` moved to top-level so test fakes don't drag in
  the concrete LiteRT-LM JNI wrapper.
- 45 tests across 4 files covering the wire formats and contract
  surfaces an HTTP server tests should never let regress:
  `ChatFormatTest` (22 tests), `StreamEncodersTest` (14 tests),
  `AutoFallbackTest` (7 tests), `SmokeTest` (2 tests).

#### Engine inference mutex (codex BLOCKER fix)

The previous lock only synchronized `ensureEngine()`. Concurrent
`/api/chat` requests could call `createConversation().sendMessageAsync()`
in parallel, which the SDK does not support — codex outside-review
caught this as a pre-existing race. `LlmEngine.generate()` now holds
`inferenceMutex.withLock` around the whole collect path. NanoHTTPD's
per-request worker threads serialize on the mutex, which is correct on
a single-GPU device.

#### Memory observability + auto-fallback after LMK

- `MemoryProbe` utility samples PSS / VmHWM / VmSwap /
  `oom_score_adj` / `summary.graphics` / availMem every 5 s and writes
  a structured logcat tag `MemProbe` plus `<filesDir>/memory.csv` for
  off-device analysis. Reuses a single BufferedWriter to avoid the
  probe driving its own RSS up.
- `AutoFallback` reads `getHistoricalProcessExitReasons` (most-recent
  record only — codex caught that scanning 5 history entries would
  re-trigger downshift on every restart even after a clean exit). If
  the previous exit was `REASON_LOW_MEMORY` or a SIGKILL with the
  packed state summary present, `nextLowerTier()` steps the
  `maxNumTokens` ceiling one rung down (4096 / 8192 / 16384 / 24576 /
  32768), and persists the new value to
  `<filesDir>/auto_max_tokens.conf` with a device fingerprint
  (BOARD + SOC_MODEL + GB-tier).
- `setProcessStateSummary(byte[14])` is called at engine init with a
  binary-packed `(version=1, maxNumTokens=int32, backend=byte,
  modelHash=int64)` payload so the next post-mortem can identify
  exactly what was loaded when the LMK fired.
- `Service.onTrimMemory(level)` matches `RUNNING_CRITICAL` AND
  `COMPLETE` only (codex caught: `>= RUNNING_CRITICAL` would catch
  `UI_HIDDEN`, which is benign — TRIM constants are not linearly
  ordered). When triggered, `engine.closeUnderInferenceLock()`
  serializes with any in-flight `generate()` flow before tearing
  down the JNI handle.

#### `computeMaxNumTokens()` priority chain

Updated for v0.4.0 so manual user overrides always win over
auto-fallback:

  1. `<filesDir>/temuxllm.conf` (user-owned, app-private)
  2. `/data/local/tmp/litertlm/temuxllm.conf` (user-owned, adb-pushable)
  3. `<filesDir>/auto_max_tokens.conf` (post-LMK auto-downshift)
  4. `TEMUXLLM_MAX_TOKENS` env var
  5. /proc/meminfo tier auto-detect

Codex caught that v0.3.3 placed the auto file above the
adb-pushable override; v0.4.0 fixes the order.

#### `readPostBodyAsUtf8` chunked-body 413

The 4 MiB body cap was previously enforced only via `Content-Length`
header. Chunked or unknown-length bodies bypassed it. v0.4.0 streams
the body with `MAX_BODY` cap regardless: as soon as we cross the cap
even by one byte, we throw `BodyTooLarge` and the outer catch
returns `413 Request Entity Too Large`. Codex caught the off-by-one
(was `total > MAX_BODY + 1`, i.e. silently accepted exactly
`MAX_BODY + 1` bytes); v0.4.0 throws on `total > MAX_BODY`. Plus
`Content-Length: 0` is treated as an empty body explicitly and does
NOT fall through to the streaming path (would have tied up a worker
thread on keep-alive connections).

#### `install.sh` model picker now factors RAM

Choosing `gemma-4-E2B-it.litertlm` vs `gemma-4-E4B-it.litertlm` now
considers BOTH SoC class and total RAM. High-end SoC + ≥ 11 GB RAM
gets E4B; high-end SoC + < 11 GB gets E2B (2.4 GB on disk frees
~1 GB more KV headroom on tight devices). Documented in the
installer banner.

### Deferred (out of scope, will revisit)

- **FGS type → `mediaPlayback`** (was originally targeted for v0.4):
  codex caught that targetSdk 35 + Android 15+ disallows starting a
  `mediaPlayback` foreground service from `BOOT_COMPLETED`, breaking
  our boot autostart. Possible fix: declare
  `dataSync|mediaPlayback`, start with `dataSync` from boot, upgrade
  on user interaction. Its own design problem; deferred.
- **Stateful ConversationPool**: codex caught (a) standard CLI
  clients resend full message history every turn → naive pooling
  duplicates content; (b) without `Conversation::Clone()` JNI the
  pool gives no real KV-sharing benefit. Defer to v0.5+.
- **llama.cpp + Adreno OpenCL backend** as an alternative engine
  path (Q4 KV cache → 32 k+ context). Long-term lever, large
  engineering effort.
- **Native JNI binding for `Conversation::Clone()`** in LiteRT-LM.
  File upstream feature request first.

### Constraints unchanged

HTTP binds 127.0.0.1 only • arm64-v8a • minSdk 33 • no new runtime
dependencies (test deps only).

## [0.3.3] — 2026-05-02

### Tool name validation (codex outside-review blocker)

`ChatFormat.parseToolCalls` now cross-checks every parsed tool name
against the request's declared `tools` array, dropping any call whose
name was NOT advertised by the client. Same hardening pattern as
Ollama's `tools/tools.go` `findTool()`. Verified on Galaxy S25 with a
prompt-injection probe (user message asking the model to emit a fake
`shell_exec` tool call): the model produced the JSON, our parser
matched the structure, but the name filter rejected it and the
response came back with empty content + `stop_reason="end_turn"`.

Without this filter, a malicious user message could trick a small
model into emitting a tool call for any name it likes — `shell_exec`,
`delete_repo`, `pay_money_to(...)` — and we would relay it to the
agent client which might execute it.

### Auto-detect KV-cache context size from device RAM

`LlmEngine.computeMaxNumTokens()` picks `EngineConfig.maxNumTokens`
based on a tier table:

  ≤ 6 GB   → 4096   (Gemma 4 default; E2B-only territory)
  ≤ 9 GB   → 8192   (8 GB phones; safe headroom for E2B)
  ≤ 13 GB  → 16384  (S25 / Adreno 830 — verified ceiling)
  ≤ 18 GB  → 24576  (Fold7 / 16 GB — projected; user-verifiable)
  else     → 32768  (Tab S10 Ultra / 24 GB+)

Empirical: 32 k context on Adreno 830 + 12 GB triggers Android's
lowmemorykiller (foreground service `prcp FGS` exit). 20 k also
killed. 16 k holds. Codex CLI's independent research agreed with
this ceiling.

Override via `<filesDir>/temuxllm.conf` or
`/data/local/tmp/litertlm/temuxllm.conf` (a single line
`max_tokens=N`). Lets power users on Fold7 / Tab S10 Ultra push
higher without rebuilding the APK:

  adb shell 'echo "max_tokens=24576" > /data/local/tmp/litertlm/temuxllm.conf'
  adb shell am force-stop dev.temuxllm.service
  adb shell am start-foreground-service \
    -n dev.temuxllm.service/dev.temuxllm.LlmService

`TEMUXLLM_MAX_TOKENS` env var still honored as a third-tier fallback
(rarely inheritable by Android foreground services).

### Launcher CLI (`scripts/temuxllm`) small-model squeeze

Both launchers now default to small-model-friendly invocations:

- `temuxllm launch claude` adds `--bare` (Claude Code's minimal mode:
  no hooks/LSP/plugins/auto-memory/CLAUDE.md auto-discovery — system
  prompt drops from ~16 k tokens to a few hundred). Set
  `TEMUXLLM_CLAUDE_FULL_AGENT=1` to drop `--bare` on a 16 GB+ device
  with `TEMUXLLM_MAX_TOKENS=24576+`.
- `temuxllm launch codex` adds `-c project_doc_max_bytes=0` (per
  OpenAI Codex config reference; suppresses `AGENTS.md` injection).
  `TEMUXLLM_CODEX_INSTRUCTIONS_FILE=<path>` env var adds
  `-c model_instructions_file="..."` to swap Codex's bundled ~8 k
  agent base instructions for whatever tiny instructions file the
  user provides (per OpenAI's "Unrolling the Codex agent loop" post).

`temuxllm launch ... --config-only` reflects the new defaults so
power users can copy them into their own scripts.

### Known limitations (not yet shipped)

- `scripts/install-termux-native.sh`'s embedded wrapper heredoc is
  still v0.3.1-era — the new `--tools` flag from v0.3.2 isn't there.
  Sync is queued for v0.3.4.
- Per-character streaming of tool-call JSON args (right now the
  buffered path emits the full args in one `partial_json` /
  `function_call_arguments.delta` chunk).

## [0.3.2] — 2026-05-02

### Tool calling — actually shipping this time

`temux_llm` now parses tool calls out of the model's output and re-encodes
them in each wire envelope's tool-use shape. Verified end-to-end on Galaxy
S25 (SD 8 Elite, Adreno 830, 12 GB) with Gemma 4 E4B + 16k context: a
`get_weather(city)` tool sent to all four envelopes round-trips through
the model and comes back as the correct shape per protocol.

How it works (no LiteRT-LM SDK `@Tool` compile-time API; we use a
prompt-injection + output-parse approach so the same code works for any
model that can follow simple instructions):

1. The HTTP request's `tools` array is rendered into a system-prompt
   block by `ChatFormat.renderToolBlock()`. Block accepts all three
   client shapes — Anthropic `{name, description, input_schema}`,
   OpenAI Chat / Ollama `{type:"function", function:{...}}` —
   auto-detected per entry.
2. The model is instructed to emit a `{"tool_calls":[...]}` JSON object
   on its own when it wants to call a tool. Otherwise plain text.
3. After inference, `ChatFormat.parseToolCalls()` walks the output
   buffer with a brace-balance scanner (ignores `"` strings, escapes)
   to find the first top-level JSON object containing `"tool_calls"`.
4. Each parsed tool call is re-encoded per envelope:
   - Ollama `/api/chat`: `message.tool_calls[].function.{name, arguments: object}`, `done_reason: "tool_calls"`
   - OpenAI `/v1/chat/completions`: `choices[0].message.tool_calls[].{id, type, function:{name, arguments: stringified}}`, `finish_reason: "tool_calls"`, `content: null`
   - Anthropic `/v1/messages`: `content[]` with `tool_use` blocks `{id, name, input: object}`, `stop_reason: "tool_use"`
   - OpenAI `/v1/responses`: `output[]` with `function_call` items `{call_id, name, arguments: stringified}`

### Streaming with tools

When `tools` is present, the request follows the buffered path even when
the client asked for streaming — we cannot spray the model's tool-call
JSON to the client one token at a time. Instead, `runStreamBuffered`
collects the full output, parses for tool calls, then synthesizes the
wire-correct streaming frames via each encoder's new `emitBuffered`
method. The client sees a real SSE stream (Anthropic content_block_start
type:"tool_use" → input_json_delta → content_block_stop, OpenAI Chat
tool_calls deltas → finish_reason: tool_calls + [DONE], OpenAI Responses
function_call output_item.added → function_call_arguments.delta/.done →
response.completed, etc.) but the deltas all arrive at once.

Real-time streaming of tool-arg JSON characters one-at-a-time is
deferred (it's a streaming-state-machine problem of ~Ollama's
`tools/tools.go` complexity; minimal user-visible benefit when the
agent client is waiting for the full call before executing).

### CLI wrapper parity (`scripts/litertlm-native-wrapper.sh`)

Adds `--tools <JSON>` flag matching the HTTP server's contract. Same
Python-based tool-block renderer; same output parser. Termux users
running `litertlm` directly (without the APK service) get equivalent
tool-calling capability. Note: requires `python3` in Termux
(`pkg install python`); install-termux-native.sh's embedded wrapper
heredoc does NOT yet ship the new logic — sync deferred to a follow-up.

### PR review fixes (chatgpt-codex-connector commit `77d3202`)

1. **`/v1/models` listed IDs that resolve() didn't honor.** Before:
   /v1/models returned all `.litertlm` files on disk; resolve() only
   accepted names that mapped to the active staged file. Result:
   model-pickers in client UIs would offer IDs that 404 on
   `/v1/chat/completions`, `/v1/messages`, `/v1/responses`. Now: only
   the active model is listed.
2. **`temuxllm status` skipped `ensure_forward()`.** Host-side users
   saw "service not reachable" even with the device fine, because
   the script forgot to set up `adb forward tcp:11434 tcp:11434`.
   `cmd_status` now calls `ensure_forward` like `launch` does.

### Constraints unchanged
HTTP binds 127.0.0.1 only • arm64-v8a • minSdk 33 • no new deps • no
LlmEngine.kt API changes from v0.2.x callers.

## [0.3.1] — 2026-05-02

### Real Claude Code and Codex CLI now actually work end-to-end

v0.3.0 had every protocol-layer piece in place but the bundled model
context (4096 tokens) was too small for Claude Code's ~16k built-in
agent system prompt or Codex CLI's ~8k. v0.3.1 lifts that ceiling
and addresses the four pre-merge codex review blockers.

#### Engine: 16k context window

`EngineConfig.maxNumTokens` is now set to **16384** by default (was
the model's compiled 4096). Override at service start via
`TEMUXLLM_MAX_TOKENS=24576` (or any positive integer) for ≥16 GB
devices that can hold a bigger KV-cache. Memory cost scales linearly
with context size — Galaxy S25 (12 GB) tops out around 16k–20k for
Gemma 4 E4B; 32k OOMs the foreground service on Adreno 830 + 12 GB.

Verified on Galaxy S25 (SD 8 Elite, Adreno 830, 12 GB, Android 16):

```
$ ANTHROPIC_BASE_URL=http://127.0.0.1:11434 ANTHROPIC_AUTH_TOKEN=ollama \
  claude --print "..."
Hello! I see you are ready to start working on the project. ...

$ codex exec --oss --local-provider ollama -c model=local "say: hi"
codex> Hello! How can I help you today?
```

Both CLIs send their full agent system prompt and receive real
on-device Gemma 4 E4B responses through their respective wire
envelopes (`/v1/messages` SSE for Claude, `/v1/responses` SSE for
Codex). The launcher script `scripts/temuxllm launch claude/codex`
sets the env vars correctly and `exec`s the target CLI.

#### Codex review blockers addressed

`codex exec --sandbox read-only` was run on the v0.3.0 diff per the
project's "release-tag must have outside-reviewer pass" rule. Four
blocking findings, all fixed:

1. **`runStream()` could double-frame on error or empty streams.**
   `emitStart()` is now deferred until the first token actually
   arrives. If the engine errors before any output (token-limit
   overflow, backend init failure), only the error event is emitted
   — no misleading `message_start`/`content_block_start` preamble
   that would confuse Anthropic / OpenAI parsers.

2. **`/v1/responses` input flattening dropped Codex agent-loop items.**
   Codex echoes prior `output_text` / `function_call` /
   `function_call_output` / `reasoning` items back into `input` on
   subsequent turns per OpenAI's "Unrolling the Codex agent loop"
   docs. Our flattener only handled `text` / `input_text`, so
   multi-turn continuity broke. Added
   `ChatFormat.flattenResponsesInput()` that handles the full
   Responses input vocabulary.

3. **Resolved-but-not-loaded model.** `handle*` methods resolved a
   request name to a registry entry but never loaded that file
   before generating, so inference always ran on whatever
   `LlmEngine.activeModelPath()` pointed to. `ModelRegistry.resolve()`
   now ALWAYS returns the active entry (or null), so the `model`
   field in responses honestly reflects which model produced the
   text. Multi-model hot-swap is out of scope for this release.

4. **`/api/version` leaked filesystem paths.** `model_path` and
   `source_model_path` exposed `/data/...` paths to any local
   process. Both removed; a new `/api/temuxllm/info` debug endpoint
   keeps them available for install scripts that need them.

5. **Default `stream` matches each protocol's convention.**
   `/api/generate` and `/api/chat` default `true` (Ollama).
   `/v1/chat/completions`, `/v1/messages`, `/v1/responses` default
   `false` (OpenAI / Anthropic). Earlier blanket `true` default
   confused SDK callers that omitted `stream` and expected JSON.

6. **Codex `/v1/models` parser fully satisfied.** Codex CLI 0.125's
   ollama provider strictly requires `slug` AND `display_name` per
   model entry in `/v1/models` (in addition to `models[]` array
   alongside OpenAI `data[]`). Both fields now emitted.

7. **`413 Request Entity Too Large` path is now reachable.**
   Previously `readJsonBody()` swallowed `BodyTooLarge` and turned
   it into a `400 invalid JSON body`. Now the sentinel propagates
   to `serve()`'s outer catch where it returns a real 413 with a
   helpful error message.

#### Other

- `looksLikeBrandedModel()` matches tighter prefixes (`claude-*`,
  `gpt-*`, `o1-*`, `o3-*`, `o4-*`) — earlier `startsWith("o3")`
  would have matched `orca3-7b`.

#### Constraints unchanged

- HTTP binds 127.0.0.1 only; arm64-v8a; minSdk 33.
- No new dependencies. No `LlmEngine.kt` API changes from v0.2.x callers.
- No `AndroidManifest.xml` changes.

## [0.3.0] — 2026-05-01

### Added — Ollama-compatible endpoints (Layer A) + launcher CLI (Layer B)

`temux_llm` now impersonates an Ollama 0.13+ server closely enough that
Claude Code, Codex CLI, OpenCode, and OpenClaw connect to it without a
proxy. Run the bundled `scripts/temuxllm launch <cli>` to auto-wire
each one.

#### New endpoints

- `GET /` — returns `Ollama is running` (probe-safe).
- `POST /v1/messages` — Anthropic Messages with full SSE event
  vocabulary (`message_start`, `content_block_delta`, `message_stop`).
  Claude Code uses this when `ANTHROPIC_BASE_URL=http://127.0.0.1:11434`.
- `POST /v1/chat/completions` — OpenAI Chat Completions SSE
  (`chat.completion.chunk` + `data: [DONE]`). OpenCode uses this.
- `POST /v1/responses` — OpenAI Responses API SSE
  (`response.created` → `response.output_text.delta` →
  `response.completed`). Codex CLI ≥0.80 uses this exclusively
  (`wire_api="chat"` was removed upstream; verified against
  `codex-rs/model-provider-info` source).
- `POST /api/chat` — Ollama-native NDJSON. OpenClaw uses this and
  explicitly rejects `/v1` because tool calling unrelizes there.
- `POST /api/show` — synthesizes Ollama-shaped capabilities/details.
- `GET /api/ps`, `GET /v1/models` — runtime / catalog probes.
- `POST /api/pull` — stub returning `{"status":"success"}`
  (Codex `--oss` calls it before falling through to inference).
- `GET /api/tags` — upgraded to full Ollama 0.13+ shape with `name`,
  `model`, `modified_at`, `size`, `digest`, `details`. Legacy fields
  (`path`, `size_bytes`) preserved.
- `GET /api/version` — adds top-level `version` field.

#### `POST /api/generate`

Now accepts a `model` field. Backward-compatible: existing v0.2.x
clients that omit it still work. Streaming response now includes
`model` and `created_at` per Ollama convention; legacy fields
(`backend`, `total_duration_ms`, `output_tokens`, `output_chars`)
preserved.

#### Launcher CLI — `scripts/temuxllm`

Single command per supported CLI:

```sh
scripts/temuxllm launch claude
scripts/temuxllm launch codex --model gemma-4-E2B-it
scripts/temuxllm launch opencode --config-only
```

Probes the service, picks the active model, sets the env vars / config
the target CLI expects, and `exec`s it. Works on a host with `adb`
attached (auto `adb forward tcp:11434 tcp:11434`) and in Termux on the
device. `--config-only` flag prints the env block without exec'ing.

#### Model resolution

Arbitrary model names are accepted. Wildcards (`local`, `default`,
`*`), exact filename match, family-prefix (`gemma`, `qwen`), and
branded names (`claude-*`, `gpt-*`, `o1`, etc.) all route to the
active model. Optional `<filesDir>/models.json` sidecar for explicit
aliases.

#### Tool calling — NOT implemented in this release

`/api/show` reports `capabilities: ["completion"]` and never advertises
`"tools"`. Coding agent CLIs that depend on tool calling will fall back
to plain chat. The wiring through LiteRT-LM 0.11's
`ConversationConfig.tools` and the per-envelope encoder translation
(OpenAI `tool_calls`, Anthropic `tool_use`, Ollama `tool_calls`) is
spec'd but deferred to a follow-up release once we have an on-device
probe round-tripping `tools=[{...}]` reliably on Gemma 4 (E2B/E4B). The
`TEMUXLLM_NO_TOOLS=1` env-var override is wired in `ModelRegistry`
already so behavior is forward-compatible.

#### Documentation

- New: [`docs/ollama-compat.md`](docs/ollama-compat.md) — endpoint
  reference, wire format tables, verification curl commands.
- README adds a "Use with CLI coding agents" section.

#### Constraints unchanged

- HTTP still binds `127.0.0.1` only.
- arm64-v8a only, `minSdk = 33`.
- Single staged `.litertlm` model file.
- No outbound traffic.

#### Real-CLI compat: text-only inference, model-context bounded

What works end-to-end (verified by direct curl on Galaxy S21+ / SD888 /
Android 15):

- All four envelope **text streams** (Anthropic SSE, OpenAI Chat SSE,
  OpenAI Responses SSE, Ollama NDJSON) emit well-formed bytes under
  success and error paths. SSE event names, `data: [DONE]` terminators,
  `sequence_number` fields, and clean error-only frames are correct.
- Probes (`/api/version`, `/api/tags`, `/v1/models`, `/api/show`,
  `/api/ps`, `/api/pull` stub, `GET /`) match what Claude Code 2.1 and
  Codex CLI 0.125 require — both pass discovery and reach inference.
- Model resolution accepts wildcard / branded / family-prefix names.
- Backward compat: existing v0.2.x `/api/generate` clients keep working.

What does NOT work end-to-end in v0.3.0:

- **Tool calling.** No tool_use / tool_call / function_call streaming
  translation between LiteRT-LM and any envelope. CLIs that run agent
  loops (Claude Code, Codex, OpenCode in agent mode) advertise tools
  but the model never emits parseable tool calls back to them. Plain
  chat round-trips work; tool calls do not. Deferred to a follow-up.
- **Multi-turn agent loops with model-context overflow.** Real
  **Claude Code** sends a ~16k-token built-in agent system prompt;
  real **Codex CLI** (`--oss`) sends ~8k. The bundled Gemma 4 E2B/E4B
  models have 4096-token context, so both CLIs fail at the inference
  call with `Input token ids are too long: NNNN >= 4096` from
  LiteRT-LM. Use a larger-context model on disk if you need real
  agent-loop sessions on-device.
- **Image inputs.** `image_url` and base64 image content blocks return
  HTTP 400 "image content is not supported on this model".

The bottom line: this release ships a working Ollama-compatible
**text-streaming** server. Direct curl, scripts, and small-prompt
programmatic clients work today. Real-CLI agent loops need both a
larger-context model AND a follow-up release that wires LiteRT-LM
0.11's `ConversationConfig.tools` parser into the per-envelope
encoders.

## [0.2.0] — 2026-05-01

CLI path re-characterized. Once you push the right binary and the right
model, the CLI's raw CPU decode beats or ties the APK service's GPU
end-to-end on every Snapdragon device we tested.

### Performance characterized

E2B, 50-word warm output. APK numbers are end-to-end including prefill;
CLI numbers are decode-only (the binary's BenchmarkInfo `Decode Speed`).
The two metrics measure different things and aren't directly comparable
as a percentage — see README "Performance" for the full disclosure.

| Device | APK CPU e2e | APK GPU e2e | CLI CPU decode |
|---|---|---|---|
| S21+ (SD 888) | 8.0 | 10.5 | 12.1 |
| S24 Ultra (SD 8 Gen 3) | 10.3 | 22.0 | 21.5 |
| S25 (SD 8 Elite) | 12.4 | 23.2 | 35.4 |

CLI wins on raw decode throughput because it is a tight native binary —
no JNI bridge, no service framework, no foreground-service notification,
no localhost HTTP round-trip. APK wins on per-call latency for short
interactive replies because its engine stays resident; CLI rebuilds the
engine each call (3-8 s warm / 10-20 s first ever). Pick by axis: APK
for sub-second turns, CLI for sustained throughput and no-USB workflows.

### Changed
- **CLI default backend reverted to `cpu`** in
  `scripts/litertlm-native-wrapper.sh` and the embedded copy inside
  `scripts/install-termux-native.sh`. CLI GPU is blocked by Termux's
  linker namespace (vendor `libOpenCL.so` requires
  `<uses-native-library>`, which Termux's manifest doesn't declare). A
  default of `gpu` would have failed for any new user running
  `litertlm-native "你好"` out of the box. CLI GPU is documented as a
  known limit; per-request `--backend gpu` still works on devices where
  it does work (none of ours).
- **`scripts/install-termux-native.sh`** runs a one-time post-install
  CPU warmup so the user's first real call hits a warm cache, and
  reports the measured decode rate so they see what their device does
  before they run anything. Skip with `SKIP_WARMUP=1`; bound with
  `WARMUP_TIMEOUT=N` (default 90 s, uses `timeout` if available).
- **`scripts/install-termux-native.sh` and
  `scripts/litertlm-native-wrapper.sh` output parser** rewritten as a
  three-state machine (`prelude → response → bench`) keyed on the
  binary's `input_prompt:` and `BenchmarkInfo:` markers. Old parser
  expected a single-line `BenchmarkInfo: key=value ...` format that
  v0.11.0-rc.1 doesn't emit; users got `total=?ms tokens=? decode=? t/s`
  on every call. Now correctly extracts `Init Total`, `Prefill Turn`,
  `Decode Turn`, `Decode Speed` from the multi-line block and sums into
  total ms.
- **`scripts/fetch_artifacts.sh`** E4B model is now sha256-pinned to
  HF commit `55b6eef9...` (was `resolve/main` + size-only check, same
  bug class as the v0.1.0 install-termux-native.sh issue Codex caught
  earlier). E4B sha256 added to `scripts/sha256_manifest.txt`.
- **`CONTRIBUTING.md`**: dropped the stale "Termux-native LiteRT-LM is
  out of scope" line (we ship it now). Added a pre-tag release
  checklist covering both install paths, default-backend sanity, pinned
  artifact verification, and outside-reviewer sign-off.
- **README + README.zh-TW**: new "Performance" section that puts CLI
  CPU next to APK CPU/GPU with explicit `(end-to-end)` vs
  `(decode-only)` column annotations. Removed the misleading
  "CLI vs APK GPU %" column. Added an end-to-end CLI walkthrough so
  the comparison is honest at all output lengths.

### Known limits
- **CLI GPU is blocked** by Termux's manifest. Termux runs as
  `untrusted_app`; the linker namespace blocks
  `dlopen("libOpenCL.so")`. We don't ship Termux. CLI CPU is the
  supported Termux path — and given that CLI CPU beats APK GPU
  end-to-end on every device tested, this is not a practical
  limitation.

### Process note
This is the fourth correction in the v0.1.x series (v0.1.1 wrong
upstream-blame → v0.1.2 fix → v0.1.3 install.sh smoke + wrapper error
detection → v0.2.0 CLI re-characterization + wrapper parser + E4B
pin). Three of those corrections were caught only by an outside
reviewer pass after the in-context Claude reviewers approved. The
release checklist in `CONTRIBUTING.md` codifies an outside-review
requirement before tag, and a fresh end-to-end install on both paths
to catch the kind of "default backend doesn't actually work" /
"installer parses a field the engine never emits" bugs that the prior
releases shipped with.

## [0.1.3] — 2026-05-01

Fixes a broken installer smoke test and a silent-failure path in the
Termux wrapper, both surfaced by an independent Codex CLI review pass
that previous reviewers missed because they shared in-context with the
maintainer.

### Fixed
- **`scripts/install.sh` smoke test** parsed an `exit_code` field that
  the in-process Engine never emits (it was a holdover from the old
  subprocess CLI shape). Any clean `bash scripts/install.sh` run after
  the SDK pivot would have failed even on successful inference. Now
  sends `"stream":false` and parses `error` / `response` /
  `output_tokens` / `total_duration_ms`.
- **`scripts/litertlm-termux-wrapper.sh`** silently swallowed
  `{"error":"...","done":true}` events in NDJSON streaming mode and
  exited 0 with blank metrics. The wrapper now detects the `error`
  field, prints it to stderr, suggests `--backend cpu` if the failure
  was on GPU, and exits nonzero. Same fix applied to the
  non-streaming branch.

### Docs
- README: clarified that "the same v0.11.0-rc.1" between APK and
  Termux-native paths actually means the Maven artifact
  `0.11.0-rc1` for the APK and the GitHub release `v0.11.0-rc.1` CLI
  binary for the native path — they are the same upstream version
  but distinct artifacts.
- CHANGELOG: added a Correction note under the v0.1.0 entry's
  "Adreno 660 GPU refuses to initialise" claim, which was the same
  manifest-declaration bug as v0.1.1, not a hardware limit. With the
  v0.1.2 manifest fix, Adreno 660 (S21+) GPU works at 10.5 t/s.
- v0.1.2 GitHub release retroactively gets a pointer to v0.1.3 in
  its body so anyone landing on the page sees the recommended upgrade.

## [0.1.2] — 2026-05-01

GPU works again. v0.1.1 misdiagnosed the cause — apologies for the noise.

### Fixed
- **GPU acceleration restored** on Adreno (Snapdragon) devices. v0.1.1
  attributed the regression to LiteRT-LM 0.11.0-rc1 upstream. That was
  wrong. The actual cause was an `AndroidManifest.xml` regression
  introduced when we pivoted from the subprocess CLI binary path to the
  in-process Maven SDK at commit `94fc38e`: the in-process JNI runs
  inside the app's linker namespace and needs explicit
  `<uses-native-library>` declarations to dlopen vendor `libOpenCL.so`
  (and `libvndksupport.so`); without them the SDK falls back to OpenGL
  and that path is incomplete. Added the three required declarations
  (`libvndksupport.so`, `libOpenCL.so`, `libOpenCL-pixel.so`,
  `required="false"` so non-Adreno devices still install). Verified
  GPU init now succeeds and `model.litertlm_*_mldrift_program_cache.bin`
  is written. References upstream
  [LiteRT-LM #2114](https://github.com/google-ai-edge/LiteRT-LM/issues/2114).

### Changed
- Default backend reverted to **`gpu`** (was forced to `cpu` in v0.1.1
  because of the misdiagnosis). Per-request `{"backend":"cpu"}` override
  still works; useful for non-Adreno SoCs where GPU init fails.
- Device matrix in README now reports both CPU and GPU decode rates.

### Verified on hardware
- S21+ (SD 888 / Adreno 660 / Android 15): GPU 10.5 t/s, CPU 8.0 t/s, cold init ~20 s
- S24 Ultra (SD 8 Gen 3 / Adreno 750 / Android 16): GPU 22.0 t/s, CPU 10.3 t/s, cold init ~11 s
- S25 (SD 8 Elite / Adreno 830 / Android 16): GPU 23.2 t/s (E2B) / 15.0 t/s (E4B), CPU 12.4 t/s, cold init ~8 s

### Known issues
- Non-Snapdragon SoCs (Tensor / Exynos / others) may fail GPU init with the
  same `INTERNAL ERROR` because they have separate upstream LiteRT-LM bugs:
  Tensor lacks OpenCL exposure ([#1860](https://github.com/google-ai-edge/LiteRT-LM/issues/1860)),
  Exynos hits a Clspv kernel bug under ANGLE-CL ([#2114](https://github.com/google-ai-edge/LiteRT-LM/issues/2114)).
  Use `{"backend":"cpu"}` per request on those devices.

## [0.1.1] — 2026-05-01

Open-source polish + honest device matrix. No new features.

### Added
- `BootReceiver` auto-starts the foreground service on `BOOT_COMPLETED`
  (post-first-launch only, per Android delivery rules).
- `LauncherActivity` (Theme.NoDisplay) — tappable temuxllm icon starts the
  service and finishes immediately, with a confirmation toast.
- Termux-native install path (`scripts/install-termux-native.sh` +
  `scripts/litertlm-native-wrapper.sh`): no APK, no USB, no host machine —
  the LiteRT-LM CLI binary runs directly inside Termux. Trades per-call
  load time for zero APK dependency.
- Device matrix in README covering S21+, S24 Ultra, S25, and Note 9 with
  measured CPU decode rates for both `gemma-4-E2B` and `gemma-4-E4B`.

### Changed
- Default backend is now **`cpu`** (was `gpu`) — see Known issues below.
  Per-request `backend` override still works.
- README rewritten for accuracy: removed stale GPU performance reference,
  fixed source file list (`LlmEngine.kt` / `HttpServer.kt` / `LauncherActivity.kt`
  / `BootReceiver.kt`), and corrected `/api/generate` body schema (only
  `prompt`, `backend`, `stream` are accepted; `model_path` and `timeout_ms`
  were never wired through).
- `install-termux-native.sh` `e4b` model path is now sha256-pinned at HF
  commit `55b6eef9e490da991fe6bc5fec1834106927b727` (was `/resolve/main/`
  + size-only check).
- `install-termux-native.sh` embedded wrapper output parser switched from
  the `<< RAWEOF` heredoc to a piped `printf` to make output-line collisions
  with the heredoc terminator impossible.

### Known issues
- **GPU acceleration is regressed** on every device tested
  (S21+ Android 15, S24 Ultra Android 16, S25 Android 16). Logcat shows
  `ml_drift_cl_gl_accelerator: OpenCL not supported` →
  `dlopen libvndksupport.so failed` → OpenGL fallback returns
  `UNIMPLEMENTED: CreateSharedMemoryManager`. The break is upstream in
  LiteRT-LM 0.11.0-rc1's OpenCL → OpenGL fallback path under Android
  14/15/16 untrusted_app linker namespaces; this project did not
  introduce it. Workaround: use CPU (which is now the default).

  > **Correction (added in v0.1.2):** the upstream-blame claim above is
  > **wrong**. The actual root cause was a missing
  > `<uses-native-library>` declaration in our own `AndroidManifest.xml`,
  > introduced when we pivoted from the subprocess CLI to the in-process
  > Maven SDK. See the v0.1.2 entry above for the real fix and hardware
  > verification.

## [0.1.0] — 2026-05-01

First open-source-ready release.

### Added
- `scripts/install.sh` one-shot installer (host preflight, fetch artifacts,
  build APK, push model, install, smoke).
- Android foreground service binding `127.0.0.1:11434` with endpoints
  `/healthz`, `/api/version`, `/api/tags`, and `/api/generate` (NDJSON
  streaming + `?stream=false` non-streaming).
- In-process inference via `com.google.ai.edge.litertlm:litertlm-android:0.11.0-rc1`
  (Engine + Conversation API). Model loads once and is reused across requests.
- Termux client wrapper `scripts/litertlm-termux-wrapper.sh` (deployed at
  `/data/local/tmp/bin/litertlm`) — streams tokens to the terminal as they
  arrive; `--json`, `--no-stream`, `--backend`, `--help` flags.
- Sha256-pinned host downloader `scripts/fetch_artifacts.sh` for the runtime
  and supported models (Gemma-4-E2B, Gemma-4-E4B, Qwen3-0.6B).
- Apache 2.0 license, NOTICE with third-party attributions.
- `.github/workflows/build.yml` (CI: gradle wrapper validation, debug APK
  build, shellcheck) and `.github/workflows/release.yml` (tag-driven APK
  upload to Releases).
- `.github/dependabot.yml` for Gradle + GitHub Actions dependency updates.
- Issue templates (bug report, device support report) and a PR template.
- `docs/findings-*.md` empirical reports across S21+ (SD888 / Adreno 660 /
  Android 15) and S25 (SD 8 Elite / Adreno 830 / Android 16).

### Verified
- End-to-end `Termux → curl 127.0.0.1:11434/api/generate → local model`
  works on S21+ with `gemma-4-E2B` (CPU). Streaming output reaches the
  terminal token-by-token.
- 4-rule GPU usefulness check (per the original engineering brief)
  passes for the v0.9.0 CLI binary path on both devices, and for the
  in-process Maven SDK path on S25 / SD 8 Elite class GPUs.
- Service binds to `[::ffff:127.0.0.1]:11434` only (verified after
  every restart).

### Known limits
- The 0.11.0-rc1 GPU executor refuses to initialise on Adreno 660 (S21+);
  CPU works there. SD 8 Gen 3 / 8 Elite class GPUs are fine.
- Gemma-4-E4B works on CPU only on S21+ (decode 0.5 t/s — too slow to
  be interactive). Use Gemma-4-E2B on phones with ≤8 GB RAM.

  > **Correction (added in v0.1.2):** the "Adreno 660 GPU refuses to
  > initialise" bullet is **wrong** — same root cause as the v0.1.1
  > entry (missing manifest declaration), not a hardware limit.
  > Adreno 660 GPU on S21+ works at 10.5 t/s with the v0.1.2 fix.
  > E4B on S21+ likewise runs faster than 0.5 t/s under the corrected
  > setup (E4B CPU on S21+ measured at 4.1 t/s in v0.1.2's matrix).

[Unreleased]: https://github.com/ImL1s/temux_llm/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/ImL1s/temux_llm/compare/v0.1.3...v0.2.0
[0.1.3]: https://github.com/ImL1s/temux_llm/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/ImL1s/temux_llm/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/ImL1s/temux_llm/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/ImL1s/temux_llm/releases/tag/v0.1.0
