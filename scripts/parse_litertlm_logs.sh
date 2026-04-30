#!/usr/bin/env bash
# scripts/parse_litertlm_logs.sh — extract BenchmarkInfo metrics + GPU/OpenCL signals.
# v0.9.0 binary auto-prints a "BenchmarkInfo:" block on every run; we grep the exact labels
# inside that block (Time to first token / Prefill Speed / Decode Speed / Init Total) so we
# don't accidentally match the same words inside the input prompt body.
#
# Output: logs/summary.txt
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOGS="$PROJECT_ROOT/logs"
OUT="$LOGS/summary.txt"

trim() { sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//'; }

extract_field() {
  # $1 = exact label inside BenchmarkInfo block, $2 = log file
  # Matches lines like:   "    Time to first token: 1.62 s"
  #                       "      Prefill Speed: 199.67 tokens/sec."
  #                       "    - Init Total: 1565.57 ms"
  grep -E "^[[:space:]]*-?[[:space:]]*${1}:" "$2" 2>/dev/null \
    | head -1 \
    | sed -E "s/^[[:space:]]*-?[[:space:]]*${1}:[[:space:]]*//" \
    | trim
}

extract_processed() {
  # "Prefill Turn 1: Processed 982 tokens in 4.918169165s duration."
  # we want "982 tokens in 4.918s"
  grep -E "^[[:space:]]*${1} Turn 1: Processed " "$2" 2>/dev/null \
    | head -1 \
    | sed -E "s/^[[:space:]]*${1} Turn 1: //" \
    | trim
}

count_signal() {
  # Use grep | wc -l so the result is always exactly one number on stdout, even when there
  # are zero matches (avoids set -e tripping or double-zero output from `grep -c`).
  grep -iE "$1" "$2" 2>/dev/null | wc -l | tr -d ' '
}

print_block() {
  local kind="$1" f="$2" kind_upper
  kind_upper=$(echo "$kind" | tr '[:lower:]' '[:upper:]')
  echo "## ${kind_upper} ($(basename "$f"))"
  if [[ ! -s "$f" ]]; then
    echo "  (log missing or empty)"
    echo
    return
  fi
  echo "  Init Total:     $(extract_field 'Init Total' "$f")"
  echo "  TTFT:           $(extract_field 'Time to first token' "$f")"
  echo "  Prefill Turn 1: $(extract_processed 'Prefill' "$f")"
  echo "  Prefill Speed:  $(extract_field 'Prefill Speed' "$f")"
  echo "  Decode Turn 1:  $(extract_processed 'Decode' "$f")"
  echo "  Decode Speed:   $(extract_field 'Decode Speed' "$f")"
  echo
  echo "  signal counts (this log):"
  echo "    OpenCL/Adreno mentions:    $(count_signal 'opencl|adreno|cl_device|cl_platform' "$f")"
  echo "    fallback mentions:         $(count_signal 'fallback|falling.back' "$f")"
  echo "    Aborted/fatal/abort:       $(count_signal '^Aborted$|F[0-9]{4}|Check failed' "$f")"
  echo "    error mentions:            $(count_signal '^ERROR' "$f")"
  echo
}

{
  echo "# LiteRT-LM benchmark summary  ($(date -u +%FT%TZ))"
  echo "# device:        ${DEVICE_SERIAL:-(unset)}"
  echo "# binary:        litert_lm_main v0.9.0 (android arm64)"
  echo "# model:         $(basename "$(ls "$PROJECT_ROOT"/models/*.litertlm 2>/dev/null | head -1)" 2>/dev/null || echo unknown)"
  echo

  for kind in cpu gpu; do
    print_block "$kind" "$LOGS/${kind}_benchmark.log"
  done

  echo "## smoke logs"
  for f in "$LOGS/cpu_smoke.log" "$LOGS/gpu_smoke.log"; do
    [[ -s "$f" ]] || { echo "  $(basename "$f") (missing)"; continue; }
    echo "  $(basename "$f"): $(wc -l < "$f") lines"
    echo "    Init Total:     $(extract_field 'Init Total' "$f")"
    echo "    TTFT:           $(extract_field 'Time to first token' "$f")"
    echo "    Prefill Speed:  $(extract_field 'Prefill Speed' "$f")"
    echo "    Decode Speed:   $(extract_field 'Decode Speed' "$f")"
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
echo
cat "$OUT"
