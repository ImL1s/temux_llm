# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

(no unreleased changes)

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
