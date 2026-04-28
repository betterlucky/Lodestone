#!/usr/bin/env bash
set -euo pipefail

PACKAGE="com.daveharris.healthmonitor"
RECEIVER="$PACKAGE/.ProbeCommandReceiver"
OUT_DIR="/tmp/hm_overnight_$(date +%Y%m%d_%H%M%S)"

echo "Stopping and fetching offline PPI..."
adb shell am broadcast -n "$RECEIVER" --es probe_command offline_stop_fetch --es probe_data_type PPI

if [[ "${INCLUDE_PPG:-0}" == "1" ]]; then
  echo "Stopping and fetching offline PPG..."
  echo "Warning: overnight PPG is large enough that Android may time out this broadcast."
  adb shell am broadcast -n "$RECEIVER" --es probe_command offline_stop_fetch --es probe_data_type PPG
else
  echo "Skipping overnight PPG fetch by default; PPI is the primary HRV lane."
fi

echo "Running normal Polar sync so sleep/Nightly Recharge/normal lanes can be compared..."
adb shell am broadcast -n "$RECEIVER" --es probe_command sync
echo "Waiting for normal sync to finish writing results..."
sleep 45

mkdir -p "$OUT_DIR"
adb exec-out run-as "$PACKAGE" cat databases/health-monitor-probe.db > "$OUT_DIR/health-monitor-probe.db"
adb exec-out run-as "$PACKAGE" cat databases/health-monitor-probe.db-wal > "$OUT_DIR/health-monitor-probe.db-wal" 2>/dev/null || true
adb exec-out run-as "$PACKAGE" cat databases/health-monitor-probe.db-shm > "$OUT_DIR/health-monitor-probe.db-shm" 2>/dev/null || true

echo "Pulled database to $OUT_DIR"
echo "Latest offline results:"
sqlite3 "$OUT_DIR/health-monitor-probe.db" \
  "select id, requestedRange, status, detailSummary, substr(errorMessage,1,180) from sync_domain_result where domain='OFFLINE_RECORDING' order by id desc limit 8;"
