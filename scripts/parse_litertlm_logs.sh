#!/usr/bin/env bash
# scripts/parse_litertlm_logs.sh — extract metrics + GPU/OpenCL signals from logs.
# Output: logs/summary.txt
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOGS="$PROJECT_ROOT/logs"
OUT="$LOGS/summary.txt"

extract_metric() {
  # $1 = label regex, $2 = file
  grep -iE "$1" "$2" 2>/dev/null | head -1 | sed -E 's/[[:space:]]+/ /g' || true
}

count_signal() {
  # $1 = keyword regex, $2 = file
  grep -ciE "$1" "$2" 2>/dev/null || echo 0
}

{
  echo "# LiteRT-LM benchmark summary  ($(date -u +%FT%TZ))"
  echo "# device: ${DEVICE_SERIAL:-(unset)}"
  echo
  for kind in cpu gpu; do
    f="$LOGS/${kind}_benchmark.log"
    echo "## ${kind^^} ($(basename "$f"))"
    if [[ ! -s "$f" ]]; then
      echo "  (log missing or empty)"
      echo
      continue
    fi
    echo "  TTFT:           $(extract_metric 'ttft|time.to.first.token' "$f" | head -c 200)"
    echo "  prefill tok/s:  $(extract_metric 'prefill.*tok' "$f" | head -c 200)"
    echo "  decode tok/s:   $(extract_metric 'decode.*tok' "$f" | head -c 200)"
    echo "  peak memory:    $(extract_metric 'peak.*memory|memory.footprint' "$f" | head -c 200)"
    echo
    echo "  signal counts (this log):"
    echo "    OpenCL/Adreno mentions:           $(count_signal 'opencl|adreno|cl_device|cl_platform' "$f")"
    echo "    'fallback'/'falling back' mentions: $(count_signal 'fallback|falling.back' "$f")"
    echo "    error/fatal mentions:             $(count_signal 'error|fatal|abort' "$f")"
    echo
  done

  echo "## smoke logs"
  for f in "$LOGS/cpu_smoke.log" "$LOGS/gpu_smoke.log"; do
    [[ -s "$f" ]] || { echo "  $(basename "$f") (missing)"; continue; }
    echo "  $(basename "$f"): $(wc -l < "$f") lines, last line: $(tail -1 "$f")"
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
