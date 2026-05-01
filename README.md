# temux_llm — local LLM on Android, ollama-style

[English](README.md) · [繁體中文](README.zh-TW.md)

[![build](https://github.com/ImL1s/temux_llm/actions/workflows/build.yml/badge.svg)](https://github.com/ImL1s/temux_llm/actions/workflows/build.yml)
[![release](https://img.shields.io/github/v/release/ImL1s/temux_llm?include_prereleases)](https://github.com/ImL1s/temux_llm/releases)
[![license](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)
[![android](https://img.shields.io/badge/android-13%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/about/versions/13)
[![runtime](https://img.shields.io/badge/runtime-LiteRT--LM%200.11.0--rc1-yellow)](https://github.com/google-ai-edge/LiteRT-LM)

A self-contained Android app + Termux client that runs Google's **Gemma 4** (or
Qwen3) on your phone's GPU and exposes it as
`http://127.0.0.1:11434/api/generate`. No network. No cloud. No data leaves
the device.

```
$ litertlm "Reply with just: hi."
hi.

[gpu  total=2676ms  tokens=62  decode=23.2 t/s]
```

Tested on **Galaxy S21+** (SD 888 / Adreno 660, Android 15), **Galaxy S24 Ultra**
(SD 8 Gen 3 / Adreno 750, Android 16), and **Galaxy S25** (SD 8 Elite / Adreno
830, Android 16). Should work on any arm64-v8a Android 13+ Snapdragon device.
See [Performance](#performance) for measured CPU + GPU decode rates
across both paths.

## Use cases / 用途

- **No network or cloud:** Run LLM prompts on airplane mode, in subways, in
  sensitive contexts where queries cannot leave the device.
- **Ollama-compatible endpoint:** Apps and scripts expecting
  `http://127.0.0.1:11434` (Ollama's standard port) work without modification.
- **Termux scripting:** Pipe LLM output into shell pipelines:
  `curl -s ... | jq .response` for structured queries on the phone.
- **Offline assistant:** Gemma-class model resident in memory, sub-second
  replies for short queries.
- **Interchangeable models:** Load any `.litertlm` model (Gemma, Qwen, future
  releases) and swap freely.
- **Private prototyping:** Build against a local endpoint during early
  dev — no API keys, no token costs, no prompts sent to third parties.

**Not for:** production traffic (single device, single tenant), long-context
work (8k context max), multi-user serving (localhost only by design).

---

## Open box use

One command from a fresh clone, with the phone plugged in via USB:

```bash
bash scripts/install.sh
```

The installer:

1. Verifies host has `adb`, JDK 17+, and a configured Android SDK.
2. Auto-picks your phone (or set `DEVICE_SERIAL=...` for a specific one).
3. Auto-picks a model based on your SoC, or set `MODEL=e2b|e4b|qwen3`:
    - **`e2b`** (default for older flagships): `gemma-4-E2B-it.litertlm`, 2.4 GB.
       Good on S21+ class hardware.
    - **`e4b`** (default for SD 8 Elite class — Fold7, S25): `gemma-4-E4B-it.litertlm`,
       3.4 GB. Smarter, slower, needs ≥10 GB RAM phone.
    - **`qwen3`**: `Qwen3-0.6B.litertlm`, 614 MB. Smallest fallback; rambles a lot.
4. Downloads the binary + accelerators + chosen model (sha256-verified).
5. Builds the debug APK with the bundled Gradle wrapper.
6. Pushes everything to the device, installs the APK, starts the service.
7. Smoke-tests `/api/generate` and prints next steps.

After it finishes, install Termux ([F-Droid build][termux-fdroid] — the Play Store
version is unmaintained) and add the wrapper to your PATH **once**:

```sh
echo 'export PATH="/data/local/tmp/bin:$PATH"' >> ~/.bashrc
```

Then in any Termux session:

```sh
litertlm "你好"
litertlm --backend cpu "Reply OK in 3 words."
litertlm --json "what is 2+2?"            # raw JSON for scripting
litertlm --help
```

[termux-fdroid]: https://f-droid.org/packages/com.termux/

---

## Termux-native (no APK)

Don't want to sideload the APK? Run the LiteRT-LM binary directly inside
Termux — no USB cable, no Android service, no host machine required.

**Inside Termux on the phone:**

```sh
# download installer from your clone, or copy it manually
bash install-termux-native.sh          # default: gemma-4-E2B-it (2.4 GB)
MODEL=qwen3 bash install-termux-native.sh   # 614 MB fallback
MODEL=e4b   bash install-termux-native.sh   # 3.4 GB, high-end SoC only
```

The installer writes everything to `~/.litertlm/` and drops the wrapper at
`~/.local/bin/litertlm-native`. Add `~/.local/bin` to PATH once, then:

```sh
litertlm-native "你好"
litertlm-native --backend cpu "Reply OK in 3 words."
litertlm-native --json "what is 2+2?"
litertlm-native --help
```

**When to pick which path:**

| | APK path (`install.sh`) | Native path (`install-termux-native.sh`) |
|---|---|---|
| Setup | host machine + USB (one-time) | inside Termux only |
| Sideloading APK | required | none |
| Per-call init | sub-second (engine resident) | 3-8 s warm / 10-20 s first-ever |
| Steady-state decode | depends on backend (see matrix) | **CLI CPU rivals or beats APK GPU on Snapdragon** |
| GPU acceleration | works on Adreno (v0.1.2+) | blocked by Termux's vendor namespace |
| Best for | short interactive turns, sub-second replies | long generation, no-USB workflows, scripts |

**Counter-intuitive but verified:** the CLI's raw CPU decode rate beats the
APK's GPU end-to-end on every Snapdragon device we tested
(see [Performance](#performance)). Why: the CLI is a tight native binary
with multi-threaded CPU inference, while the APK pays JNI + service +
foreground-service-notification overhead. The APK still wins on per-call
latency because its engine is resident; the CLI pays init per call.

Both paths use LiteRT-LM 0.11.0-rc.1 (the APK depends on the Maven
artifact `com.google.ai.edge.litertlm:litertlm-android:0.11.0-rc1`,
the Termux-native path uses the matching `litert_lm_main` CLI binary
from the `v0.11.0-rc.1` GitHub release) and the same models.

---

## What it ships

```
scripts/install.sh                  one-shot installer (above)
scripts/install-termux-native.sh    Termux-only installer (no APK)
scripts/litertlm-native-wrapper.sh  source for the Termux-native CLI wrapper
scripts/fetch_artifacts.sh          sha256-verified host downloader
scripts/litertlm-termux-wrapper.sh  Termux client for the APK service
scripts/preflight.sh                host + device readiness probe
scripts/setup_litertlm_android.sh   manual push (used by install.sh)
scripts/run_{cpu,gpu}_smoke.sh      raw adb-shell smokes (skip APK)
scripts/run_litertlm_benchmark.sh   long-prompt benchmark
scripts/parse_litertlm_logs.sh      extract BenchmarkInfo to logs/summary.txt
scripts/sha256_manifest.txt         pinned hashes for binary + .so + models

android/                            Android Studio project (Kotlin)
  app/src/main/kotlin/dev/temuxllm/
    LlmService.kt                   foreground service holding the engine
    LlmEngine.kt                    in-process Engine + Conversation wrapper
    HttpServer.kt                   NanoHTTPD on 127.0.0.1:11434
    LauncherActivity.kt             tappable icon → starts service, finishes
    BootReceiver.kt                 auto-start on BOOT_COMPLETED

docs/specs/                         phase-2 architecture spec
docs/plans/                         implementation plans (phase-1 / phase-2a)
docs/findings-*.md                  test results with real numbers per device
docs/screenshots/                   phone-screen captures of Termux running it
```

---

## API

```
GET  /healthz       -> "ok"
GET  /api/version   -> {service, phase, runtime, default_backend,
                        model_path, source_model_path, engine_loaded}
GET  /api/tags      -> {models: [{name, path, size_bytes}, ...]}
POST /api/generate  -> NDJSON stream of {response, done} (or one JSON if stream=false)
```

`/api/generate` request body:

```json
{
  "prompt":  "Hi",
  "backend": "cpu" | "gpu",   // default: "gpu"
  "stream":  true             // default: true (NDJSON one-line-per-token)
}
```

Streaming response: one JSON object per line (`application/x-ndjson`):

```
{"response":"Hi","done":false}
{"response":"!","done":false}
{"response":"","done":true,"backend":"gpu","total_duration_ms":820,"output_tokens":2,"output_chars":3}
```

Non-streaming (`stream=false`) returns one document with `model`, `backend`,
`response`, `done`, `total_duration_ms`, `output_tokens`.

The service binds only to `127.0.0.1`. Verified after every restart with
`ss -tnlp | grep 11434` showing `[::ffff:127.0.0.1]:11434` (never `0.0.0.0`).

---

## Constraints (kept from the original brief)

- No root.
- arm64-v8a only.
- Localhost-only HTTP. Never exposed beyond loopback.
- Runtime is LiteRT-LM loading `.litertlm` (not the older `.tflite` / `.task`).

---

## Performance

`gemma-4-E2B-it` (2.4 GB), 50-word output prompt, warm engine.

| Device | SoC | APK CPU<br>(end-to-end) | APK GPU<br>(end-to-end) | CLI CPU<br>(decode-only) |
|---|---|---|---|---|
| Galaxy S21+ | SD 888 / Adreno 660 | 8.0 t/s | 10.5 t/s | **12.1 t/s** |
| Galaxy S24 Ultra | SD 8 Gen 3 / Adreno 750 | 10.3 t/s | 22.0 t/s | 21.5 t/s |
| Galaxy S25 | SD 8 Elite / Adreno 830 | 12.4 t/s | 23.2 t/s | **35.4 t/s** |

The two metrics measure different things and are not directly comparable as
a percentage. Reported separately so each is honest for its use case:

- **APK end-to-end** = `output_tokens / (total_duration_ms / 1000)`, the full
  cycle of one warm `/api/generate` request — includes prefill, decode, and
  the loopback HTTP/JNI overhead the user actually feels. Excludes the
  resident engine's one-time init.
- **CLI decode-only** = `Decode Speed` reported by the binary's
  `BenchmarkInfo` block — sustained tokens/sec once the engine is producing
  output, measured in isolation from prefill and per-call init.

For a CLI end-to-end view: the warm wall-time-per-call is **3-8 s** (engine
init dominates short prompts; long generation amortizes it).
A 60-token output on S25 takes ~3 s wall ≈ 20 t/s end-to-end, vs the APK GPU's
~3 s for 62 tokens ≈ 23 t/s end-to-end — roughly tied for short outputs.
The CLI's decode-only advantage (35 t/s on S25) shows up when generation is
long enough for init to amortize. See "Why CLI CPU beats APK GPU" below for
the architectural reason.

**Per-call latency** is a different axis: APK keeps `Engine` resident, so a
warm `/api/generate` returns in 200-1000 ms for short answers. CLI rebuilds
the engine each call, so per-call wall is **3-8 s warm / 10-20 s first ever**.
Pick the path based on whether you optimize for per-call latency
(APK) or sustained decode throughput (CLI).

`gemma-4-E4B-it` (3.4 GB) — APK only:

| Device | E4B CPU | E4B GPU |
|---|---|---|
| Galaxy S21+ | 4.1 t/s | (not measured) |
| Galaxy S24 Ultra | 5.7 t/s | (not measured) |
| Galaxy S25 | 7.8 t/s | 15.0 t/s |
| Galaxy Note 9 | unsupported (minSdk=33 / Android 13+) | |

**First-call init cost:**

- **APK GPU:** OpenCL kernel-compile takes 8-22 s the first time the engine
  starts. The SDK writes `model.litertlm_*_mldrift_program_cache.bin` into
  the app's filesDir; subsequent service starts skip the compile and reuse
  it. If you re-push a model, the cache is invalidated and you pay this
  cost again.
- **APK CPU:** XNNPack weight cache build takes 3-7 s on first call; warm
  init is 0.5-0.8 s.
- **CLI CPU:** First-ever call builds the xnnpack cache file next to the
  model (~10-20 s, one-time). Subsequent calls pay only engine init
  (~2-3 s). The `install-termux-native.sh` script auto-runs a warmup at
  the end of install so the user's first interactive call is already warm.

**Why CLI CPU beats APK GPU:** The CLI is a tight native binary doing
multi-threaded CPU inference — no JNI, no service framework overhead, no
foreground-service notification, no per-request socket round-trip. The
APK GPU pays for the SDK's JVM↔native bridge plus the localhost HTTP
hop on every call. For long generation (where decode dominates init),
CLI's raw throughput wins. For short interactive replies (where init
dominates), APK's resident engine wins.

---

## Known issues

### Note 9 / Android < 13

`minSdk=33` (Android 13). Older devices like Galaxy Note 9 (Android 10) reject
the APK install with `INSTALL_FAILED_OLDER_SDK`. There is no plan to lower the
floor — the LiteRT-LM SDK targets API 33+.

### Non-Snapdragon SoCs (Tensor, Exynos)

GPU is verified on Adreno (Qualcomm Snapdragon) only. Other SoC families have
their own LiteRT-LM upstream issues:

- **Pixel / Tensor G3+:** OpenCL not exposed by Tensor — see
  [LiteRT-LM #1860](https://github.com/google-ai-edge/LiteRT-LM/issues/1860).
- **Exynos (S26 / Xclipse):** Clspv kernel bug under ANGLE-CL — see
  [LiteRT-LM #2114](https://github.com/google-ai-edge/LiteRT-LM/issues/2114).

If GPU init returns `INTERNAL ERROR ... compiled_model_executor.cc:1928` on
your device, fall back to CPU per request:

```bash
curl -s http://127.0.0.1:11434/api/generate \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"hi","backend":"cpu","stream":false}'
```

---

## Uninstall

```bash
adb shell am force-stop dev.temuxllm.service
adb uninstall dev.temuxllm.service
adb shell rm -rf /data/local/tmp/litertlm /data/local/tmp/bin/litertlm
```
