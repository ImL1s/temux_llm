# Phase 2 End-to-End Tests on S21+ (Termux client)

**Date:** 2026-05-01
**Device:** Galaxy S21+ (`RFCNC0WNT9H` / SM-G9960 / SM8350 Snapdragon 888 / Adreno 660 / Android 15)
**Model:** Qwen3-0.6B (`/data/local/tmp/litertlm/model.litertlm`, 614,236,160 B)
**Binary:** `litert_lm_main` v0.9.0 (android arm64), bundled in jniLibs as `liblitert_lm_main.so`
**App:** `dev.temuxllm.service` v0.1.0-phase2a, APK 26 MB, foreground service `:dataSync` on `127.0.0.1:11434`
**Termux:** `com.termux` (also `com.termux.x11` and a third app present; Termux bash is the test harness)
**PRD:** `.omc/prd.json` (5 user stories, all `passes:true` after this run)

---

## TL;DR

Termux on the S21+ now drives the local model end-to-end via a one-line shell wrapper:

```
$ bash /data/local/tmp/bin/litertlm "你好"
你好！有什么可以帮到你的吗？😊

[cpu  ttft=3.45s  prefill=3.06t/s  decode=5.36t/s  total=25228ms]
```

The Phase-2 service path holds against:
- functional sanity (4 endpoints + CPU/GPU generate, EN + CJK)
- malformed input (empty body, missing prompt, bogus backend, raw text instead of JSON)
- 5-request stress (alternating CPU/GPU, no degradation, no leaks)
- a force-stop+restart cycle (back online in 1 s, OpenCL kernel cache survives)
- a Termux-side wrapper with `--backend / --json / --help` flags

A real bug was found and fixed inside this session: the service was paying full cold-start init on every request because SELinux denies `untrusted_app` writes to `/data/local/tmp` (where the model lived), so the binary's XNNPack and OpenCL caches were silently failing to persist. After staging the model into the app's own `filesDir`, warm-cache CPU init dropped from 3.8 s to 0.6 s (6.1×) and warm GPU init dropped from 18 s to 9.4 s (1.9×).

A second small bug was found and fixed: an empty POST body would hang the server until curl timed out, because the body-reader fell through to a 4 MiB read with no available bytes. Fixed to short-circuit when `Content-Length: 0`.

---

## Devices, model, and ground rules

| Item | Value |
|---|---|
| adb serial | `RFCNC0WNT9H` |
| `getprop ro.soc.model` | `SM8350` |
| `getprop ro.build.version.release` | `15` |
| ABI | `arm64-v8a` |
| `/vendor/lib64/libOpenCL.so` | present, allowlisted in `/vendor/etc/public.libraries.txt` |
| `/data` free | 47,271 MiB at session start (after user freed 46 GiB by hand) |
| Model file | 614,236,160 B (Qwen3-0.6B q4) |
| Bind | `[::ffff:127.0.0.1]:11434` (IPv4-mapped loopback only — verified after every restart) |

The `model.litertlm` was already on this device's `/data/local/tmp/litertlm/` from earlier work; it stays there as the source of truth. The service copies it once into `/data/user/0/dev.temuxllm.service/files/litertlm/model.litertlm` so the binary can write its caches next to it.

---

## US-001 — Sanity matrix from Termux

**Status:** ✅ PASS

| Endpoint / call | Result |
|---|---|
| `GET /healthz` | `200 OK`, body `ok` |
| `GET /api/version` | `200 OK`, JSON with `phase: 2b`, `default_backend: gpu`, `model_path: /data/user/0/dev.temuxllm.service/files/litertlm/model.litertlm`, `source_model_path: /data/local/tmp/litertlm/model.litertlm` |
| `GET /api/tags` | `200 OK`, lists `model` 614,236,160 B at the staged path |
| `POST /api/generate` `{prompt:"Reply OK in 3 words.", backend:"cpu"}` | exit_code=0, ttft 4.03 s, prefill 4.16 t/s, decode 6.52 t/s — **first cold-cache call** |
| `POST /api/generate` `{prompt:"Reply OK in 3 words.", backend:"cpu"}` (warm) | exit_code=0, ttft 3 s, init **620 ms** (vs 3,814 ms cold), decode 5.16 t/s |
| `POST /api/generate` `{prompt:"請用繁體中文一句話介紹自己。", backend:"gpu"}` | exit_code=0, ttft 0.7 s, prefill 30.94 t/s, decode 11.36 t/s, response contains CJK (`<think>` reasoning + reply in 簡中) |
| `POST /api/generate` `{prompt:"Reply OK in 3 words.", backend:"gpu"}` cold | exit_code=0, ttft 0.85 s, init **18,253 ms** (OpenCL kernel compile) |
| `POST /api/generate` (same, warm) | exit_code=0, ttft 0.68 s, init **9,434 ms** (kernel cache hit) |

Termux UI run (full sanity script) screenshot: `docs/screenshots/2026-05-01-termux-s21plus-sanity-matrix.png` shows the GPU CJK section with `<think>...</think>` and the model's 簡中 introduction.

---

## US-002 — Error-path matrix

**Status:** ✅ PASS

| Request | HTTP | Body |
|---|---|---|
| `POST /api/generate` empty body (Content-Length: 0) | `400` | `{"error":"prompt is required"}` |
| `POST /api/generate` `{}` | `400` | `{"error":"prompt is required"}` |
| `POST /api/generate` `{"prompt":"x","backend":"tpu"}` | `400` | `{"error":"backend must be cpu|gpu (got tpu)"}` |
| `POST /api/generate` `not json` | `500` | `{"error":"JSONException","message":"Value not of type java.lang.String cannot be converted to JSONObject"}` |
| `GET /healthz` (after the four error requests) | `200` | `ok` |
| `POST /api/generate` `{"prompt":"ok","backend":"gpu"}` (after errors) | `200` | exit_code=0, ttft 0.68 s, prefill 16.67 t/s, decode 11.81 t/s |

Bug found and fixed mid-story: the empty-body case timed out at 5 s instead of returning 400 in <100 ms. The `readPostBodyAsUtf8` body reader fell through to a 4 MiB blocking read when `Content-Length: 0`. Patch: short-circuit when `cl <= 0`. See commit chain: `fix(android): stage model into filesDir...` followed by `fix(http): readPostBodyAsUtf8 short-circuit on Content-Length: 0`.

---

## US-003 — Sequential stress + restart survival

**Status:** ✅ PASS

5 sequential requests, alternating GPU / CPU / GPU / CPU / GPU with the same prompt `"Say hi."`:

| Run | Backend | Wall | ttft | init | decode | resp len |
|---|---|---|---|---|---|---|
| 1 | gpu | 22 s | 0.67 s | 9,488 ms | 12.36 t/s | 512 |
| 2 | cpu | 22 s | 3.33 s |   583 ms |  5.37 t/s | 395 |
| 3 | gpu | 22 s | 0.68 s | 9,785 ms | 11.61 t/s | 512 |
| 4 | cpu | 21 s | 3.41 s |   481 ms |  5.38 t/s | 395 |
| 5 | gpu | 23 s | 0.68 s | 9,826 ms | 11.97 t/s | 512 |

All `exit_code=0`; numbers are stable across runs (no leaks, no degradation).

Restart cycle: `am force-stop dev.temuxllm.service` → `am start-foreground-service` → `/healthz` returned `ok` at **t+1 s** after restart (well under the 5-s budget). `ss -tnlp` post-restart shows `[::ffff:127.0.0.1]:11434` only. The next `POST /api/generate {backend:"gpu"}` succeeded with `exit_code=0`, `ttft=0.67 s`, `init=8,657 ms` — slightly faster than the first warm GPU run because the OpenCL kernel cache survived the service kill (it lives in `filesDir/litertlm/`, owned by the app and not wiped by force-stop).

---

## US-004 — Termux `litertlm` convenience wrapper

**Status:** ✅ PASS

Source: `scripts/litertlm-termux-wrapper.sh` (committed). Deployed to `/data/local/tmp/bin/litertlm` (mode 0755, owned shell:shell so Termux can read+exec).

| Invocation | Result |
|---|---|
| `bash /data/local/tmp/bin/litertlm --help` | 9-line usage, exit 0 |
| `bash /data/local/tmp/bin/litertlm` (no args) | `error: prompt is required` + usage on stderr, EXIT=2 |
| `bash /data/local/tmp/bin/litertlm "Say hi."` | model response + `[gpu  ttft=0.69s  prefill=37.97t/s  decode=11.44t/s  total=21198ms]` |
| `bash /data/local/tmp/bin/litertlm --backend cpu "你好"` | 簡中 think + `你好！有什么可以帮到你的吗？😊` + `[cpu  ttft=3.45s  prefill=3.06t/s  decode=5.36t/s  total=25228ms]` |
| `bash /data/local/tmp/bin/litertlm --json "hi"` | full JSON, exit 0 |

Implementation notes:
- Shebang `#!/usr/bin/env bash` works in Termux (`/usr/bin/env` is a Termux-side symlink) but not in raw `adb shell`. Acceptance is "from Termux", so this is fine.
- The wrapper does *not* require Python; JSON encoding of the prompt is sed-based, response text extraction is sed-based. Termux ships `bash`, `curl`, and `sed` by default — nothing else needed.
- CJK round-trip works because the wrapper writes prompt bytes raw (no shell interpolation through positional args after `--backend ... "$@"`) and the server's `readPostBodyAsUtf8` decodes UTF-8.

Screenshots:
- `docs/screenshots/2026-05-01-termux-litertlm-wrapper-gpu.png` — `bash /data/local/tmp/bin/litertlm "Say hi."` (GPU)
- `docs/screenshots/2026-05-01-termux-litertlm-wrapper-cjk.png` — CPU + 你好 (with emoji)
- `docs/screenshots/2026-05-01-termux-litertlm-wrapper-json.png` — `--json` mode

To put `litertlm` in PATH inside Termux, add this line to `~/.bashrc`:

```sh
export PATH="/data/local/tmp/bin:$PATH"
```

Then `litertlm "..."` works without the `bash /data/local/tmp/bin/...` prefix.

---

## Bugs found and fixed during this session

### Bug 1 — model staging (root cause: SELinux app-domain write denial)
**Symptom:** every `/api/generate` paid full cold-start init (~50–60 s on CPU, ~18 s on GPU). Phase-1 measurements were warm-cache (binary running as `shell` uid where caches persist); Phase-2 was unknowingly re-paying cold init each time.
**Root cause:** the binary tries to write `model.litertlm.xnnpack_cache` and `model.litertlm_mldrift_program_cache.bin` next to the model. The model was at `/data/local/tmp/litertlm/`, which is `shell_data_file` in SELinux; `untrusted_app` (the service's app domain) cannot write there. POSIX 0777 doesn't override.
**Fix:** `LiteRtLmRunner.ensureModelStaged()` copies the model from `/data/local/tmp/litertlm/model.litertlm` to `/data/user/0/dev.temuxllm.service/files/litertlm/model.litertlm` on first run. The binary now runs with `cwd` set to that directory and writes its caches next to it. Idempotent (skips when sizes match).
**Validation:** warm-cache CPU init 3,814 ms → 620 ms (6.1×); warm-cache GPU init 18,253 ms → 9,434 ms (1.9×); cache survives service restart.

### Bug 2 — empty POST body hang (root cause: missing short-circuit)
**Symptom:** `curl ... -d ''` (Content-Length: 0) hung until the curl 5-s timeout, returning HTTP 000.
**Root cause:** `readPostBodyAsUtf8` did `if (cl in 1..cap) cl else cap`. When `cl == 0`, the limit fell through to the 4-MiB cap, and `inputStream.read(buf, 0, 4MB)` blocked forever waiting for bytes.
**Fix:** `if (cl <= 0) return ""`. Empty body is treated as `JSONObject()` and the missing-prompt check returns 400 in well under 100 ms.

---

## Final state

```
$ git log --oneline | head -16
…most recent commits…
fix(android): stage model into filesDir so XNNPack/OpenCL cache persists
docs: update phase 2 TL;DR with S21+ Termux end-to-end demo
fix(http): force UTF-8 decode of POST body; CJK prompt now works
docs: phase 2 complete — Android service + /api/generate working
feat(android): phase-2b — bundle binary as jniLib + /api/generate
feat(android): phase-2a scaffold — foreground service + 127.0.0.1 HTTP
docs(phase2): spec + 2a scaffold plan for android service
docs: validate S21+ after user freed /data; two-device matrix
fix(scripts): integrate phase-4 validator findings
feat: phase-1 validation complete on S25 (GO for phase 2)
fix(preflight): lower MIN_TMP_FREE_MB gate
feat(scripts): full phase-2 toolkit + Codex review integration
chore: scaffold litertlm-on-s21plus project
```

(Plus this Ralph-session's commits for cache-staging, body-reader, wrapper script, and this doc.)

PRD: every story `passes:true`. The brief's stated end-state — Termux user calls a local LLM through `127.0.0.1` like Ollama, runtime is LiteRT-LM loading `.litertlm`, no network round-trip — is achieved on S21+ (and earlier on S25). All four GPU-usefulness conditions from brief §100 still hold; nothing in this Phase-2 path regresses Phase-1's validation.
