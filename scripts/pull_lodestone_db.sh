#!/usr/bin/env bash
set -euo pipefail

out_dir="${1:-/tmp/lodestone_db_$(date +%Y%m%d_%H%M%S)}"
package_name="com.daveharris.healthmonitor"
db_name="health-monitor-probe.db"

mkdir -p "$out_dir"

adb exec-out run-as "$package_name" cat "databases/$db_name" > "$out_dir/$db_name"

for suffix in -wal -shm; do
  if adb shell run-as "$package_name" ls "databases/$db_name$suffix" >/dev/null 2>&1; then
    adb exec-out run-as "$package_name" cat "databases/$db_name$suffix" > "$out_dir/$db_name$suffix"
  fi
done

echo "$out_dir/$db_name"
