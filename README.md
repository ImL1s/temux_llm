# temux_llm — local LLM on Android, ollama-style

[![build](https://github.com/iml1s/temux_llm/actions/workflows/build.yml/badge.svg)](https://github.com/iml1s/temux_llm/actions/workflows/build.yml)
[![release](https://img.shields.io/github/v/release/iml1s/temux_llm?include_prereleases)](https://github.com/iml1s/temux_llm/releases)
[![license](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)
[![android](https://img.shields.io/badge/android-13%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/about/versions/13)
[![runtime](https://img.shields.io/badge/runtime-LiteRT--LM%200.11.0--rc1-yellow)](https://github.com/google-ai-edge/LiteRT-LM)

A self-contained Android app + Termux client that runs Google's **Gemma 4** (or
Qwen3) on your phone's GPU and exposes it as `http://127.0.0.1:11434/api/generate`.
No network. No cloud. No data leaves the device.

```
$ litertlm "用一句繁體中文介紹自己。"
我是一個由 Google DeepMind 開發的開放權重大型語言模型，名叫 Gemma 4。

[gpu  ttft=0.5s  prefill=40.3t/s  decode=19.2t/s]
```

Tested on **Galaxy S21+** (SD 888 / Adreno 660 / Android 15) and **Galaxy S25**
(SD 8 Elite / Adreno 830 / Android 16). Should work on any arm64-v8a Android 13+
flagship, including **Galaxy Z Fold7**.

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
| Requires USB + host | yes (one-time) | no |
| Requires sideloading APK | yes | no |
| Best for | interactive chat, scripts | occasional / batch use |

The native path trades per-call load time for zero APK dependency.
Both paths use the same v0.11.0-rc.1 binary and the same models.

---

## What it ships

```
scripts/install.sh                one-shot installer (above)
scripts/fetch_artifacts.sh        sha256-verified host downloader
scripts/litertlm-termux-wrapper.sh  the Termux client (deployed to /data/local/tmp/bin/litertlm)
scripts/preflight.sh              host + device readiness probe
scripts/setup_litertlm_android.sh manual push (used by install.sh)
scripts/run_{cpu,gpu}_smoke.sh    raw adb-shell smokes (skip APK)
scripts/run_litertlm_benchmark.sh long-prompt benchmark
scripts/parse_litertlm_logs.sh    extract BenchmarkInfo to logs/summary.txt
scripts/sha256_manifest.txt       7 pinned hashes for binary + .so + model

android/                          Android Studio project (Kotlin)
  app/src/main/kotlin/dev/temuxllm/
    LlmService.kt                 foreground service
    HttpServer.kt                 NanoHTTPD on 127.0.0.1:11434
    LiteRtLmRunner.kt             ProcessBuilder wrapper around the CLI binary
    _lib.sh                       shared shell validation helpers

docs/specs/                       phase-2 architecture spec
docs/plans/                       phase-1 + phase-2a implementation plans
docs/findings-*.md                test results with real numbers per device
docs/screenshots/                 phone-screen captures of Termux running it
```

---

## API

```
GET  /healthz       -> "ok"
GET  /api/version   -> {service, phase, binary, default_backend, model_path, ...}
GET  /api/tags      -> {models: [{name, path, size_bytes}, ...]}
POST /api/generate  -> {response, exit_code, ttft_seconds, prefill_tokens_per_sec,
                        decode_tokens_per_sec, total_duration_ms, ...}
```

`/api/generate` body:

```json
{
  "prompt":   "Hi",
  "backend":  "cpu" | "gpu",
  "model_path": "/optional/override.litertlm",
  "timeout_ms": 120000
}
```

The service binds only to `127.0.0.1`. Verified after every restart with
`ss -tnlp | grep 11434` showing `[::ffff:127.0.0.1]:11434` (never `0.0.0.0`).

---

## Constraints (kept from the original brief)

- No root.
- arm64-v8a only.
- Localhost-only HTTP. Never exposed beyond loopback.
- Runtime is LiteRT-LM loading `.litertlm` (not the older `.tflite` / `.task`).

---

## Performance reference (Qwen3-0.6B, 16-token prompt)

| | TTFT | Prefill | Decode | Init Total |
|---|---|---|---|---|
| S21+ CPU (cold) | 0.68 s | 25.6 t/s | 17.9 t/s | 1.78 s |
| S21+ GPU (cold) | 0.24 s | 79.2 t/s | 26.0 t/s | 10.7 s |
| S25 CPU (warm) | 0.62 s | 27.8 t/s | 18.2 t/s | 0.55 s |
| S25 GPU (warm) | 0.21 s | 86.4 t/s | 27.1 t/s | 9.4 s |

Performance reference (gemma-4-E2B, "Reply with just: hi."):

| | TTFT | Prefill | Decode |
|---|---|---|---|
| S21+ CPU | 1.45 s | 11.1 t/s | 10.2 t/s |
| S21+ GPU | 0.42 s | 39.7 t/s | 24.7 t/s |

GPU init pays a ~10-15 s OpenCL kernel-compile cost on first run; subsequent runs
reuse the cache (it persists in the app's filesDir). CPU first-run init pays
~3-7 s rebuilding the XNNPack weight cache; warm init drops to ~0.5-0.8 s.

---

## Uninstall

```bash
adb shell am force-stop dev.temuxllm.service
adb uninstall dev.temuxllm.service
adb shell rm -rf /data/local/tmp/litertlm /data/local/tmp/bin/litertlm
```
