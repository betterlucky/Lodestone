#!/usr/bin/env python3
"""Summarise Flow/Loop incident provenance without exposing raw health payloads."""

from __future__ import annotations

import argparse
import json
import sqlite3
from datetime import date, timedelta
from pathlib import Path
from typing import Any

import daily_data_completeness as completeness

LANE_TO_POLAR_KEY = {
    "SLEEP": "sleep",
    "NIGHTLY_RECHARGE": "nightly_recharge",
    "PPI_247": "ppi247",
    "HR_247": "hr247",
    "SKIN_TEMPERATURE": "skin_temperature",
    "DAILY_SUMMARY": "daily_summary",
    "ACTIVITY_SAMPLES": "activity_samples",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Report local Loop vs cloud/API backfill provenance around a Flow maintenance incident."
    )
    parser.add_argument("--health-db", required=True, help="Path to the Lodestone/HealthMonitor SQLite database.")
    parser.add_argument("--start-date", required=True, help="First source date to include, YYYY-MM-DD.")
    parser.add_argument("--end-date", required=True, help="Last source date to include, YYYY-MM-DD.")
    parser.add_argument("--incident-label", default="June 2026 Flow/Loop incident", help="Human label for this report.")
    parser.add_argument("--flow-visible-firmware", help="Firmware version Polar Flow showed for this device, if manually observed.")
    parser.add_argument("--flow-observed-date", help="Date Polar Flow firmware was observed, YYYY-MM-DD.")
    parser.add_argument("--public-firmware-version", help="Firmware version listed by a public/manual source.")
    parser.add_argument("--public-firmware-release-date", help="Release date listed by the public/manual source.")
    parser.add_argument(
        "--public-firmware-source",
        default="https://support.polar.com/en/polar-360-firmware-updates",
        help="Source URL or label for --public-firmware-version.",
    )
    parser.add_argument("--public-checked-date", help="Date the public/manual source was checked, YYYY-MM-DD.")
    parser.add_argument("--json", action="store_true", help="Emit JSON instead of text.")
    return parser.parse_args()


def date_range(start: str, end: str) -> list[str]:
    start_date = date.fromisoformat(start)
    end_date = date.fromisoformat(end)
    if end_date < start_date:
        return []
    return [(start_date + timedelta(days=offset)).isoformat() for offset in range((end_date - start_date).days + 1)]


def connect(path: str | Path) -> sqlite3.Connection:
    conn = sqlite3.connect(path)
    conn.row_factory = sqlite3.Row
    return conn


def table_exists(conn: sqlite3.Connection, table: str) -> bool:
    row = conn.execute(
        "select 1 from sqlite_master where type = 'table' and name = ? limit 1",
        (table,),
    ).fetchone()
    return row is not None


def firmware_observations(conn: sqlite3.Connection, args: argparse.Namespace) -> list[dict[str, Any]]:
    observations: list[dict[str, Any]] = []
    if table_exists(conn, "device_profile"):
        rows = conn.execute(
            """
            select deviceId, firmwareVersion, lastSeenAtEpochMs
            from device_profile
            where firmwareVersion is not null and firmwareVersion != ''
            order by lastSeenAtEpochMs desc
            """
        ).fetchall()
        observations.extend(
            {
                "source": "saved_device_profile",
                "version": row["firmwareVersion"],
                "device_id": row["deviceId"],
                "observed_at_epoch_ms": row["lastSeenAtEpochMs"],
                "detail": "Lodestone-saved device profile value from BLE Device Information Service metadata.",
            }
            for row in rows
        )
    if table_exists(conn, "app_settings"):
        row = conn.execute(
            """
            select selectedDeviceId, lastKnownFirmwareBySelectedDevice
            from app_settings
            where id = 1 and lastKnownFirmwareBySelectedDevice is not null
              and lastKnownFirmwareBySelectedDevice != ''
            """
        ).fetchone()
        if row:
            observations.append(
                {
                    "source": "saved_selected_device_setting",
                    "version": row["lastKnownFirmwareBySelectedDevice"],
                    "device_id": row["selectedDeviceId"],
                    "detail": "Firmware value Lodestone saved for the selected device settings.",
                }
            )
    if table_exists(conn, "sync_run"):
        rows = conn.execute(
            """
            select firmwareVersion, count(*) as runs, min(startedAtEpochMs) as firstRun,
                   max(startedAtEpochMs) as lastRun
            from sync_run
            where firmwareVersion is not null and firmwareVersion != ''
            group by firmwareVersion
            order by lastRun desc
            """
        ).fetchall()
        observations.extend(
            {
                "source": "sync_run_runtime_snapshot",
                "version": row["firmwareVersion"],
                "run_count": row["runs"],
                "first_seen_epoch_ms": row["firstRun"],
                "last_seen_epoch_ms": row["lastRun"],
                "detail": "Firmware attached to Lodestone sync runs from the runtime BLE metadata available at run time.",
            }
            for row in rows
        )
    if args.flow_visible_firmware:
        observations.append(
            {
                "source": "manual_polar_flow_observation",
                "version": args.flow_visible_firmware,
                "observed_date": args.flow_observed_date,
                "detail": "Manual observation from Polar Flow. Not treated as a public latest-firmware claim.",
            }
        )
    if args.public_firmware_version:
        observations.append(
            {
                "source": "manual_public_firmware_reference",
                "version": args.public_firmware_version,
                "release_date": args.public_firmware_release_date,
                "checked_date": args.public_checked_date,
                "reference": args.public_firmware_source,
                "detail": "Manual public-source reference; compare only with source/date attached.",
            }
        )
    return observations


def summarize_lane(report: dict[str, Any], lane: str) -> dict[str, Any]:
    lane_report = report["polar"][LANE_TO_POLAR_KEY[lane]]
    provenance = lane_report["provenance"]
    by_origin = provenance.get("by_origin") or {}
    return {
        "present": lane_report.get("present"),
        "records": provenance.get("records", 0),
        "local_loop_records": by_origin.get("local_loop", 0),
        "cloud_backfill_records": by_origin.get("cloud_backfill", 0),
        "unknown_records": by_origin.get("unknown", 0),
        "ranges": provenance.get("ranges", []),
    }


def build_report(args: argparse.Namespace) -> dict[str, Any]:
    if not Path(args.health_db).exists():
        raise SystemExit(f"Database not found: {args.health_db}")
    source_dates = date_range(args.start_date, args.end_date)
    reports = completeness.build_reports(args.health_db, source_dates, garmin_db=None)
    conn = connect(args.health_db)
    try:
        firmware = firmware_observations(conn, args)
    finally:
        conn.close()

    days: list[dict[str, Any]] = []
    backfilled: dict[str, list[str]] = {lane: [] for lane in LANE_TO_POLAR_KEY}
    local_present: dict[str, list[str]] = {lane: [] for lane in LANE_TO_POLAR_KEY}
    mixed: dict[str, list[str]] = {lane: [] for lane in LANE_TO_POLAR_KEY}
    for day_report in reports:
        lanes = {lane: summarize_lane(day_report, lane) for lane in LANE_TO_POLAR_KEY}
        for lane, lane_summary in lanes.items():
            has_local = lane_summary["local_loop_records"] > 0
            has_cloud = lane_summary["cloud_backfill_records"] > 0
            if has_local:
                local_present[lane].append(day_report["source_date"])
            if has_cloud:
                backfilled[lane].append(day_report["source_date"])
            if has_local and has_cloud:
                mixed[lane].append(day_report["source_date"])
        days.append({"source_date": day_report["source_date"], "lanes": lanes})

    return {
        "incident_label": args.incident_label,
        "date_range": {"start": args.start_date, "end": args.end_date, "count": len(source_dates)},
        "days": days,
        "summary": {
            "local_loop_by_lane": local_present,
            "cloud_backfilled_by_lane": backfilled,
            "mixed_local_and_cloud_by_lane": mixed,
        },
        "firmware_observations": firmware,
        "notes": [
            "requestedRange beginning cloud_backfill: is treated as cloud/API backfill provenance.",
            "Firmware observations are source-labelled; no entry should be read as an unqualified latest-firmware claim.",
        ],
    }


def print_text(report: dict[str, Any]) -> None:
    date_range_report = report["date_range"]
    print(f"Flow incident provenance report: {report['incident_label']}")
    print(f"  source dates: {date_range_report['start']}..{date_range_report['end']} ({date_range_report['count']} day(s))")
    print()
    print("Data provenance")
    for day in report["days"]:
        print(f"  {day['source_date']}")
        for lane, lane_report in day["lanes"].items():
            print(
                "    "
                f"{lane}: local={lane_report['local_loop_records']} "
                f"cloud/API={lane_report['cloud_backfill_records']} "
                f"unknown={lane_report['unknown_records']}"
            )
    print()
    print("Backfilled lanes")
    for lane, dates in report["summary"]["cloud_backfilled_by_lane"].items():
        print(f"  {lane}: {', '.join(dates) if dates else 'none'}")
    print()
    print("Firmware observations")
    if not report["firmware_observations"]:
        print("  none recorded")
    for observation in report["firmware_observations"]:
        bits = [f"source={observation['source']}", f"version={observation['version']}"]
        for key in ("device_id", "observed_date", "checked_date", "release_date", "run_count"):
            if observation.get(key) is not None:
                bits.append(f"{key}={observation[key]}")
        if observation.get("reference"):
            bits.append(f"reference={observation['reference']}")
        print(f"  {'; '.join(bits)}")


def main() -> None:
    args = parse_args()
    report = build_report(args)
    if args.json:
        print(json.dumps(report, indent=2))
    else:
        print_text(report)


if __name__ == "__main__":
    main()
