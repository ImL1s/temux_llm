#!/usr/bin/env bash
# scripts/run_litertlm_benchmark.sh — runs benchmark in {cpu,gpu} mode per BACKEND env.
# Usage: BACKEND=cpu bash scripts/run_litertlm_benchmark.sh
#        BACKEND=gpu bash scripts/run_litertlm_benchmark.sh
#
# IMPORTANT: v0.9.0 litert_lm_main does NOT expose --benchmark / --benchmark_prefill_tokens /
# --benchmark_decode_tokens / --async / --report_peak_memory_footprint flags (only in newer
# main-branch builds). Verified empirically via --helpfull on the v0.9.0 binary which lists
# only --backend / --input_prompt / --input_prompt_file / --model_path.
#
# However: the v0.9.0 binary AUTO-PRINTS BenchmarkInfo (Init phases, TTFT, prefill tok/s,
# decode tok/s) on every run regardless of flags. So we drive prefill volume with a long
# input prompt (~4000 chars ≈ 1000 tokens) and let the binary print metrics naturally.
set -euo pipefail
DEVICE_SERIAL="${DEVICE_SERIAL:-RFCNC0WNT9H}"
DEVICE_FOLDER="${DEVICE_FOLDER:-/data/local/tmp/litertlm}"
BACKEND="${BACKEND:?set BACKEND=cpu or BACKEND=gpu}"
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG="$PROJECT_ROOT/logs/${BACKEND}_benchmark.log"
PROMPT_FILE="$PROJECT_ROOT/scripts/long_prompt.txt"

mkdir -p "$PROJECT_ROOT/logs"
[[ -f "$PROMPT_FILE" ]] || { echo "FATAL: missing $PROMPT_FILE (long benchmark prompt)" >&2; exit 1; }
DEVICE_PROMPT_PATH="$DEVICE_FOLDER/long_prompt.txt"
adb -s "$DEVICE_SERIAL" push "$PROMPT_FILE" "$DEVICE_PROMPT_PATH" >/dev/null

# Always clear any stale XNNPack cache before benchmark — caches from a different model or
# different backend can break loading (observed empirically when switching gemma-4 -> Qwen3).
adb -s "$DEVICE_SERIAL" shell "rm -f $DEVICE_FOLDER/model.litertlm.xnnpack_cache 2>/dev/null"

BACKEND_UPPER=$(echo "$BACKEND" | tr '[:lower:]' '[:upper:]')
{
  echo "=== ${BACKEND_UPPER} benchmark @ $(date -u +%FT%TZ) ==="
  echo "device:        $DEVICE_SERIAL"
  echo "prompt-file:   $DEVICE_PROMPT_PATH ($(wc -c < "$PROMPT_FILE") chars host-side)"
  echo
} | tee "$LOG"

# LD_LIBRARY_PATH is required for ANY backend on this binary — libGemmaModelConstraintProvider.so
# is a hard dynamic dep regardless of --backend. Empirically confirmed via setup --help failure.
LD_PREFIX="LD_LIBRARY_PATH=$DEVICE_FOLDER"

# Pin to performance cores when taskset is available (per build-and-run.md tip).
# `f0` = mask 0xF0 = upper 4 cores on most flagship Snapdragons including SM8350.
TASKSET=""
if adb -s "$DEVICE_SERIAL" shell 'command -v taskset >/dev/null 2>&1 && echo yes || echo no' | tr -d '\r' | grep -q yes; then
  TASKSET="taskset f0"
  echo "[info] taskset available — pinning to mask f0 (perf cores)" | tee -a "$LOG"
else
  echo "[info] taskset NOT available on device — running without core affinity" | tee -a "$LOG"
fi

adb -s "$DEVICE_SERIAL" shell "cd $DEVICE_FOLDER && \
  $LD_PREFIX $TASKSET ./litert_lm_main \
  --backend=$BACKEND \
  --model_path=$DEVICE_FOLDER/model.litertlm \
  --input_prompt_file=$DEVICE_PROMPT_PATH" 2>&1 | tee -a "$LOG"

echo "log: $LOG"
