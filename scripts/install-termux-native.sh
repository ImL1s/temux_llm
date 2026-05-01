#!/usr/bin/env bash
# scripts/install-termux-native.sh — Termux-native LiteRT-LM installer.
# Run this INSIDE Termux on the Android device (not on a host machine).
# No APK, no USB cable, no Android service required.
#
# What it does:
#   1. Installs curl via pkg if not already present.
#   2. Downloads the v0.11.0-rc.1 litert_lm_main binary + 6 .so accelerators
#      to $HOME/.litertlm/ with sha256 verification (idempotent).
#   3. Downloads the chosen model (default: gemma-4-E2B-it, 2.4 GB).
#   4. Drops litertlm-native-wrapper.sh at $HOME/.local/bin/litertlm-native.
#   5. Prints PATH guidance.
#
# Usage:
#   bash install-termux-native.sh
#   MODEL=qwen3  bash install-termux-native.sh
#   MODEL=e4b    bash install-termux-native.sh
#
# MODEL values:
#   e2b   (default) — gemma-4-E2B-it.litertlm, 2.4 GB, works on most arm64 phones
#   qwen3           — Qwen3-0.6B.litertlm, 614 MB, smallest fallback
#   e4b             — gemma-4-E4B-it.litertlm, 3.4 GB, needs high-end SoC + RAM
#
# Tradeoff vs the APK path:
#   Each invocation here reloads the model (1-7 s warm cache, 12-60 s cold).
#   The APK service keeps the engine resident (sub-second per call).
#   Use this path when you cannot or do not want to sideload the APK.

set -eu

INSTALL_DIR="${HOME}/.litertlm"
BIN_DIR="${HOME}/.local/bin"
TAG="v0.11.0-rc.1"
BINARY_URL="https://github.com/google-ai-edge/LiteRT-LM/releases/download/${TAG}/litert_lm_main.android_arm64"
LFS_BASE="https://media.githubusercontent.com/media/google-ai-edge/LiteRT-LM/${TAG}/prebuilt/android_arm64"
QWEN3_COMMIT="49837332af6863b008a73a5394ed60789504069d"
GEMMA4_E2B_COMMIT="7fa1d78473894f7e736a21d920c3aa80f950c0db"
GEMMA4_E4B_COMMIT="55b6eef9e490da991fe6bc5fec1834106927b727"
QWEN3_URL="https://huggingface.co/litert-community/Qwen3-0.6B/resolve/${QWEN3_COMMIT}/Qwen3-0.6B.litertlm"
GEMMA4_E2B_URL="https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/${GEMMA4_E2B_COMMIT}/gemma-4-E2B-it.litertlm"
GEMMA4_E4B_URL="https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/${GEMMA4_E4B_COMMIT}/gemma-4-E4B-it.litertlm"

MODEL="${MODEL:-e2b}"

# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------

say() { printf '\n\033[1;36m=== %s ===\033[0m\n' "$*"; }
info() { printf '  %s\n' "$*"; }
die() { printf '\033[1;31mFATAL:\033[0m %s\n' "$*" >&2; exit 1; }

sha256_of() {
    sha256sum "$1" 2>/dev/null | awk '{print $1}' \
      || shasum -a 256 "$1" | awk '{print $1}'
}

# Fetch $1 -> $2, verify against expected sha256 $3. Idempotent.
fetch_verify() {
    local url="$1" dest="$2" expected="$3" label="$4"
    if [ -f "$dest" ]; then
        local got
        got=$(sha256_of "$dest")
        if [ "$got" = "$expected" ]; then
            info "[skip] $label — sha256 already correct"
            return 0
        fi
        info "[redownload] $label — sha256 mismatch (corrupt or partial)"
        rm -f "$dest"
    fi
    info "[fetch] $label"
    info "        $url"
    curl -fL --retry 3 --retry-delay 2 --progress-bar -o "$dest" "$url"
    local got
    got=$(sha256_of "$dest")
    if [ "$got" != "$expected" ]; then
        printf 'FATAL: sha256 mismatch for %s\n  got:      %s\n  expected: %s\n' \
            "$label" "$got" "$expected" >&2
        rm -f "$dest"
        exit 1
    fi
    info "[ok] $label — sha256 verified"
}

# ---------------------------------------------------------------------------
# 1. ensure curl
# ---------------------------------------------------------------------------
say "checking prerequisites"
if ! command -v curl >/dev/null 2>&1; then
    info "curl not found — installing via pkg"
    pkg install -y curl
fi
info "curl: $(command -v curl)"

# ---------------------------------------------------------------------------
# 2. create dirs
# ---------------------------------------------------------------------------
say "creating install directories"
mkdir -p "$INSTALL_DIR" "$BIN_DIR"
info "runtime dir : $INSTALL_DIR"
info "bin dir     : $BIN_DIR"

# ---------------------------------------------------------------------------
# 3. download binary
# ---------------------------------------------------------------------------
say "downloading litert_lm_main binary (${TAG})"
fetch_verify \
    "$BINARY_URL" \
    "$INSTALL_DIR/litert_lm_main" \
    "2e1fed4e73ba4337029b47650fe5194dd067c1a0ae9adc139b262a615bdedeb7" \
    "litert_lm_main"
chmod +x "$INSTALL_DIR/litert_lm_main"

# ---------------------------------------------------------------------------
# 4. download .so accelerators
# ---------------------------------------------------------------------------
say "downloading .so accelerators (${TAG})"

fetch_verify \
    "${LFS_BASE}/libGemmaModelConstraintProvider.so" \
    "$INSTALL_DIR/libGemmaModelConstraintProvider.so" \
    "45ca57e55d52976e5d2dadfc0e874499fc0671c169a28077772c25264f9d81f6" \
    "libGemmaModelConstraintProvider.so"

fetch_verify \
    "${LFS_BASE}/libLiteRtGpuAccelerator.so" \
    "$INSTALL_DIR/libLiteRtGpuAccelerator.so" \
    "8b34f71405a8145461358f304bc3b773c9cee8f189dd764ca5f03f87e7dcdec8" \
    "libLiteRtGpuAccelerator.so"

fetch_verify \
    "${LFS_BASE}/libLiteRtOpenClAccelerator.so" \
    "$INSTALL_DIR/libLiteRtOpenClAccelerator.so" \
    "e3386a53095e13c37ea0b0f63ebcf5ec1698404e63baf606558664ebe8c5dcf1" \
    "libLiteRtOpenClAccelerator.so"

fetch_verify \
    "${LFS_BASE}/libLiteRtTopKOpenClSampler.so" \
    "$INSTALL_DIR/libLiteRtTopKOpenClSampler.so" \
    "2c6849c4c59980ffbd39ce0b51602cf7bf6328de84d861862c677b3ba783ab90" \
    "libLiteRtTopKOpenClSampler.so"

fetch_verify \
    "${LFS_BASE}/libLiteRtTopKWebGpuSampler.so" \
    "$INSTALL_DIR/libLiteRtTopKWebGpuSampler.so" \
    "438f408248c5e2329fb4a3371d5569689a31adf1f07f6663bac87c91c31fc6f1" \
    "libLiteRtTopKWebGpuSampler.so"

fetch_verify \
    "${LFS_BASE}/libLiteRtWebGpuAccelerator.so" \
    "$INSTALL_DIR/libLiteRtWebGpuAccelerator.so" \
    "7ba9b2e940e479cfd0cb237e6a8fc8b56ffdaea2f010ee2fba53e38f71a6a596" \
    "libLiteRtWebGpuAccelerator.so"

# ---------------------------------------------------------------------------
# 5. download model
# ---------------------------------------------------------------------------
case "$MODEL" in
    e2b)
        MODEL_FILE="gemma-4-E2B-it.litertlm"
        MODEL_SHA="ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42"
        MODEL_URL="$GEMMA4_E2B_URL"
        say "downloading gemma-4-E2B-it.litertlm (2.4 GB) — default, widest phone support"
        ;;
    qwen3)
        MODEL_FILE="Qwen3-0.6B.litertlm"
        MODEL_SHA="555579ff2f4fd13379abe69c1c3ab5200f7338bc92471557f1d6614a6e5ab0b4"
        MODEL_URL="$QWEN3_URL"
        say "downloading Qwen3-0.6B.litertlm (614 MB) — smallest/fastest"
        ;;
    e4b)
        MODEL_FILE="gemma-4-E4B-it.litertlm"
        MODEL_SHA="f335f2bfd1b758dc6476db16c0f41854bd6237e2658d604cbe566bcefd00a7bc"
        MODEL_URL="$GEMMA4_E4B_URL"
        say "downloading gemma-4-E4B-it.litertlm (3.4 GB) — needs high-end SoC + RAM"
        ;;
    *)
        die "unknown MODEL='$MODEL' (use e2b, qwen3, or e4b)"
        ;;
esac

fetch_verify "$MODEL_URL" "$INSTALL_DIR/$MODEL_FILE" "$MODEL_SHA" "$MODEL_FILE"

# ---------------------------------------------------------------------------
# 6. install the wrapper script
# ---------------------------------------------------------------------------
say "installing litertlm-native wrapper to $BIN_DIR/litertlm-native"

# The wrapper is embedded here so this single script is self-contained.
# It will be replaced/updated each time install-termux-native.sh is re-run.
cat > "$BIN_DIR/litertlm-native" << 'WRAPPER_EOF'
#!/usr/bin/env bash
# litertlm-native — direct CLI wrapper for litert_lm_main.
# Installed by scripts/install-termux-native.sh. Do not edit manually.
# See scripts/litertlm-native-wrapper.sh in the project for the source.
exec "$(dirname "$0")/../.litertlm/litertlm-native-impl.sh" "$@"
WRAPPER_EOF

# Write the real implementation next to the binary.
cat > "$INSTALL_DIR/litertlm-native-impl.sh" << 'IMPL_EOF'
#!/usr/bin/env bash
# litertlm-native-impl.sh — implementation of the litertlm-native CLI.
# Called by $HOME/.local/bin/litertlm-native.
# Directly execs litert_lm_main — no APK or HTTP service required.
#
# Usage:
#   litertlm-native [--backend cpu|gpu] [--json] [--help] "<prompt>"
#
# --backend cpu|gpu   (default: gpu)
# --json              print raw stdout from the binary (includes BenchmarkInfo)
# --help              show this message
#
# NOTE: the binary buffers all output internally; there is no streaming.
# The full response prints at end, identical to --no-stream on the HTTP wrapper.

set -u

LITERTLM_DIR="${HOME}/.litertlm"
BINARY="${LITERTLM_DIR}/litert_lm_main"
BACKEND="gpu"
JSON_OUT=0
PROMPT=""

usage() {
    cat <<EOF
Usage: litertlm-native [--backend cpu|gpu] [--json] [--help] "<prompt>"

  --backend BK    cpu | gpu  (default: gpu)
  --json          print raw binary output (includes BenchmarkInfo lines)
  --help          this message
  prompt          required positional argument

Runtime dir: ${LITERTLM_DIR}
NOTE: each call reloads the model (1-7 s warm, 12-60 s cold on first GPU run).
For sub-second calls, use the APK path (scripts/install.sh + litertlm).
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        --backend)
            if [ $# -lt 2 ]; then
                printf 'error: --backend requires a value (cpu or gpu)\n' >&2
                usage >&2; exit 2
            fi
            BACKEND="$2"; shift 2 ;;
        --json)
            JSON_OUT=1; shift ;;
        --help|-h)
            usage; exit 0 ;;
        --)
            shift; PROMPT="$*"; break ;;
        -*)
            printf 'unknown flag: %s\n' "$1" >&2; usage >&2; exit 2 ;;
        *)
            PROMPT="$*"; break ;;
    esac
done

if [ -z "${PROMPT:-}" ]; then
    printf 'error: prompt is required\n' >&2
    usage >&2; exit 2
fi

case "$BACKEND" in
    cpu|gpu) ;;
    *) printf 'error: --backend must be cpu or gpu (got: %s)\n' "$BACKEND" >&2; exit 2 ;;
esac

if [ ! -x "$BINARY" ]; then
    printf 'error: binary not found at %s\n' "$BINARY" >&2
    printf '       run scripts/install-termux-native.sh first\n' >&2
    exit 1
fi

# Pick model file — prefer the largest model present, in order of quality.
# User can override with MODEL_PATH env var.
pick_model() {
    local candidates
    candidates="gemma-4-E4B-it.litertlm gemma-4-E2B-it.litertlm Qwen3-0.6B.litertlm"
    for f in $candidates; do
        if [ -f "${LITERTLM_DIR}/${f}" ]; then
            printf '%s/%s\n' "$LITERTLM_DIR" "$f"
            return 0
        fi
    done
    return 1
}

MODEL_PATH="${MODEL_PATH:-}"
if [ -z "$MODEL_PATH" ]; then
    if ! MODEL_PATH=$(pick_model); then
        printf 'error: no model file found in %s\n' "$LITERTLM_DIR" >&2
        printf '       run scripts/install-termux-native.sh to download one\n' >&2
        exit 1
    fi
fi

if [ ! -f "$MODEL_PATH" ]; then
    printf 'error: model not found: %s\n' "$MODEL_PATH" >&2
    exit 1
fi

# Run the binary. LD_LIBRARY_PATH ensures it finds the bundled .so accelerators.
# The binary prints BenchmarkInfo lines to stdout; we capture and parse them.
RAW=$(LD_LIBRARY_PATH="${LITERTLM_DIR}:${LD_LIBRARY_PATH:-}" \
      "$BINARY" \
      --backend "$BACKEND" \
      --model_path "$MODEL_PATH" \
      --input_prompt "$PROMPT" 2>&1) || {
    code=$?
    printf 'error: litert_lm_main exited with code %d\n' "$code" >&2
    printf '%s\n' "$RAW" >&2
    exit "$code"
}

if [ "$JSON_OUT" = "1" ]; then
    printf '%s\n' "$RAW"
    exit 0
fi

# Pretty output: strip BenchmarkInfo lines, print the model's reply,
# then print a metrics summary line.
#
# BenchmarkInfo lines look like (examples from real binary output):
#   BenchmarkInfo: prefill_tokens_per_sec=39.7 decode_tokens_per_sec=24.7 ...
#   BenchmarkInfo: total_duration_ms=1823 output_tokens=42 ...
#
# The actual response text is everything that is NOT a BenchmarkInfo line
# and NOT blank separator lines the binary emits around the response block.

RESPONSE=""
TOTAL_MS=""
TOKENS=""
DECODE_TPS=""

# Pipe rather than heredoc: a heredoc with `<< RAWEOF / $RAW / RAWEOF` would
# silently truncate output if the model's reply ever contained a line that read
# exactly RAWEOF on its own. Piping is immune to that and also avoids the
# unquoted-expansion subtleties of `done << RAWEOF`.
while IFS= read -r line; do
    case "$line" in
        BenchmarkInfo:*)
            t=$(printf '%s' "$line" | sed -n 's/.*total_duration_ms=\([0-9]*\).*/\1/p')
            tk=$(printf '%s' "$line" | sed -n 's/.*output_tokens=\([0-9]*\).*/\1/p')
            d=$(printf '%s' "$line" | sed -n 's/.*decode_tokens_per_sec=\([0-9.]*\).*/\1/p')
            [ -n "$t" ]  && TOTAL_MS="$t"
            [ -n "$tk" ] && TOKENS="$tk"
            [ -n "$d" ]  && DECODE_TPS="$d"
            ;;
        *)
            if [ -z "$RESPONSE" ]; then
                RESPONSE="$line"
            else
                RESPONSE="${RESPONSE}
${line}"
            fi
            ;;
    esac
done <<EOF
$(printf '%s\n' "$RAW")
EOF

# Trim leading/trailing blank lines from the response.
RESPONSE=$(printf '%s' "$RESPONSE" | sed -e '/./,$!d' -e 's/[[:space:]]*$//')

if [ -z "$RESPONSE" ]; then
    printf '(empty response)\n'
else
    printf '%s\n' "$RESPONSE"
fi

printf '\n[%s  total=%sms  tokens=%s  decode=%s t/s]\n' \
    "$BACKEND" \
    "${TOTAL_MS:-?}" \
    "${TOKENS:-?}" \
    "${DECODE_TPS:-?}"
IMPL_EOF

chmod +x "$BIN_DIR/litertlm-native" "$INSTALL_DIR/litertlm-native-impl.sh"
info "[ok] $BIN_DIR/litertlm-native installed"

# ---------------------------------------------------------------------------
# 7. PATH guidance
# ---------------------------------------------------------------------------
say "setup complete"
cat << EOF

Runtime installed to: ${INSTALL_DIR}/
  litert_lm_main      (binary)
  *.so                (6 accelerators)
  ${MODEL_FILE}  (model)

Wrapper installed to: ${BIN_DIR}/litertlm-native

EOF

# Check whether ~/.local/bin is already on PATH
case ":${PATH}:" in
    *":${BIN_DIR}:"*)
        printf '  ~/.local/bin is already on your PATH.\n'
        ;;
    *)
        printf '  ACTION REQUIRED — add ~/.local/bin to your PATH:\n'
        printf '\n'
        printf '    echo '"'"'export PATH="${HOME}/.local/bin:${PATH}"'"'"' >> ~/.bashrc\n'
        printf '    source ~/.bashrc\n'
        printf '\n'
        ;;
esac

cat << EOF

Then run:
  litertlm-native "你好"
  litertlm-native --backend cpu "Reply OK in 3 words."
  litertlm-native --json "what is 2+2?"
  litertlm-native --help

Each call reloads the model (1-7 s warm cache, 12-60 s cold on GPU first run).
For sub-second calls, use the APK path instead: bash scripts/install.sh
EOF
