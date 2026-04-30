# Phase 1 Findings — LiteRT-LM Android CLI on Samsung devices

**Date:** 2026-05-01 (revised after S21+ re-validation)
**Plan executed:** `docs/plans/2026-05-01-litertlm-s21plus-android-cli.md`
**Outcome:** **GO** for Phase 2 (Android foreground service + localhost-only HTTP API for Termux), with three brief deviations recorded below.

---

## TL;DR

The official LiteRT-LM Android arm64 CLI path **works on both devices we tested**, and the GPU backend gives a meaningful prefill+TTFT speedup over CPU on each one. All four of the brief's GPU-usefulness conditions pass on both. Phase 2 is unblocked.

| Device | SoC | GPU | Android | Status |
|---|---|---|---|---|
| Galaxy S21+ (`SM-G9960` / `t2q`) | SM8350 (SD 888) | Adreno 660 | 15 | full PASS (CPU + GPU + benchmark) |
| Galaxy S25 (`SM-S931Q` / `pa1q`) | SM8750 (SD 8 Elite for Galaxy) | Adreno 830 | 16 | full PASS (CPU + GPU + benchmark) |

The S21+ initially blocked on `/data` partition exhaustion (99.5% full of user apps); the user freed ~46 GiB by hand and the second pass completed cleanly. **Adreno 660 OpenCL works fine from `adb shell` on Android 15** — the brief's vendor-namespace concern did not materialize. Both devices used the exact same scripts, binary, .so set, and model — only `DEVICE_SERIAL` differs.

The brief's reference model `gemma-4-E2B-it.litertlm` is too new for the **v0.9.0** prebuilt Android binary (latest released Android asset; v0.10.x dropped Android binaries). v0.9.0 reports `INVALID_ARGUMENT: Unsupported model type` on it. We substituted `litert-community/Qwen3-0.6B/Qwen3-0.6B.litertlm` (614 MB), which v0.9.0 supports natively. Both devices ran the same model.

---

## Devices

| Item | S21+ | S25 |
|---|---|---|
| adb serial | `RFCNC0WNT9H` | `RFCY71LAFYE` |
| Model / codename | `SM-G9960` / `t2q` | `SM-S931Q` / `pa1q` |
| SoC | `SM8350` Snapdragon 888 | `SM8750` Snapdragon 8 Elite for Galaxy |
| GPU | Adreno 660 | Adreno 830 |
| Android | 15 (SDK 35) | 16 |
| ABI | arm64-v8a ✓ | arm64-v8a ✓ |
| `/vendor/lib64/libOpenCL.so` | present ✓ | present ✓ |
| `/vendor/etc/public.libraries.txt` allowlists `libOpenCL.so` | yes ✓ | (not re-probed; assumed yes) |
| `/data` free at start (initial run) | 1,556 MiB / 224 GiB (99.5% used) | 198 GiB free |
| `/data` free after user manually freed | 47,271 MiB | (n/a) |
| Outcome | full Phase 1 PASS | full Phase 1 PASS |

The S21+ first attempt failed because the `/data` partition was 99.5% used by user apps; the model push (2.46 GB) plus the XNNPack weight cache (similar volume) couldn't fit. After the user manually freed ~46 GiB the second pass completed cleanly, validating the original brief target hardware (SD 888 / Adreno 660 / Android 15).

Both devices ran the exact same scripts, the exact same v0.9.0 binary, the exact same v0.9.0 `.so` set, and the exact same Qwen3-0.6B model. Only `DEVICE_SERIAL` changed.

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

### 1. Devices: validated S21+ AND S25
The brief's nominal target was a Z Fold7 (SD 8 Elite). The user offered an S21+ to test on, but on first attempt its `/data` was 99.5% full of apps and the model + XNNPack cache wouldn't fit. We pivoted to the S25 (SD 8 Elite for Galaxy / Android 16, 198 GB free) which matches the brief's original SoC class exactly. After the user manually freed ~46 GiB on the S21+, we returned and validated it cleanly too. **No deviation**: brief target hardware confirmed, plus a more powerful sibling for upper-bound numbers.

### 2. Model: `gemma-4-E2B-it.litertlm` → `Qwen3-0.6B.litertlm`
The brief explicitly asked for `litert-community/gemma-4-E2B-it-litert-lm`. The v0.9.0 prebuilt binary, however, returns `INVALID_ARGUMENT: Unsupported model type` on this file (uploaded to HF on 2026-04-29, after v0.9.0 shipped). v0.10.x has no Android arm64 release asset. To validate the runtime path without spending hours building from source, we substituted `litert-community/Qwen3-0.6B/Qwen3-0.6B.litertlm` (614 MB), which the v0.9.0 binary supports natively. Phase 2 (Android service + localhost API) does not depend on which Gemma version is loaded — the runtime, the dlopen of OpenCL, and the inference loop are the same.

If a future task strictly requires a Gemma 4 model, the next step is to build `litert_lm_main.android_arm64` from current `main` with Bazel (out of scope for this validation).

### 3. Benchmark flags
The brief specified `--benchmark`, `--benchmark_prefill_tokens=1024`, `--benchmark_decode_tokens=256`, `--async=false`, `--report_peak_memory_footprint`. **None** of these flags exist in the v0.9.0 binary — `--helpfull` lists only `--backend / --input_prompt / --input_prompt_file / --model_path`. They are documented in `docs/getting-started/build-and-run.md` on `main`, which describes a newer build.

The v0.9.0 binary, however, **auto-prints a `BenchmarkInfo:` block on every run** with: Init Phases (Conversation / Executor / LLM metadata / Model assets / Tokenizer / Total), Time to first token, Prefill Speed (tokens/sec) per turn, Decode Speed (tokens/sec) per turn. To approximate the brief's 1024-token prefill spec, we drive prefill volume with a long input prompt (`scripts/long_prompt.txt`, 4,777 chars → 982 prefill tokens; close enough to 1024 for comparative purposes). Peak memory footprint is **not produced** by the v0.9.0 binary and is therefore unreported.

---

## Results — both devices, Qwen3-0.6B

### Smoke runs (short prompt: "Say hello in one short sentence.", ~16 prefill tokens)

| Metric | S21+ CPU | S21+ GPU | S21+ GPU/CPU | S25 CPU | S25 GPU | S25 GPU/CPU |
|---|---|---|---|---|---|---|
| Init Total (ms) | 2,499 | 13,458 | 0.19× (1-time) | 1,777 | 10,677 | 0.17× (1-time) |
| TTFT (s) | 2.95 | 0.73 | **4.04×** | 0.68 | 0.24 | **2.83×** |
| Prefill (tok/s) | 5.72 | 24.87 | **4.35×** | 25.59 | 79.21 | **3.10×** |
| Decode (tok/s) | 6.62 | 12.13 | **1.83×** | 17.88 | 25.95 | **1.45×** |

### Benchmark runs (long prompt, 982 prefill tokens — close to brief's 1024 spec)

| Metric | S21+ CPU | S21+ GPU | S21+ GPU/CPU | S25 CPU | S25 GPU | S25 GPU/CPU |
|---|---|---|---|---|---|---|
| Init Total (ms) | 3,041 | 11,913 | 0.26× (1-time) | 1,566 | 11,908 | 0.13× (1-time) |
| TTFT (s) | 14.70 | 5.68 | **2.59×** | 4.98 | 1.62 | **3.07×** |
| Prefill — 982 tok | 14.54 s | 5.59 s | 2.60× | 4.92 s | 1.57 s | 3.12× |
| Prefill (tok/s) | 67.53 | 175.60 | **2.60×** | 199.67 | 624.05 | **3.13×** |
| Decode (tok/s) | 6.33 | 10.83 | **1.71×** | 17.18 | 19.95 | **1.16×** |

### Cross-device comparison (S25 vs S21+)

| Metric (long prompt) | S25/S21+ CPU | S25/S21+ GPU |
|---|---|---|
| TTFT | 2.95× faster | 3.51× faster |
| Prefill speed | 2.96× faster | 3.55× faster |
| Decode speed | 2.71× faster | 1.84× faster |
| GPU init | (n/a) | 1.00× — both ~12 s |

S25 (Adreno 830 + Oryon) is ~3× faster than S21+ (Adreno 660 + Cortex-X1) on prefill across either backend. Decode improves less because decode is more memory-bandwidth bound, where the gap between LPDDR5 and LPDDR5T-9600 is smaller. **GPU init time is identical across devices** — bound by OpenCL kernel compilation cost, not raw GPU performance.

### Stop-condition signal counts (S21+ benchmark logs)

| Signal | CPU log | GPU log |
|---|---|---|
| OpenCL / Adreno / cl_device / cl_platform mentions | 2 | **8** |
| `fallback` / `falling back` mentions | 0 | 0 |
| `Aborted` / Check failed / `F[0-9]{4}` lines | 0 | 0 |
| `^ERROR` lines | 0 | 0 |

(S25 numbers are identical in shape; see `logs/summary.txt-s25.log`.)

GPU benchmark log on S21+ contains the same OpenCL bring-up sequence as on S25:
- `INFO: Loaded OpenCL library with dlopen.`
- `INFO: [gpu_environment.cc:217] Created OpenCL device from provided device id and platform id.`
- `INFO: [gpu_environment.h:152] Created LiteRT GpuEnvironment.`
- `INFO: [delegate_kernel.cc:712] Initializing OpenCL-based API from serialized data.`

This is empirical confirmation that **`adb shell` running a binary from `/data/local/tmp` can `dlopen("libOpenCL.so")` on Adreno 660 / Android 15** — the brief's main GPU-availability concern resolves favorably for this target hardware.

---

## Brief's four-rule GPU usefulness check (§100)

| # | Rule | S21+ | S25 | Evidence |
|---|---|---|---|---|
| 1 | GPU run did not crash | ✓ | ✓ | `Aborted/fatal/abort: 0` on both. |
| 2 | Logs show GPU/OpenCL/accelerator path loaded | ✓ | ✓ | OpenCL mentions ≥8 in both gpu_benchmark logs; explicit `Loaded OpenCL library with dlopen` and `Created OpenCL device`. |
| 3 | Benchmark shows meaningful TTFT/prefill improvement vs CPU | ✓ | ✓ | S21+: TTFT 2.59×, Prefill 2.60×. S25: TTFT 3.07×, Prefill 3.13×. |
| 4 | No silent fallback to CPU is suspected | ✓ | ✓ | `fallback mentions: 0` on both; GPU init logs are present and ordered as expected; OpenCL kernels compile (11.9 s init cost on both — the smoking gun). |

**ALL PASS on BOTH devices → GO for Phase 2.**

---

## Decision

**GO for Phase 2 — Android foreground service + localhost-only HTTP API for Termux.**

Both validated devices satisfy the brief's GPU-usefulness rule. The S21+ (lower bound — Adreno 660, SD 888, Android 15) reaches **175 prefill tok/s + 10.83 decode tok/s** on GPU; the S25 (upper bound — Adreno 830, SD 8 Elite, Android 16) reaches **624 prefill tok/s + 19.95 decode tok/s**. The Phase-2 service can target either; users with older flagships still get usable performance.

Concrete next steps (out of scope for this plan):

1. Wrap the runtime in an Android foreground service. Two architectural options:
   - **(a) In-process API:** Use the LiteRT-LM Android/Kotlin SDK if/when published to Maven (`com.google.ai.edge:litert-lm:*` — unconfirmed at the time of writing). Single Android app, model loads once, multi-request server.
   - **(b) Subprocess wrapper:** Spawn the v0.9.0 CLI binary as a child of the service. Each request reloads the model (1.5–12 s init cost is significant). Probably not viable for an interactive ollama-like UX.
   - **(c) Long-lived server binary:** Build `litert_lm_main` from current `main` with a server flag (or add one). Long-lived binary spawned via the service. Most flexible but requires a multi-hour Bazel build.
2. The service must bind only to `127.0.0.1` (per brief). HTTP wrapper analogous to ollama: POST `/api/generate`, stream tokens via SSE.
3. Termux-side client is a thin HTTP wrapper. No Termux-native LiteRT-LM in phase 2 either (brief constraint preserved).
4. Default model: Qwen3-0.6B works on v0.9.0 today. Add Gemma-4 once we build a newer binary from source. Both can ship.

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
- `logs/cpu_smoke-s21plus.log` / `logs/gpu_smoke-s21plus.log` / `logs/cpu_benchmark-s21plus.log` / `logs/gpu_benchmark-s21plus.log` — S21+ raw evidence.
- `logs/cpu_smoke-s25.log` / `logs/gpu_smoke-s25.log` / `logs/cpu_benchmark-s25.log` / `logs/gpu_benchmark-s25.log` — S25 raw evidence.
- `logs/summary.txt` (latest device's parse), `logs/summary.txt-s25.log` (S25 parse snapshot), `logs/binary_help.log`, `logs/binary_helpfull.log` — supporting.
