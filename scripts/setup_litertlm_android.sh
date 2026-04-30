#!/usr/bin/env bash
# scripts/setup_litertlm_android.sh — push binary + .so + model to device.
# Re-runnable; preflight gates the operation.
set -euo pipefail

DEVICE_SERIAL="${DEVICE_SERIAL:-RFCNC0WNT9H}"
DEVICE_FOLDER="${DEVICE_FOLDER:-/data/local/tmp/litertlm}"
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "[1/4] preflight"
DEVICE_SERIAL="$DEVICE_SERIAL" bash "$PROJECT_ROOT/scripts/preflight.sh"

echo
echo "[2/4] mkdir on device"
adb -s "$DEVICE_SERIAL" shell "mkdir -p $DEVICE_FOLDER"

echo
echo "[3/4] push binary + .so"
adb -s "$DEVICE_SERIAL" push "$PROJECT_ROOT/bin/litert_lm_main" "$DEVICE_FOLDER/"
adb -s "$DEVICE_SERIAL" shell "chmod +x $DEVICE_FOLDER/litert_lm_main"
adb -s "$DEVICE_SERIAL" push "$PROJECT_ROOT/bin/android_arm64/." "$DEVICE_FOLDER/"

echo
echo "[4/4] push model (this may take 1-2 min over USB 2.0, model is ~2.4 GB)"
adb -s "$DEVICE_SERIAL" push "$PROJECT_ROOT/models/gemma-4-E2B-it.litertlm" "$DEVICE_FOLDER/model.litertlm"

echo
echo "=== device contents ==="
adb -s "$DEVICE_SERIAL" shell "ls -lah $DEVICE_FOLDER"
echo
adb -s "$DEVICE_SERIAL" shell "df -h /data/local/tmp"
echo
echo "=== litert_lm_main --help (for flag fact-check; saved to logs/binary_help.log) ==="
mkdir -p "$PROJECT_ROOT/logs"
adb -s "$DEVICE_SERIAL" shell "cd $DEVICE_FOLDER && ./litert_lm_main --help 2>&1 || true" \
  | tee "$PROJECT_ROOT/logs/binary_help.log"
echo "=== setup done ==="
