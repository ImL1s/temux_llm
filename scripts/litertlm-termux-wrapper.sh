#!/usr/bin/env bash
# litertlm — convenience wrapper around the local temuxllm service on 127.0.0.1:11434.
# Designed to run inside Termux (/usr/bin/env -> Termux env). Lives at
# /data/local/tmp/bin/litertlm; world-readable + executable by Termux app uid.
#
# Usage:
#   litertlm "prompt"                 # default backend (gpu)
#   litertlm --backend cpu "prompt"
#   litertlm --json "prompt"          # raw JSON response
#   litertlm --help

set -u
HOST="${LITERTLM_HOST:-127.0.0.1:11434}"
BACKEND="gpu"
JSON_OUT=0
PROMPT=""

usage() {
  cat <<EOF
Usage: litertlm [--backend cpu|gpu] [--json] "<prompt>"

  --backend BK    cpu | gpu  (default: gpu)
  --json          print raw JSON response from /api/generate
  --help          this message
  prompt          required positional argument

Env LITERTLM_HOST overrides "127.0.0.1:11434".
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --backend)
      if [ $# -lt 2 ]; then
        echo "error: --backend requires a value (cpu or gpu)" >&2
        usage >&2; exit 2
      fi
      BACKEND="$2"; shift 2 ;;
    --json) JSON_OUT=1; shift ;;
    --help|-h) usage; exit 0 ;;
    --) shift; PROMPT="$*"; break ;;
    -*) echo "unknown flag: $1" >&2; usage >&2; exit 2 ;;
    *) PROMPT="$*"; break ;;
  esac
done

if [ -z "${PROMPT:-}" ]; then
  echo "error: prompt is required" >&2
  usage >&2
  exit 2
fi

case "$BACKEND" in
  cpu|gpu) ;;
  *) echo "error: --backend must be cpu or gpu (got: $BACKEND)" >&2; exit 2 ;;
esac

# JSON-encode the prompt manually (escape backslash, double-quote, newline, tab, CR).
escape_json() {
  printf '%s' "$1" \
    | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' -e ':a;N;$!ba;s/\n/\\n/g' \
          -e 's/\t/\\t/g' -e 's/\r/\\r/g'
}
ESC_PROMPT="$(escape_json "$PROMPT")"
PAYLOAD='{"prompt":"'"$ESC_PROMPT"'","backend":"'"$BACKEND"'"}'

RESP="$(curl -sm 120 -X POST "http://$HOST/api/generate" \
  -H 'Content-Type: application/json' \
  --data-binary "$PAYLOAD")"

if [ -z "$RESP" ]; then
  echo "error: empty response from $HOST/api/generate (service down or timeout)" >&2
  exit 1
fi

if [ "$JSON_OUT" = "1" ]; then
  printf '%s\n' "$RESP"
  exit 0
fi

# Pretty mode WITHOUT python: extract "response" + a few metrics via sed.
# Order matters: take the substring between "response":" and the next ","done":...
# This is fragile but our service emits a well-known schema (HttpServer.kt).
extract_str() {
  # $1 = field name (e.g. response)
  printf '%s' "$RESP" | sed -n 's/.*"'"$1"'":"\(\([^"\\]\|\\.\)*\)".*/\1/p' | head -1
}
extract_num() {
  # $1 = field
  printf '%s' "$RESP" | sed -n 's/.*"'"$1"'":\([0-9.]*\).*/\1/p' | head -1
}

TEXT="$(extract_str response)"
# Unescape \n \t \" \\ in the response.
TEXT="$(printf '%b' "$(printf '%s' "$TEXT" \
  | sed -e 's/\\\"/\"/g' -e 's/\\\\/\\/g' -e 's/\\n/\n/g' -e 's/\\t/\t/g')")"
TTFT="$(extract_num ttft_seconds)"
PREFILL="$(extract_num prefill_tokens_per_sec)"
DECODE="$(extract_num decode_tokens_per_sec)"
TOTAL="$(extract_num total_duration_ms)"

if [ -z "$TEXT" ]; then
  printf '(empty response — raw JSON below)\n%s\n' "$RESP"
else
  printf '%s\n' "$TEXT"
fi
printf '\n[%s  ttft=%ss  prefill=%st/s  decode=%st/s  total=%sms]\n' \
  "$BACKEND" "${TTFT:-?}" "${PREFILL:-?}" "${DECODE:-?}" "${TOTAL:-?}"
