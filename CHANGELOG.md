# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

(no unreleased changes)

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

[Unreleased]: https://github.com/iml1s/temux_llm/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/iml1s/temux_llm/releases/tag/v0.1.0
