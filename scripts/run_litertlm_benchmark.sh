#!/usr/bin/env bash
# scripts/run_litertlm_benchmark.sh — runs benchmark in {cpu,gpu} mode per BACKEND env.
# Usage: BACKEND=cpu bash scripts/run_litertlm_benchmark.sh
#        BACKEND=gpu bash scripts/run_litertlm_benchmark.sh
set -euo pipefail
DEVICE_SERIAL="${DEVICE_SERIAL:-RFCNC0WNT9H}"
DEVICE_FOLDER="${DEVICE_FOLDER:-/data/local/tmp/litertlm}"
BACKEND="${BACKEND:?set BACKEND=cpu or BACKEND=gpu}"
PREFILL="${PREFILL:-1024}"
DECODE="${DECODE:-256}"
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG="$PROJECT_ROOT/logs/${BACKEND}_benchmark.log"

mkdir -p "$PROJECT_ROOT/logs"
{
  echo "=== ${BACKEND^^} benchmark @ $(date -u +%FT%TZ) ==="
  echo "device:        $DEVICE_SERIAL"
  echo "prefill:       $PREFILL"
  echo "decode:        $DECODE"
  echo "async:         false"
  echo "peak memory:   on"
  echo
} | tee "$LOG"

LD_PREFIX=""
[[ "$BACKEND" == "gpu" ]] && LD_PREFIX="LD_LIBRARY_PATH=$DEVICE_FOLDER"

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
  --benchmark \
  --benchmark_prefill_tokens=$PREFILL \
  --benchmark_decode_tokens=$DECODE \
  --async=false \
  --report_peak_memory_footprint" 2>&1 | tee -a "$LOG"

echo "log: $LOG"
