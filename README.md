# temux_llm — local LLM on Android, ollama-style

[![build](https://github.com/ImL1s/temux_llm/actions/workflows/build.yml/badge.svg)](https://github.com/ImL1s/temux_llm/actions/workflows/build.yml)
[![release](https://img.shields.io/github/v/release/ImL1s/temux_llm?include_prereleases)](https://github.com/ImL1s/temux_llm/releases)
[![license](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)
[![android](https://img.shields.io/badge/android-13%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/about/versions/13)
[![runtime](https://img.shields.io/badge/runtime-LiteRT--LM%200.11.0--rc1-yellow)](https://github.com/google-ai-edge/LiteRT-LM)

A self-contained Android app + Termux client that runs Google's **Gemma 4** (or
Qwen3) on your phone and exposes it as `http://127.0.0.1:11434/api/generate`.
No network. No cloud. No data leaves the device.

```
$ litertlm "Reply with just: hi."
hi.

[cpu  total=4918ms  tokens=61  decode=12.4 t/s]
```

Tested on **Galaxy S21+** (SD 888, Android 15), **Galaxy S24 Ultra** (SD 8 Gen 3,
Android 16), and **Galaxy S25** (SD 8 Elite, Android 16). Should work on any
arm64-v8a Android 13+ device. See [device matrix](#device-matrix) for measured
decode rates.

> **Known issue (v0.1.x):** GPU acceleration is currently regressed on all
> tested devices — LiteRT-LM 0.11.0-rc1 fails to load OpenCL via
> `libvndksupport.so` and the OpenGL fallback returns
> `UNIMPLEMENTED: CreateSharedMemoryManager`. The service therefore
> defaults to **CPU**. See [Known issues](#known-issues) for details.

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
| Latency per call | sub-second (model resident) | 1-7 s warm / 12-60 s cold |
| Requires USB cable + host | yes (one-time, for sideload) | no |
| Requires sideloading APK | yes | no |
| Best for | interactive chat, scripts | occasional / batch use |

The native path trades per-call load time for zero APK dependency.
Both paths use the same v0.11.0-rc.1 binary and the same models.

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
  "backend": "cpu" | "gpu",   // default: "cpu"
  "stream":  true             // default: true (NDJSON one-line-per-token)
}
```

Streaming response: one JSON object per line (`application/x-ndjson`):

```
{"response":"Hi","done":false}
{"response":"!","done":false}
{"response":"","done":true,"backend":"cpu","total_duration_ms":820,"output_tokens":2,"output_chars":3}
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

## Device matrix

CPU decode rates measured with `gemma-4-E2B-it` and `gemma-4-E4B-it`,
50-word output prompt, warm engine (cold init dropped), in-process Engine
via the APK service.

| Device | SoC | Android | RAM | E2B CPU | E4B CPU |
|---|---|---|---|---|---|
| Galaxy S21+ | SD 888 (SM8350) | 15 | 8 GB | 8.0 t/s | 4.1 t/s |
| Galaxy S24 Ultra | SD 8 Gen 3 (SM8650) | 16 | 12 GB | 10.3 t/s | 5.7 t/s |
| Galaxy S25 | SD 8 Elite (SM8750) | 16 | 12 GB | 12.4 t/s | 7.8 t/s |
| Galaxy Note 9 | Exynos 9810 | 10 | 6 GB | unsupported (minSdk=33 / Android 13+) | — |

`decode = output_tokens / (total_duration_ms / 1000)` end-to-end (includes
prefill within the warm window). First call after service start pays
~3-7 s rebuilding the XNNPack weight cache; warm init drops to ~0.5-0.8 s.

GPU rows are intentionally omitted — see Known issues below.

---

## Known issues

### GPU acceleration regressed in LiteRT-LM 0.11.0-rc1 (v0.1.x)

On every device tested (S21+ Android 15, S24 Ultra Android 16, S25 Android 16),
selecting `backend: "gpu"` fails with:

```
INTERNAL: ERROR: [.../llm_litert_compiled_model_executor.cc:1928]
└ ERROR: [./third_party/odml/litert/litert/cc/litert_compiled_model.h:1780]
```

Logcat shows the SDK trying to load OpenCL via `libvndksupport.so`:

```
ml_drift_cl_gl_accelerator.cc:126  OpenCL not supported on this platform. Using OpenGL instead.
tflite                              Failed to load OpenCL library with dlopen:
                                    dlopen failed: library "libvndksupport.so" not found.
delegate_opengl.cc:218              UNIMPLEMENTED: CreateSharedMemoryManager is not implemented.
```

The OpenCL → OpenGL fallback path in 0.11.0-rc1 is incomplete on Android
14/15/16 untrusted_app linker namespaces. This is upstream — not something
this project introduced — and will be fixed when LiteRT-LM picks up the
shared-memory implementation.

**Workaround:** run on CPU (which is the default since v0.1.1):

```bash
curl -s http://127.0.0.1:11434/api/generate \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"hi","backend":"cpu","stream":false}'
```

### Note 9 / Android < 13

`minSdk=33` (Android 13). Older devices like Galaxy Note 9 (Android 10) reject
the APK install with `INSTALL_FAILED_OLDER_SDK`. There is no plan to lower the
floor — the LiteRT-LM SDK targets API 33+.

---

## Uninstall

```bash
adb shell am force-stop dev.temuxllm.service
adb uninstall dev.temuxllm.service
adb shell rm -rf /data/local/tmp/litertlm /data/local/tmp/bin/litertlm
```
