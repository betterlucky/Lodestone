#!/usr/bin/env bash
set -euo pipefail

WORKDIR="$(cd "$(dirname "$0")/.." && pwd)"
EXPORT_GLOB="${HM_EXPORT_GLOB:-/tmp/hmexports/probe-export-*.json}"
GARMIN_DB="${GARMIN_DB:-$HOME/garmin-givemydata/garmin.db}"
PYTHON_BIN="${PYTHON_BIN:-}"

if [[ -z "$PYTHON_BIN" ]]; then
  for candidate in /opt/homebrew/bin/python3.12 python3.12 python3.11 python3.10 python3; do
    if command -v "$candidate" >/dev/null 2>&1; then
      PYTHON_BIN="$(command -v "$candidate")"
      break
    fi
  done
fi

if [[ -z "$PYTHON_BIN" ]]; then
  echo "No suitable Python interpreter found" >&2
  exit 1
fi

if [[ ! -f "$GARMIN_DB" ]]; then
  echo "Garmin DB not found at $GARMIN_DB" >&2
  exit 1
fi

exports=()
while IFS= read -r line; do
  exports+=("$line")
done < <(ls -1t $EXPORT_GLOB 2>/dev/null || true)

if [[ ${#exports[@]} -eq 0 ]]; then
  echo "No probe exports found for $EXPORT_GLOB" >&2
  exit 1
fi

fallback_output=""
fallback_path=""

for export_path in "${exports[@]}"; do
  output="$("$PYTHON_BIN" "$WORKDIR/scripts/morning_model_report.py" "$export_path" --garmin-db "$GARMIN_DB" 2>&1 || true)"
  if [[ -z "$fallback_output" ]]; then
    fallback_output="$output"
    fallback_path="$export_path"
  fi
  if ! grep -q "^Overnight: unavailable" <<<"$output"; then
    echo "$output"
    exit 0
  fi
done

echo "No Polar export with an available overnight window was found. Showing newest report from $fallback_path." >&2
echo "$fallback_output"
