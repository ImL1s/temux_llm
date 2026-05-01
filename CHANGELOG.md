# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

(no unreleased changes)

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
