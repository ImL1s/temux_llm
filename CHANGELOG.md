# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

(no unreleased changes)

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

[Unreleased]: https://github.com/ImL1s/temux_llm/compare/v0.1.1...HEAD
[0.1.1]: https://github.com/ImL1s/temux_llm/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/ImL1s/temux_llm/releases/tag/v0.1.0
