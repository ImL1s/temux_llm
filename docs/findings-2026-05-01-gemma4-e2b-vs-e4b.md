# Gemma-4 E2B vs E4B on Samsung Galaxy S21+ (Adreno 660)

**Date:** 2026-05-01
**Device:** S21+ (`RFCNC0WNT9H` / SM8350 / Adreno 660 / Android 15 / 8 GB RAM, ~2.5 GB free)
**Binary:** `litert_lm_main` v0.11.0-rc.1 (released 2026-04-30)
**Pretext:** v0.9.0 binary refused gemma-4 with `INVALID_ARGUMENT: Unsupported model type`. v0.11.0-rc.1 (just-released RC, still pre-release at the time of this test) is the first Android arm64 binary that loads it.

---

## TL;DR

On the S21+, **Gemma-4-E2B is the practical default; Gemma-4-E4B is not viable**:
- E4B GPU returns empty (likely OOM during model load on Adreno 660 with the 3.4 GB model).
- E4B CPU runs but produces tokens at **0.51 t/s** — about 2 seconds per token, unusable for interactive use.
- E2B GPU produces tokens at 16-25 t/s and TTFT under half a second. Same prompts, same hardware.

A more capable phone (e.g. S25 / SM8750 / Adreno 830 / 12 GB RAM) is expected to run E4B on GPU; the S21+ does not.

---

## Same prompt, both models (CPU)

Prompt: `"用一句繁體中文介紹自己。"`

| Model | wall | ttft | init | prefill | decode | response |
|---|---|---|---|---|---|---|
| **E2B** (CPU) | 5 s | 1.75 s | 1.08 s | 13.05 t/s | 7.07 t/s | "您好，請問有什麼需要我幫您做的嗎？" |
| **E4B** (CPU) | 65 s | 5.86 s | 16.63 s | 4.34 t/s | **0.51 t/s** | "我是一個由 Google DeepMind 開發的開放權重大型語言模型，名叫 Gemma 4。" |

Quality observation: **E4B identifies itself by name and credits Google DeepMind**; E2B does not. E4B's response is more "spec-aware" but the 0.5 t/s decode rate makes it unusable interactively on this device.

## Same prompt, both models (GPU)

Prompt: `"Reply with just: hi."`

| Model | wall | ttft | init | prefill | decode | response |
|---|---|---|---|---|---|---|
| **E2B** (GPU) | 18 s | 0.42 s | 15.10 s | 39.7 t/s | 24.7 t/s | "hi." |
| **E4B** (GPU) | 17 s | — | — | — | — | (empty) |

E4B GPU **returns 0 bytes** at curl's 240-second cap. Service stays up afterward (`/healthz` still returns `ok`). No abort/Check-failed in logcat — the binary appears to fail silently inside the model-load phase, likely OpenCL allocation refusal on Adreno 660. Mobile GPUs share system RAM; with only ~2.5 GB free at test time, the GPU couldn't get the working set it needed for a 3.4 GB model.

## E2B headline numbers (default)

After restoring E2B as the active model:

```
$ curl -X POST 127.0.0.1:11434/api/generate -d '{"prompt":"hi","backend":"gpu"}'
{"response":"Hi! How can I help you today? 😊",
 "exit_code":0, "ttft_seconds":0.32, "init_total_ms":15119.69,
 "prefill_tokens_per_sec":..., "decode_tokens_per_sec":16.05, ...}
```

GPU init is 15 s (one-time OpenCL kernel-compile + program-cache build); subsequent runs reuse the cache and are much faster.

---

## Recommendation matrix

| Device class | Recommended model | Why |
|---|---|---|
| S21+ / SD 888 / Adreno 660 / 8 GB | **gemma-4-E2B** | Fits in unified RAM, GPU usable, decode 16-25 t/s |
| S25 / SD 8 Elite / Adreno 830 / 12 GB | **gemma-4-E4B** (likely) | More headroom, expected to run on GPU; not yet retested here |
| Older / lower RAM | Qwen3-0.6B (smaller, faster, but `<think>` ramble in output) | E2B's 2.4 GB may not fit |

---

## Other small models (sanity, no testing today)

`litert-community/` HuggingFace org has these pre-built `.litertlm` chat models as of 2026-05-01:

- gemma-4-E2B-it (active default; 2.4 GB)
- gemma-4-E4B-it (3.4 GB; doesn't run on S21+)
- Gemma3-1B-IT, Gemma3-4B-IT, Gemma3-12B-IT, Gemma3-27B-IT, Gemma2-2B-IT (Gemma 3 generation; gated)
- Qwen3-0.6B (614 MB; smallest viable chat model; rambles in `<think>` block)
- Qwen3-4B, Qwen3-8B, Qwen3-14B (all uploaded 2026-04-21)
- Qwen2.5-{0.5,1.5}B-Instruct, TinyLlama-1.1B, SmolLM-135M, Phi-4-mini-instruct, DeepSeek-R1-Distill-Qwen-1.5B

There is no "Qwen 3.5/3.6/4" yet. Qwen3-0.6B remains the smallest official chat checkpoint; for higher quality on this hardware class, gemma-4-E2B beats Qwen3-0.6B in instruction-following and brevity (Qwen3 always emits `<think>...</think>` reasoning preamble before answering).

---

## Files of record

- `models/gemma-4-E2B-it.litertlm` (default, sha256 `ab7838cd...`, 2,583,085,056 B)
- `models/gemma-4-E4B-it.litertlm` (downloaded for this test, sha256 `f335f2bf...`, 3,654,467,584 B; **no manifest entry — kept locally for future S25 testing**)
- `bin/litert_lm_main` v0.11.0-rc.1 (sha256 `2e1fed4e...`)
- All commits in this Phase-2 test cycle on `main`; latest is `3a7cb2e feat: upgrade litert_lm_main v0.9.0 → v0.11.0-rc.1; gemma-4 now works`.
