#!/usr/bin/env bash
# scripts/preflight.sh — verify host adb and target device readiness.
# Bails on first failure with a clear message; safe to re-run.
set -euo pipefail

DEVICE_SERIAL="${DEVICE_SERIAL:-RFCNC0WNT9H}"   # default = S21+
DEVICE_FOLDER="${DEVICE_FOLDER:-/data/local/tmp/litertlm}"
MIN_TMP_FREE_MB="${MIN_TMP_FREE_MB:-3300}"      # 2,544 MiB artifact + ~755 MiB safety

die() { echo "FATAL: $*" >&2; exit 1; }
ok()  { echo "[ok] $*"; }

# 1. adb on PATH
command -v adb >/dev/null || die "adb not on PATH"
ok "adb: $(adb version | head -1)"

# 2. device reachable
adb -s "$DEVICE_SERIAL" get-state 2>/dev/null | grep -q "^device$" \
  || die "device $DEVICE_SERIAL not in 'device' state — check USB / adb daemon"
ok "device $DEVICE_SERIAL online"

# 3. ABI is arm64-v8a
abi=$(adb -s "$DEVICE_SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r')
[[ "$abi" == "arm64-v8a" ]] || die "ABI is $abi, expected arm64-v8a"
ok "ABI: $abi"

# 4. SoC (informational; warn on mismatch)
soc=$(adb -s "$DEVICE_SERIAL" shell getprop ro.soc.model | tr -d '\r')
case "$soc" in
  SM8350) ok "SoC: $soc (Snapdragon 888 — expected for S21+)" ;;
  SM8750) ok "SoC: $soc (Snapdragon 8 Elite for Galaxy — backup S25)" ;;
  *) echo "[warn] unrecognized SoC: $soc — proceeding anyway" ;;
esac

android=$(adb -s "$DEVICE_SERIAL" shell getprop ro.build.version.release | tr -d '\r')
ok "Android: $android"

# 5. OpenCL library presence (required for GPU backend)
adb -s "$DEVICE_SERIAL" shell ls /vendor/lib64/libOpenCL.so >/dev/null 2>&1 \
  || die "/vendor/lib64/libOpenCL.so missing — GPU path will not work"
ok "/vendor/lib64/libOpenCL.so present"

# 5b. Linker-namespace probe (informational): does Android linker expose libOpenCL.so
#     to a non-root shell binary running from /data/local/tmp? Presence ≠ dlopen success.
echo
echo "[probe] Android linker namespace visibility for libOpenCL.so:"
adb -s "$DEVICE_SERIAL" shell '
  echo "  -- public.libraries entries with libOpenCL.so:";
  grep -R "^libOpenCL.so$" /vendor/etc/public.libraries*.txt /system/etc/public.libraries*.txt /odm/etc/public.libraries*.txt 2>/dev/null || echo "    (none)";
  echo "  -- ld.config.txt context (libOpenCL / namespace.default / namespace.sphal):";
  grep -nE "libOpenCL|namespace\.default|namespace\.sphal" /linkerconfig/ld.config.txt 2>/dev/null | head -30 || echo "    (linkerconfig not readable as shell user)";
' 2>&1 | sed 's/^/  /'

# 6. /data/local/tmp writable
adb -s "$DEVICE_SERIAL" shell "touch /data/local/tmp/_omc_probe && rm /data/local/tmp/_omc_probe" >/dev/null \
  || die "/data/local/tmp not writable"
ok "/data/local/tmp writable"

# 7. Free space (parse `stat -f`, blocks * %s / MiB)
read -r blk_size avail_blk < <(adb -s "$DEVICE_SERIAL" shell "stat -f -c '%s %a' /data/local/tmp" | tr -d '\r')
free_mb=$(( blk_size * avail_blk / 1024 / 1024 ))
echo "[info] /data/local/tmp free: ${free_mb} MiB (need >= ${MIN_TMP_FREE_MB})"
if [[ "$free_mb" -lt "$MIN_TMP_FREE_MB" ]]; then
  die "insufficient /data free; run: scripts/cleanup_device_tmp.sh"
fi
ok "free space sufficient"

# 8. Confirm host artifacts exist
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
[[ -x "$PROJECT_ROOT/bin/litert_lm_main" ]] || die "bin/litert_lm_main missing — run scripts/fetch_artifacts.sh"
[[ -d "$PROJECT_ROOT/bin/android_arm64" ]]  || die "bin/android_arm64/ missing — run scripts/fetch_artifacts.sh"
[[ -f "$PROJECT_ROOT/models/gemma-4-E2B-it.litertlm" ]] || die "model missing — run scripts/fetch_artifacts.sh"
ok "host artifacts present"

echo
echo "=== preflight passed ==="
