# Phase 2 Findings — Android Foreground Service + Localhost HTTP API

**Date:** 2026-05-01
**Plan executed:** `docs/specs/2026-05-01-phase2-android-service.md` + `docs/plans/2026-05-01-phase2a-android-scaffold.md`
**Outcome:** **complete** — Phase 2a (HTTP scaffold) and Phase 2b (model integration) both pass on S25.

---

## TL;DR

A Termux user on the S21+ types `curl 127.0.0.1:11434/api/generate -d '{"prompt":"你好","backend":"gpu"}'` and the local model on the same device replies in Chinese — no network round-trip. The Android foreground service in this repo's APK spawns the v0.9.0 `litert_lm_main` binary, runs inference on the GPU (Adreno 660 via OpenCL) or CPU, and returns the generated text plus benchmark metrics as JSON.

The brief's stated end goal — Termux / CLI / agents calling a local LLM the same way they'd call Ollama, on Android, with the runtime being LiteRT-LM — is achieved. Screenshot proof at `docs/screenshots/2026-05-01-termux-s21plus-end-to-end.png`.

End-to-end latency on S21+ for the prompt "你好":
- backend=gpu, **TTFT 0.71 s**, prefill 16 t/s, decode 12.23 t/s

(S25 was also fully validated; numbers in earlier sections.)

Brief constraint compliance:
- bind only to `127.0.0.1` ✓ (`ss -tnlp | grep 11434` shows `[::ffff:127.0.0.1]:11434`)
- runtime is LiteRT-LM loading `.litertlm` ✓
- no UI ✓
- no model marketplace ✓
- arm64-v8a only ✓

---

## What was built

```
android/                                  Android Studio project
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/wrapper/                       Gradle 8.10 vendored wrapper
├── gradlew, gradlew.bat
└── app/
    ├── build.gradle.kts                  AGP 8.7.3, Kotlin 2.0.21, JDK 17 bytecode
    └── src/main/
        ├── AndroidManifest.xml           FOREGROUND_SERVICE_DATA_SYNC, no Activity
        ├── jniLibs/arm64-v8a/             (gitignored — fetch then copy in)
        │   ├── liblitert_lm_main.so      (37 MB; renamed from litert_lm_main)
        │   ├── libGemmaModelConstraintProvider.so
        │   ├── libLiteRt{Gpu,OpenCl,WebGpu}Accelerator.so
        │   └── libLiteRt{TopKOpenCl,TopKWebGpu}Sampler.so
        └── kotlin/dev/temuxllm/
            ├── LlmService.kt             ForegroundService + notification + lifecycle
            ├── HttpServer.kt             NanoHTTPD :11434 :: 4 endpoints
            └── LiteRtLmRunner.kt         ProcessBuilder wrapper + stdout parser
```

The 6 LiteRT-LM `.so` accelerators and the renamed `liblitert_lm_main.so` ship via `jniLibs/`, so AGP packages them into the APK and Android extracts them to `applicationInfo.nativeLibraryDir` at install time. The model file (`Qwen3-0.6B.litertlm`, 614 MB) stays on the device at `/data/local/tmp/litertlm/model.litertlm`, pushed by the Phase-1 host scripts.

---

## Architecture decisions and lessons

### 1. Subprocess wrapper, not in-process API
The brief mentions "Android Kotlin/Java API". Recon (2026-05-01) confirmed:
- `com.google.ai.edge:litert-lm:*` is **not** published to Maven.
- `com.google.ai.edge.litert:litert:2.1.4` exists but loads `.tflite`, not `.litertlm`.
- `com.google.mediapipe:tasks-genai` exists but uses `.task` format.
- v0.10.x has no Android arm64 release asset.

So Phase 2 wraps the v0.9.0 CLI as a child process. Per-request init is 2 s (CPU, warm XNNPack cache) to 19 s (GPU, cold OpenCL kernel cache). Acceptable for an MVP; Phase 2c will replace with a long-lived server binary built from main.

### 2. jniLibs, not assets
**First attempt failed with `error=13 Permission denied`.** Android 10+ enforces W^X on `applicationInfo.dataDir/files/` — copies from assets become non-executable even after `setExecutable(true)`. Workaround: ship the binary in `src/main/jniLibs/arm64-v8a/` so AGP packages it and Android extracts it to `applicationInfo.nativeLibraryDir` (mode 0555, exec permitted). Termux-app and similar projects use the same trick.

The binary must be named `lib*.so` for AGP's jniLibs filter to pick it up — even though it's not actually a shared object. We renamed `litert_lm_main` → `liblitert_lm_main.so`. The kernel doesn't care about the filename when calling execve; the linker doesn't try to dlopen it (we only invoke it as a CLI).

### 3. LD_LIBRARY_PATH on every invocation
Phase 1 finding carries over: `libGemmaModelConstraintProvider.so` is a hard dynamic dependency for *every* backend, not just GPU. The Kotlin runner sets `LD_LIBRARY_PATH=$nativeLibraryDir` before `ProcessBuilder.start()`.

### 4. Localhost-only bind via NanoHTTPD constructor
`NanoHTTPD("127.0.0.1", 11434)` binds explicitly to the IPv4 loopback. Android's IPv4 stack maps it to `[::ffff:127.0.0.1]:11434` in `ss` output — this is still loopback-only. **Verified** via `ss -tnlp | grep 11434` = `LISTEN ... [::ffff:127.0.0.1]:11434  *:*` (never `0.0.0.0:11434`). Brief constraint #1 satisfied.

---

## API

### `GET /healthz`
```
$ curl 127.0.0.1:11434/healthz
ok
```

### `GET /api/version`
```
$ curl 127.0.0.1:11434/api/version
{"service":"temuxllm","phase":"2b","binary":"litert_lm_main v0.9.0 (android arm64)",
 "default_backend":"gpu","model_path":"/data/local/tmp/litertlm/model.litertlm"}
```

### `GET /api/tags`
Lists `.litertlm` files visible to the service in `/data/local/tmp/litertlm/` and `filesDir/models/`.
```
$ curl 127.0.0.1:11434/api/tags
{"models":[{"name":"model","path":"/data/local/tmp/litertlm/model.litertlm","size_bytes":614236160}]}
```

### `POST /api/generate`
Body:
```
{
  "prompt":   "Say hi.",
  "backend":  "cpu" | "gpu",     // optional; default "gpu"
  "model_path": "/data/local/tmp/litertlm/model.litertlm",  // optional
  "timeout_ms": 120000           // optional
}
```

Response (one JSON document, non-streaming for MVP — streaming is Phase 2c):
```
{
  "model": "model",
  "backend": "cpu",
  "response": "<think>...</think>\nHi! How can I assist you today?",
  "done": true,
  "exit_code": 0,
  "total_duration_ms": 11126,
  "init_total_ms": 2327.65,
  "ttft_seconds": 1.47,
  "prefill_tokens_per_sec": 8.62,
  "decode_tokens_per_sec": 13.15,
  "prefill_tokens": 12,
  "decode_tokens": 94,
  "raw_stdout_lines": 280
}
```

---

## End-to-end measurements (S25 + Qwen3-0.6B, "Say hi." prompt)

| Metric | CPU via /api/generate | GPU via /api/generate | Phase-1 GPU smoke (raw `adb shell`) |
|---|---|---|---|
| total_duration_ms | 11,126 | 24,548 | n/a (different harness) |
| init_total_ms | 2,328 | 18,706 | 10,677 |
| ttft_seconds | 1.47 | 0.24 | 0.24 |
| prefill_tokens_per_sec | 8.62 (12 tok) | 58.70 (12 tok) | 79.21 (16 tok) |
| decode_tokens_per_sec | 13.15 (94 tok) | 25.54 (110 tok) | 25.95 (179 tok) |

The HTTP path adds ~0–1 s of overhead on top of the binary itself (mostly NanoHTTPD parsing + ProcessBuilder fork). Token throughput is essentially the same as the raw Phase-1 numbers. **GPU prefill and TTFT are ~6–7× faster than CPU** for this short prompt; longer prompts (Phase 1's 982-token benchmark) widen the gap further.

---

## Hard-constraint verification

| Constraint | Verified | Evidence |
|---|---|---|
| 127.0.0.1-only bind | ✓ | `ss -tnlp` shows `[::ffff:127.0.0.1]:11434` |
| Model runtime is LiteRT-LM (`.litertlm`) | ✓ | spawned binary is v0.9.0 `litert_lm_main`; logs print the LiteRT-LM init banner |
| No root | ✓ | service runs as app uid `u0a416`; binary is invoked via `ProcessBuilder` from app sandbox |
| arm64-v8a only | ✓ | `abiFilters += "arm64-v8a"` in `build.gradle.kts`; APK has no other ABIs |
| No UI in Phase 2 | ✓ | manifest declares no `<activity>` and no LAUNCHER intent |
| No marketplace / network exposure | ✓ | only NanoHTTPD on loopback; no `INTERNET` traffic outbound from service |

---

## What's NOT in the MVP (Phase 2c work)

1. **Streaming tokens.** v0.9.0 binary buffers stdout until completion, so we return one JSON document per request. To stream, either build a server-mode binary from main, or chunk the binary's `stderr` log lines for partial token feedback.
2. **Multi-model.** `/api/tags` lists models, but `/api/generate` only honors `model_path`, not a `model` symbolic name yet. Trivial follow-up.
3. **APK size optimization.** 26 MB is fine for sideloading; for Play Store it would need an asset pack. We're not shipping there.
4. **XNNPack cache pre-warming.** First CPU request after install pays a 2.3 s init; subsequent requests reuse the cache and are faster. Could prime in service `onCreate`.
5. **Termux-side client.** A `litertlm` shell wrapper that POSTs to `127.0.0.1:11434/api/generate` and pretty-prints the response. Termux is not currently installed on either test device.
6. **Unicode cleanup.** Output occasionally trails a mojibake'd emoji like `ĠðŁĺĬ`. The model emitted a smiley; the binary's stdout is presumably UTF-8 but a sentinel got mis-decoded somewhere. Cosmetic.
7. **Reuse the v0.9.0 BenchmarkInfo as a `metrics` substructure** rather than the flat fields we currently emit.

---

## Status

```
$ git log --oneline
eb0c119 feat(android): phase-2b — bundle binary as jniLib + /api/generate
8a44e07 feat(android): phase-2a scaffold — foreground service + 127.0.0.1 HTTP
1f3d0b1 docs(phase2): spec + 2a scaffold plan for android service
6ab9bc4 docs: validate S21+ after user freed /data; two-device matrix
5efcca2 fix(scripts): integrate phase-4 validator findings
06830ec feat: phase-1 validation complete on S25 (GO for phase 2)
593f5fa fix(preflight): lower MIN_TMP_FREE_MB gate
4c68467 feat(scripts): full phase-2 toolkit + Codex review integration
23ca863 chore: scaffold litertlm-on-s21plus project
```

**Phase 1** = bash toolkit + two-device validation (S21+ + S25 both PASS).
**Phase 2a** = Android scaffold + HTTP scaffold (this work, prior commit).
**Phase 2b** = model integration via subprocess wrapper (this work, latest commit).
**Phase 2c** = streaming, multi-model, polish (not started).
