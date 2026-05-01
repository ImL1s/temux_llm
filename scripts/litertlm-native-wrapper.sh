#!/usr/bin/env bash
# scripts/litertlm-native-wrapper.sh — source copy of the native CLI wrapper.
# The installer (install-termux-native.sh) embeds this logic directly into
# $HOME/.litertlm/litertlm-native-impl.sh on the device.
# Keep this file in sync with the IMPL_EOF heredoc in install-termux-native.sh.
#
# Usage (once installed):
#   litertlm-native [--backend cpu|gpu] [--json] [--help] "<prompt>"
#
# --backend cpu|gpu   (default: gpu)
# --json              print raw binary stdout (includes BenchmarkInfo lines)
# --help              show this message
#
# NOTE: litert_lm_main does NOT stream — it buffers output until inference is
# complete. --no-stream is therefore implicit; there is no streaming mode here.
# For streaming, use the APK path (litertlm, which calls the HTTP service).

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

# Pretty output: strip BenchmarkInfo lines, print model reply, then metrics.
RESPONSE=""
TOTAL_MS=""
TOKENS=""
DECODE_TPS=""

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
done << RAWEOF
$RAW
RAWEOF

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
