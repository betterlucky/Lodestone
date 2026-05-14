#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REMOTE_DIR="/sdcard/Android/data/com.daveharris.healthmonitor/files/analysis-sleep2/screenshots"
LOCAL_DIR="$ROOT_DIR/calibration/sleep2/imported"

mkdir -p "$LOCAL_DIR"

if ! command -v adb >/dev/null 2>&1; then
  echo "adb is not available on PATH." >&2
  exit 1
fi

if ! adb get-state >/dev/null 2>&1; then
  echo "No Android device is connected or authorised." >&2
  exit 1
fi

remote_files="$(adb shell "find '$REMOTE_DIR' -maxdepth 1 -type f \\( -name 'sleep2-statistics-*.png' -o -name 'sleep2-statistics-*.json' \\) 2>/dev/null" | tr -d '\r')"

if [[ -z "$remote_files" ]]; then
  echo "No Sleep2 imports found at $REMOTE_DIR"
  exit 0
fi

echo "$remote_files" | while IFS= read -r remote_file; do
  [[ -z "$remote_file" ]] && continue
  adb pull "$remote_file" "$LOCAL_DIR/" >/dev/null
  echo "Pulled $(basename "$remote_file")"
done

echo "Sleep2 imports are available in $LOCAL_DIR"
