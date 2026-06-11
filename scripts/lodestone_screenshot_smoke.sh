#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_ID="${APP_ID:-com.daveharris.healthmonitor}"
OUT_DIR="${OUT_DIR:-$ROOT_DIR/build/qa-screenshots/$(date +%Y%m%d-%H%M%S)}"
ADB_BIN="${ADB:-adb}"
ADB_SERIAL="${ADB_SERIAL:-}"
LODESTONE_STOP_GRADLE="${LODESTONE_STOP_GRADLE:-1}"
LODESTONE_KILL_ADB_SERVER="${LODESTONE_KILL_ADB_SERVER:-0}"
ADB_CMD=("$ADB_BIN")
if [[ -n "$ADB_SERIAL" ]]; then
  ADB_CMD+=(-s "$ADB_SERIAL")
fi

cleanup() {
  if [[ "$LODESTONE_STOP_GRADLE" == "1" ]]; then
    (cd "$ROOT_DIR" && ./gradlew --stop >/dev/null 2>&1) || true
  fi
  if [[ "$LODESTONE_KILL_ADB_SERVER" == "1" ]]; then
    "$ADB_BIN" kill-server >/dev/null 2>&1 || true
  fi
}

on_exit() {
  local exit_code=$?
  trap - EXIT INT TERM
  cleanup
  exit "$exit_code"
}

trap on_exit EXIT INT TERM

mkdir -p "$OUT_DIR"

if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 is required for uiautomator hierarchy parsing." >&2
  exit 2
fi

if ! "${ADB_CMD[@]}" get-state >/dev/null 2>&1; then
  echo "No authorised Android device or emulator is connected." >&2
  echo "Start an AVD such as Lodestone_API35_phone, then rerun this script." >&2
  exit 2
fi

cd "$ROOT_DIR"
./gradlew :app:assembleDebug >/dev/null

"${ADB_CMD[@]}" install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
if ! "${ADB_CMD[@]}" shell pm path "$APP_ID" | grep -q '^package:'; then
  echo "Install did not leave $APP_ID visible to package manager." >&2
  exit 3
fi

for permission in android.permission.BLUETOOTH_SCAN android.permission.BLUETOOTH_CONNECT; do
  "${ADB_CMD[@]}" shell pm grant "$APP_ID" "$permission" >/dev/null 2>&1 || true
done

"${ADB_CMD[@]}" shell am start -n "$APP_ID/.MainActivity" >/dev/null
for _ in {1..12}; do
  if "${ADB_CMD[@]}" shell dumpsys window 2>/dev/null | tr -d '\r' | grep -Eq "mCurrentFocus|mFocusedApp" &&
      "${ADB_CMD[@]}" shell dumpsys window 2>/dev/null | tr -d '\r' | grep -Eq "$APP_ID"; then
    break
  fi
  sleep 1
done

screen_size="$("${ADB_CMD[@]}" shell wm size | tr -d '\r' | awk -F': ' '/Physical size/ {print $2}')"
width="${screen_size%x*}"
height="${screen_size#*x}"
if [[ -z "$width" || -z "$height" || "$width" == "$screen_size" ]]; then
  width=1080
  height=2400
fi

tap_bottom_nav() {
  local label="$1"
  local slot="$2"
  local x=$(( width * slot / 6 ))
  local y=$(( height - 90 ))
  tap_label "$label" "$x" "$y"
  sleep 1
}

dump_window() {
  local name="$1"
  "${ADB_CMD[@]}" shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 || true
  "${ADB_CMD[@]}" pull /sdcard/window.xml "$OUT_DIR/${name}.xml" >/dev/null 2>&1 || true
}

tap_label() {
  local label="$1"
  local fallback_x="$2"
  local fallback_y="$3"
  dump_window "tap-${label// /-}"
  local bounds
  bounds="$(LABEL="$label" XML="$OUT_DIR/tap-${label// /-}.xml" python3 - <<'PY' || true
import os
import re
import xml.etree.ElementTree as ET

label = os.environ["LABEL"]
xml_path = os.environ["XML"]
try:
    root = ET.parse(xml_path).getroot()
except Exception:
    raise SystemExit(1)

for node in root.iter("node"):
    if label in {node.attrib.get("text"), node.attrib.get("content-desc")}:
        bounds = node.attrib.get("bounds", "")
        match = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
        if match:
            x1, y1, x2, y2 = map(int, match.groups())
            print(f"{(x1 + x2) // 2} {(y1 + y2) // 2}")
            break
PY
)"
  if [[ -n "$bounds" ]]; then
    read -r tap_x tap_y <<<"$bounds"
    if [[ "$tap_x" =~ ^[0-9]+$ && "$tap_y" =~ ^[0-9]+$ ]]; then
      "${ADB_CMD[@]}" shell input tap "$tap_x" "$tap_y"
    else
      "${ADB_CMD[@]}" shell input tap "$fallback_x" "$fallback_y"
    fi
  else
    "${ADB_CMD[@]}" shell input tap "$fallback_x" "$fallback_y"
  fi
}

capture() {
  local name="$1"
  "${ADB_CMD[@]}" exec-out screencap -p > "$OUT_DIR/${name}.png"
  dump_window "$name"
}

capture "00-launch"
tap_bottom_nav "Now" 1
capture "01-now"
tap_bottom_nav "Journal" 3
capture "02-journal"
tap_bottom_nav "History" 5
capture "03-history"

tap_label "Settings" $(( width - 90 )) 110
sleep 1
capture "04-settings"

echo "Saved screenshot smoke artifacts to $OUT_DIR"
