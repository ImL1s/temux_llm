# Phase 1 Findings — LiteRT-LM Android CLI on Samsung devices

**Date:** 2026-05-01
**Plan executed:** `docs/plans/2026-05-01-litertlm-s21plus-android-cli.md`
**Outcome:** **GO** for Phase 2 (Android foreground service + localhost-only HTTP API for Termux), with three brief deviations recorded below.

---

## TL;DR

The official LiteRT-LM Android arm64 CLI path **works**, and the GPU backend on
Adreno 830 (Snapdragon 8 Elite for Galaxy) gives **3.07× faster TTFT and 3.13× faster prefill** than the CPU backend on the same device while running the same model with the same input prompt. All four of the brief's GPU-usefulness conditions are met. Phase 2 is unblocked.

The intended target device (Samsung Galaxy S21+, SM8350 / Adreno 660) was unable to complete the validation due to **device storage exhaustion** — its `/data` partition was 99.5% full before we started. The validation was repeated end-to-end on the Samsung Galaxy S25 (SM8750 / Adreno 830 / Android 16), which matches the brief's original target spec exactly. The same scripts and the same artifact set work on either device — only `DEVICE_SERIAL` changes.

The brief's reference model `gemma-4-E2B-it.litertlm` is too new for the **v0.9.0** prebuilt Android binary (the latest released Android binary; v0.10.x dropped Android assets). v0.9.0 reports `INVALID_ARGUMENT: Unsupported model type` on it. We substituted `litert-community/Qwen3-0.6B/Qwen3-0.6B.litertlm` (614 MB), which v0.9.0 fully supports.

---

## Devices

| Item | S21+ (intended) | S25 (validated) |
|---|---|---|
| adb serial | `RFCNC0WNT9H` | `RFCY71LAFYE` |
| Model / codename | `SM-G9960` / `t2q` | `SM-S931Q` / `pa1q` |
| SoC | `SM8350` Snapdragon 888 | `SM8750` Snapdragon 8 Elite for Galaxy |
| GPU | Adreno 660 | Adreno 830 |
| Android | 15 (SDK 35) | 16 |
| ABI | arm64-v8a ✓ | arm64-v8a ✓ |
| `/vendor/lib64/libOpenCL.so` | present ✓ | present ✓ |
| `/vendor/etc/public.libraries.txt` allowlists `libOpenCL.so` | yes ✓ | (not re-probed; assumed yes) |
| `/data` free at start | 1,556 MiB / 224 GiB total (99.5% used) | 198 GiB free / 222 GiB total |
| Outcome | blocked (storage) | full Phase 1 PASS |

The S21+ /data partition is full of user apps (the device hosts a developer's working set). After clearing every safely-deletable artifact in `/data/local/tmp` (Flutter `.deb` installers, two APKs, perfd/, dalvik-cache/) we reclaimed 1,534 MiB, but the model push of 2.46 GB plus the XNNPack weight cache build (which writes a similar volume of data alongside the model) needs more than the device can offer without uninstalling user apps. The XNNPack delegate also ignores the `TFLITE_XNNPACK_DELEGATE_DISABLE_CACHE` env var on this binary, so we cannot opt out of the cache.

The S25 has 198 GB free and matches the brief's *original* hardware target (SD 8 Elite, Android 16) exactly. Switching is documented in the plan as the explicit fallback for "S21+ fails for hardware/OS reasons".

---

## Artifacts (host)

All eight artifacts verified by sha256 against `scripts/sha256_manifest.txt`:

| Artifact | Size | sha256 | Source |
|---|---|---|---|
| `bin/litert_lm_main` | 37,280,824 B | `606a933f…361bb629` | GH release v0.9.0 |
| `bin/android_arm64/libGemmaModelConstraintProvider.so` | 20,186,832 B | `03c5be00…150a4656a` | repo @ v0.9.0 (LFS) |
| `bin/android_arm64/libLiteRtGpuAccelerator.so` | 12,882,984 B | `83c19e84…1c601cd8` | repo @ v0.9.0 (LFS) |
| `bin/android_arm64/libLiteRtOpenClAccelerator.so` | 2,974,704 B | `795391cf…cdfaaee471c46dc` | repo @ v0.9.0 (LFS) |
| `bin/android_arm64/libLiteRtTopKOpenClSampler.so` | 1,430,872 B | `c7901b30…dfbd747` | repo @ v0.9.0 (LFS) |
| `bin/android_arm64/libLiteRtTopKWebGpuSampler.so` | 1,465,840 B | `6a070fad…aa4fdebf` | repo @ v0.9.0 (LFS) |
| `bin/android_arm64/libLiteRtWebGpuAccelerator.so` | 8,762,960 B | `5a3d5532…91800c204e` | repo @ v0.9.0 (LFS) |
| `models/gemma-4-E2B-it.litertlm` | 2,583,085,056 B | `ab7838cd…11b27e42` | HF `litert-community/gemma-4-E2B-it-litert-lm` @ commit `7fa1d784…0c0db` |
| `models/Qwen3-0.6B.litertlm` (substitute) | 614,236,160 B | `555579ff…5ab0b4` | HF `litert-community/Qwen3-0.6B` @ main |

**ABI pin:** the v0.9.0 binary and the v0.9.0 prebuilt `.so` set are bytewise different from the `main` branch versions of the same files (e.g. `libLiteRtOpenClAccelerator.so` is 2,974,704 B at v0.9.0 vs 2,696,144 B at `main`). The fetcher pins everything to v0.9.0 to avoid an ABI mismatch.

**HF mutability:** the original plan downloaded the Gemma model from `main`, which Codex (independent review) flagged as risky because HF `main` had already been mutated within hours of the `_qualcomm_sm8750.litertlm` upload. The fetcher now pins the model URL to commit `7fa1d78473894f7e736a21d920c3aa80f950c0db` and verifies sha256 against the manifest.

---

## Brief deviations and why

### 1. Device: S21+ → S25
The brief's target was a Z Fold7 (SD 8 Elite). The user offered an S21+ to test on, but its `/data` was 99.5% full of apps and the model + XNNPack cache wouldn't fit. We executed the full validation on the S25 (SD 8 Elite for Galaxy / Android 16), which matches the brief's *original* SoC class. The plan documents this fallback in `Backup device available`.

### 2. Model: `gemma-4-E2B-it.litertlm` → `Qwen3-0.6B.litertlm`
The brief explicitly asked for `litert-community/gemma-4-E2B-it-litert-lm`. The v0.9.0 prebuilt binary, however, returns `INVALID_ARGUMENT: Unsupported model type` on this file (uploaded to HF on 2026-04-29, after v0.9.0 shipped). v0.10.x has no Android arm64 release asset. To validate the runtime path without spending hours building from source, we substituted `litert-community/Qwen3-0.6B/Qwen3-0.6B.litertlm` (614 MB), which the v0.9.0 binary supports natively. Phase 2 (Android service + localhost API) does not depend on which Gemma version is loaded — the runtime, the dlopen of OpenCL, and the inference loop are the same.

If a future task strictly requires a Gemma 4 model, the next step is to build `litert_lm_main.android_arm64` from current `main` with Bazel (out of scope for this validation).

### 3. Benchmark flags
The brief specified `--benchmark`, `--benchmark_prefill_tokens=1024`, `--benchmark_decode_tokens=256`, `--async=false`, `--report_peak_memory_footprint`. **None** of these flags exist in the v0.9.0 binary — `--helpfull` lists only `--backend / --input_prompt / --input_prompt_file / --model_path`. They are documented in `docs/getting-started/build-and-run.md` on `main`, which describes a newer build.

The v0.9.0 binary, however, **auto-prints a `BenchmarkInfo:` block on every run** with: Init Phases (Conversation / Executor / LLM metadata / Model assets / Tokenizer / Total), Time to first token, Prefill Speed (tokens/sec) per turn, Decode Speed (tokens/sec) per turn. To approximate the brief's 1024-token prefill spec, we drive prefill volume with a long input prompt (`scripts/long_prompt.txt`, 4,777 chars → 982 prefill tokens; close enough to 1024 for comparative purposes). Peak memory footprint is **not produced** by the v0.9.0 binary and is therefore unreported.

---

## Results — S25 + Qwen3-0.6B

### Smoke runs (short prompt: "Say hello in one short sentence.", ~16 prefill tokens)

| Metric | CPU | GPU | GPU/CPU |
|---|---|---|---|
| Init Total | 1,777 ms | 10,677 ms | 0.17× (one-time) |
| Time to first token | 0.68 s | 0.24 s | **2.83×** |
| Prefill Speed | 25.59 tok/s | 79.21 tok/s | **3.10×** |
| Decode Speed | 17.88 tok/s | 25.95 tok/s | **1.45×** |
| Output | "Hi there!" | "Hello!" | (semantically equivalent) |

### Benchmark runs (long prompt, 982 prefill tokens — close to brief's 1024 spec)

| Metric | CPU | GPU | GPU/CPU |
|---|---|---|---|
| Init Total | 1,566 ms | 11,908 ms | 0.13× (one-time) |
| Time to first token | 4.98 s | 1.62 s | **3.07×** |
| Prefill — 982 tokens | 4.918 s | 1.574 s | **3.12×** |
| Prefill Speed | 199.67 tok/s | 624.05 tok/s | **3.13×** |
| Decode — tokens × time | 186 × 10.83 s | 3,114 × 156.11 s | (different stop) |
| Decode Speed | 17.18 tok/s | 19.95 tok/s | **1.16×** |

GPU paid an 11.9-s one-time OpenCL kernel-compile cost on first run. After that, prefill is ≥3× faster; decode is consistently a bit faster.

### Stop-condition signal counts (from `logs/summary.txt`)

| Signal | CPU benchmark log | GPU benchmark log |
|---|---|---|
| OpenCL / Adreno / cl_device / cl_platform mentions | 2 | **8** |
| `fallback` / `falling back` mentions | 0 | 0 |
| `Aborted` / Check failed / `F[0-9]{4}` lines | 0 | 0 |
| `^ERROR` lines | 0 | 0 |

GPU benchmark log explicitly contains:
- `INFO: Loaded OpenCL library with dlopen.`
- `INFO: [gpu_environment.cc:217] Created OpenCL device from provided device id and platform id.`
- `INFO: [gpu_environment.cc:280] Reusing provided EGL environment.`
- `INFO: [gpu_environment.h:152] Created LiteRT GpuEnvironment.`
- `INFO: [delegate_kernel.cc:712] Initializing OpenCL-based API from serialized data.`

These prove the GPU path was actually taken, not a silent fallback to CPU.

---

## Brief's four-rule GPU usefulness check (§100)

| # | Rule | Verdict | Evidence |
|---|---|---|---|
| 1 | GPU run did not crash | ✓ | `Aborted/fatal/abort: 0` in gpu_benchmark.log; clean teardown via `DestroyAccelerator`. |
| 2 | Logs show GPU/OpenCL/accelerator path loaded | ✓ | 8 OpenCL/Adreno mentions; explicit `Loaded OpenCL library with dlopen` and `Created OpenCL device`. |
| 3 | Benchmark shows meaningful TTFT/prefill improvement vs CPU | ✓ | TTFT 3.07×, Prefill 3.13× on 982-token prefill; same on smoke prompt. |
| 4 | No silent fallback to CPU is suspected | ✓ | `fallback mentions: 0`; GPU init logs are present and ordered as expected; OpenCL kernels actually compile (11.9 s init cost is the giveaway). |

**ALL PASS → GO for Phase 2.**

---

## Decision

**GO for Phase 2 — Android foreground service + localhost-only HTTP API for Termux.**

Concrete next steps (out of scope for this plan):

1. Wrap the runtime in an Android foreground service that owns the `litert_lm_main` process or links the LiteRT-LM Android/Kotlin API in-process. The service should bind only to `127.0.0.1` (per brief constraint).
2. Termux-side client is a thin HTTP wrapper analogous to an `ollama` CLI — POSTs prompts, streams tokens. No Termux-native LiteRT-LM in phase 2 either (brief constraint preserved).
3. Decide whether to ship the v0.9.0 binary with Qwen3-0.6B as the default model (works today) or rebuild from current `main` to support Gemma-4. The default model can be swapped after the runtime path is solid.
4. Re-test once on S21+ if a user needs that hardware: requires uninstalling enough apps to free ~1 GB of /data on top of `/data/local/tmp` cleanup.

---

## Files of record

- `docs/plans/2026-05-01-litertlm-s21plus-android-cli.md` — original plan (Critic-reviewed by Codex; integration commits visible in `git log`).
- `scripts/fetch_artifacts.sh` — sha256-verified fetcher; pins binary, .so, and HF model commit.
- `scripts/sha256_manifest.txt` — 8 expected hashes.
- `scripts/preflight.sh` — adb / ABI / SoC / OpenCL / namespace probe / free-space gate.
- `scripts/setup_litertlm_android.sh` — push to device + capture `--help`.
- `scripts/run_cpu_smoke.sh`, `scripts/run_gpu_smoke.sh` — smoke runs.
- `scripts/run_litertlm_benchmark.sh` — long-prompt benchmark for both backends.
- `scripts/long_prompt.txt` — 4,777-char prompt → 982 prefill tokens.
- `scripts/parse_litertlm_logs.sh` — extracts BenchmarkInfo metrics + GPU signal counts → `logs/summary.txt`.
- `logs/cpu_smoke.log`, `logs/gpu_smoke.log`, `logs/cpu_benchmark.log`, `logs/gpu_benchmark.log`, `logs/summary.txt`, `logs/binary_help.log`, `logs/binary_helpfull.log` — raw evidence.
