#!/usr/bin/env bash
# scripts/fetch_artifacts.sh — Phase 0: download host-side artifacts.
# Idempotent: skips files whose sha256 already matches the manifest.
# Verifies every download against scripts/sha256_manifest.txt; bails on mismatch.
#
# Default MODEL is `gemma` (gemma-4-E2B-it.litertlm, 2.4 GB — Google's
# small phone model, supported by the v0.11.0-rc.1 binary; v0.9.0 returned
# INVALID_ARGUMENT on it). Other options:
#   MODEL=qwen3       Qwen3-0.6B.litertlm (614 MB, smallest fallback)
#   MODEL=gemma_e4b   gemma-4-E4B-it.litertlm (3.4 GB, high-end SoC only)
#   MODEL=both        Qwen3 + gemma_e2b (historical alias, kept for back-compat)
#   MODEL=all         Qwen3 + gemma_e2b + gemma_e4b
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
GEMMA4_E2B_COMMIT="7fa1d78473894f7e736a21d920c3aa80f950c0db"
GEMMA4_E4B_COMMIT="55b6eef9e490da991fe6bc5fec1834106927b727"

BINARY_URL="https://github.com/google-ai-edge/LiteRT-LM/releases/download/${LITERTLM_TAG}/litert_lm_main.android_arm64"
LFS_BASE="https://media.githubusercontent.com/media/google-ai-edge/LiteRT-LM/${LITERTLM_TAG}/prebuilt/android_arm64"
QWEN3_URL="https://huggingface.co/litert-community/Qwen3-0.6B/resolve/${QWEN3_COMMIT}/Qwen3-0.6B.litertlm"
GEMMA4_URL="https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/${GEMMA4_E2B_COMMIT}/gemma-4-E2B-it.litertlm"
GEMMA4_E4B_URL="https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/${GEMMA4_E4B_COMMIT}/gemma-4-E4B-it.litertlm"

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
MODEL="${MODEL:-gemma}"
case "$MODEL" in
  qwen3)
    fetch_with_verify "$QWEN3_URL" "$MODEL_DIR/Qwen3-0.6B.litertlm" "models/Qwen3-0.6B.litertlm"
    ;;
  gemma|gemma_e2b)
    fetch_with_verify "$GEMMA4_URL" "$MODEL_DIR/gemma-4-E2B-it.litertlm" "models/gemma-4-E2B-it.litertlm"
    ;;
  gemma_e4b)
    fetch_with_verify "$GEMMA4_E4B_URL" "$MODEL_DIR/gemma-4-E4B-it.litertlm" "models/gemma-4-E4B-it.litertlm"
    ;;
  both)
    # 'both' is historical (named when there were only two models). Kept as
    # an alias for the most common pairing; use 'all' to include E4B too.
    fetch_with_verify "$QWEN3_URL" "$MODEL_DIR/Qwen3-0.6B.litertlm" "models/Qwen3-0.6B.litertlm"
    fetch_with_verify "$GEMMA4_URL" "$MODEL_DIR/gemma-4-E2B-it.litertlm" "models/gemma-4-E2B-it.litertlm"
    ;;
  all)
    fetch_with_verify "$QWEN3_URL" "$MODEL_DIR/Qwen3-0.6B.litertlm" "models/Qwen3-0.6B.litertlm"
    fetch_with_verify "$GEMMA4_URL" "$MODEL_DIR/gemma-4-E2B-it.litertlm" "models/gemma-4-E2B-it.litertlm"
    fetch_with_verify "$GEMMA4_E4B_URL" "$MODEL_DIR/gemma-4-E4B-it.litertlm" "models/gemma-4-E4B-it.litertlm"
    ;;
  *)
    echo "FATAL: unknown MODEL='$MODEL' (use qwen3, gemma|gemma_e2b, gemma_e4b, both, or all)" >&2
    exit 1
    ;;
esac

echo
echo "=== fetch_artifacts.sh done — all sha256 verified ==="
