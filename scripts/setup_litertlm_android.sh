#!/usr/bin/env bash
# scripts/setup_litertlm_android.sh — push binary + .so + model to device.
# Re-runnable; preflight gates the operation. NOTE: pushes overwrite each time
# (no mtime/size diff) — model push is ~2 min over USB 2.0 due to its 600 MB-2.4 GB size.
set -euo pipefail

DEVICE_SERIAL="${DEVICE_SERIAL:-RFCNC0WNT9H}"
DEVICE_FOLDER="${DEVICE_FOLDER:-/data/local/tmp/litertlm}"
MODEL_FILE="${MODEL_FILE:-Qwen3-0.6B.litertlm}"
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# shellcheck source=scripts/_lib.sh
source "$(cd "$(dirname "$0")" && pwd)/_lib.sh"
omc_validate_device_folder "$DEVICE_FOLDER" || exit 1

echo "[1/4] preflight"
DEVICE_SERIAL="$DEVICE_SERIAL" DEVICE_FOLDER="$DEVICE_FOLDER" MODEL_FILE="$MODEL_FILE" \
  bash "$PROJECT_ROOT/scripts/preflight.sh"

echo
echo "[2/4] mkdir on device"
adb -s "$DEVICE_SERIAL" shell "mkdir -p '$DEVICE_FOLDER'"

echo
echo "[3/4] push binary + .so"
adb -s "$DEVICE_SERIAL" push "$PROJECT_ROOT/bin/litert_lm_main" "$DEVICE_FOLDER/"
adb -s "$DEVICE_SERIAL" shell "chmod +x '$DEVICE_FOLDER/litert_lm_main'"
adb -s "$DEVICE_SERIAL" push "$PROJECT_ROOT/bin/android_arm64/." "$DEVICE_FOLDER/"

echo
echo "[4/4] push model: $MODEL_FILE (this may take 1-2 min over USB 2.0)"
adb -s "$DEVICE_SERIAL" push "$PROJECT_ROOT/models/$MODEL_FILE" "$DEVICE_FOLDER/model.litertlm"

echo
echo "=== device contents ==="
adb -s "$DEVICE_SERIAL" shell "ls -lah '$DEVICE_FOLDER'"
echo
adb -s "$DEVICE_SERIAL" shell "df -h /data/local/tmp"
echo
echo "=== litert_lm_main --help (for flag fact-check; saved to logs/binary_help.log) ==="
mkdir -p "$PROJECT_ROOT/logs"
# LD_LIBRARY_PATH required even for --help — libGemmaModelConstraintProvider.so is a hard dep.
adb -s "$DEVICE_SERIAL" shell "cd '$DEVICE_FOLDER' && LD_LIBRARY_PATH='$DEVICE_FOLDER' ./litert_lm_main --help 2>&1 || true" \
  | tee "$PROJECT_ROOT/logs/binary_help.log"
echo "=== setup done ==="
