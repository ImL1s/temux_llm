#!/usr/bin/env bash
# scripts/fetch_artifacts.sh — Phase 0: download host-side artifacts.
# Idempotent: skips files whose sha256 already matches the manifest.
# Verifies every download against scripts/sha256_manifest.txt; bails on mismatch.
#
# Default model is Qwen3-0.6B (614 MB, smallest, fastest). Set MODEL=gemma to fetch
# gemma-4-E2B-it.litertlm (2.4 GB, Google's latest small phone model — supported by
# the v0.11.0-rc.1 binary; v0.9.0 returned INVALID_ARGUMENT on it).
# MODEL=both fetches both.
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BIN_DIR="$PROJECT_ROOT/bin"
SO_DIR="$BIN_DIR/android_arm64"
MODEL_DIR="$PROJECT_ROOT/models"
MANIFEST="$PROJECT_ROOT/scripts/sha256_manifest.txt"
mkdir -p "$BIN_DIR" "$SO_DIR" "$MODEL_DIR"

# --- Pin every artifact for ABI / content stability ---
LITERTLM_TAG="v0.11.0-rc.1"

QWEN3_COMMIT="49837332af6863b008a73a5394ed60789504069d"
GEMMA4_COMMIT="7fa1d78473894f7e736a21d920c3aa80f950c0db"

BINARY_URL="https://github.com/google-ai-edge/LiteRT-LM/releases/download/${LITERTLM_TAG}/litert_lm_main.android_arm64"
LFS_BASE="https://media.githubusercontent.com/media/google-ai-edge/LiteRT-LM/${LITERTLM_TAG}/prebuilt/android_arm64"
QWEN3_URL="https://huggingface.co/litert-community/Qwen3-0.6B/resolve/${QWEN3_COMMIT}/Qwen3-0.6B.litertlm"
GEMMA4_URL="https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/${GEMMA4_COMMIT}/gemma-4-E2B-it.litertlm"

# --- helpers ---
sha_of() { shasum -a 256 "$1" | awk '{print $1}'; }

expected_sha_of() {
  # Read sha256 from manifest by trailing path. Splits on a 2-space separator
  # (so paths and sources may contain spaces safely).
  local rel="$1"
  awk -F '  +' -v rel="$rel" '
    /^[[:space:]]*#/ {next}
    /^[[:space:]]*$/  {next}
    {
      sha=$1; path=$2
      if (path == rel) { print sha; exit }
    }
  ' "$MANIFEST"
}

verify_or_die() {
  local path="$1" key="$2"
  local got expected
  got=$(sha_of "$path")
  expected=$(expected_sha_of "$key")
  if [[ -z "$expected" ]]; then
    echo "FATAL: no manifest entry for '$key'" >&2
    exit 1
  fi
  if [[ "$got" != "$expected" ]]; then
    echo "FATAL: sha256 mismatch for $key" >&2
    echo "  got:      $got" >&2
    echo "  expected: $expected" >&2
    exit 1
  fi
  echo "[ok] $key  sha256 verified"
}

fetch_with_verify() {
  local url="$1" dest="$2" key="$3"
  local expected; expected=$(expected_sha_of "$key")
  if [[ -z "$expected" ]]; then
    echo "FATAL: no manifest entry for $key" >&2; exit 1
  fi
  if [[ -f "$dest" ]] && [[ "$(sha_of "$dest")" == "$expected" ]]; then
    echo "[skip] $key already verified"
    return 0
  fi
  echo "[fetch] $url"
  echo "        -> $dest"
  rm -f "$dest"
  curl -fL --retry 3 --retry-delay 2 -o "$dest" "$url"
  verify_or_die "$dest" "$key"
}

# 1. Binary
fetch_with_verify "$BINARY_URL" "$BIN_DIR/litert_lm_main" "bin/litert_lm_main"
chmod +x "$BIN_DIR/litert_lm_main"
file "$BIN_DIR/litert_lm_main" | grep -q "ARM aarch64" \
  || { echo "FATAL: binary is not aarch64 ELF" >&2; exit 1; }

# 2. .so set (Git LFS dereferenced via media subdomain at the pinned tag)
declare -a SO_FILES=(
  "libGemmaModelConstraintProvider.so"
  "libLiteRtGpuAccelerator.so"
  "libLiteRtOpenClAccelerator.so"
  "libLiteRtTopKOpenClSampler.so"
  "libLiteRtTopKWebGpuSampler.so"
  "libLiteRtWebGpuAccelerator.so"
)
for so in "${SO_FILES[@]}"; do
  fetch_with_verify "$LFS_BASE/$so" "$SO_DIR/$so" "bin/android_arm64/$so"
  file "$SO_DIR/$so" | grep -q "ARM aarch64" \
    || { echo "FATAL: $so is not aarch64 ELF" >&2; exit 1; }
done

# 3. Model — choose with MODEL env var. All three work with v0.11.0-rc.1 binary.
GEMMA4_E4B_URL="https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm"
MODEL="${MODEL:-gemma}"
case "$MODEL" in
  qwen3)
    fetch_with_verify "$QWEN3_URL" "$MODEL_DIR/Qwen3-0.6B.litertlm" "models/Qwen3-0.6B.litertlm"
    ;;
  gemma|gemma_e2b)
    fetch_with_verify "$GEMMA4_URL" "$MODEL_DIR/gemma-4-E2B-it.litertlm" "models/gemma-4-E2B-it.litertlm"
    ;;
  gemma_e4b)
    # E4B has no manifest entry yet (varies; large model, dev preview).
    DST="$MODEL_DIR/gemma-4-E4B-it.litertlm"
    EXPECTED_SIZE=3654467584
    if [[ -f "$DST" ]]; then
      got=$(stat -f%z "$DST" 2>/dev/null || stat -c%s "$DST")
      [[ "$got" == "$EXPECTED_SIZE" ]] && { echo "[skip] $DST already $EXPECTED_SIZE B"; }
    fi
    if [[ ! -f "$DST" ]] || [[ "$(stat -f%z "$DST" 2>/dev/null || stat -c%s "$DST")" != "$EXPECTED_SIZE" ]]; then
      curl -fL --retry 3 --retry-delay 2 -o "$DST" "$GEMMA4_E4B_URL"
    fi
    ;;
  both)
    fetch_with_verify "$QWEN3_URL" "$MODEL_DIR/Qwen3-0.6B.litertlm" "models/Qwen3-0.6B.litertlm"
    fetch_with_verify "$GEMMA4_URL" "$MODEL_DIR/gemma-4-E2B-it.litertlm" "models/gemma-4-E2B-it.litertlm"
    ;;
  *)
    echo "FATAL: unknown MODEL='$MODEL' (use qwen3, gemma, gemma_e4b, or both)" >&2
    exit 1
    ;;
esac

echo
echo "=== fetch_artifacts.sh done — all sha256 verified ==="
