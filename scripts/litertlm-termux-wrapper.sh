#!/usr/bin/env bash
# litertlm — convenience wrapper around the local temuxllm service on 127.0.0.1:11434.
# Designed to run inside Termux (/usr/bin/env -> Termux env). Lives at
# /data/local/tmp/bin/litertlm; world-readable + executable by Termux app uid.
#
# Default mode: streams tokens to the terminal as they're generated (NDJSON path).
# --json mode: prints the raw NDJSON stream verbatim (one JSON object per line).
# --no-stream:  asks the server for a single JSON document at the end.
#
# Usage:
#   litertlm "prompt"
#   litertlm --backend cpu "prompt"
#   litertlm --json "prompt"
#   litertlm --no-stream "prompt"
#   litertlm --help

set -u
HOST="${LITERTLM_HOST:-127.0.0.1:11434}"
BACKEND="gpu"
JSON_OUT=0
STREAM=1
PROMPT=""

usage() {
  cat <<EOF
Usage: litertlm [--backend cpu|gpu] [--json] [--no-stream] "<prompt>"

  --backend BK    cpu | gpu  (default: gpu)
  --json          print raw NDJSON stream from /api/generate (one obj per line)
  --no-stream     server returns one final JSON document (slower TTFT visible)
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
    --no-stream) STREAM=0; shift ;;
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
STREAM_JSON=$([ "$STREAM" = "1" ] && echo "true" || echo "false")
PAYLOAD='{"prompt":"'"$ESC_PROMPT"'","backend":"'"$BACKEND"'","stream":'"$STREAM_JSON"'}'

# Streaming branch: server returns NDJSON, we read line by line as it arrives.
if [ "$STREAM" = "1" ]; then
  if [ "$JSON_OUT" = "1" ]; then
    # Raw NDJSON pass-through.
    curl -Nsm 300 -X POST "http://$HOST/api/generate" \
      -H 'Content-Type: application/json' \
      --data-binary "$PAYLOAD"
    exit $?
  fi
  # Pretty: print the response field of each chunk to stdout, then a metrics line on done.
  TOTAL_MS="?"; TOKENS="?"; CHARS="?"
  while IFS= read -r line; do
    [ -z "$line" ] && continue
    # token chunk: {"response":"piece","done":false}
    chunk=$(printf '%s' "$line" | sed -n 's/.*"response":"\(\([^"\\]\|\\.\)*\)".*/\1/p' | head -1)
    if [ -n "$chunk" ]; then
      printf '%b' "$(printf '%s' "$chunk" \
        | sed -e 's/\\\"/\"/g' -e 's/\\\\/\\/g' -e 's/\\n/\n/g' -e 's/\\t/\t/g')"
    fi
    case "$line" in
      *'"done":true'*)
        TOTAL_MS=$(printf '%s' "$line" | sed -n 's/.*"total_duration_ms":\([0-9]*\).*/\1/p')
        TOKENS=$(printf '%s' "$line" | sed -n 's/.*"output_tokens":\([0-9]*\).*/\1/p')
        CHARS=$(printf '%s' "$line" | sed -n 's/.*"output_chars":\([0-9]*\).*/\1/p')
        ;;
    esac
  done < <(curl -Nsm 300 -X POST "http://$HOST/api/generate" \
              -H 'Content-Type: application/json' \
              --data-binary "$PAYLOAD")
  printf '\n\n[%s  total=%sms  tokens=%s  chars=%s]\n' \
    "$BACKEND" "${TOTAL_MS:-?}" "${TOKENS:-?}" "${CHARS:-?}"
  exit 0
fi

# Non-streaming branch: server returns one JSON document.
RESP="$(curl -sm 300 -X POST "http://$HOST/api/generate" \
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

# Pretty mode for non-stream JSON.
TEXT=$(printf '%s' "$RESP" | sed -n 's/.*"response":"\(\([^"\\]\|\\.\)*\)".*/\1/p' | head -1)
TEXT=$(printf '%b' "$(printf '%s' "$TEXT" \
  | sed -e 's/\\\"/\"/g' -e 's/\\\\/\\/g' -e 's/\\n/\n/g' -e 's/\\t/\t/g')")
TOTAL=$(printf '%s' "$RESP" | sed -n 's/.*"total_duration_ms":\([0-9]*\).*/\1/p')
TOKENS=$(printf '%s' "$RESP" | sed -n 's/.*"output_tokens":\([0-9]*\).*/\1/p')

if [ -z "$TEXT" ]; then
  printf '(empty response — raw JSON below)\n%s\n' "$RESP"
else
  printf '%s\n' "$TEXT"
fi
printf '\n[%s  total=%sms  tokens=%s]\n' "$BACKEND" "${TOTAL:-?}" "${TOKENS:-?}"
