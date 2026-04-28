#!/usr/bin/env python3

from __future__ import annotations

import argparse
from statistics import mean
from typing import Any

import garmin_adapter as ga


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Summarize Garmin overnight trends from garmin.db for recovery-oriented analysis."
    )
    parser.add_argument(
        "--garmin-db",
        required=True,
        help="Path to garmin.db produced by garmin-givemydata.",
    )
    parser.add_argument(
        "--target-date",
        help="Optional source date to anchor the report. Defaults to the newest Garmin night.",
    )
    parser.add_argument(
        "--recent-nights",
        type=int,
        default=10,
        help="Number of most recent nights to print in the nightly table.",
    )
    return parser.parse_args()


def avg(rows: list[dict[str, Any]], key: str) -> float | None:
    values = [float(row[key]) for row in rows if row.get(key) is not None]
    return round(mean(values), 3) if values else None


def corr(rows: list[dict[str, Any]], key1: str, key2: str) -> float | None:
    pairs = [
        (float(row[key1]), float(row[key2]))
        for row in rows
        if row.get(key1) is not None and row.get(key2) is not None
    ]
    if len(pairs) < 3:
        return None
    xs = [x for x, _ in pairs]
    ys = [y for _, y in pairs]
    mean_x = sum(xs) / len(xs)
    mean_y = sum(ys) / len(ys)
    numerator = sum((x - mean_x) * (y - mean_y) for x, y in pairs)
    denominator = (
        sum((x - mean_x) ** 2 for x in xs) * sum((y - mean_y) ** 2 for y in ys)
    ) ** 0.5
    if not denominator:
        return None
    return round(numerator / denominator, 3)


def fmt(value: Any, digits: int = 1) -> str:
    if value is None:
        return "-"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float):
        return f"{value:.{digits}f}"
    return str(value)


def classify_direction(first: float | None, last: float | None, *, lower_is_better: bool = False) -> str:
    if first is None or last is None:
        return "insufficient data"
    delta = last - first
    if lower_is_better:
        delta = -delta
    if delta >= 4:
        return "meaningful improvement"
    if delta >= 1.5:
        return "modest improvement"
    if delta <= -4:
        return "meaningful worsening"
    if delta <= -1.5:
        return "modest worsening"
    return "broadly flat"


def main() -> None:
    args = parse_args()
    lane = ga.load_garmin_comparison_lane(args.garmin_db, args.target_date)
    if not lane.get("available"):
        raise SystemExit(f"Garmin lane unavailable: {lane.get('reason', 'unknown')}")

    nights = lane.get("historical_nights") or []
    if not nights:
        raise SystemExit("No Garmin nights available.")

    chronological = list(reversed(nights))
    first_window = chronological[: min(7, len(chronological))]
    last_window = nights[: min(7, len(nights))]
    latest = nights[0]
    baseline = lane.get("garmin_baseline_summary") or {}
    hrv_baseline = ((baseline.get("current_vs_recent") or {}).get("hrv_mean") or {}).get("windows") or {}
    delta_baseline = ((baseline.get("current_vs_recent") or {}).get("hrv_delta") or {}).get("windows") or {}

    print("Garmin trend analysis")
    print(f"  date range: {chronological[0]['source_date']} -> {latest['source_date']}")
    print(f"  nights available: {len(nights)}")
    print("  note: treat HRV/HR/respiration as primary; Body Battery and stress are secondary black-box context.")
    print()

    print("Recovery direction")
    print(
        f"  HRV mean: first7={fmt(avg(first_window, 'hrv_mean'), 1)} "
        f"last7={fmt(avg(last_window, 'hrv_mean'), 1)} "
        f"({classify_direction(avg(first_window, 'hrv_mean'), avg(last_window, 'hrv_mean'))})"
    )
    print(
        f"  HRV overnight delta: first7={fmt(avg(first_window, 'hrv_delta'), 1)} "
        f"last7={fmt(avg(last_window, 'hrv_delta'), 1)} "
        f"({classify_direction(avg(first_window, 'hrv_delta'), avg(last_window, 'hrv_delta'))})"
    )
    print(
        f"  RHR: first7={fmt(avg(first_window, 'resting_hr'), 1)} "
        f"last7={fmt(avg(last_window, 'resting_hr'), 1)} "
        f"({classify_direction(avg(first_window, 'resting_hr'), avg(last_window, 'resting_hr'), lower_is_better=True)})"
    )
    print(
        f"  Avg sleep HR: first7={fmt(avg(first_window, 'avg_sleep_hr'), 1)} "
        f"last7={fmt(avg(last_window, 'avg_sleep_hr'), 1)} "
        f"({classify_direction(avg(first_window, 'avg_sleep_hr'), avg(last_window, 'avg_sleep_hr'), lower_is_better=True)})"
    )
    print(
        f"  Respiration: first7={fmt(avg(first_window, 'avg_respiration'), 1)} "
        f"last7={fmt(avg(last_window, 'avg_respiration'), 1)} "
        f"({classify_direction(avg(first_window, 'avg_respiration'), avg(last_window, 'avg_respiration'), lower_is_better=True)})"
    )
    print(
        f"  Deep sleep: first7={fmt(avg(first_window, 'deep_sleep_minutes'), 1)} "
        f"last7={fmt(avg(last_window, 'deep_sleep_minutes'), 1)} "
        f"({classify_direction(avg(first_window, 'deep_sleep_minutes'), avg(last_window, 'deep_sleep_minutes'))})"
    )
    print()

    print("Latest night vs recent baseline")
    print(
        f"  source date: {latest['source_date']} "
        f"HRV={fmt(latest.get('hrv_mean'), 1)} "
        f"RHR={fmt(latest.get('resting_hr'), 0)} "
        f"sleepHR={fmt(latest.get('avg_sleep_hr'), 1)} "
        f"resp={fmt(latest.get('avg_respiration'), 1)} "
        f"BB_wake={fmt(latest.get('body_battery_at_wake'), 0)}"
    )
    if hrv_baseline.get("7d"):
        print(
            f"  HRV vs 7d: delta={fmt(hrv_baseline['7d'].get('delta_from_mean'), 1)} "
            f"percentile={fmt(hrv_baseline['7d'].get('percentile_vs_prior'), 3)}"
        )
    if delta_baseline.get("7d"):
        print(
            f"  HRV shape vs 7d: delta={fmt(delta_baseline['7d'].get('delta_from_mean'), 1)} "
            f"percentile={fmt(delta_baseline['7d'].get('percentile_vs_prior'), 3)}"
        )
    print(
        f"  sleep context: total={fmt(latest.get('total_sleep_minutes'), 1)} "
        f"deep={fmt(latest.get('deep_sleep_minutes'), 1)} "
        f"awake={fmt(latest.get('awake_minutes'), 1)} "
        f"SpO2={fmt(latest.get('average_spo2'), 1)}"
    )
    print()

    print("Relationship scan")
    for label, key in [
        ("RHR", "resting_hr"),
        ("Avg sleep HR", "avg_sleep_hr"),
        ("Respiration", "avg_respiration"),
        ("Deep sleep", "deep_sleep_minutes"),
        ("Awake minutes", "awake_minutes"),
        ("Body Battery at wake", "body_battery_at_wake"),
        ("Stress", "avg_stress"),
        ("Average SpO2", "average_spo2"),
    ]:
        print(f"  hrv_mean vs {label}: {fmt(corr(nights, 'hrv_mean', key), 3)}")
    print()

    print("Recent nights")
    header = (
        "  date         HRV   dHRV  RHR  sHR  Resp  BBw  Stress  Sleep  Deep  Awake  SpO2  Status"
    )
    print(header)
    for row in nights[: args.recent_nights]:
        print(
            "  "
            f"{row['source_date']}  "
            f"{fmt(row.get('hrv_mean'), 1):>4}  "
            f"{fmt(row.get('hrv_delta'), 1):>5}  "
            f"{fmt(row.get('resting_hr'), 0):>3}  "
            f"{fmt(row.get('avg_sleep_hr'), 1):>4}  "
            f"{fmt(row.get('avg_respiration'), 1):>4}  "
            f"{fmt(row.get('body_battery_at_wake'), 0):>3}  "
            f"{fmt(row.get('avg_stress'), 0):>6}  "
            f"{fmt(row.get('total_sleep_minutes'), 0):>5}  "
            f"{fmt(row.get('deep_sleep_minutes'), 0):>4}  "
            f"{fmt(row.get('awake_minutes'), 0):>5}  "
            f"{fmt(row.get('average_spo2'), 0):>4}  "
            f"{fmt(row.get('garmin_status'), 0)}"
        )


if __name__ == "__main__":
    main()
