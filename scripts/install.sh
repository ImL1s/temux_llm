#!/usr/bin/env bash
# scripts/install.sh — one-shot installer.
#
# What it does, end-to-end:
#   1. Sanity-check host (adb, JDK, Android SDK).
#   2. Auto-detect your device SoC, recommend a model (E2B for older flagships,
#      E4B for SD 8 Elite class). Override with MODEL=e2b|e4b|qwen3.
#   3. Download the v0.11.0-rc.1 binary + .so set + the chosen model to the
#      host (skips files whose sha256 already matches scripts/sha256_manifest.txt).
#   4. Copy binary + .so into android/app/src/main/jniLibs/arm64-v8a/.
#   5. Build the debug APK with the bundled Gradle wrapper.
#   6. Push the model to /data/local/tmp/litertlm/ on the device.
#   7. Install the APK and launch the foreground service.
#   8. Push scripts/litertlm-termux-wrapper.sh to /data/local/tmp/bin/litertlm.
#   9. Verify /healthz and run a short /api/generate.
#
# Usage:
#   bash scripts/install.sh                              # auto-pick device + model
#   DEVICE_SERIAL=ABC1234   bash scripts/install.sh      # specific device
#   MODEL=e4b               bash scripts/install.sh      # force model
#   DEVICE_SERIAL=... MODEL=qwen3 bash scripts/install.sh
#
# Prereqs (one-time, on host):
#   - adb on PATH (Android SDK platform-tools).
#   - JDK 17 or newer (java + javac).
#   - ANDROID_HOME pointing at an SDK with cmdline-tools + platforms;android-35
#     + build-tools;35.0.0 (Android Studio installs all of these).
#   - The phone has USB debugging enabled and is plugged in.
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"

# ---- 1. host sanity ----
say() { printf '\n\033[1;36m=== %s ===\033[0m\n' "$*"; }
die() { printf '\n\033[1;31mFATAL:\033[0m %s\n' "$*" >&2; exit 1; }

say "checking host prerequisites"
command -v adb  >/dev/null || die "adb not on PATH (install Android platform-tools)"
command -v java >/dev/null || die "java not on PATH (install JDK 17+)"
[[ -n "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ]] \
  || die "ANDROID_HOME or ANDROID_SDK_ROOT must be set to your Android SDK"
[[ -f "$PROJECT_ROOT/android/local.properties" ]] || \
  printf 'sdk.dir=%s\n' "${ANDROID_HOME:-$ANDROID_SDK_ROOT}" \
    > "$PROJECT_ROOT/android/local.properties"
echo "  adb:  $(command -v adb)"
echo "  java: $(java -version 2>&1 | head -1)"
echo "  sdk:  ${ANDROID_HOME:-$ANDROID_SDK_ROOT}"

# ---- 2. pick device ----
say "selecting target device"
if [[ -z "${DEVICE_SERIAL:-}" ]]; then
  COUNT=$(adb devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')
  if [[ "$COUNT" -eq 0 ]]; then
    die "no Android device in 'device' state. Plug it in, accept the USB-debug dialog, retry."
  elif [[ "$COUNT" -eq 1 ]]; then
    DEVICE_SERIAL=$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')
    echo "  auto-picked single device: $DEVICE_SERIAL"
  else
    echo "multiple devices attached:"
    adb devices -l | tail -n +2
    die "set DEVICE_SERIAL=... and rerun."
  fi
fi
ABI=$(adb -s "$DEVICE_SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r')
SOC=$(adb -s "$DEVICE_SERIAL" shell getprop ro.soc.model | tr -d '\r')
MODEL_NAME=$(adb -s "$DEVICE_SERIAL" shell getprop ro.product.model | tr -d '\r')
RELEASE=$(adb -s "$DEVICE_SERIAL" shell getprop ro.build.version.release | tr -d '\r')
echo "  $DEVICE_SERIAL  $MODEL_NAME  SoC=$SOC  ABI=$ABI  Android=$RELEASE"
[[ "$ABI" == "arm64-v8a" ]] || die "device ABI is $ABI, expected arm64-v8a"

# ---- 3. pick model ----
if [[ -z "${MODEL:-}" ]]; then
  case "$SOC" in
    SM8750|SM8650|SM8550|SM8450|MT6989|MT6991|MT6993)
      MODEL=e4b
      echo "  detected high-end SoC ($SOC); defaulting to gemma-4-E4B (3.4 GB)" ;;
    *)
      MODEL=e2b
      echo "  defaulting to gemma-4-E2B (2.4 GB) — set MODEL=e4b to try the bigger one" ;;
  esac
fi
case "$MODEL" in
  e2b)   FETCH_MODEL=gemma; MODEL_FILE=gemma-4-E2B-it.litertlm ;;
  e4b)   FETCH_MODEL=gemma_e4b; MODEL_FILE=gemma-4-E4B-it.litertlm ;;
  qwen3) FETCH_MODEL=qwen3; MODEL_FILE=Qwen3-0.6B.litertlm ;;
  *) die "unknown MODEL=$MODEL (use e2b, e4b, or qwen3)" ;;
esac
say "host artifacts (binary + .so + model: $MODEL_FILE)"
MODEL="$FETCH_MODEL" bash "$PROJECT_ROOT/scripts/fetch_artifacts.sh"

# ---- 4. sync into Android jniLibs ----
say "staging binary + .so into android/app/src/main/jniLibs/arm64-v8a/"
JNI_DIR="$PROJECT_ROOT/android/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$JNI_DIR"
rm -f "$JNI_DIR"/*
cp "$PROJECT_ROOT/bin/litert_lm_main" "$JNI_DIR/liblitert_lm_main.so"
cp "$PROJECT_ROOT/bin/android_arm64/"*.so "$JNI_DIR/"
ls -la "$JNI_DIR" | tail -n +2

# ---- 5. build APK ----
say "building APK via Gradle wrapper (first run takes ~1 min)"
( cd "$PROJECT_ROOT/android" && ./gradlew :app:assembleDebug --no-daemon -q )
APK="$PROJECT_ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
[[ -f "$APK" ]] || die "APK not produced at $APK"
echo "  $(stat -f%z "$APK" 2>/dev/null || stat -c%s "$APK") bytes"

# ---- 6. push model ----
say "pushing $MODEL_FILE to device /data/local/tmp/litertlm/ (~1-2 min over USB)"
adb -s "$DEVICE_SERIAL" shell am force-stop dev.temuxllm.service 2>&1 || true
adb -s "$DEVICE_SERIAL" shell "mkdir -p /data/local/tmp/litertlm /data/local/tmp/bin"
adb -s "$DEVICE_SERIAL" push "$PROJECT_ROOT/models/$MODEL_FILE" /data/local/tmp/litertlm/model.litertlm

# ---- 7. install APK + start service ----
say "installing APK and starting the foreground service"
adb -s "$DEVICE_SERIAL" install -r "$APK" >/dev/null
adb -s "$DEVICE_SERIAL" shell am start-foreground-service \
  -n dev.temuxllm.service/dev.temuxllm.LlmService >/dev/null

# ---- 8. push Termux wrapper ----
say "deploying litertlm wrapper to /data/local/tmp/bin/litertlm"
adb -s "$DEVICE_SERIAL" push "$PROJECT_ROOT/scripts/litertlm-termux-wrapper.sh" \
  /data/local/tmp/bin/litertlm
adb -s "$DEVICE_SERIAL" shell "chmod 755 /data/local/tmp/bin/litertlm"

# ---- 9. verify ----
say "verifying — waiting up to 60s for service ready (model staging copy on first run)"
for i in $(seq 1 60); do
  R=$(adb -s "$DEVICE_SERIAL" shell "curl -sm 2 http://127.0.0.1:11434/healthz 2>/dev/null" \
        | tr -d '\r')
  if [[ "$R" == "ok" ]]; then echo "  ready at t+${i}s"; break; fi
  [[ $i -eq 60 ]] && die "service did not return /healthz=ok within 60 s"
  sleep 1
done

say "first inference (this primes the kernel cache; warm runs are much faster)"
RESP=$(adb -s "$DEVICE_SERIAL" shell \
  "curl -sm 240 -X POST http://127.0.0.1:11434/api/generate -H 'Content-Type: application/json' --data-binary '{\"prompt\":\"Reply with just: hi.\",\"backend\":\"gpu\"}'")
[[ -n "$RESP" ]] || die "empty response from /api/generate"
EXIT=$(printf '%s' "$RESP" | sed -n 's/.*"exit_code":\([0-9-]*\).*/\1/p')
TEXT=$(printf '%s' "$RESP" | sed -n 's/.*"response":"\([^"]*\)".*/\1/p')
echo "  exit_code=$EXIT  response=$TEXT"
[[ "$EXIT" == "0" ]] || die "/api/generate returned non-zero exit_code"

# ---- done ----
say "✓ install complete on $MODEL_NAME ($DEVICE_SERIAL)"
cat <<EOF

Next steps on the phone (one-time):

  1. Install Termux from F-Droid (Play Store version is unmaintained):
       https://f-droid.org/packages/com.termux/

  2. Open Termux, then:
       echo 'export PATH="/data/local/tmp/bin:\$PATH"' >> ~/.bashrc
       source ~/.bashrc

  3. Talk to your local model:
       litertlm "你好,用一句話介紹自己。"
       litertlm --backend cpu "Reply OK in 3 words."
       litertlm --json "what is 2+2?"

The Android service binds only to 127.0.0.1:11434. No traffic leaves the phone.
Stop the service:  adb -s $DEVICE_SERIAL shell am force-stop dev.temuxllm.service
Uninstall:         adb -s $DEVICE_SERIAL uninstall dev.temuxllm.service
EOF
