#!/usr/bin/env bash
# scripts/cleanup_device_tmp.sh — interactively reclaim space in /data/local/tmp.
# Lists candidates and requires explicit y/N before EACH deletion.
# Set FORCE=1 to skip prompts (use only when you're sure all entries are safe).
set -euo pipefail

DEVICE_SERIAL="${DEVICE_SERIAL:-RFCNC0WNT9H}"
TARGET="/data/local/tmp"
KEEP_DIR="litertlm"   # never delete our own working dir
FORCE="${FORCE:-0}"

echo "Candidates in $TARGET on $DEVICE_SERIAL:"
adb -s "$DEVICE_SERIAL" shell "ls -lah $TARGET" || true
echo

mapfile -t entries < <(adb -s "$DEVICE_SERIAL" shell "ls $TARGET" \
  | tr -d '\r' \
  | grep -v "^${KEEP_DIR}\$" \
  || true)

if [[ ${#entries[@]} -eq 0 ]]; then
  echo "Nothing to clean."
  exit 0
fi

for e in "${entries[@]}"; do
  [[ -z "$e" ]] && continue
  size=$(adb -s "$DEVICE_SERIAL" shell "du -sh $TARGET/$e 2>/dev/null | cut -f1" | tr -d '\r')
  if [[ "$FORCE" == "1" ]]; then
    ans="y"
    echo "Delete $TARGET/$e ($size) — FORCE=1"
  else
    read -r -p "Delete $TARGET/$e ($size) ? [y/N] " ans
  fi
  case "$ans" in
    y|Y) adb -s "$DEVICE_SERIAL" shell "rm -rf $TARGET/$e" && echo "  removed.";;
    *)   echo "  skipped." ;;
  esac
done

echo
echo "After cleanup:"
adb -s "$DEVICE_SERIAL" shell "df -h /data/local/tmp" || true
