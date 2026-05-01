#!/usr/bin/env bash
# scripts/litertlm-native-wrapper.sh — source copy of the native CLI wrapper.
# The installer (install-termux-native.sh) embeds this logic directly into
# $HOME/.litertlm/litertlm-native-impl.sh on the device.
# Keep this file in sync with the IMPL_EOF heredoc in install-termux-native.sh.
#
# Usage (once installed):
#   litertlm-native [--backend cpu|gpu] [--json] [--tools <json>] [--help] "<prompt>"
#
# --backend cpu|gpu   (default: cpu — CLI GPU is blocked in Termux's
#                      linker namespace; advertise gpu as experimental only)
# --json              print raw binary stdout (includes BenchmarkInfo lines)
# --tools <json>      JSON array of tool definitions (Anthropic / OpenAI shape).
#                     Injects the tool block into the prompt and prints any
#                     parsed `{"tool_calls":[...]}` JSON in the response.
#                     Same parser as the APK service. CLI parity.
# --help              show this message
#
# NOTE: litert_lm_main does NOT stream — it buffers output until inference is
# complete. --no-stream is therefore implicit; there is no streaming mode here.
# For streaming, use the APK path (litertlm, which calls the HTTP service).

set -u

LITERTLM_DIR="${HOME}/.litertlm"
BINARY="${LITERTLM_DIR}/litert_lm_main"
BACKEND="cpu"
JSON_OUT=0
TOOLS_JSON=""
PROMPT=""

usage() {
    cat <<EOF
Usage: litertlm-native [--backend cpu|gpu] [--json] [--tools '<JSON-array>'] [--help] "<prompt>"

  --backend BK    cpu | gpu  (default: cpu; gpu is blocked in Termux)
  --json          print raw binary output (includes BenchmarkInfo lines)
  --tools JSON    pass tool definitions to the model. Same shape the APK's
                  /api/chat / /v1/chat/completions / /v1/messages accept
                  (auto-detected per element):
                    Anthropic   {name, description, input_schema}
                    OpenAI Chat {type:"function", function:{name, description, parameters}}
                  When set, the model is instructed to emit
                  {"tool_calls":[{"name":"...","arguments":{...}}]} JSON
                  for any tool invocation, which is parsed back out.
  --help          this message
  prompt          required positional argument

Runtime dir: ${LITERTLM_DIR}
NOTE: each call rebuilds the engine (~3-8 s warm / ~10-20 s first ever; the
xnnpack cache file is persistent next to the model). Steady-state CPU
decode beats the APK service's GPU on most Snapdragon devices; APK still
wins on per-call latency for short turns. For sub-second short calls,
use the APK path (scripts/install.sh + litertlm).
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
        --tools)
            if [ $# -lt 2 ]; then
                printf 'error: --tools requires a JSON array argument\n' >&2
                usage >&2; exit 2
            fi
            TOOLS_JSON="$2"; shift 2 ;;
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

# When --tools is set, prepend a system-prompt block that describes the tools
# and instructs the model to emit a JSON object on its own when it wants to
# call one. Same convention as the APK service's /api/chat /v1/messages
# /v1/chat/completions /v1/responses; we parse the same `{"tool_calls":[...]}`
# pattern out of the response on this side.
FINAL_PROMPT="$PROMPT"
if [ -n "$TOOLS_JSON" ]; then
    if ! command -v python3 >/dev/null 2>&1; then
        printf 'error: --tools requires python3 on PATH (used to render tool block)\n' >&2
        exit 2
    fi
    TOOL_BLOCK=$(printf '%s' "$TOOLS_JSON" | python3 -c '
import json, sys
try:
    tools = json.loads(sys.stdin.read())
    if not isinstance(tools, list):
        sys.stderr.write("error: --tools must be a JSON array\n")
        sys.exit(2)
except Exception as e:
    sys.stderr.write("error: --tools is not valid JSON: " + str(e) + "\n")
    sys.exit(2)
print("You have access to the following tools. If a tool is useful for the user’s request, respond with ONLY a JSON object on its own — no prose, no markdown — in this exact format:")
print()
print("{\"tool_calls\":[{\"name\":\"<tool_name>\",\"arguments\":{<key>:<value>, ...}}]}")
print()
print("Use this format only when calling a tool. Otherwise respond normally with plain text.")
print()
print("Available tools:")
print()
for t in tools:
    fn = t.get("function") if isinstance(t, dict) else None
    if isinstance(fn, dict):
        name = fn.get("name", "")
        desc = fn.get("description", "")
        params = fn.get("parameters")
    elif isinstance(t, dict):
        name = t.get("name", "")
        desc = t.get("description", "")
        params = t.get("input_schema") or t.get("parameters")
    else:
        continue
    if not name:
        continue
    line = "- " + str(name)
    if desc:
        line += ": " + str(desc)
    print(line)
    if params is not None:
        print("  schema: " + json.dumps(params, ensure_ascii=False))
print()
') || exit 2
    FINAL_PROMPT="${TOOL_BLOCK}\n${PROMPT}"
fi

# Run the binary. LD_LIBRARY_PATH ensures it finds the bundled .so accelerators.
RAW=$(LD_LIBRARY_PATH="${LITERTLM_DIR}:${LD_LIBRARY_PATH:-}" \
      "$BINARY" \
      --backend "$BACKEND" \
      --model_path "$MODEL_PATH" \
      --input_prompt "$(printf '%b' "$FINAL_PROMPT")" 2>&1) || {
    code=$?
    printf 'error: litert_lm_main exited with code %d\n' "$code" >&2
    printf '%s\n' "$RAW" >&2
    exit "$code"
}

if [ "$JSON_OUT" = "1" ]; then
    printf '%s\n' "$RAW"
    exit 0
fi

# Pretty output: parse BenchmarkInfo block, print model reply, then metrics.
#
# The v0.11.0-rc.1 binary writes a multi-line BenchmarkInfo block to stderr
# at the end of the run, e.g.:
#
#   BenchmarkInfo:
#       - Init Total: 512.05 ms
#     Prefill Turns (Total 1 turns):
#       Prefill Turn 1: Processed 15 tokens in 372.565677ms duration.
#         Prefill Speed: 40.26 tokens/sec.
#     Decode Turns (Total 1 turns):
#       Decode Turn 1: Processed 3 tokens in 93.549531ms duration.
#         Decode Speed: 32.07 tokens/sec.
#
# We extract Init/Prefill/Decode durations + decode tokens/decode speed and
# sum them into total ms. The response text is everything that is NOT in the
# BenchmarkInfo block and NOT one of the INFO/WARNING/ERROR log lines.
# Two-pass split: the binary always emits its BenchmarkInfo block at the
# very end of execution (after model output). So the LAST occurrence of a
# literal "BenchmarkInfo:" line is guaranteed to be the binary's, regardless
# of what the model itself emitted. This sidesteps the marker-collision
# problem of a single-pass state machine — if a model legitimately writes
# "BenchmarkInfo:" mid-response, that copy comes BEFORE the binary's, so
# `tail -1` picks the right boundary.
BOUNDARY=$(printf '%s\n' "$RAW" | grep -n '^BenchmarkInfo:$' | tail -1 | cut -d: -f1)
if [ -n "$BOUNDARY" ]; then
    RESP_RAW=$(printf '%s\n' "$RAW" | sed -n "1,$((BOUNDARY - 1))p")
    BENCH_RAW=$(printf '%s\n' "$RAW" | sed -n "$((BOUNDARY + 1)),\$p")
else
    RESP_RAW="$RAW"
    BENCH_RAW=""
fi

# Response: between `input_prompt:` and the boundary, minus log lines.
# `awk` rather than `sed` so we can express "after the first input_prompt:".
# Drop the binary's log/diagnostic lines. Caveat: a model response that
# legitimately begins a line with `INFO:`, `WARNING:`, `VERBOSE:`, or
# `ERROR:` will be silently dropped — accepted tradeoff because those
# prefixes are vastly more common from the binary than from the model.
# `VERBOSE:` in particular is emitted without the `[file.cc:line]` prefix
# that INFO uses, so we cannot tighten the regex further without missing
# real binary logs.
RESPONSE=$(printf '%s\n' "$RESP_RAW" | awk '
    /^input_prompt:/ { found=1; next }
    found {
        if ($0 ~ /^(INFO|WARNING|VERBOSE|ERROR):/) next
        if ($0 ~ /^[EFW][0-9]{4} /) next
        print
    }
')

# Bench: parse one field per line from the bench block.
INIT_MS=$(printf '%s\n' "$BENCH_RAW" | sed -n 's/.*Init Total: \([0-9.]*\) ms.*/\1/p' | head -1)
PREFILL_MS=$(printf '%s\n' "$BENCH_RAW" | sed -n 's/.*Prefill Turn[^P]*Processed [0-9]* tokens in \([0-9.]*\)ms duration.*/\1/p' | head -1)
DECODE_TOKENS=$(printf '%s\n' "$BENCH_RAW" | sed -n 's/.*Decode Turn[^P]*Processed \([0-9]*\) tokens.*/\1/p' | head -1)
DECODE_MS=$(printf '%s\n' "$BENCH_RAW" | sed -n 's/.*Decode Turn[^P]*Processed [0-9]* tokens in \([0-9.]*\)ms duration.*/\1/p' | head -1)
DECODE_TPS=$(printf '%s\n' "$BENCH_RAW" | sed -n 's/.*Decode Speed: \([0-9.]*\) tokens.*/\1/p' | head -1)

RESPONSE=$(printf '%s' "$RESPONSE" | sed -e '/./,$!d' -e 's/[[:space:]]*$//')

# Compose total = init + prefill + decode (in ms; INIT_MS is already ms,
# PREFILL_MS / DECODE_MS are ms with decimals).
if [ -n "$INIT_MS" ] || [ -n "$PREFILL_MS" ] || [ -n "$DECODE_MS" ]; then
    TOTAL_MS=$(awk -v a="${INIT_MS:-0}" -v b="${PREFILL_MS:-0}" -v c="${DECODE_MS:-0}" \
                   'BEGIN { printf "%.0f", a + b + c }')
else
    TOTAL_MS=""
fi
TOKENS="${DECODE_TOKENS:-}"

# When --tools was set, look for a `{"tool_calls":[...]}` JSON object in the
# model output and surface it. CLI wrapper parity with the APK service: same
# wire-level expectation of the model, same `name(args)` extraction logic.
TOOL_CALLS_PRETTY=""
TEXT_AFTER_TOOLS="$RESPONSE"
if [ -n "$TOOLS_JSON" ] && [ -n "$RESPONSE" ] && command -v python3 >/dev/null 2>&1; then
    PY_OUT=$(printf '%s' "$RESPONSE" | python3 -c '
import json, re, sys
buf = sys.stdin.read()
# Find a {... "tool_calls": [...] ...} object using brace balance.
key = '"'"'"tool_calls"'"'"'
i = buf.find(key)
if i < 0:
    sys.stdout.write(buf); sys.stderr.write("\x00"); sys.exit(0)
start = i
while start >= 0 and buf[start] != "{":
    start -= 1
if start < 0:
    sys.stdout.write(buf); sys.stderr.write("\x00"); sys.exit(0)
depth = 0; in_str = False; esc = False; end = -1
for j in range(start, len(buf)):
    c = buf[j]
    if in_str:
        if esc: esc = False
        elif c == "\\": esc = True
        elif c == "\"": in_str = False
    else:
        if c == "\"": in_str = True
        elif c == "{": depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                end = j; break
if end < 0:
    sys.stdout.write(buf); sys.stderr.write("\x00"); sys.exit(0)
try:
    obj = json.loads(buf[start:end+1])
    calls = obj.get("tool_calls", [])
except Exception:
    sys.stdout.write(buf); sys.stderr.write("\x00"); sys.exit(0)
text_remnant = (buf[:start] + buf[end+1:]).strip()
sys.stdout.write(text_remnant)
sys.stderr.write("\x00")
for c in calls:
    name = c.get("name") or (c.get("function") or {}).get("name") or ""
    args = c.get("arguments")
    if args is None: args = (c.get("function") or {}).get("arguments")
    if isinstance(args, str):
        try: args = json.loads(args)
        except Exception: pass
    sys.stderr.write(name + "::" + json.dumps(args, ensure_ascii=False) + "\n")
' 2>/dev/null) || true
    # Python writes: stdout=text-remnant, stderr=NUL-prefixed-then-tool-calls
    # We re-emit them; bash limitation: capture stderr separately requires
    # a temp file. Use { ... } 2>tmp pattern.
    TMP_TOOLCALLS=$(mktemp 2>/dev/null || printf '/tmp/temuxllm-tc-%s' "$$")
    TEXT_AFTER_TOOLS=$(printf '%s' "$RESPONSE" | python3 -c '
import json, sys
buf = sys.stdin.read()
key = "\"tool_calls\""
i = buf.find(key)
if i < 0: print(buf, end=""); sys.exit(0)
start = i
while start >= 0 and buf[start] != "{": start -= 1
if start < 0: print(buf, end=""); sys.exit(0)
depth=0; ins=False; esc=False; end=-1
for j in range(start, len(buf)):
    c=buf[j]
    if ins:
        if esc: esc=False
        elif c=="\\": esc=True
        elif c=="\"": ins=False
    else:
        if c=="\"": ins=True
        elif c=="{": depth+=1
        elif c=="}":
            depth-=1
            if depth==0: end=j; break
if end<0: print(buf, end=""); sys.exit(0)
print((buf[:start]+buf[end+1:]).strip(), end="")
')
    TOOL_CALLS_PRETTY=$(printf '%s' "$RESPONSE" | python3 -c '
import json, sys
buf = sys.stdin.read()
key = "\"tool_calls\""
i = buf.find(key)
if i < 0: sys.exit(0)
start = i
while start >= 0 and buf[start] != "{": start -= 1
if start < 0: sys.exit(0)
depth=0; ins=False; esc=False; end=-1
for j in range(start, len(buf)):
    c=buf[j]
    if ins:
        if esc: esc=False
        elif c=="\\": esc=True
        elif c=="\"": ins=False
    else:
        if c=="\"": ins=True
        elif c=="{": depth+=1
        elif c=="}":
            depth-=1
            if depth==0: end=j; break
if end<0: sys.exit(0)
try: obj=json.loads(buf[start:end+1])
except Exception: sys.exit(0)
for c in obj.get("tool_calls", []):
    name=c.get("name") or (c.get("function") or {}).get("name") or ""
    args=c.get("arguments")
    if args is None: args=(c.get("function") or {}).get("arguments")
    if isinstance(args, str):
        try: args=json.loads(args)
        except Exception: pass
    print("Tool call: " + name + "(" + json.dumps(args, ensure_ascii=False) + ")")
')
    rm -f "$TMP_TOOLCALLS" 2>/dev/null || true
fi

if [ -n "$TOOL_CALLS_PRETTY" ]; then
    if [ -n "$TEXT_AFTER_TOOLS" ]; then
        printf '%s\n\n' "$TEXT_AFTER_TOOLS"
    fi
    printf '%s\n' "$TOOL_CALLS_PRETTY"
elif [ -z "$RESPONSE" ]; then
    printf '(empty response)\n'
else
    printf '%s\n' "$RESPONSE"
fi

printf '\n[%s  total=%sms  tokens=%s  decode=%s t/s]\n' \
    "$BACKEND" \
    "${TOTAL_MS:-?}" \
    "${TOKENS:-?}" \
    "${DECODE_TPS:-?}"
