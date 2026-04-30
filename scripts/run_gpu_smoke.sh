#!/usr/bin/env bash
# scripts/run_gpu_smoke.sh — GPU backend smoke. Requires LD_LIBRARY_PATH=$DEVICE_FOLDER
# so .so files (libLiteRtOpenClAccelerator.so etc.) resolve before /vendor/lib64.
set -euo pipefail
DEVICE_SERIAL="${DEVICE_SERIAL:-RFCNC0WNT9H}"
DEVICE_FOLDER="${DEVICE_FOLDER:-/data/local/tmp/litertlm}"
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG="$PROJECT_ROOT/logs/gpu_smoke.log"

# shellcheck source=scripts/_lib.sh
source "$(cd "$(dirname "$0")" && pwd)/_lib.sh"
omc_validate_device_folder "$DEVICE_FOLDER" || exit 1

mkdir -p "$PROJECT_ROOT/logs"
{
  echo "=== GPU smoke @ $(date -u +%FT%TZ) ==="
  echo "device:  $DEVICE_SERIAL"
  echo "folder:  $DEVICE_FOLDER"
  echo
} | tee "$LOG"

adb -s "$DEVICE_SERIAL" shell "cd '$DEVICE_FOLDER' && \
  LD_LIBRARY_PATH='$DEVICE_FOLDER' \
  ./litert_lm_main \
  --backend=gpu \
  --model_path='$DEVICE_FOLDER/model.litertlm' \
  --input_prompt='Say hello in one short sentence.'" 2>&1 | tee -a "$LOG"

echo
echo "log: $LOG"
echo
echo "=== triage signals ==="
grep -iE "opencl|adreno|gpu|accelerator|fallback|dlopen|cl_device|cl_platform" "$LOG" | head -30 || true
