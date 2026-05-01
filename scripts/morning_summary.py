#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
from collections import Counter
from datetime import datetime
from pathlib import Path
from statistics import mean
from typing import Any


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Summarise the latest overnight-ready data from a Health Monitor probe export."
    )
    parser.add_argument(
        "export",
        nargs="?",
        help="Path to a probe-export JSON file. If omitted, the newest file in --exports-dir is used.",
    )
    parser.add_argument(
        "--exports-dir",
        default="/tmp/hmexports",
        help="Directory to scan for the newest probe-export JSON when no file is given.",
    )
    return parser.parse_args()


def load_rows(args: argparse.Namespace) -> tuple[Path, list[dict[str, Any]]]:
    export_path = Path(args.export) if args.export else newest_export(Path(args.exports_dir))
    rows = json.loads(export_path.read_text())
    return export_path, rows


def newest_export(directory: Path) -> Path:
    candidates = sorted(directory.glob("probe-export-*.json"))
    if not candidates:
        raise SystemExit(f"No export files found in {directory}")
    return candidates[-1]


def parse_payload(row: dict[str, Any]) -> dict[str, Any]:
    raw = row.get("rawPayloadJson")
    if not raw:
        return {}
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return {}


def latest_row(rows: list[dict[str, Any]], domain: str, require_source_date: bool = False) -> dict[str, Any] | None:
    candidates = [row for row in rows if row.get("domain") == domain]
    if require_source_date:
        candidates = [row for row in candidates if row.get("sourceDate")]
    if not candidates:
        return None
    return max(candidates, key=lambda row: ((row.get("sourceDate") or ""), row.get("syncTimestampEpochMs") or 0))


def latest_valid_sleep_row(rows: list[dict[str, Any]]) -> dict[str, Any] | None:
    candidates = []
    for row in rows:
        if row.get("domain") != "sleep":
            continue
        payload = parse_payload(row)
        result = payload.get("result") or {}
        end_time = result.get("sleepEndTime")
        start_time = result.get("sleepStartTime")
        result_date = result.get("sleepResultDate") or row.get("sourceDate")
        if end_time and start_time and result_date:
            candidates.append((row, result_date, end_time))
    if not candidates:
        return None
    return max(candidates, key=lambda item: (item[1], item[2]))[0]


def rows_for_date(rows: list[dict[str, Any]], domain: str, source_date: str) -> list[dict[str, Any]]:
    return [row for row in rows if row.get("domain") == domain and row.get("sourceDate") == source_date]


def unique_rows(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    seen: set[tuple[Any, Any, Any]] = set()
    result: list[dict[str, Any]] = []
    for row in sorted(rows, key=lambda item: item.get("syncTimestampEpochMs") or 0):
        key = (
            row.get("domain"),
            row.get("sourceDate"),
            row.get("rawPayloadJson"),
        )
        if key in seen:
            continue
        seen.add(key)
        result.append(row)
    return result


def latest_cluster_rows(rows: list[dict[str, Any]], window_ms: int = 120_000) -> list[dict[str, Any]]:
    if not rows:
        return []
    latest_ts = max((row.get("syncTimestampEpochMs") or 0) for row in rows)
    clustered = [
        row
        for row in rows
        if latest_ts - (row.get("syncTimestampEpochMs") or 0) <= window_ms
    ]
    return unique_rows(clustered)


def daily_summary_row(rows: list[dict[str, Any]], source_date: str) -> dict[str, Any] | None:
    candidates = latest_cluster_rows(rows_for_date(rows, "daily_summary", source_date))
    valid = []
    for row in candidates:
        payload = parse_payload(row)
        if payload.get("date"):
            valid.append((row, payload))
    if not valid:
        return None
    return max((item[0] for item in valid), key=lambda row: row.get("syncTimestampEpochMs") or 0)


def aggregate_ppi(rows: list[dict[str, Any]], source_date: str) -> dict[str, Any]:
    sample_counts: list[int] = []
    ppi_values: list[int] = []
    error_values: list[int] = []
    movement_count = 0
    skin_contact_count = 0
    online_count = 0

    # PPI_247 rows are additive batches rather than one complete daily snapshot.
    # A later sync can contain only a small tail of new batches, so using only
    # the latest sync cluster under-counts the day/overnight trajectory.
    for row in unique_rows(rows_for_date(rows, "ppi247", source_date)):
        payload = parse_payload(row)
        summary = payload.get("summary") or {}
        samples = payload.get("samples") or {}
        sample_counts.append(int(summary.get("sampleCount") or 0))
        ppi_values.extend(samples.get("ppiValueList") or [])
        error_values.extend(samples.get("ppiErrorEstimateList") or [])
        statuses = samples.get("statusList") or []
        movement_count += sum(1 for item in statuses if item.get("movement") != "MOVING_NOT_DETECTED")
        skin_contact_count += sum(1 for item in statuses if item.get("skinContact") == "SKIN_CONTACT_DETECTED")
        online_count += sum(1 for item in statuses if item.get("intervalStatus") == "INTERVAL_IS_ONLINE")

    return {
        "batch_count": len(sample_counts),
        "sample_count": sum(sample_counts),
        "avg_ppi": mean(ppi_values) if ppi_values else None,
        "avg_error": mean(error_values) if error_values else None,
        "movement_count": movement_count,
        "skin_contact_count": skin_contact_count,
        "online_count": online_count,
    }


def aggregate_skin_temperature(rows: list[dict[str, Any]], source_date: str) -> dict[str, Any]:
    temperatures: list[float] = []
    for row in latest_cluster_rows(rows_for_date(rows, "skin_temperature", source_date)):
        payload = parse_payload(row)
        result = payload.get("result") or {}
        for item in result.get("skinTemperatureList") or []:
            temperature = item.get("temperature")
            if temperature is not None:
                temperatures.append(float(temperature))
    return {
        "sample_count": len(temperatures),
        "avg_temperature": mean(temperatures) if temperatures else None,
        "min_temperature": min(temperatures) if temperatures else None,
        "max_temperature": max(temperatures) if temperatures else None,
    }


def aggregate_sleep(rows: list[dict[str, Any]], source_date: str) -> dict[str, Any] | None:
    row = latest_row(latest_cluster_rows(rows_for_date(rows, "sleep", source_date)), "sleep")
    if not row:
        return None
    payload = parse_payload(row)
    result = payload.get("result") or {}
    summary = result.get("summary") or {}
    phase_durations = compute_sleep_stage_minutes(result)
    return {
        "start": result.get("sleepStartTime"),
        "end": result.get("sleepEndTime"),
        "result_date": result.get("sleepResultDate") or source_date,
        "duration_minutes": summary.get("durationMinutes"),
        "goal_delta_minutes": summary.get("goalDeltaMinutes"),
        "cycle_count": summary.get("cycleCount"),
        "phase_minutes": phase_durations,
    }


def aggregate_nightly_recharge(rows: list[dict[str, Any]], source_date: str) -> dict[str, Any] | None:
    row = latest_row(latest_cluster_rows(rows_for_date(rows, "nightly_recharge", source_date)), "nightly_recharge")
    if not row:
        return None
    payload = parse_payload(row)
    summary = payload.get("summary") or {}
    return {
        "baseline_ready": summary.get("baselineReady"),
        "ans_available": summary.get("ansAvailable"),
        "recovery_available": summary.get("recoveryAvailable"),
        "mean_rri": summary.get("meanNightlyRecoveryRRI"),
        "mean_rmssd": summary.get("meanNightlyRecoveryRMSSD"),
        "mean_respiration_interval": summary.get("meanNightlyRecoveryRespirationInterval"),
    }


def aggregate_hr(rows: list[dict[str, Any]], source_date: str) -> dict[str, Any] | None:
    row = latest_row(latest_cluster_rows(rows_for_date(rows, "hr247", source_date)), "hr247")
    if not row:
        return None
    payload = parse_payload(row)
    return payload.get("summary") or None


def aggregate_activity(rows: list[dict[str, Any]], source_date: str) -> dict[str, Any] | None:
    row = latest_row(latest_cluster_rows(rows_for_date(rows, "activity_samples", source_date)), "activity_samples")
    if not row:
        return None
    payload = parse_payload(row)
    return payload.get("summary") or None


def aggregate_daily_summary(rows: list[dict[str, Any]], source_date: str) -> dict[str, Any] | None:
    row = daily_summary_row(rows, source_date)
    if not row:
        return None
    payload = parse_payload(row)
    return payload.get("summary") or None


def format_float(value: float | None, digits: int = 1) -> str:
    if value is None:
        return "n/a"
    return f"{value:.{digits}f}"


def format_minutes(value: int | float | None) -> str:
    if value is None:
        return "n/a"
    total = int(round(value))
    sign = "-" if total < 0 else ""
    total = abs(total)
    hours, minutes = divmod(total, 60)
    if hours and minutes:
        return f"{sign}{hours}h {minutes}m"
    if hours:
        return f"{sign}{hours}h"
    return f"{sign}{minutes}m"


def format_delta_minutes(value: int | float | None) -> str:
    if value is None:
        return "n/a"
    sign = "+" if value > 0 else ""
    return f"{sign}{format_minutes(value)}"


def parse_iso_datetime(value: str | None) -> datetime | None:
    if not value:
        return None
    try:
        return datetime.fromisoformat(value)
    except ValueError:
        return None


def format_clock(value: str | None) -> str:
    dt = parse_iso_datetime(value)
    if not dt:
        return "n/a"
    return dt.strftime("%I:%M%p").lstrip("0").lower()


def format_sleep_window(result_date: str | None, start: str | None, end: str | None) -> str:
    end_dt = parse_iso_datetime(end)
    start_dt = parse_iso_datetime(start)
    anchor = end_dt or start_dt
    if not anchor:
        return "n/a"
    prefix = anchor.strftime("%a %d-%m-%y")
    return f"{prefix} {format_clock(start)} - {format_clock(end)}"


def compute_sleep_stage_minutes(result: dict[str, Any]) -> dict[str, int]:
    phases = result.get("sleepWakePhases") or []
    duration_minutes = result.get("summary", {}).get("durationMinutes")
    total_seconds = int(duration_minutes * 60) if duration_minutes is not None else None
    if not phases or total_seconds is None:
        return {}

    durations = Counter()
    ordered = sorted(
        (
            {
                "seconds": int(item.get("secondsFromSleepStart") or 0),
                "state": item.get("state") or "UNKNOWN",
            }
            for item in phases
        ),
        key=lambda item: item["seconds"],
    )

    for index, phase in enumerate(ordered):
        start_seconds = phase["seconds"]
        next_seconds = ordered[index + 1]["seconds"] if index + 1 < len(ordered) else total_seconds
        span_seconds = max(0, next_seconds - start_seconds)
        durations[phase["state"]] += span_seconds

    return {state: round(seconds / 60) for state, seconds in durations.items()}


def format_sleep_stages(phase_minutes: dict[str, int]) -> str:
    if not phase_minutes:
        return "n/a"
    labels = {
        "NONREM12": "light sleep (N1/N2)",
        "NONREM3": "deep sleep (N3)",
        "REM": "REM",
        "WAKE": "awake",
    }
    preferred = ["NONREM12", "NONREM3", "REM", "WAKE"]
    ordered = [key for key in preferred if key in phase_minutes] + [key for key in phase_minutes if key not in preferred]
    return ", ".join(f"{labels.get(key, key)}={format_minutes(phase_minutes[key])}" for key in ordered)


def format_percent(part: int, whole: int) -> str:
    if whole <= 0:
        return "n/a"
    return f"{(part / whole) * 100:.0f}%"


def print_summary(export_path: Path, rows: list[dict[str, Any]]) -> None:
    latest_sleep = latest_valid_sleep_row(rows)
    if not latest_sleep:
        raise SystemExit("No sleep rows found in export.")

    sleep_payload = parse_payload(latest_sleep)
    sleep_result = sleep_payload.get("result") or {}
    source_date = sleep_result.get("sleepResultDate") or latest_sleep["sourceDate"]
    sleep = aggregate_sleep(rows, source_date)
    recharge = aggregate_nightly_recharge(rows, source_date)
    hr = aggregate_hr(rows, source_date)
    ppi = aggregate_ppi(rows, source_date)
    skin = aggregate_skin_temperature(rows, source_date)
    daily = aggregate_daily_summary(rows, source_date)
    activity = aggregate_activity(rows, source_date)

    print(f"Export: {export_path}")
    print(f"Overnight date: {source_date}")
    print()
    print("Sleep")
    print(
        f"  window: {format_sleep_window(sleep.get('result_date'), sleep['start'], sleep['end'])}"
        if sleep else "  unavailable"
    )
    if sleep:
        print(f"  duration: {format_minutes(sleep['duration_minutes'])}")
        print(f"  vs goal: {format_delta_minutes(sleep['goal_delta_minutes'])}")
        print(f"  cycles: {sleep['cycle_count']}")
        print(f"  stages: {format_sleep_stages(sleep['phase_minutes'])}")

    print()
    print("Nightly recharge")
    if recharge:
        print(f"  baseline ready: {recharge['baseline_ready']}")
        print(f"  ANS available: {recharge['ans_available']}")
        print(f"  recovery available: {recharge['recovery_available']}")
        print(f"  mean RRI: {recharge['mean_rri']}")
        print(f"  mean RMSSD: {recharge['mean_rmssd']}")
        print(f"  mean respiration interval: {recharge['mean_respiration_interval']}")
    else:
        print("  unavailable")

    print()
    print("HR / PPI")
    if hr:
        print(f"  avg HR: {format_float(hr.get('avgHr'), 2)} bpm")
        print(f"  HR range: {hr.get('minHr')} - {hr.get('maxHr')}")
    else:
        print("  HR unavailable")
    print(f"  avg PPI: {format_float(ppi['avg_ppi'], 2)} ms across {ppi['sample_count']} intervals")
    print(f"  avg error estimate: {format_float(ppi['avg_error'], 2)}")
    print(
        "  signal quality: "
        f"online={format_percent(ppi['online_count'], ppi['sample_count'])}, "
        f"skin contact={format_percent(ppi['skin_contact_count'], ppi['sample_count'])}, "
        f"movement detected={format_percent(ppi['movement_count'], ppi['sample_count'])}"
    )

    print()
    print("Skin temperature")
    print(f"  samples: {skin['sample_count']}, avg: {format_float(skin['avg_temperature'], 2)} C")
    print(f"  range: {format_float(skin['min_temperature'], 2)} - {format_float(skin['max_temperature'], 2)} C")

    print()
    print("Activity context")
    if daily:
        print(f"  steps: {daily.get('steps')}, distance: {daily.get('activityDistance')}, activity calories: {daily.get('activityCalories')}")
        print(
            "  class time: "
            f"sleep={format_minutes(daily.get('sleepMinutes'))}, "
            f"sedentary={format_minutes(daily.get('sedentaryMinutes'))}, "
            f"light={format_minutes(daily.get('lightActivityMinutes'))}"
        )
    else:
        print("  daily summary unavailable")
    if activity:
        print(
            f"  recorded steps: {activity.get('totalRecordedSteps')}, "
            f"avg MET: {format_float(activity.get('avgMet'), 3)}"
        )
    else:
        print("  activity samples unavailable")


def main() -> None:
    args = parse_args()
    export_path, rows = load_rows(args)
    print_summary(export_path, rows)


if __name__ == "__main__":
    main()
