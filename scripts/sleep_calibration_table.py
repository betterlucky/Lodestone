#!/usr/bin/env python3

from __future__ import annotations

import argparse
import csv
import json
import sqlite3
from dataclasses import dataclass
from datetime import datetime, time, timedelta
from pathlib import Path
from statistics import mean, median
from zoneinfo import ZoneInfo


LOCAL_TZ = ZoneInfo("Europe/London")
DEFAULT_DB = Path("/tmp/lodestone_db_latest/health-monitor-probe.db")
DEFAULT_LABELS = Path("calibration/sleep2/sleep2_manual_labels.csv")
WAKE_BACKDATE = timedelta(minutes=5)
ONSET_WINDOW_EPOCHS = 4
ONSET_MIN_HR_DROP_BPM = 3.0
ONSET_MAX_HR_STD_BPM = 3.0
ONSET_MAX_MOVEMENT_RATIO = 0.05


@dataclass(frozen=True)
class Sleep2Label:
    source_date: str
    onset: datetime | None
    wake: datetime | None
    asleep_minutes: int | None
    notes: str


@dataclass(frozen=True)
class Window:
    start: datetime | None
    end: datetime | None
    asleep_minutes: float | None = None


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Compare manual Sleep2/H10 labels against Loop sleep windows and "
            "Lodestone's provisional PPI sleep-window heuristic."
        )
    )
    parser.add_argument("--db", type=Path, default=DEFAULT_DB, help="Pulled Lodestone SQLite DB.")
    parser.add_argument(
        "--labels",
        type=Path,
        default=DEFAULT_LABELS,
        help="Local ignored CSV of manually read Sleep2/H10 labels.",
    )
    parser.add_argument("--format", choices=("markdown", "csv"), default="markdown")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if not args.db.exists():
        raise SystemExit(f"DB not found: {args.db}")
    if not args.labels.exists():
        raise SystemExit(f"Labels CSV not found: {args.labels}")

    labels = load_labels(args.labels)
    conn = sqlite3.connect(args.db)
    conn.row_factory = sqlite3.Row
    rows = [build_row(conn, label) for label in labels]
    if args.format == "csv":
        print_csv(rows)
    else:
        print_markdown(rows)


def load_labels(path: Path) -> list[Sleep2Label]:
    result: list[Sleep2Label] = []
    with path.open(newline="") as handle:
        for row in csv.DictReader(handle):
            source_date = row["source_date"].strip()
            result.append(
                Sleep2Label(
                    source_date=source_date,
                    onset=parse_local_time(source_date, row.get("sleep2_onset_time")),
                    wake=parse_local_time(source_date, row.get("sleep2_wake_time")),
                    asleep_minutes=parse_minutes(row.get("sleep2_asleep_minutes")),
                    notes=(row.get("notes") or "").strip(),
                )
            )
    return result


def build_row(conn: sqlite3.Connection, label: Sleep2Label) -> dict[str, object]:
    loop = load_loop_window(conn, label.source_date)
    heuristic = estimate_lodestone_window(conn, label.source_date)
    return {
        "date": label.source_date,
        "sleep2_onset": fmt_time(label.onset),
        "loop_onset": fmt_time(loop.start),
        "heuristic_onset": fmt_time(heuristic.start),
        "loop_onset_delta_min": delta_minutes(loop.start, label.onset),
        "heuristic_onset_delta_min": delta_minutes(heuristic.start, label.onset),
        "sleep2_wake": fmt_time(label.wake),
        "loop_wake": fmt_time(loop.end),
        "heuristic_wake": fmt_time(heuristic.end),
        "loop_wake_delta_min": delta_minutes(loop.end, label.wake),
        "heuristic_wake_delta_min": delta_minutes(heuristic.end, label.wake),
        "sleep2_asleep_min": label.asleep_minutes,
        "loop_asleep_min": round(loop.asleep_minutes, 1) if loop.asleep_minutes is not None else None,
        "heuristic_window_min": window_minutes(heuristic),
        "notes": label.notes,
    }


def load_loop_window(conn: sqlite3.Connection, source_date: str) -> Window:
    row = conn.execute(
        """
        select rawPayloadJson
        from sleep_night_raw
        where sourceDate = ?
        order by syncTimestampEpochMs desc
        limit 1
        """,
        (source_date,),
    ).fetchone()
    if row is None:
        return Window(None, None)
    result = json.loads(row["rawPayloadJson"]).get("result") or {}
    start = parse_polar_datetime(result.get("sleepStartTime"))
    end = parse_polar_datetime(result.get("sleepEndTime"))
    return Window(start, end, loop_asleep_minutes(result, start, end))


def estimate_lodestone_window(conn: sqlite3.Connection, source_date: str) -> Window:
    source_day = datetime.fromisoformat(source_date).replace(tzinfo=LOCAL_TZ)
    window_start = int((source_day - timedelta(hours=12)).timestamp() * 1000)
    window_end = int((source_day + timedelta(hours=18)).timestamp() * 1000)
    markers = conn.execute(
        """
        select markerEpochMs, markerSource, notes
        from wake_marker
        where markerEpochMs >= ? and markerEpochMs <= ?
        order by markerEpochMs
        """,
        (window_start, window_end),
    ).fetchall()
    markers = [row for row in markers if row["notes"] != "manual awake command"]
    candidate_pairs = []
    for bed in [row for row in markers if row["markerSource"] == "manual_going_to_bed"]:
        wake = next(
            (
                row
                for row in markers
                if row["markerSource"] == "manual_im_awake" and row["markerEpochMs"] > bed["markerEpochMs"]
            ),
            None,
        )
        if wake is not None:
            candidate_pairs.append((bed, wake))
    if not candidate_pairs:
        return Window(None, None)
    bed, wake = candidate_pairs[-1]
    end_ms = max(wake["markerEpochMs"] - int(WAKE_BACKDATE.total_seconds() * 1000), bed["markerEpochMs"])
    start_ms = estimate_sleep_onset_ms(conn, bed["markerEpochMs"], end_ms) or bed["markerEpochMs"]
    return Window(from_epoch_ms(start_ms), from_epoch_ms(end_ms))


def estimate_sleep_onset_ms(conn: sqlite3.Connection, bed_ms: int, end_ms: int) -> int | None:
    epochs = conn.execute(
        """
        select *
        from ppi247_epoch
        where epochStartEpochMs >= ? and epochStartEpochMs <= ? and meanHrBpm is not null
        order by epochStartEpochMs
        """,
        (bed_ms, end_ms),
    ).fetchall()
    if len(epochs) < ONSET_WINDOW_EPOCHS:
        return None
    first_hour_hr = [
        row["meanHrBpm"]
        for row in epochs
        if row["epochStartEpochMs"] < bed_ms + 60 * 60_000
        and not row["epochQuality"].startswith("poor")
    ]
    if len(first_hour_hr) < 3:
        return None
    baseline_hr = median(first_hour_hr)
    for index in range(0, len(epochs) - ONSET_WINDOW_EPOCHS + 1):
        window = epochs[index : index + ONSET_WINDOW_EPOCHS]
        if any(row["epochQuality"].startswith("poor") or row["meanHrBpm"] is None for row in window):
            continue
        hrs = [row["meanHrBpm"] for row in window]
        sample_count = max(sum(row["sampleCount"] for row in window), 1)
        movement_ratio = sum(row["movementDetectedCount"] for row in window) / sample_count
        if (
            median(hrs) <= baseline_hr - ONSET_MIN_HR_DROP_BPM
            and population_stddev(hrs) <= ONSET_MAX_HR_STD_BPM
            and movement_ratio <= ONSET_MAX_MOVEMENT_RATIO
        ):
            return window[0]["epochStartEpochMs"]
    return None


def loop_asleep_minutes(result: dict[str, object], start: datetime | None, end: datetime | None) -> float | None:
    phases = result.get("sleepWakePhases")
    if not isinstance(phases, list) or start is None or end is None:
        return None
    total_seconds = max(int((end - start).total_seconds()), 0)
    asleep_seconds = 0
    for index, phase in enumerate(phases):
        if not isinstance(phase, dict):
            continue
        seconds_from_start = int(phase.get("secondsFromSleepStart") or 0)
        next_seconds = (
            int(phases[index + 1].get("secondsFromSleepStart") or 0)
            if index + 1 < len(phases) and isinstance(phases[index + 1], dict)
            else total_seconds
        )
        if phase.get("state") != "WAKE":
            asleep_seconds += max(next_seconds - seconds_from_start, 0)
    return asleep_seconds / 60


def parse_local_time(source_date: str, raw_value: str | None) -> datetime | None:
    if not raw_value:
        return None
    value = raw_value.strip()
    if not value:
        return None
    if "T" in value or "-" in value:
        return datetime.fromisoformat(value).astimezone(LOCAL_TZ)
    parsed_time = time.fromisoformat(value)
    source_day = datetime.fromisoformat(source_date).date()
    return datetime.combine(source_day, parsed_time, LOCAL_TZ)


def parse_polar_datetime(raw_value: str | None) -> datetime | None:
    if not raw_value:
        return None
    return datetime.fromisoformat(raw_value).astimezone(LOCAL_TZ)


def parse_minutes(raw_value: str | None) -> int | None:
    if raw_value is None or raw_value.strip() == "":
        return None
    return int(raw_value)


def from_epoch_ms(value: int) -> datetime:
    return datetime.fromtimestamp(value / 1000, LOCAL_TZ)


def delta_minutes(value: datetime | None, baseline: datetime | None) -> float | None:
    if value is None or baseline is None:
        return None
    return round((value - baseline).total_seconds() / 60, 1)


def window_minutes(window: Window) -> float | None:
    if window.start is None or window.end is None:
        return None
    return round((window.end - window.start).total_seconds() / 60, 1)


def population_stddev(values: list[float]) -> float:
    if len(values) < 2:
        return 0.0
    average = sum(values) / len(values)
    return (sum((value - average) ** 2 for value in values) / len(values)) ** 0.5


def fmt_time(value: datetime | None) -> str:
    return value.strftime("%H:%M") if value else ""


def fmt_value(value: object) -> str:
    return "" if value is None else str(value)


def print_csv(rows: list[dict[str, object]]) -> None:
    if not rows:
        return
    writer = csv.DictWriter(__import__("sys").stdout, fieldnames=list(rows[0].keys()))
    writer.writeheader()
    writer.writerows(rows)


def print_markdown(rows: list[dict[str, object]]) -> None:
    headers = [
        "date",
        "sleep2_onset",
        "loop_onset",
        "heuristic_onset",
        "loop_onset_delta_min",
        "heuristic_onset_delta_min",
        "sleep2_wake",
        "loop_wake",
        "heuristic_wake",
        "loop_wake_delta_min",
        "heuristic_wake_delta_min",
        "sleep2_asleep_min",
        "loop_asleep_min",
        "heuristic_window_min",
    ]
    print("| " + " | ".join(headers) + " |")
    print("| " + " | ".join("---" for _ in headers) + " |")
    for row in rows:
        print("| " + " | ".join(fmt_value(row.get(header)) for header in headers) + " |")
    print()
    print("Delta summary")
    for key, label in [
        ("loop_onset_delta_min", "Loop onset"),
        ("heuristic_onset_delta_min", "Lodestone onset"),
        ("loop_wake_delta_min", "Loop wake"),
        ("heuristic_wake_delta_min", "Lodestone wake"),
    ]:
        values = [abs(float(row[key])) for row in rows if row.get(key) not in (None, "")]
        if values:
            print(
                f"- {label}: n={len(values)}, "
                f"MAE={mean(values):.1f}m, median={median(values):.1f}m, max={max(values):.1f}m"
            )
        else:
            print(f"- {label}: n=0")


if __name__ == "__main__":
    main()
