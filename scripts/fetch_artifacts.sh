#!/usr/bin/env bash
# scripts/fetch_artifacts.sh — Phase 0: download host-side artifacts.
# Idempotent: skips files that already exist with matching sha256 (per scripts/sha256_manifest.txt).
# Re-fetches on size or sha256 mismatch. Bails with FATAL on any final mismatch.
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BIN_DIR="$PROJECT_ROOT/bin"
SO_DIR="$BIN_DIR/android_arm64"
MODEL_DIR="$PROJECT_ROOT/models"
MANIFEST="$PROJECT_ROOT/scripts/sha256_manifest.txt"
mkdir -p "$BIN_DIR" "$SO_DIR" "$MODEL_DIR"

# --- Pin every artifact for ABI / content stability ---
LITERTLM_TAG="v0.9.0"

# HF .litertlm pinned to the commit when this exact file was first uploaded.
# `main` is mutable: we observed sm8750 .litertlm replaced within hours of release.
# Pinned commit verified via: shasum -a 256 model == ab7838cdfc... (manifest).
MODEL_COMMIT="7fa1d78473894f7e736a21d920c3aa80f950c0db"

BINARY_URL="https://github.com/google-ai-edge/LiteRT-LM/releases/download/${LITERTLM_TAG}/litert_lm_main.android_arm64"
LFS_BASE="https://media.githubusercontent.com/media/google-ai-edge/LiteRT-LM/${LITERTLM_TAG}/prebuilt/android_arm64"
MODEL_URL="https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/${MODEL_COMMIT}/gemma-4-E2B-it.litertlm"

# --- helpers ---
sha_of() { shasum -a 256 "$1" | awk '{print $1}'; }
expected_sha_of() {
  # Read sha256 from manifest by trailing path. Strips comments and blank lines.
  local rel="$1"
  awk -v rel="$rel" '
    /^[[:space:]]*#/ {next}
    /^[[:space:]]*$/  {next}
    {
      sha=$1; path=$2
      if (path == rel) { print sha; exit }
    }
  ' "$MANIFEST"
}

verify_or_die() {
  # $1 = path on disk (absolute), $2 = path key in manifest (relative to project root)
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

# fetch_with_verify <url> <abs_dest> <manifest_key>
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

# 2. .so set (Git LFS dereferenced via media subdomain at v0.9.0)
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

# 3. Model (~2.41 GB, pinned commit)
fetch_with_verify "$MODEL_URL" "$MODEL_DIR/gemma-4-E2B-it.litertlm" "models/gemma-4-E2B-it.litertlm"

echo
echo "=== fetch_artifacts.sh done — all sha256 verified ==="
