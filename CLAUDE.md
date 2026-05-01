# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A localhost-only on-device LLM service for Android, built around
LiteRT-LM 0.11.0-rc1. Two install paths share the same models but use
different runtimes:

- **APK path** — a foreground `LlmService` (Kotlin) that loads a model
  via the in-process Maven SDK (`com.google.ai.edge.litertlm:litertlm-android`),
  exposes `127.0.0.1:11434` via NanoHTTPD, and stays resident. Engine
  is built once per service start; per-call warm latency is sub-second.
  GPU works on Adreno (Snapdragon).
- **Termux-native path** — `litert_lm_main` CLI binary invoked per call
  by a thin shell wrapper. No Android service. Engine rebuilt per call
  (~3-8 s warm, ~10-20 s first ever after xnnpack cache build). Steady-state
  decode rate beats the APK GPU on every Snapdragon device tested
  because there's no JNI/service/HTTP overhead. **CLI GPU is hard-blocked
  by Termux's manifest** — Termux runs as `untrusted_app` with no
  `<uses-native-library>` declaration for vendor `libOpenCL.so`, and we
  don't ship Termux. CPU is the supported CLI backend.

## Build / install / smoke

End-to-end install (host with USB-connected phone):
```bash
bash scripts/install.sh                    # auto-detect device + model
DEVICE_SERIAL=ABCDEF MODEL=e2b bash scripts/install.sh
```

APK only:
```bash
cd android && ./gradlew :app:assembleDebug --no-daemon
```

Lint + shellcheck (what CI runs):
```bash
cd android && ./gradlew :app:lintDebug --no-daemon
bash -n scripts/*.sh                       # syntax
shellcheck --severity=warning scripts/install.sh scripts/fetch_artifacts.sh ...
```

There is no unit-test suite. Verification is on-device:
```bash
adb shell 'curl -s http://127.0.0.1:11434/healthz'                     # "ok"
adb shell 'curl -sm 60 -X POST http://127.0.0.1:11434/api/generate \
  -H "Content-Type: application/json" \
  --data-binary "{\"prompt\":\"hi\",\"backend\":\"cpu\",\"stream\":false}"'
bash scripts/run_cpu_smoke.sh DEVICE_SERIAL                            # raw binary, no APK
bash scripts/run_gpu_smoke.sh DEVICE_SERIAL
bash scripts/run_litertlm_benchmark.sh DEVICE_SERIAL                   # long-prompt
```

## Architecture you have to read multiple files to see

**The SDK pivot at commit `94fc38e`** swapped a subprocess-CLI runtime
for the in-process Maven SDK. That has lasting consequences:

- The APK now runs LiteRT-LM as JNI inside the app process, so the
  app's manifest controls what vendor `.so` files the linker can
  resolve. `AndroidManifest.xml` declares `<uses-native-library>` for
  `libvndksupport.so` / `libOpenCL.so` / `libOpenCL-pixel.so`. **Without
  these tags, GPU init falls back to OpenGL → MLDrift's OpenGL delegate
  is incomplete → `INTERNAL ERROR ... compiled_model_executor.cc`.**
  v0.1.1 misdiagnosed this as an upstream bug (see CHANGELOG); v0.1.2
  is the manifest fix.
- The Termux-native path still runs the CLI binary directly, so the
  manifest doesn't apply — but Termux's own manifest doesn't declare
  vendor OpenCL either, hence CLI GPU is blocked.

**Default backend is architecturally meaningful, not a preference:**

- APK (`HttpServer.kt`, `LlmEngine.kt`): default `gpu` — GPU works there.
- CLI (`scripts/litertlm-native-wrapper.sh`, embedded copy in
  `scripts/install-termux-native.sh`): default `cpu` — GPU is blocked
  in Termux. A default of `gpu` would fail any new user on first call.

**The wrapper output parser is a 2-pass split, not a state machine.**
Both `litertlm-native-wrapper.sh` and the embedded copy in the installer
locate the boundary between response and bench by finding the LAST
literal `^BenchmarkInfo:$` line in the merged stream. The binary
guarantees its `BenchmarkInfo:` block is emitted at end-of-execution,
so a model that emits the same string mid-response cannot derail
parsing (`tail -1` picks the binary's, not the model's). Keep this
invariant if you touch the parser.

**Artifact pinning lives in two places and they must stay in sync:**

- `scripts/fetch_artifacts.sh` — host-side downloader, pins binary +
  `.so` set + models to specific upstream commits, verifies against
  `scripts/sha256_manifest.txt`.
- `scripts/install-termux-native.sh` — Termux-side, pins the same
  artifacts inline (no manifest file, hashes hard-coded).

A common failure mode: the maintainer has a stale CLI binary from an
earlier session at `bin/litert_lm_main` that no longer matches the
pinned sha. The pinned sha matches what new users get from a clean
install; the local copy may not. **Always re-fetch with
`scripts/fetch_artifacts.sh` before benchmarking** if numbers look off.

## Release rules (read CONTRIBUTING.md before tagging)

The pre-tag checklist in `CONTRIBUTING.md` was distilled from real
mistakes in v0.1.0 → v0.2.0. Three release-blocking bugs in that
window were caught only by an outside reviewer (Codex CLI) after
in-context Claude reviewers had approved:

- v0.1.1 → v0.1.2: GPU "upstream regression" claim was actually our
  missing manifest tags.
- v0.1.2 → v0.1.3: `scripts/install.sh` smoke parsed an `exit_code`
  field the in-process Engine never emits — would die on any clean
  install.
- v0.1.3 → v0.2.0: CLI default `gpu` would have failed on every Termux
  install; shim wrapper resolved its impl path to a non-existent
  directory; output parser silently dropped all metrics for the new
  binary's multi-line BenchmarkInfo format.

Procedural rule that came out of this and lives in
`~/.claude/projects/.../memory/`: **tag commits require an outside
reviewer pass (Codex CLI or human) in addition to in-context Claude
review.** The two catch different classes of mistake. Don't skip.

## API

`/api/generate` body: `{prompt, backend?, stream?}`. Anything else (e.g.
`model_path`, `timeout_ms`) is documented in stale notes only — not
wired through. `HttpServer.handleGenerate` is the source of truth.

Streaming response is NDJSON (`application/x-ndjson`). Engine errors
arrive as `{"error":"...","done":true}` either as the only document
(non-streaming) or as a single line in the stream. Both wrappers
detect this and exit nonzero with a `--backend cpu` hint.

## Constraints that rejection-cancel a PR

- HTTP must bind `127.0.0.1`. No `0.0.0.0`. No exceptions.
- arm64-v8a only. The Maven SDK does not ship for x86_64 / armv7.
- `minSdk = 33` (Android 13+). The SDK requires it.
- Models are user-provided `.litertlm` files — no auto-download of new
  models from new sources without sha256-pinning to a specific HF
  commit and adding to `scripts/sha256_manifest.txt`.

## Non-obvious gotchas

- Pushing a new model to `/data/local/tmp/litertlm/model.litertlm`
  invalidates the in-app `xnnpack_cache_*` and
  `*_mldrift_program_cache.bin` files (file-id encoded in cache name).
  Next call rebuilds them.
- `LauncherActivity` uses `Theme.NoDisplay`. It MUST call `finish()`
  synchronously in `onCreate` before `onResume` runs, or Android 12+
  throws `BadTokenException`. Don't add async work there.
- The `BootReceiver` only fires on the *second* boot after install
  (Android delivers `BOOT_COMPLETED` only to apps the user has
  launched at least once). This is intentional, not a bug.
