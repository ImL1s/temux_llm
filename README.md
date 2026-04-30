# temux_llm — LiteRT-LM on Android, CLI-first

Phase 1 goal: validate the official LiteRT-LM Android arm64 native CLI on a
Samsung Galaxy S21+ (SM-G9960, Snapdragon 888, Adreno 660, Android 15) running
`gemma-4-E2B-it.litertlm` with CPU and GPU backends, then decide GO/NO-GO for
Phase 2 (Android foreground service + localhost-only HTTP API for Termux).

See `docs/plans/2026-05-01-litertlm-s21plus-android-cli.md` for the implementation plan.

## Layout

```
bin/                        v0.9.0 prebuilt binary + .so (gitignored)
  litert_lm_main
  android_arm64/lib*.so
models/                     gemma-4-E2B-it.litertlm (gitignored, ~2.41 GB)
logs/                       smoke / benchmark / summary outputs (gitignored)
scripts/                    bash orchestrators (committed)
docs/                       plans + findings (committed)
```

## Quick start (after fetching artifacts)

```bash
DEVICE_SERIAL=RFCNC0WNT9H bash scripts/preflight.sh
DEVICE_SERIAL=RFCNC0WNT9H bash scripts/cleanup_device_tmp.sh   # interactive
DEVICE_SERIAL=RFCNC0WNT9H bash scripts/setup_litertlm_android.sh
DEVICE_SERIAL=RFCNC0WNT9H bash scripts/run_cpu_smoke.sh
DEVICE_SERIAL=RFCNC0WNT9H bash scripts/run_gpu_smoke.sh
BACKEND=cpu DEVICE_SERIAL=RFCNC0WNT9H bash scripts/run_litertlm_benchmark.sh
BACKEND=gpu DEVICE_SERIAL=RFCNC0WNT9H bash scripts/run_litertlm_benchmark.sh
bash scripts/parse_litertlm_logs.sh
```

## Hard constraints (from the brief)

- No root.
- No Termux-native LiteRT-LM in phase 1 (Termux is client only via 127.0.0.1).
- No UI, no model marketplace.
- Bind only to 127.0.0.1, never 0.0.0.0.
- CPU smoke must pass before any GPU work.
