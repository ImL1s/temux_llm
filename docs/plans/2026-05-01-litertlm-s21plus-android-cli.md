# LiteRT-LM Android CLI on Samsung Galaxy S21+ — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task.

**Goal:** Validate the official LiteRT-LM Android arm64 native CLI on a Samsung Galaxy S21+ (SM-G9960, Snapdragon 888, Adreno 660, Android 15), running `gemma-4-E2B-it.litertlm` with CPU and GPU backends, and apply the brief's "GPU is useful only if…" interpretation rule to decide whether to proceed to the Android foreground service + localhost API phase.

**Architecture:** macOS arm64 host pulls v0.9.0 prebuilt artifacts (`litert_lm_main.android_arm64` from GitHub Release + matching `prebuilt/android_arm64/*.so` set from the repo via Git LFS at the same `v0.9.0` tag) plus the generic `gemma-4-E2B-it.litertlm` model from HuggingFace `litert-community/`. Bash orchestrators push everything to `/data/local/tmp/litertlm` on the S21+ via adb, run CPU smoke → CPU benchmark → GPU smoke → GPU benchmark, save raw logs on host, then parse for OpenCL/Adreno/silent-fallback signals.

**Tech Stack:** macOS host (curl, adb, bash, python3); LiteRT-LM v0.9.0 prebuilt (Bazel-built C++); Android 15 / arm64-v8a; Adreno 660 OpenCL via `/vendor/lib64/libOpenCL.so`.

---

## Confirmed Constraints (from recon, 2026-05-01)

| Item | Value | Source |
|---|---|---|
| Target device serial | `RFCNC0WNT9H` | `adb devices -l` |
| Model / device codename | `SM-G9960` / `t2q` | `getprop ro.product.model` / `ro.product.device` |
| SoC | `SM8350` (Snapdragon 888) | `getprop ro.soc.model` |
| GPU | Adreno 660 (`/vendor/lib64/libEGL_adreno.so` etc.) | `ls /vendor/lib64` |
| OpenCL library | `/vendor/lib64/libOpenCL.so` ✓ present | `ls /vendor/lib64` |
| Android version | 15 (SDK 35) | `getprop ro.build.version.release` |
| ABI | `arm64-v8a` ✓ | `getprop ro.product.cpu.abi` |
| RAM | 7,539,592 kB total / ~2,427,596 kB available | `cat /proc/meminfo` |
| `/data` free | ~1.6 GB free of 224 GB (f2fs) | `stat -f /data/local/tmp` |
| Reclaimable in `/data/local/tmp` | ~1.5 GB (legacy APKs, Termux coverage, Flutter prefs) | `du -sh /data/local/tmp` |
| Latest Android binary | v0.9.0 (`litert_lm_main.android_arm64`, 37,280,824 B) | GH Releases API |
| Newer releases (v0.10.x) | Drop Android assets | GH Releases API |
| Model `gemma-4-E2B-it.litertlm` size | 2,583,085,056 B (~2.41 GB) | HF HEAD `Content-Length` |
| Model `gemma-4-E4B-it.litertlm` size | 3,654,467,584 B (~3.40 GB) — **WILL NOT FIT** on S21+ | HF HEAD `Content-Length` |
| `.so` set total (v0.9.0) | ~41 MB (6 files) | `prebuilt/android_arm64?ref=v0.9.0` |

**Storage math for S21+ E2B path:** binary 37 MB + `.so` ~41 MB + model 2,413 MB = **~2,491 MB**. After cleaning `/data/local/tmp` legacy artifacts (frees ~1,500 MB) we have ~3,100 MB available, leaving ~600 MB headroom. Tight but workable. **E4B is out of scope on this device.**

**Backup device available:** `RFCY71LAFYE` (SM-S931Q / SM8750 Snapdragon 8 Elite for Galaxy / Android 16 / 198 GB free). Matches the brief's original target spec exactly. Use only if S21+ GPU path fails for hardware/OS reasons (Adreno 660 OpenCL refused, vendor namespace blocked, etc.).

---

## Stop Conditions (from brief, mandatory)

1. **CPU smoke fails** → fix binary/model/path first; do not advance.
2. **GPU smoke fails because `.so` missing or `LD_LIBRARY_PATH` wrong** → fix push & env; retry.
3. **GPU fails due to vendor/OpenCL namespace** → stop on this device; switch to S25 *or* skip to Android foreground-service route (out of this plan's scope).
4. **GPU smoke succeeds AND benchmark shows meaningful TTFT/prefill improvement AND no silent fallback** → green-light Phase 2 (Android foreground service + localhost HTTP API). Phase 2 is **out of scope** for this plan.

---

## Task 0: Initialize project as a git repo

**Files:**
- Create: `.gitignore`
- Create: `README.md`

**Step 0.1: Init repo**

```bash
cd /Users/setsuna-new/Documents/temux_llm
git init -b main
```

Expected: `Initialized empty Git repository in .../temux_llm/.git/`

**Step 0.2: Write `.gitignore`**

```
bin/
models/
logs/
*.litertlm
*.so
*.android_arm64
.DS_Store
.venv/
__pycache__/
```

**Step 0.3: Write `README.md`** (one-paragraph project intent — see plan Goal/Architecture)

**Step 0.4: First commit**

```bash
git add .gitignore README.md docs/
git commit -m "chore: scaffold litertlm-on-s21plus project"
```

Expected: commit succeeds with 3+ files.

---

## Task 1: Fetch the v0.9.0 Android arm64 binary to host

**Files:**
- Create: `bin/litert_lm_main` (mode 0755, ELF aarch64)
- Create: `scripts/fetch_artifacts.sh`

**Step 1.1: Write `scripts/fetch_artifacts.sh` — binary section**

```bash
#!/usr/bin/env bash
# scripts/fetch_artifacts.sh — Phase 0: download host-side artifacts.
# Idempotent: skips files that already exist with the expected size.
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BIN_DIR="$PROJECT_ROOT/bin"
SO_DIR="$BIN_DIR/android_arm64"
MODEL_DIR="$PROJECT_ROOT/models"
mkdir -p "$BIN_DIR" "$SO_DIR" "$MODEL_DIR"

# --- Pin every artifact to v0.9.0 for ABI consistency ---
LITERTLM_TAG="v0.9.0"
BINARY_URL="https://github.com/google-ai-edge/LiteRT-LM/releases/download/${LITERTLM_TAG}/litert_lm_main.android_arm64"
BINARY_SIZE=37280824   # bytes, from GH release asset metadata 2026-05-01

fetch() {
  local url="$1" dest="$2" expected_size="$3"
  if [[ -f "$dest" ]]; then
    local actual; actual=$(stat -f%z "$dest" 2>/dev/null || stat -c%s "$dest")
    if [[ "$actual" == "$expected_size" ]]; then
      echo "[skip] $dest (size=$actual matches)"
      return 0
    else
      echo "[refetch] $dest size=$actual expected=$expected_size"
    fi
  fi
  echo "[fetch] $url -> $dest"
  curl -fL --retry 3 --retry-delay 2 -o "$dest" "$url"
  local got; got=$(stat -f%z "$dest" 2>/dev/null || stat -c%s "$dest")
  [[ "$got" == "$expected_size" ]] || { echo "size mismatch $got vs $expected_size" >&2; exit 1; }
}

# 1. Binary
fetch "$BINARY_URL" "$BIN_DIR/litert_lm_main" "$BINARY_SIZE"
chmod +x "$BIN_DIR/litert_lm_main"
# Verify ELF aarch64
file "$BIN_DIR/litert_lm_main" | grep -q "ARM aarch64" \
  || { echo "binary is not aarch64 ELF" >&2; exit 1; }
echo "[ok] binary ready: $BIN_DIR/litert_lm_main"
```

**Step 1.2: Run it**

```bash
bash scripts/fetch_artifacts.sh
```

Expected output (last line):

```
[ok] binary ready: /Users/.../bin/litert_lm_main
```

**Step 1.3: Manual sanity check**

```bash
file bin/litert_lm_main
```

Expected: `bin/litert_lm_main: ELF 64-bit LSB pie executable, ARM aarch64, version 1 (SYSV), ...`

**Step 1.4: Commit**

```bash
git add scripts/fetch_artifacts.sh
git commit -m "feat(scripts): fetch litert_lm_main android arm64 v0.9.0 binary"
```

---

## Task 2: Fetch the v0.9.0 `.so` set (Git LFS via media.githubusercontent.com)

**Files:**
- Modify: `scripts/fetch_artifacts.sh` (append `.so` block)
- Create: `bin/android_arm64/lib*.so` × 6

**Why pin to v0.9.0:** the `prebuilt/android_arm64/libLiteRtOpenClAccelerator.so` on `main` is 2,696,144 B but on `v0.9.0` is 2,974,704 B — different binaries, almost certainly ABI-incompatible with the v0.9.0 release binary. Pin everything to the same tag.

**Step 2.1: Append to `scripts/fetch_artifacts.sh`**

```bash
# 2. .so set (Git LFS — use media.githubusercontent.com to dereference)
LFS_BASE="https://media.githubusercontent.com/media/google-ai-edge/LiteRT-LM/${LITERTLM_TAG}/prebuilt/android_arm64"

# Sizes pinned to v0.9.0 tag (verified 2026-05-01 via GH contents API).
declare -a SO_FILES=(
  "libGemmaModelConstraintProvider.so"
  "libLiteRtGpuAccelerator.so"
  "libLiteRtOpenClAccelerator.so"
  "libLiteRtTopKOpenClSampler.so"
  "libLiteRtTopKWebGpuSampler.so"
  "libLiteRtWebGpuAccelerator.so"
)

for so in "${SO_FILES[@]}"; do
  # We do not hard-code sizes per .so here — we trust LFS resolution and
  # verify each is an aarch64 ELF after download.
  url="${LFS_BASE}/${so}"
  dest="$SO_DIR/$so"
  if [[ -f "$dest" ]] && file "$dest" | grep -q "ARM aarch64"; then
    echo "[skip] $dest already aarch64 ELF"
    continue
  fi
  echo "[fetch] $url"
  curl -fL --retry 3 --retry-delay 2 -o "$dest" "$url"
  file "$dest" | grep -q "ARM aarch64" \
    || { echo "$so is not aarch64 ELF (got: $(file "$dest"))" >&2; exit 1; }
done
echo "[ok] .so set ready in $SO_DIR"
```

**Step 2.2: Run**

```bash
bash scripts/fetch_artifacts.sh
ls -la bin/android_arm64/
```

Expected: 6 `.so` files, total ~41 MB, each `ELF 64-bit LSB shared object, ARM aarch64`.

**Step 2.3: Spot-verify one shared object**

```bash
file bin/android_arm64/libLiteRtOpenClAccelerator.so
```

Expected: `... shared object, ARM aarch64, ...`

**Step 2.4: Commit**

```bash
git add scripts/fetch_artifacts.sh
git commit -m "feat(scripts): fetch v0.9.0 prebuilt android_arm64 .so set via LFS"
```

---

## Task 3: Download `gemma-4-E2B-it.litertlm` model to host

**Files:**
- Modify: `scripts/fetch_artifacts.sh` (append model block)
- Create: `models/gemma-4-E2B-it.litertlm` (~2.41 GB)

**Step 3.1: Append to `scripts/fetch_artifacts.sh`**

```bash
# 3. Model — generic .litertlm (CPU/GPU). The _qualcomm_sm8750 variant is for
# S25-class SoCs and not appropriate for SM8350 / Adreno 660.
MODEL_URL="https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
MODEL_SIZE=2583085056   # bytes, 2026-05-01 HEAD

fetch "$MODEL_URL" "$MODEL_DIR/gemma-4-E2B-it.litertlm" "$MODEL_SIZE"
echo "[ok] model ready: $MODEL_DIR/gemma-4-E2B-it.litertlm ($MODEL_SIZE bytes)"
```

**Step 3.2: Run** (this will take several minutes — 2.41 GB)

```bash
bash scripts/fetch_artifacts.sh
```

Expected output (last lines):
```
[ok] .so set ready in .../bin/android_arm64
[fetch] https://huggingface.co/.../gemma-4-E2B-it.litertlm -> .../models/gemma-4-E2B-it.litertlm
[ok] model ready: .../models/gemma-4-E2B-it.litertlm (2583085056 bytes)
```

**Step 3.3: Commit**

```bash
git add scripts/fetch_artifacts.sh
git commit -m "feat(scripts): fetch gemma-4-E2B-it.litertlm to models/"
```

---

## Task 4: Write `scripts/preflight.sh` (host + device sanity check)

**Files:**
- Create: `scripts/preflight.sh`

**Behavior:** must check, in this order, and bail with a clear message on first failure: (1) `adb` on PATH, (2) target serial reachable, (3) device ABI is `arm64-v8a`, (4) SoC is recognized (warn if not SM8350), (5) `/vendor/lib64/libOpenCL.so` present, (6) `/data/local/tmp` is writable, (7) sufficient free space.

**Step 4.1: Write the script**

```bash
#!/usr/bin/env bash
# scripts/preflight.sh — verify host adb and target device readiness.
set -euo pipefail

DEVICE_SERIAL="${DEVICE_SERIAL:-RFCNC0WNT9H}"   # default = S21+
DEVICE_FOLDER="${DEVICE_FOLDER:-/data/local/tmp/litertlm}"
MIN_TMP_FREE_MB="${MIN_TMP_FREE_MB:-2700}"      # need ~2.5 GB clear

die() { echo "FATAL: $*" >&2; exit 1; }
ok()  { echo "[ok] $*"; }

# 1. adb
command -v adb >/dev/null || die "adb not on PATH"
ok "adb: $(adb version | head -1)"

# 2. device reachable
adb -s "$DEVICE_SERIAL" get-state | grep -q "^device$" \
  || die "device $DEVICE_SERIAL not in 'device' state"
ok "device $DEVICE_SERIAL online"

# 3. ABI
abi=$(adb -s "$DEVICE_SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r')
[[ "$abi" == "arm64-v8a" ]] || die "ABI is $abi, expected arm64-v8a"
ok "ABI: $abi"

# 4. SoC (informational; warn on mismatch)
soc=$(adb -s "$DEVICE_SERIAL" shell getprop ro.soc.model | tr -d '\r')
case "$soc" in
  SM8350) ok "SoC: $soc (Snapdragon 888 — expected for S21+)" ;;
  SM8750) ok "SoC: $soc (Snapdragon 8 Elite for Galaxy — backup S25)" ;;
  *) echo "[warn] unrecognized SoC: $soc" ;;
esac

android=$(adb -s "$DEVICE_SERIAL" shell getprop ro.build.version.release | tr -d '\r')
ok "Android: $android"

# 5. OpenCL library presence
adb -s "$DEVICE_SERIAL" shell ls /vendor/lib64/libOpenCL.so >/dev/null 2>&1 \
  || die "/vendor/lib64/libOpenCL.so missing — GPU path will not work"
ok "/vendor/lib64/libOpenCL.so present"

# 6. /data/local/tmp writable
adb -s "$DEVICE_SERIAL" shell "touch /data/local/tmp/_omc_probe && rm /data/local/tmp/_omc_probe" \
  || die "/data/local/tmp not writable"
ok "/data/local/tmp writable"

# 7. Free space (parse stat -f, blocks * 4096 / 1MiB)
read -r blk_size avail_blk < <(adb -s "$DEVICE_SERIAL" shell "stat -f -c '%s %a' /data/local/tmp" | tr -d '\r')
free_mb=$(( blk_size * avail_blk / 1024 / 1024 ))
echo "[info] /data/local/tmp free: ${free_mb} MiB (need >= ${MIN_TMP_FREE_MB})"
[[ "$free_mb" -ge "$MIN_TMP_FREE_MB" ]] \
  || die "insufficient /data free; run scripts/cleanup_device_tmp.sh first"
ok "free space sufficient"

# 8. Confirm host artifacts exist
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
[[ -x "$PROJECT_ROOT/bin/litert_lm_main" ]] || die "bin/litert_lm_main missing — run fetch_artifacts.sh"
[[ -d "$PROJECT_ROOT/bin/android_arm64" ]] || die "bin/android_arm64/ missing — run fetch_artifacts.sh"
[[ -f "$PROJECT_ROOT/models/gemma-4-E2B-it.litertlm" ]] || die "model missing — run fetch_artifacts.sh"
ok "host artifacts present"

echo
echo "=== preflight passed ==="
```

**Step 4.2: Make executable & dry-run**

```bash
chmod +x scripts/preflight.sh
DEVICE_SERIAL=RFCNC0WNT9H bash scripts/preflight.sh
```

Expected: ends with `=== preflight passed ===` OR fails on free-space check (in which case proceed to Task 5).

**Step 4.3: Commit**

```bash
git add scripts/preflight.sh
git commit -m "feat(scripts): host+device preflight check"
```

---

## Task 5: Write `scripts/cleanup_device_tmp.sh` (interactive — pause for user confirmation)

**Why:** S21+'s `/data/local/tmp` already has ~1.5 GB of unrelated files (`TB-2.5.1-…apk`, `app-release.apk`, `com.termux-coverage_data`, `current_ui.xml`, `FlutterSharedPreferences.xml`). These are NOT ours. We must list them and prompt before deleting.

**Files:**
- Create: `scripts/cleanup_device_tmp.sh`

**Step 5.1: Write the script**

```bash
#!/usr/bin/env bash
# scripts/cleanup_device_tmp.sh — interactively reclaim space in /data/local/tmp.
# Will list candidates and require explicit y/N before each deletion.
set -euo pipefail

DEVICE_SERIAL="${DEVICE_SERIAL:-RFCNC0WNT9H}"
TARGET="/data/local/tmp"
KEEP_DIR="litertlm"   # never delete our own working dir

echo "Candidates in $TARGET on $DEVICE_SERIAL:"
adb -s "$DEVICE_SERIAL" shell "ls -lah $TARGET" || true
echo

mapfile -t entries < <(adb -s "$DEVICE_SERIAL" shell "ls $TARGET" | tr -d '\r' | grep -v "^${KEEP_DIR}\$" || true)

if [[ ${#entries[@]} -eq 0 ]]; then
  echo "Nothing to clean."
  exit 0
fi

for e in "${entries[@]}"; do
  [[ -z "$e" ]] && continue
  size=$(adb -s "$DEVICE_SERIAL" shell "du -sh $TARGET/$e 2>/dev/null | cut -f1" | tr -d '\r')
  read -r -p "Delete $TARGET/$e ($size) ? [y/N] " ans
  case "$ans" in
    y|Y) adb -s "$DEVICE_SERIAL" shell "rm -rf $TARGET/$e" && echo "  removed."; ;;
    *)   echo "  skipped." ;;
  esac
done

echo
echo "After cleanup:"
adb -s "$DEVICE_SERIAL" shell "df -h /data/local/tmp" || true
```

**Step 5.2: Run interactively**

```bash
chmod +x scripts/cleanup_device_tmp.sh
DEVICE_SERIAL=RFCNC0WNT9H bash scripts/cleanup_device_tmp.sh
```

Manually answer `y` for the 5 known legacy items (or whichever are safe to delete — confirm with the human). After cleanup re-run preflight:

```bash
DEVICE_SERIAL=RFCNC0WNT9H bash scripts/preflight.sh
```

Expected: now passes the free-space gate.

**Step 5.3: Commit**

```bash
git add scripts/cleanup_device_tmp.sh
git commit -m "feat(scripts): interactive /data/local/tmp cleanup helper"
```

---

## Task 6: Write `scripts/setup_litertlm_android.sh` (push artifacts to device)

**Files:**
- Create: `scripts/setup_litertlm_android.sh`

**Step 6.1: Write the script**

```bash
#!/usr/bin/env bash
# scripts/setup_litertlm_android.sh — push binary + .so + model to device.
# Idempotent: re-pushes only when host file mtime newer than device file.
set -euo pipefail

DEVICE_SERIAL="${DEVICE_SERIAL:-RFCNC0WNT9H}"
DEVICE_FOLDER="${DEVICE_FOLDER:-/data/local/tmp/litertlm}"
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "[1/4] preflight"
DEVICE_SERIAL="$DEVICE_SERIAL" bash "$PROJECT_ROOT/scripts/preflight.sh"

echo "[2/4] mkdir on device"
adb -s "$DEVICE_SERIAL" shell "mkdir -p $DEVICE_FOLDER"

echo "[3/4] push binary + .so"
adb -s "$DEVICE_SERIAL" push "$PROJECT_ROOT/bin/litert_lm_main" "$DEVICE_FOLDER/"
adb -s "$DEVICE_SERIAL" shell "chmod +x $DEVICE_FOLDER/litert_lm_main"
adb -s "$DEVICE_SERIAL" push "$PROJECT_ROOT/bin/android_arm64/." "$DEVICE_FOLDER/"

echo "[4/4] push model (this may take 1-2 min over USB 2.0)"
adb -s "$DEVICE_SERIAL" push "$PROJECT_ROOT/models/gemma-4-E2B-it.litertlm" "$DEVICE_FOLDER/model.litertlm"

echo
echo "=== device contents ==="
adb -s "$DEVICE_SERIAL" shell "ls -lah $DEVICE_FOLDER"
adb -s "$DEVICE_SERIAL" shell "df -h /data/local/tmp"
echo "=== setup done ==="
```

**Step 6.2: Run**

```bash
chmod +x scripts/setup_litertlm_android.sh
DEVICE_SERIAL=RFCNC0WNT9H bash scripts/setup_litertlm_android.sh
```

Expected last output: directory listing showing `litert_lm_main`, `model.litertlm` (≈2.41 GB), 6 `.so` files; `df` shows non-100% usage on /data.

**Step 6.3: Commit**

```bash
git add scripts/setup_litertlm_android.sh
git commit -m "feat(scripts): push litert_lm_main + .so + model to device"
```

---

## Task 7: CPU smoke test (brief stop condition #1)

**Files:**
- Create: `scripts/run_cpu_smoke.sh`
- Create: `logs/cpu_smoke.log` (output)

**Step 7.1: Write the script**

```bash
#!/usr/bin/env bash
# scripts/run_cpu_smoke.sh — minimal CPU-backend invocation.
set -euo pipefail
DEVICE_SERIAL="${DEVICE_SERIAL:-RFCNC0WNT9H}"
DEVICE_FOLDER="${DEVICE_FOLDER:-/data/local/tmp/litertlm}"
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG="$PROJECT_ROOT/logs/cpu_smoke.log"

mkdir -p "$PROJECT_ROOT/logs"
echo "=== CPU smoke @ $(date -u +%FT%TZ) ===" | tee "$LOG"

adb -s "$DEVICE_SERIAL" shell "cd $DEVICE_FOLDER && ./litert_lm_main \
  --backend=cpu \
  --model_path=$DEVICE_FOLDER/model.litertlm \
  --input_prompt='Say hello in one short sentence.'" 2>&1 | tee -a "$LOG"

echo
echo "log: $LOG"
```

**Step 7.2: Run**

```bash
chmod +x scripts/run_cpu_smoke.sh
DEVICE_SERIAL=RFCNC0WNT9H bash scripts/run_cpu_smoke.sh
```

Expected: model loads (may take 10-30 s on CPU), then emits some token output. If it crashes (segfault, missing tokenizer file, OOM), STOP and triage — do not advance.

**Step 7.3: Verify**

```bash
grep -q "Hello\|Sure\|Hi\|hello" logs/cpu_smoke.log && echo "PASS: got reply" || echo "FAIL: no reply"
```

**Step 7.4: Commit**

```bash
git add scripts/run_cpu_smoke.sh logs/cpu_smoke.log
git commit -m "test(cpu): CPU smoke run on S21+ (E2B)"
```

> If CPU smoke fails: do NOT advance to GPU. Triage the failure (missing arg, model schema mismatch, segfault), fix, then return here.

---

## Task 8: GPU smoke test (with `LD_LIBRARY_PATH`)

**Files:**
- Create: `scripts/run_gpu_smoke.sh`
- Create: `logs/gpu_smoke.log`

**Step 8.1: Write the script**

```bash
#!/usr/bin/env bash
# scripts/run_gpu_smoke.sh — GPU backend smoke. Needs LD_LIBRARY_PATH=$DEVICE_FOLDER
# so that .so files (libLiteRtOpenClAccelerator.so etc.) resolve.
set -euo pipefail
DEVICE_SERIAL="${DEVICE_SERIAL:-RFCNC0WNT9H}"
DEVICE_FOLDER="${DEVICE_FOLDER:-/data/local/tmp/litertlm}"
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG="$PROJECT_ROOT/logs/gpu_smoke.log"

mkdir -p "$PROJECT_ROOT/logs"
echo "=== GPU smoke @ $(date -u +%FT%TZ) ===" | tee "$LOG"

adb -s "$DEVICE_SERIAL" shell "cd $DEVICE_FOLDER && \
  LD_LIBRARY_PATH=$DEVICE_FOLDER \
  ./litert_lm_main \
  --backend=gpu \
  --model_path=$DEVICE_FOLDER/model.litertlm \
  --input_prompt='Say hello in one short sentence.'" 2>&1 | tee -a "$LOG"

echo
echo "log: $LOG"
```

**Step 8.2: Run**

```bash
chmod +x scripts/run_gpu_smoke.sh
DEVICE_SERIAL=RFCNC0WNT9H bash scripts/run_gpu_smoke.sh
```

Expected: GPU initialization log lines, then model output. Possible failure modes — note all in the log:
- `dlopen failed: library "libLiteRtOpenClAccelerator.so" not found` → push problem (re-run Task 6).
- `CL_DEVICE_NOT_FOUND` / `clGetPlatformIDs` errors → vendor namespace blocking (stop condition #3).
- `falling back to CPU backend` / no GPU strings in log → silent fallback.

**Step 8.3: Quick triage helper**

```bash
grep -iE "opencl|adreno|gpu|accelerator|fallback|dlopen" logs/gpu_smoke.log | head -30
```

**Step 8.4: Commit**

```bash
git add scripts/run_gpu_smoke.sh logs/gpu_smoke.log
git commit -m "test(gpu): GPU smoke run on S21+ (E2B, OpenCL via Adreno 660)"
```

> Stop conditions evaluated here:
> - dlopen of our `.so` failed → fix push (Task 6) and retry.
> - OpenCL platform unavailable / namespace error → stop on this device, write up findings, optionally repeat the plan with `DEVICE_SERIAL=RFCY71LAFYE` (S25).

---

## Task 9: CPU benchmark (prefill 1024 / decode 256 / async=false / peak mem)

**Files:**
- Create: `scripts/run_litertlm_benchmark.sh`
- Create: `logs/cpu_benchmark.log`

**Step 9.1: Write the script**

```bash
#!/usr/bin/env bash
# scripts/run_litertlm_benchmark.sh — runs benchmark in {cpu,gpu} mode per BACKEND env.
# Must invoke with BACKEND=cpu or BACKEND=gpu.
set -euo pipefail
DEVICE_SERIAL="${DEVICE_SERIAL:-RFCNC0WNT9H}"
DEVICE_FOLDER="${DEVICE_FOLDER:-/data/local/tmp/litertlm}"
BACKEND="${BACKEND:?set BACKEND=cpu or BACKEND=gpu}"
PREFILL="${PREFILL:-1024}"
DECODE="${DECODE:-256}"
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG="$PROJECT_ROOT/logs/${BACKEND}_benchmark.log"

mkdir -p "$PROJECT_ROOT/logs"
echo "=== ${BACKEND^^} benchmark @ $(date -u +%FT%TZ), prefill=$PREFILL decode=$DECODE async=false ===" | tee "$LOG"

LD_PREFIX=""
[[ "$BACKEND" == "gpu" ]] && LD_PREFIX="LD_LIBRARY_PATH=$DEVICE_FOLDER"

adb -s "$DEVICE_SERIAL" shell "cd $DEVICE_FOLDER && \
  $LD_PREFIX ./litert_lm_main \
  --backend=$BACKEND \
  --model_path=$DEVICE_FOLDER/model.litertlm \
  --benchmark \
  --benchmark_prefill_tokens=$PREFILL \
  --benchmark_decode_tokens=$DECODE \
  --async=false \
  --report_peak_memory_footprint" 2>&1 | tee -a "$LOG"

echo "log: $LOG"
```

**Step 9.2: Run CPU benchmark**

```bash
chmod +x scripts/run_litertlm_benchmark.sh
BACKEND=cpu DEVICE_SERIAL=RFCNC0WNT9H bash scripts/run_litertlm_benchmark.sh
```

Expected: lines containing TTFT, prefill tokens/sec, decode tokens/sec, peak memory footprint. Save the log even if numbers look slow — CPU is the baseline, not the goal.

**Step 9.3: Commit**

```bash
git add scripts/run_litertlm_benchmark.sh logs/cpu_benchmark.log
git commit -m "bench(cpu): CPU benchmark on S21+ E2B prefill=1024 decode=256"
```

---

## Task 10: GPU benchmark (same args)

**Files:**
- Create: `logs/gpu_benchmark.log`

**Step 10.1: Run** (script already exists from Task 9)

```bash
BACKEND=gpu DEVICE_SERIAL=RFCNC0WNT9H bash scripts/run_litertlm_benchmark.sh
```

**Step 10.2: Quick eyeball**

```bash
grep -iE "ttft|prefill|decode|peak|opencl|adreno|fallback" logs/gpu_benchmark.log
```

Expected: similar metrics block, plus OpenCL/Adreno init lines absent in CPU log.

**Step 10.3: Commit**

```bash
git add logs/gpu_benchmark.log
git commit -m "bench(gpu): GPU benchmark on S21+ E2B (Adreno 660)"
```

---

## Task 11: Write `scripts/parse_litertlm_logs.sh`

**Files:**
- Create: `scripts/parse_litertlm_logs.sh`
- Create: `logs/summary.txt`

**Step 11.1: Write the parser** (resilient to missing fields — surface "MISSING" rather than crash)

```bash
#!/usr/bin/env bash
# scripts/parse_litertlm_logs.sh — extract metrics + GPU/OpenCL signals from logs.
# Output: logs/summary.txt
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOGS="$PROJECT_ROOT/logs"
OUT="$LOGS/summary.txt"

extract_metric() {
  # $1=label regex, $2=file
  grep -iE "$1" "$2" 2>/dev/null | head -1 | sed -E 's/^[^0-9-]*//; s/[[:space:]]+/ /g' || true
}

count_signal() {
  # $1=keyword regex, $2=file
  grep -ciE "$1" "$2" 2>/dev/null || echo 0
}

{
  echo "# LiteRT-LM benchmark summary  ($(date -u +%FT%TZ))"
  echo
  for kind in cpu gpu; do
    f="$LOGS/${kind}_benchmark.log"
    echo "## ${kind^^} ($(basename "$f"))"
    if [[ ! -s "$f" ]]; then
      echo "  (log missing or empty)"; echo; continue
    fi
    echo "  TTFT:           $(extract_metric 'ttft|time.to.first.token' "$f" || echo MISSING)"
    echo "  prefill tok/s:  $(extract_metric 'prefill.*tok' "$f" || echo MISSING)"
    echo "  decode tok/s:   $(extract_metric 'decode.*tok' "$f" || echo MISSING)"
    echo "  peak memory:    $(extract_metric 'peak.*memory|memory.footprint' "$f" || echo MISSING)"
    echo
    echo "  signal counts (this log):"
    echo "    OpenCL/Adreno mentions: $(count_signal 'opencl|adreno|cl_device' "$f")"
    echo "    'fallback'/'falling back' mentions: $(count_signal 'fallback|falling.back' "$f")"
    echo "    error/fatal mentions: $(count_signal 'error|fatal|abort' "$f")"
    echo
  done

  echo "## smoke logs"
  for f in "$LOGS/cpu_smoke.log" "$LOGS/gpu_smoke.log"; do
    [[ -s "$f" ]] || { echo "  $(basename "$f") (missing)"; continue; }
    echo "  $(basename "$f"): $(wc -l < "$f") lines, last line: $(tail -1 "$f")"
  done

  echo
  echo "## interpretation rule (per brief §100)"
  echo "  GPU is considered useful only if ALL hold:"
  echo "    1. GPU run did not crash (no fatal/abort)."
  echo "    2. logs show GPU/OpenCL/accelerator path loaded (signal count > 0)."
  echo "    3. benchmark shows meaningful TTFT / prefill improvement vs CPU."
  echo "    4. no silent fallback to CPU is suspected (fallback count == 0)."
} > "$OUT"

echo "wrote $OUT"
cat "$OUT"
```

**Step 11.2: Run**

```bash
chmod +x scripts/parse_litertlm_logs.sh
bash scripts/parse_litertlm_logs.sh
```

Expected: prints summary including TTFT/prefill/decode/peak-memory rows and the four-rule checklist.

**Step 11.3: Commit**

```bash
git add scripts/parse_litertlm_logs.sh logs/summary.txt
git commit -m "feat(scripts): parse logs and emit summary.txt"
```

---

## Task 12: Apply the brief's GPU-usefulness rule and decide

**Files:**
- Create: `docs/findings-2026-05-01-s21plus.md` (decision document)

**Step 12.1: Inspect the four rules manually**

```bash
cat logs/summary.txt
```

For each of the four rules, mark ✓ / ✗ with the supporting log line.

**Step 12.2: Write `docs/findings-2026-05-01-s21plus.md`**

Required sections:
1. Device & artifact versions (copy from preflight + `file bin/litert_lm_main`).
2. CPU-baseline numbers (TTFT / prefill tps / decode tps / peak mem).
3. GPU numbers (same).
4. The four-rule evaluation, each with a one-line verdict and a quoted log excerpt.
5. **Decision:** GO / NO-GO for "Phase 2 — Android foreground service + localhost API."
6. If NO-GO on S21+, note whether to retry the entire plan with `DEVICE_SERIAL=RFCY71LAFYE` (S25) and what to expect (`gemma-4-E2B-it_qualcomm_sm8750.litertlm` becomes interesting on SM8750).

**Step 12.3: Commit**

```bash
git add docs/findings-2026-05-01-s21plus.md
git commit -m "docs: S21+ Phase-1 findings and Phase-2 GO/NO-GO decision"
```

---

## Out of scope (do NOT do in this plan)

- Building `litert_lm_main` from source with Bazel (use the v0.9.0 release asset).
- Termux-native LiteRT-LM (brief §14 explicitly says no).
- Android foreground service / localhost HTTP API (Phase 2 — separate plan after GO decision).
- QNN / HTP / NPU backends (brief §48).
- Any UI work, model marketplace, or non-localhost network exposure.
- Pushing to S25 — only if S21+ definitively fails the four-rule GPU usefulness check.

## Risks already identified

| Risk | Mitigation |
|---|---|
| v0.9.0 binary ABI vs `main` `.so` | Pin .so URL to `?ref=v0.9.0` (Task 2). |
| Adreno 660 OpenCL refused on Android 15 | Captured in Task 8 logs; falls under stop condition #3. |
| `/data/local/tmp` too full | Task 5 reclaims ~1.5 GB before push. |
| 2.4 GB RAM available may be tight for prefill 1024 | E2B is ~2 GB on disk → typical ~1.5 GB working set; if OOM, drop prefill to 512 and re-run benchmark task. Document the deviation in `findings-…md`. |
| HF download interrupted | `fetch_artifacts.sh` re-checks size and re-downloads on mismatch. |

---
