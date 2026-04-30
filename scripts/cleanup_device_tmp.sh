#!/usr/bin/env bash
# scripts/cleanup_device_tmp.sh — interactively reclaim space in /data/local/tmp.
# Lists candidates and requires explicit y/N before EACH deletion.
# Set FORCE=1 to skip prompts (use only when you are sure all entries are safe).
#
# Compatible with bash 3.2 (macOS default). Names returned by device-side `ls` are
# sanitized via omc_validate_simple_name to block injection through crafted file names.
set -euo pipefail

DEVICE_SERIAL="${DEVICE_SERIAL:-RFCNC0WNT9H}"
TARGET="/data/local/tmp"
KEEP_DIR="litertlm"   # never delete our own working dir
FORCE="${FORCE:-0}"

# shellcheck source=scripts/_lib.sh
source "$(cd "$(dirname "$0")" && pwd)/_lib.sh"

echo "Candidates in $TARGET on $DEVICE_SERIAL:"
adb -s "$DEVICE_SERIAL" shell "ls -lah '$TARGET'" || true
echo

# bash 3.2 compatible: while-read into array (avoids `mapfile` / `readarray`).
entries=()
while IFS= read -r line; do
  [[ -n "$line" ]] && entries+=("$line")
done < <(adb -s "$DEVICE_SERIAL" shell "ls '$TARGET'" \
  | tr -d '\r' \
  | grep -v "^${KEEP_DIR}\$" \
  || true)

if [[ ${#entries[@]} -eq 0 ]]; then
  echo "Nothing to clean."
  exit 0
fi

for e in "${entries[@]}"; do
  if ! omc_validate_simple_name "$e"; then
    echo "  skipping unsafe name: $e"
    continue
  fi
  size=$(adb -s "$DEVICE_SERIAL" shell "du -sh '$TARGET/$e' 2>/dev/null | cut -f1" | tr -d '\r')
  if [[ "$FORCE" == "1" ]]; then
    ans="y"
    echo "Delete $TARGET/$e ($size) — FORCE=1"
  else
    read -r -p "Delete $TARGET/$e ($size) ? [y/N] " ans
  fi
  case "$ans" in
    y|Y) adb -s "$DEVICE_SERIAL" shell "rm -rf '$TARGET/$e'" && echo "  removed.";;
    *)   echo "  skipped." ;;
  esac
done

echo
echo "After cleanup:"
adb -s "$DEVICE_SERIAL" shell "df -h /data/local/tmp" || true
