#!/usr/bin/env bash
# scripts/run_cpu_smoke.sh — minimal CPU-backend invocation.
set -euo pipefail
DEVICE_SERIAL="${DEVICE_SERIAL:-RFCNC0WNT9H}"
DEVICE_FOLDER="${DEVICE_FOLDER:-/data/local/tmp/litertlm}"
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG="$PROJECT_ROOT/logs/cpu_smoke.log"

mkdir -p "$PROJECT_ROOT/logs"
{
  echo "=== CPU smoke @ $(date -u +%FT%TZ) ==="
  echo "device:  $DEVICE_SERIAL"
  echo "folder:  $DEVICE_FOLDER"
  echo "binary:  $(file "$PROJECT_ROOT/bin/litert_lm_main" | sed 's/.*: //')"
  echo
} | tee "$LOG"

adb -s "$DEVICE_SERIAL" shell "cd $DEVICE_FOLDER && ./litert_lm_main \
  --backend=cpu \
  --model_path=$DEVICE_FOLDER/model.litertlm \
  --input_prompt='Say hello in one short sentence.'" 2>&1 | tee -a "$LOG"

echo
echo "log: $LOG"
