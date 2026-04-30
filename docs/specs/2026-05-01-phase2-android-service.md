# Phase 2 Spec — Android Foreground Service + Localhost HTTP API for LiteRT-LM

**Date:** 2026-05-01
**Builds on:** Phase 1 findings (`docs/findings-2026-05-01-s21plus.md`) — both S21+ and S25 PASS the four-rule GPU usefulness check.

---

## Goal

Termux user types `curl 127.0.0.1:11434/api/generate -d '{"model":"qwen3-0.6b","prompt":"hi"}'` and gets streamed token output. The model actually runs through LiteRT-LM with the GPU backend on Adreno 660 / Adreno 830. No data leaves the device.

This unblocks the brief's stated end-state: CLI / agents on Android calling a local LLM the same way they'd call Ollama.

---

## Hard constraints (from brief, mandatory)

1. Bind only to `127.0.0.1`, never `0.0.0.0`.
2. No root.
3. Android target arm64-v8a only.
4. Model runtime is LiteRT-LM loading `.litertlm` files (verified Phase 1 path).
5. Termux is *only* a client (curls localhost). Termux-native LiteRT-LM is out of scope.
6. No UI in Phase 2; no model marketplace; no over-the-network exposure.
7. Phase 2 service must own the GPU lifecycle so OpenCL/Adreno is reachable.

---

## Architecture decision: subprocess wrapper

The brief mentions "Android Kotlin/Java API" for LiteRT-LM, but recon (2026-05-01) finds:

- `com.google.ai.edge:litert-lm:*` — **NOT** published to Maven.
- `com.google.ai.edge.litert:litert:2.1.4` — exists, but loads `.tflite` only, not `.litertlm`.
- `com.google.mediapipe:tasks-genai` — exists, but uses `.task` format (different from `.litertlm`).
- LiteRT-LM v0.10.x — released for Linux/macOS/Windows, **no Android arm64 binary asset**.
- LiteRT-LM v0.9.0 — has a working `litert_lm_main.android_arm64` CLI; this is what Phase 1 validated.

**Therefore Phase 2 MVP wraps the v0.9.0 CLI as a child process** spawned by an Android foreground service. The service exposes a localhost HTTP server. Tradeoffs:

| Aspect | Subprocess wrapper (chosen) | In-process API (rejected — not available) | Long-lived server binary (deferred) |
|---|---|---|---|
| Brief compliance | ✓ runtime is LiteRT-LM loading `.litertlm` | n/a | ✓ same |
| Effort to MVP | low (use v0.9.0 binary as-is) | n/a | high (Bazel build of LiteRT-LM main + C++ server scaffolding) |
| Per-request init cost | 1.5 s (CPU) / 11.9 s (GPU first run; XNNPack cache warm reuses) | n/a (one-time) | n/a (one-time) |
| GPU access | service runs from app sandbox; needs ABI access | n/a | same |
| Model load amortization | none in MVP — each request reloads | full | full |

**MVP gives us the right behavior at the cost of init latency.** A v0.10.x successor or a custom long-lived binary will replace the wrapper later (out of scope here).

### Mitigation for per-request init

XNNPack writes a weight cache file alongside the model. After the first run on a given device, the cache exists and subsequent loads are dramatically faster (Phase 1 measured init ≈ 1.5 s on S25 CPU). The OpenCL kernel cache also persists. So **the second and subsequent requests are fast enough** — only the cold first request after install is slow.

---

## API surface (ollama-compatible subset)

```
POST http://127.0.0.1:11434/api/generate
Content-Type: application/json

{
  "model":   "qwen3-0.6b",         // string; selects which .litertlm to use
  "prompt":  "Hello, world.",       // string
  "backend": "gpu",                 // optional: "cpu"|"gpu"; default "gpu"
  "stream":  true                   // optional bool; default true
}
```

Response (when `stream: true`): newline-delimited JSON, one line per generated token, terminating with a `done` line:

```
{"model":"qwen3-0.6b","response":"Hello"}
{"model":"qwen3-0.6b","response":"!"}
{"model":"qwen3-0.6b","response":""," done":true,"total_duration_ms":2730,"prefill_speed":..,"decode_speed":..}
```

Optional secondary endpoints:

- `GET /api/tags` — lists installed `.litertlm` files in the app's filesDir
- `GET /api/version` — returns the wrapper's version + the v0.9.0 binary build id
- `GET /healthz` — 200 OK, plaintext "ok"

The shape is compatible with `ollama` clients (Termux's `ollama-cli`, `curl`-based scripts) so existing tooling works.

---

## Components

```
+-------------------------+        +---------------------------------+
| Termux (client)         |        | TermuxLLMService (Android app)  |
|   curl 127.0.0.1:11434  |  HTTP  |  ┌───────────────────────────┐  |
|                         | <----> |  │ Foreground Service         │ |
|                         |  loop  |  │   - lifecycle owner        │ |
|                         | back   |  │   - holds notification     │ |
+-------------------------+        |  │                            │ |
                                   |  │ ┌────────────────────────┐ │ |
                                   |  │ │ Embedded HTTP server   │ │ |
                                   |  │ │  - Ktor or NanoHTTPD   │ │ |
                                   |  │ │  - bind 127.0.0.1:11434│ │ |
                                   |  │ │  - SSE / NDJSON stream │ │ |
                                   |  │ └─────────┬──────────────┘ │ |
                                   |  │           │                │ |
                                   |  │ ┌─────────▼──────────────┐ │ |
                                   |  │ │ LiteRtLmRunner         │ │ |
                                   |  │ │  - spawns v0.9.0 binary│ │ |
                                   |  │ │    via ProcessBuilder  │ │ |
                                   |  │ │  - parses BenchmarkInfo│ │ |
                                   |  │ │  - parses generated    │ │ |
                                   |  │ │    text from stdout    │ │ |
                                   |  │ └─────────┬──────────────┘ │ |
                                   |  └───────────│────────────────┘ |
                                   |              │                  |
                                   |  ┌───────────▼───────────────┐  |
                                   |  │ filesDir/litertlm/         │ |
                                   |  │   litert_lm_main           │ |
                                   |  │   *.so (6 files, v0.9.0)   │ |
                                   |  │   model.litertlm           │ |
                                   |  │   model.litertlm.xnnpack…  │ |
                                   |  └────────────────────────────┘ |
                                   +---------------------------------+
```

The artifact bundle (binary + .so + model) ships *inside the APK* in `assets/`. On first launch the service copies them to `filesDir/litertlm/` and chmods the binary +x. From then on, every request is a `ProcessBuilder` invocation.

---

## Tech stack

- **Build:** Gradle 8.x via `./gradlew` wrapper. Android Gradle Plugin 8.x.
- **Language:** Kotlin (services, HTTP handler).
- **HTTP:** NanoHTTPD (`org.nanohttpd:nanohttpd:2.3.1` — small, no Coroutines required, hard-bind 127.0.0.1 trivial). Alternative: Ktor server, heavier.
- **Min SDK:** 33 (Android 13) — covers S21+ Android 15 and S25 Android 16; gives access to modern foreground-service typing.
- **Target SDK:** 35 (matches the SDK we have under `~/development/android-sdk/platforms/android-35`).
- **Toolchain on host:** OpenJDK 21 ✓, Android SDK 35 ✓, NDK 28 ✓, Gradle wrapper auto-bootstraps.
- **No NDK code in MVP** — we ship the prebuilt v0.9.0 binary in `assets/`. NDK is available for later if we add a native helper.

---

## Phased delivery

### Phase 2a — scaffold + HTTP skeleton (this plan covers this)
- Bootstrap Gradle project at `android/`.
- Foreground service with notification.
- NanoHTTPD on `127.0.0.1:11434`, returns hard-coded JSON.
- Build first APK; install on S25; verify `curl 127.0.0.1:11434/healthz` returns "ok".
- **Stop here** for review.

### Phase 2b — model loading and inference (next plan)
- Asset bundling (binary + .so + model).
- LiteRtLmRunner that spawns subprocess and parses output.
- Wire `/api/generate` to the runner; stream tokens.
- Test from Termux client (or `adb shell curl`).

### Phase 2c — productionization (future)
- Multi-model selection.
- XNNPack cache priming on install.
- Replace v0.9.0 wrapper with a long-lived server binary (built from LiteRT-LM main).
- Termux-side `ollama`-compatible client wrapper script.

---

## Out of scope for Phase 2

- UI (no Activity beyond what's needed to start the service).
- Authentication (localhost-only; no auth needed).
- Multi-user, model marketplace, hot-swap.
- Phase 2c items above.
- iOS / non-arm64 / non-Android targets.

---

## Open risks (to watch in 2a / 2b)

| Risk | Mitigation |
|---|---|
| Foreground service can't dlopen `libOpenCL.so` from app sandbox (vs. `adb shell` we tested) | Phase 1 confirmed `/vendor/etc/public.libraries.txt` allowlists `libOpenCL.so`. App linker namespace inherits that allowlist for SP_HAL libs. Verify in 2b smoke. |
| App can't `chmod +x` files in filesDir / `exec()` is restricted on newer Android | App-private filesDir on Android 13+ permits exec on owned binaries. Will verify in 2a. |
| APK size: shipping ~660 MB of assets (37 MB binary + 47 MB .so + 614 MB model) is huge | Use `app bundle` + resource asset packs / AssetDelivery on first launch, OR fetch model from device-local source (`/sdcard/Download/`). Decision deferred to 2b. |
| Per-request init cost still painful | XNNPack/OpenCL caches warm after first run. Time them; if still bad, consider 2c long-lived server. |

---

## Files of record

This spec lives at `docs/specs/2026-05-01-phase2-android-service.md`.
The 2a implementation plan will live at `docs/plans/2026-05-01-phase2a-android-scaffold.md`.
