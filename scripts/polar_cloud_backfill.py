#!/usr/bin/env python3
"""Import missing Polar AccessLink cloud data into a Lodestone SQLite database.

Research/recovery tool only. Does not replace normal Loop BLE sync.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import math
import re
import sqlite3
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

from polar_accesslink_tokens import get_access_token, load_local_env  # noqa: E402

BASE_URL = "https://www.polaraccesslink.com/v4/data"
PARSER_VERSION = 3
PARSE_STATUS = "PARSED"
REQUESTED_RANGE_PREFIX = "cloud_backfill"
CHUNK_SIZE = 126
EPOCH_MINUTES = 5
MIN_USABLE_SAMPLES_PER_EPOCH = 120
MIN_PPI_MS = 300
MAX_PPI_MS = 2000
MAX_ERROR_ESTIMATE_MS = 50

SLEEP_STATE_MAP = {
    "SLEEP_STATE_WAKE": "WAKE",
    "SLEEP_STATE_NON_REM1": "NONREM1",
    "SLEEP_STATE_NON_REM2": "NONREM12",
    "SLEEP_STATE_NON_REM3": "NONREM3",
    "SLEEP_STATE_REM": "REM",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Backfill Lodestone DB from Polar AccessLink cloud.")
    parser.add_argument("--db", required=True, help="Path to health-monitor-probe.db")
    parser.add_argument("--from-date", required=True, help="Start date YYYY-MM-DD (inclusive).")
    parser.add_argument("--to-date", required=True, help="End date YYYY-MM-DD (exclusive).")
    parser.add_argument("--device-id", help="Loop device id. Defaults to most common id in DB.")
    parser.add_argument("--env-file", default=".env.polar", help="Polar OAuth env file.")
    parser.add_argument("--timezone", default="Europe/London", help="Timezone for PPI epoch dates.")
    parser.add_argument(
        "--domains",
        default="ppi,sleep,nightly,hr,activity,skin",
        help="Comma list: ppi,sleep,nightly,hr,activity,skin (hr derives from PPI epochs when cloud HR is empty).",
    )
    parser.add_argument("--dry-run", action="store_true", help="Report gaps only; do not write.")
    return parser.parse_args()


def fetch_json(endpoint: str, token: str, from_date: str, to_date: str, features: tuple[str, ...] = ()) -> Any:
    params: list[tuple[str, str]] = [("from", from_date), ("to", to_date)]
    params.extend(("features", feature) for feature in features)
    query = urllib.parse.urlencode(params)
    request = urllib.request.Request(
        f"{BASE_URL}/{endpoint}?{query}",
        headers={"Accept": "application/json", "Authorization": f"Bearer {token}"},
    )
    with urllib.request.urlopen(request, timeout=120) as response:
        raw = response.read().decode("utf-8")
    return json.loads(raw) if raw else None


def date_range(from_date: str, to_date: str) -> list[str]:
    start = dt.date.fromisoformat(from_date)
    end = dt.date.fromisoformat(to_date)
    days: list[str] = []
    current = start
    while current < end:
        days.append(current.isoformat())
        current += dt.timedelta(days=1)
    return days


def parse_local_start_time(source_date: str, start_time: str, zone: ZoneInfo) -> dt.datetime:
    for fmt in ("%H:%M:%S.%f", "%H:%M:%S"):
        try:
            parsed = dt.datetime.strptime(f"{source_date} {start_time}", f"%Y-%m-%d {fmt}")
            return parsed.replace(tzinfo=zone)
        except ValueError:
            continue
    raise ValueError(f"Unsupported startTime {start_time!r} for {source_date}")


def ms_to_start_time(offset_ms: int) -> str:
    total_ms = offset_ms
    hours = total_ms // 3_600_000
    total_ms %= 3_600_000
    minutes = total_ms // 60_000
    total_ms %= 60_000
    seconds = total_ms // 1000
    millis = total_ms % 1000
    return f"{hours:02d}:{minutes:02d}:{seconds:02d}.{millis:03d}"


def parse_offset_seconds(value: str) -> int:
    match = re.fullmatch(r"(\d+)s", value or "")
    if not match:
        return 0
    return int(match.group(1))


def status_entry(sample: dict[str, Any]) -> dict[str, str]:
    return {
        "skinContact": "SKIN_CONTACT_DETECTED" if sample.get("skinContact") else "SKIN_CONTACT_NOT_DETECTED",
        "movement": "MOVING_DETECTED" if sample.get("movement") else "MOVING_NOT_DETECTED",
        "intervalStatus": "INTERVAL_IS_OFFLINE" if sample.get("offline") else "INTERVAL_IS_ONLINE",
    }


def build_ppi_payload(source_date: str, start_time: str, trigger_type: str, chunk: list[dict[str, Any]]) -> dict[str, Any]:
    ppi_values = [int(sample["ppInterval"]) for sample in chunk]
    error_values = [int(sample.get("errorEstimateMillis") or 0) for sample in chunk]
    statuses = [status_entry(sample) for sample in chunk]
    movement_count = sum(1 for sample in chunk if sample.get("movement"))
    skin_count = sum(1 for sample in chunk if sample.get("skinContact"))
    online_count = sum(1 for sample in chunk if not sample.get("offline"))
    return {
        "date": source_date,
        "samples": {
            "startTime": start_time,
            "triggerType": trigger_type,
            "ppiValueList": ppi_values,
            "ppiErrorEstimateList": error_values,
            "statusList": statuses,
        },
        "summary": {
            "sampleCount": len(ppi_values),
            "avgPpi": sum(ppi_values) / len(ppi_values) if ppi_values else None,
            "avgErrorEstimate": sum(error_values) / len(error_values) if error_values else None,
            "movementDetectedCount": movement_count,
            "skinContactDetectedCount": skin_count,
            "onlineIntervalCount": online_count,
        },
    }


def cloud_ppi_records_for_day(source_date: str, cloud_samples: list[dict[str, Any]], trigger_type: str) -> list[dict[str, Any]]:
    if not cloud_samples:
        return []
    records: list[dict[str, Any]] = []
    index = 0
    while index < len(cloud_samples):
        chunk = cloud_samples[index : index + CHUNK_SIZE]
        start_time = ms_to_start_time(int(chunk[0]["offsetMillis"]))
        key_summary = f"start={start_time}, samples={len(chunk)}, trigger={trigger_type}"
        records.append(
            {
                "sourceDate": source_date,
                "keySummary": key_summary,
                "rawPayloadJson": json.dumps(build_ppi_payload(source_date, start_time, trigger_type, chunk), separators=(",", ":")),
            }
        )
        index += len(chunk)
    return records


def fetch_cloud_ppi_by_day(token: str, from_date: str, to_date: str) -> dict[str, list[dict[str, Any]]]:
    by_day: dict[str, list[dict[str, Any]]] = {}
    for day in date_range(from_date, to_date):
        next_day = (dt.date.fromisoformat(day) + dt.timedelta(days=1)).isoformat()
        payload = fetch_json("ppi-samples", token, day, next_day, features=("samples",))
        daily = (payload or {}).get("dailyPpiSamples") or []
        if not daily:
            by_day[day] = []
            continue
        day_block = daily[0]
        per_device = day_block.get("ppiSamplesPerDevice") or []
        samples = per_device[0].get("ppiSamples") if per_device else []
        by_day[day] = samples or []
    return by_day


def existing_ppi_keys(conn: sqlite3.Connection, device_id: str) -> set[str]:
    rows = conn.execute(
        "SELECT sourceDate || '|' || keySummary FROM ppi247_day_raw WHERE deviceId = ?",
        (device_id,),
    ).fetchall()
    return {row[0] for row in rows}


def insert_ppi_records(
    conn: sqlite3.Connection,
    device_id: str,
    records: list[dict[str, Any]],
    existing_keys: set[str],
    dry_run: bool,
) -> int:
    inserted = 0
    now_ms = int(time.time() * 1000)
    for record in records:
        dedupe_key = f"{record['sourceDate']}|{record['keySummary']}"
        if dedupe_key in existing_keys:
            continue
        inserted += 1
        if dry_run:
            continue
        conn.execute(
            """
            INSERT INTO ppi247_day_raw (
                deviceId, sourceDate, requestedRange, syncTimestampEpochMs,
                keySummary, rawPayloadJson, parserVersion, parseStatus
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                device_id,
                record["sourceDate"],
                f"{REQUESTED_RANGE_PREFIX}:ppi",
                now_ms,
                record["keySummary"],
                record["rawPayloadJson"],
                PARSER_VERSION,
                PARSE_STATUS,
            ),
        )
        existing_keys.add(dedupe_key)
    return inserted


def samples_from_raw(record_row: sqlite3.Row, zone: ZoneInfo) -> list[dict[str, Any]]:
    payload = json.loads(record_row["rawPayloadJson"])
    samples = payload.get("samples") or {}
    source_date = record_row["sourceDate"]
    start_time = samples.get("startTime")
    if not start_time:
        return []
    start_local = parse_local_start_time(source_date, start_time, zone)
    start_epoch_ms = int(start_local.timestamp() * 1000)
    trigger_type = samples.get("triggerType") or "unknown"
    ppi_values = samples.get("ppiValueList") or []
    error_values = samples.get("ppiErrorEstimateList") or []
    status_values = samples.get("statusList") or []
    timestamp_epoch_ms = start_epoch_ms
    parsed: list[dict[str, Any]] = []
    for index, ppi_ms in enumerate(ppi_values):
        timestamp_epoch_ms += int(ppi_ms)
        status = status_values[index] if index < len(status_values) else {}
        parsed.append(
            {
                "timestampEpochMs": timestamp_epoch_ms,
                "deviceId": record_row["deviceId"],
                "ppiMs": int(ppi_ms),
                "errorEstimateMs": int(error_values[index]) if index < len(error_values) else 0,
                "skinContactDetected": status.get("skinContact") != "SKIN_CONTACT_NOT_DETECTED",
                "movementDetected": status.get("movement") == "MOVING_DETECTED",
                "intervalOnline": status.get("intervalStatus") != "INTERVAL_IS_OFFLINE",
                "triggerType": trigger_type,
            }
        )
    return parsed


def is_usable(sample: dict[str, Any]) -> bool:
    return (
        MIN_PPI_MS <= sample["ppiMs"] <= MAX_PPI_MS
        and sample["skinContactDetected"]
        and sample["intervalOnline"]
        and sample["errorEstimateMs"] <= MAX_ERROR_ESTIMATE_MS
    )


def percentile(values: list[float], quantile: float) -> float | None:
    if not values:
        return None
    sorted_values = sorted(values)
    if len(sorted_values) == 1:
        return sorted_values[0]
    position = max(0.0, min(1.0, quantile)) * (len(sorted_values) - 1)
    lower = int(position)
    upper = min(lower + 1, len(sorted_values) - 1)
    fraction = position - lower
    return sorted_values[lower] + ((sorted_values[upper] - sorted_values[lower]) * fraction)


def rmssd(values: list[float]) -> float | None:
    if len(values) < 2:
        return None
    squared = [(current - previous) ** 2 for previous, current in zip(values, values[1:])]
    return math.sqrt(sum(squared) / len(squared))


def rebuild_ppi_epochs(conn: sqlite3.Connection, source_dates: list[str], zone: ZoneInfo, dry_run: bool) -> int:
    normalized = sorted({day for day in source_dates if day})
    if not normalized:
        return 0
    placeholders = ",".join("?" for _ in normalized)
    rows = conn.execute(
        f"""
        SELECT deviceId, sourceDate, keySummary, rawPayloadJson
        FROM ppi247_day_raw
        WHERE sourceDate IN ({placeholders})
        ORDER BY sourceDate ASC, keySummary ASC
        """,
        normalized,
    ).fetchall()
    epochs: list[tuple[Any, ...]] = []
    epoch_ms = EPOCH_MINUTES * 60_000
    updated_at = int(time.time() * 1000)
    samples_by_date: dict[str, list[dict[str, Any]]] = {}
    for row in rows:
        samples_by_date.setdefault(row["sourceDate"], []).extend(samples_from_raw(row, zone))
    for source_date, samples in sorted(samples_by_date.items()):
        samples.sort(key=lambda item: item["timestampEpochMs"])
        grouped: dict[int, list[dict[str, Any]]] = {}
        for sample in samples:
            window = (sample["timestampEpochMs"] // epoch_ms) * epoch_ms
            grouped.setdefault(window, []).append(sample)
        for epoch_start, epoch_samples in sorted(grouped.items()):
            usable = [sample for sample in epoch_samples if is_usable(sample)]
            ppis = [float(sample["ppiMs"]) for sample in usable]
            hrs = [60_000.0 / ppi for ppi in ppis]
            errors = [float(sample["errorEstimateMs"]) for sample in epoch_samples]
            error_p90 = percentile(errors, 0.90)
            hr_range = (max(hrs) - min(hrs)) if hrs else None
            if len(usable) < MIN_USABLE_SAMPLES_PER_EPOCH:
                quality = "poor_sparse"
            elif len(usable) / len(epoch_samples) < 0.75:
                quality = "poor_contact_or_error"
            elif (error_p90 or 0.0) > 100.0 or (hr_range or 0.0) > 50.0:
                quality = "review"
            elif (error_p90 or float("inf")) <= 25.0:
                quality = "good"
            else:
                quality = "usable"
            local_date = dt.datetime.fromtimestamp(epoch_start / 1000, tz=zone).date().isoformat()
            epochs.append(
                (
                    epoch_samples[0]["deviceId"],
                    local_date,
                    epoch_start,
                    epoch_start + epoch_ms,
                    len(epoch_samples),
                    len(usable),
                    sum(1 for sample in epoch_samples if not sample["skinContactDetected"]),
                    sum(1 for sample in epoch_samples if sample["movementDetected"]),
                    sum(1 for sample in epoch_samples if not sample["intervalOnline"]),
                    sum(1 for sample in epoch_samples if sample["errorEstimateMs"] > MAX_ERROR_ESTIMATE_MS),
                    sum(1 for sample in epoch_samples if sample["ppiMs"] < MIN_PPI_MS),
                    sum(1 for sample in epoch_samples if sample["ppiMs"] > MAX_PPI_MS),
                    (sum(ppis) / len(ppis)) if ppis else None,
                    percentile(ppis, 0.50),
                    percentile(ppis, 0.10),
                    percentile(ppis, 0.90),
                    rmssd(ppis),
                    (sum(hrs) / len(hrs)) if hrs else None,
                    int(min(hrs)) if hrs else None,
                    int(max(hrs)) if hrs else None,
                    percentile(errors, 0.50),
                    error_p90,
                    quality,
                    ",".join(sorted({sample["triggerType"] for sample in epoch_samples})),
                    updated_at,
                )
            )
    if dry_run:
        return len(epochs)
    conn.execute(f"DELETE FROM ppi247_epoch WHERE sourceDate IN ({placeholders})", normalized)
    conn.executemany(
        """
        INSERT INTO ppi247_epoch (
            deviceId, sourceDate, epochStartEpochMs, epochEndEpochMs,
            sampleCount, usableSampleCount, skinContactFalseCount, movementDetectedCount,
            offlineIntervalCount, highErrorCount, ppiLowCount, ppiHighCount,
            meanPpiMs, medianPpiMs, ppiP10Ms, ppiP90Ms, rmssdMs,
            meanHrBpm, minHrBpm, maxHrBpm, medianErrorEstimateMs, errorEstimateP90Ms,
            epochQuality, triggerTypesCsv, updatedAtEpochMs
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        epochs,
    )
    return len(epochs)


def build_sleep_payload(night: dict[str, Any], device_id: str) -> dict[str, Any] | None:
    sleep_date = night.get("sleepDate")
    result = night.get("sleepResult") or {}
    hypnogram = result.get("hypnogram") or {}
    sleep_start = hypnogram.get("sleepStart")
    sleep_end = hypnogram.get("sleepEnd")
    if not sleep_date or not sleep_start or not sleep_end:
        return None
    phases = []
    for change in hypnogram.get("sleepStateChanges") or []:
        phases.append(
            {
                "secondsFromSleepStart": parse_offset_seconds(change.get("offsetFromStart")),
                "state": SLEEP_STATE_MAP.get(change.get("newState"), change.get("newState")),
            }
        )
    cycles = []
    for cycle in result.get("sleepCycles") or []:
        cycles.append(
            {
                "secondsFromSleepStart": parse_offset_seconds(cycle.get("offsetFromStart")),
                "sleepDepthStart": cycle.get("sleepDepthStart"),
            }
        )
    start_dt = dt.datetime.fromisoformat(sleep_start)
    end_dt = dt.datetime.fromisoformat(sleep_end)
    duration_minutes = int((end_dt - start_dt).total_seconds() // 60)
    sleep_goal = hypnogram.get("sleepGoal")
    if isinstance(sleep_goal, str) and sleep_goal.endswith("s") and sleep_goal[:-1].isdigit():
        sleep_goal_minutes = int(int(sleep_goal[:-1]) // 60)
    elif sleep_goal not in (None, ""):
        sleep_goal_minutes = int(sleep_goal)
    else:
        sleep_goal_minutes = None
    phase_counts: dict[str, int] = {}
    for phase in phases:
        state = str(phase.get("state"))
        phase_counts[state] = phase_counts.get(state, 0) + 1
    return {
        "date": sleep_date,
        "result": {
            "sleepStartTime": sleep_start,
            "sleepEndTime": sleep_end,
            "lastModified": hypnogram.get("lastModified"),
            "sleepGoalMinutes": sleep_goal_minutes,
            "sleepStartOffsetSeconds": hypnogram.get("sleepStartOffsetSeconds") or 0,
            "sleepEndOffsetSeconds": hypnogram.get("sleepEndOffsetSeconds") or 0,
            "userSleepRating": None,
            "deviceId": device_id,
            "batteryRanOut": hypnogram.get("batteryRanOut") or False,
            "sleepResultDate": sleep_date,
            "originalSleepRange": None,
            "sleepWakePhases": phases,
            "sleepCycles": cycles,
            "snoozeTime": None,
            "alarmTime": None,
            "sleepSkinTemperatureResult": None,
            "summary": {
                "sleepResultDate": sleep_date,
                "durationMinutes": duration_minutes,
                "goalDeltaMinutes": (duration_minutes - sleep_goal_minutes) if sleep_goal_minutes is not None else None,
                "phaseCounts": phase_counts,
                "cycleCount": len(cycles),
                "batteryRanOut": hypnogram.get("batteryRanOut") or False,
            },
        },
    }


def sleep_is_resolved(payload: dict[str, Any]) -> bool:
    result = payload.get("result") or {}
    summary = result.get("summary") or {}
    return bool(summary.get("durationMinutes") and result.get("sleepStartTime") and result.get("sleepEndTime"))


def backfill_sleep(
    conn: sqlite3.Connection,
    token: str,
    device_id: str,
    from_date: str,
    to_date: str,
    dry_run: bool,
) -> dict[str, int]:
    stats = {"inserted": 0, "skipped_stub_only": 0, "removed_stubs": 0}
    for day in date_range(from_date, to_date):
        next_day = (dt.date.fromisoformat(day) + dt.timedelta(days=1)).isoformat()
        payload = fetch_json("sleeps", token, day, next_day, features=("sleep-result",))
        nights = (payload or {}).get("nightSleeps") or []
        if not nights:
            existing = conn.execute(
                "SELECT id, keySummary, rawPayloadJson FROM sleep_night_raw WHERE sourceDate = ?",
                (day,),
            ).fetchall()
            unresolved = [row for row in existing if "wakePhases=0" in row["keySummary"]]
            if unresolved and not dry_run:
                conn.execute("DELETE FROM sleep_night_raw WHERE sourceDate = ? AND keySummary LIKE '%wakePhases=0%'", (day,))
                stats["removed_stubs"] += len(unresolved)
            continue
        for night in nights:
            sleep_payload = build_sleep_payload(night, device_id)
            if not sleep_payload or not sleep_is_resolved(sleep_payload):
                stats["skipped_stub_only"] += 1
                continue
            sleep_date = sleep_payload["date"]
            wake_phases = len(sleep_payload["result"].get("sleepWakePhases") or [])
            key_summary = f"date={sleep_date}, wakePhases={wake_phases}"
            existing = conn.execute(
                "SELECT rawPayloadJson FROM sleep_night_raw WHERE deviceId = ? AND sourceDate = ?",
                (device_id, sleep_date),
            ).fetchall()
            if any(sleep_is_resolved(json.loads(row["rawPayloadJson"])) for row in existing):
                continue
            stats["inserted"] += 1
            if dry_run:
                continue
            conn.execute("DELETE FROM sleep_night_raw WHERE deviceId = ? AND sourceDate = ?", (device_id, sleep_date))
            conn.execute(
                """
                INSERT INTO sleep_night_raw (
                    deviceId, sourceDate, requestedRange, syncTimestampEpochMs,
                    keySummary, rawPayloadJson, parserVersion, parseStatus
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    device_id,
                    sleep_date,
                    f"{REQUESTED_RANGE_PREFIX}:sleep",
                    int(time.time() * 1000),
                    key_summary,
                    json.dumps(sleep_payload, separators=(",", ":")),
                    PARSER_VERSION,
                    PARSE_STATUS,
                ),
            )
    return stats


def build_nightly_payload(cloud_row: dict[str, Any]) -> dict[str, Any]:
    sleep_date = cloud_row.get("sleepResultDate")
    return {
        "createdTimestamp": cloud_row.get("created"),
        "modifiedTimestamp": cloud_row.get("modified"),
        "ansStatus": cloud_row.get("ansStatus"),
        "recoveryIndicator": cloud_row.get("recoveryIndicator"),
        "recoveryIndicatorSubLevel": cloud_row.get("recoveryIndicatorSubLevel"),
        "ansRate": cloud_row.get("ansRate"),
        "scoreRateObsolete": cloud_row.get("scoreRateObsolete"),
        "meanNightlyRecoveryRRI": cloud_row.get("meanNightlyRecoveryRri"),
        "meanNightlyRecoveryRMSSD": cloud_row.get("meanNightlyRecoveryRmssd"),
        "meanNightlyRecoveryRespirationInterval": cloud_row.get("meanNightlyRecoveryRespirationInterval"),
        "meanBaselineRRI": cloud_row.get("meanBaselineRri"),
        "sdBaselineRRI": cloud_row.get("sdBaselineRri"),
        "meanBaselineRMSSD": cloud_row.get("meanBaselineRmssd"),
        "sdBaselineRMSSD": cloud_row.get("sdBaselineRmssd"),
        "meanBaselineRespirationInterval": cloud_row.get("meanBaselineRespirationInterval"),
        "sdBaselineRespirationInterval": cloud_row.get("sdBaselineRespirationInterval"),
        "sleepTip": cloud_row.get("sleepTip"),
        "vitalityTip": cloud_row.get("vitalityTip"),
        "exerciseTip": cloud_row.get("exerciseTip"),
        "sleepResultDate": sleep_date,
        "summary": {
            "sleepResultDate": sleep_date,
            "baselineReady": all((cloud_row.get(key) or -1) >= 0 for key in ("meanBaselineRri", "meanBaselineRmssd", "meanBaselineRespirationInterval")),
            "ansAvailable": (cloud_row.get("ansStatus") or -100.0) >= 0.0,
            "recoveryAvailable": (cloud_row.get("recoveryIndicator") or -1) >= 0,
            "meanNightlyRecoveryRRI": cloud_row.get("meanNightlyRecoveryRri"),
            "meanNightlyRecoveryRMSSD": cloud_row.get("meanNightlyRecoveryRmssd"),
            "meanNightlyRecoveryRespirationInterval": cloud_row.get("meanNightlyRecoveryRespirationInterval"),
        },
    }


def backfill_nightly(
    conn: sqlite3.Connection,
    token: str,
    device_id: str,
    from_date: str,
    to_date: str,
    dry_run: bool,
) -> int:
    inserted = 0
    payload = fetch_json("nightly-recharge-results", token, from_date, to_date)
    rows = (payload or {}).get("nightlyRechargeResults") or []
    for cloud_row in rows:
        sleep_date = cloud_row.get("sleepResultDate")
        if not sleep_date:
            continue
        existing = conn.execute(
            "SELECT 1 FROM nightly_recharge_raw WHERE deviceId = ? AND sourceDate = ? LIMIT 1",
            (device_id, sleep_date),
        ).fetchone()
        if existing:
            continue
        inserted += 1
        if dry_run:
            continue
        nightly_payload = build_nightly_payload(cloud_row)
        key_summary = (
            f"ans={cloud_row.get('ansStatus')}, indicator={cloud_row.get('recoveryIndicator')}, date={sleep_date}"
        )
        conn.execute("DELETE FROM nightly_recharge_raw WHERE deviceId = ? AND sourceDate = ?", (device_id, sleep_date))
        conn.execute(
            """
            INSERT INTO nightly_recharge_raw (
                deviceId, sourceDate, requestedRange, syncTimestampEpochMs,
                keySummary, rawPayloadJson, parserVersion, parseStatus
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                device_id,
                sleep_date,
                f"{REQUESTED_RANGE_PREFIX}:nightly",
                int(time.time() * 1000),
                key_summary,
                json.dumps(nightly_payload, separators=(",", ":")),
                PARSER_VERSION,
                PARSE_STATUS,
            ),
        )
    return inserted


def fetch_accesslink_json(
    token: str,
    endpoint: str,
    from_date: str,
    to_date: str,
    features: tuple[str, ...] = (),
) -> Any:
    params: list[tuple[str, str]] = [("from", from_date), ("to", to_date)]
    params.extend(("features", feature) for feature in features)
    query = urllib.parse.urlencode(params)
    request = urllib.request.Request(
        f"{BASE_URL}/{endpoint}?{query}",
        headers={"Accept": "application/json", "Authorization": f"Bearer {token}"},
    )
    with urllib.request.urlopen(request, timeout=120) as response:
        raw = response.read().decode("utf-8")
    return json.loads(raw) if raw else None


def parse_interval_seconds(value: Any) -> int | None:
    if value is None:
        return None
    if isinstance(value, str):
        stripped = value.strip()
        if stripped.endswith("ms") and stripped[:-2].isdigit():
            return int(int(stripped[:-2]) // 1000)
        if stripped.isdigit():
            parsed = int(stripped)
            return parsed // 1000 if parsed > 1000 else parsed
        return None
    if isinstance(value, (int, float)):
        parsed = int(value)
        return parsed // 1000 if parsed > 1000 else parsed
    return None


def combine_day_and_clock(source_date: str, clock: str, zone: ZoneInfo) -> str:
    clock = clock.strip()
    if "T" in clock:
        return clock
    return f"{source_date}T{clock}"


def map_ppi_quality_to_hr(quality: str, mean_hr: float | None) -> str:
    if mean_hr is None:
        return "poor_invalid"
    if quality == "good":
        return "good"
    if quality in {"usable", "review"}:
        return "usable_with_invalid_samples"
    return "poor_invalid"


def backfill_hr_from_ppi_epochs(
    conn: sqlite3.Connection,
    device_id: str,
    dates: list[str],
    dry_run: bool,
) -> dict[str, int]:
    updated_days = 0
    inserted_epochs = 0
    now_ms = int(time.time() * 1000)
    for day in dates:
        ppi_rows = conn.execute(
            """
            SELECT epochStartEpochMs, epochEndEpochMs, usableSampleCount, meanHrBpm,
                   minHrBpm, maxHrBpm, triggerTypesCsv, epochQuality
            FROM ppi247_epoch
            WHERE sourceDate = ? AND meanHrBpm IS NOT NULL
            ORDER BY epochStartEpochMs ASC
            """,
            (day,),
        ).fetchall()
        if not ppi_rows:
            continue
        hr_count = conn.execute(
            "SELECT COUNT(*) FROM hr247_epoch WHERE sourceDate = ? AND deviceId = ?",
            (day, device_id),
        ).fetchone()[0]
        if hr_count >= len(ppi_rows):
            continue
        updated_days += 1
        inserted_epochs += len(ppi_rows)
        if dry_run:
            print(f"HR {day}: would rebuild {len(ppi_rows)} epochs from PPI (had {hr_count})")
            continue
        conn.execute("DELETE FROM hr247_epoch WHERE sourceDate = ? AND deviceId = ?", (day, device_id))
        conn.executemany(
            """
            INSERT INTO hr247_epoch (
                deviceId, sourceDate, epochStartEpochMs, epochEndEpochMs,
                sampleCount, meanHrBpm, medianHrBpm, minHrBpm, maxHrBpm,
                triggerTypesCsv, epochQuality, updatedAtEpochMs
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            [
                (
                    device_id,
                    day,
                    row["epochStartEpochMs"],
                    row["epochEndEpochMs"],
                    row["usableSampleCount"],
                    row["meanHrBpm"],
                    row["meanHrBpm"],
                    row["minHrBpm"],
                    row["maxHrBpm"],
                    row["triggerTypesCsv"],
                    map_ppi_quality_to_hr(row["epochQuality"], row["meanHrBpm"]),
                    now_ms,
                )
                for row in ppi_rows
            ],
        )
        print(f"HR {day}: rebuilt {len(ppi_rows)} epochs from PPI (was {hr_count})")
    return {"days": updated_days, "epochs": inserted_epochs}


def cloud_activity_sessions_for_day(day_payload: dict[str, Any]) -> list[dict[str, Any]]:
    sessions: list[dict[str, Any]] = []
    for device_block in day_payload.get("activitiesPerDevice") or []:
        sessions.extend(device_block.get("activitySamples") or [])
    return sessions


def cloud_activity_to_local_payload(source_date: str, sessions: list[dict[str, Any]], zone: ZoneInfo) -> dict[str, Any]:
    local_sessions: list[dict[str, Any]] = []
    for session in sessions:
        step_block = session.get("stepSamples") or {}
        met_block = session.get("metSamples") or {}
        clock = step_block.get("startTime") or met_block.get("startTime")
        if not clock:
            continue
        start_time = combine_day_and_clock(source_date, str(clock), zone)
        step_interval = parse_interval_seconds(step_block.get("interval"))
        met_interval = parse_interval_seconds(met_block.get("interval"))
        activity_infos = []
        for info in session.get("activityInfos") or []:
            info_time = info.get("time")
            if not info_time:
                continue
            activity_infos.append(
                {
                    "activityClass": info.get("activityClass"),
                    "timeStamp": combine_day_and_clock(source_date, str(info_time), zone),
                    "factor": float(info["factor"]) if info.get("factor") is not None else None,
                }
            )
        local_sessions.append(
            {
                "startTime": start_time,
                "metRecordingInterval": met_interval,
                "metSamples": [float(value) for value in (met_block.get("mets") or [])],
                "stepRecordingInterval": step_interval,
                "stepSamples": [int(value) for value in (step_block.get("steps") or [])],
                "activityInfoList": activity_infos,
            }
        )
    total_steps = sum(sum(session.get("stepSamples") or []) for session in local_sessions)
    met_count = sum(len(session.get("metSamples") or []) for session in local_sessions)
    return {
        "polarActivitySamplesDataList": local_sessions,
        "summary": {
            "sessionCount": len(local_sessions),
            "firstStartTime": local_sessions[0]["startTime"] if local_sessions else None,
            "lastStartTime": local_sessions[-1]["startTime"] if local_sessions else None,
            "metSampleCount": met_count,
            "stepSampleCount": sum(len(session.get("stepSamples") or []) for session in local_sessions),
            "activityInfoCount": sum(len(session.get("activityInfoList") or []) for session in local_sessions),
            "totalRecordedSteps": total_steps,
            "avgMet": (
                sum(value for session in local_sessions for value in (session.get("metSamples") or []))
                / met_count
                if met_count
                else None
            ),
        },
    }


def parse_datetime_epoch_ms(value: str, zone: ZoneInfo) -> int | None:
    try:
        if "T" in value:
            parsed = dt.datetime.fromisoformat(value)
            if parsed.tzinfo is None:
                parsed = parsed.replace(tzinfo=zone)
            return int(parsed.timestamp() * 1000)
    except ValueError:
        return None
    return None


def rebuild_activity_epochs(conn: sqlite3.Connection, source_dates: list[str], zone: ZoneInfo, dry_run: bool) -> int:
    placeholders = ",".join("?" for _ in source_dates)
    rows = conn.execute(
        f"""
        SELECT deviceId, sourceDate, rawPayloadJson
        FROM activity_samples_raw
        WHERE sourceDate IN ({placeholders})
        ORDER BY sourceDate ASC
        """,
        source_dates,
    ).fetchall()
    epochs: list[tuple[Any, ...]] = []
    updated_at = int(time.time() * 1000)
    for row in rows:
        payload = json.loads(row["rawPayloadJson"])
        output: dict[int, dict[str, Any]] = {}
        for session in payload.get("polarActivitySamplesDataList") or []:
            start = parse_datetime_epoch_ms(str(session.get("startTime")), zone)
            if start is None:
                continue
            met_interval = session.get("metRecordingInterval")
            step_interval = session.get("stepRecordingInterval")
            for index, met in enumerate(session.get("metSamples") or []):
                if met_interval is None:
                    continue
                epoch_start = start + (index * int(met_interval) * 1000)
                bucket = output.setdefault(epoch_start, {})
                bucket["met"] = float(met)
                bucket["metInterval"] = int(met_interval)
            for index, steps in enumerate(session.get("stepSamples") or []):
                if step_interval is None:
                    continue
                epoch_start = start + (index * int(step_interval) * 1000)
                bucket = output.setdefault(epoch_start, {})
                bucket["steps"] = int(steps)
                bucket["stepInterval"] = int(step_interval)
            for info in session.get("activityInfoList") or []:
                epoch_start = parse_datetime_epoch_ms(str(info.get("timeStamp")), zone)
                if epoch_start is None:
                    continue
                bucket = output.setdefault(epoch_start, {})
                bucket["activityClass"] = info.get("activityClass")
                bucket["activityFactor"] = info.get("factor")
        for epoch_start, draft in sorted(output.items()):
            interval_seconds = min(
                value for value in [draft.get("metInterval"), draft.get("stepInterval")] if value is not None
            ) if any(draft.get(key) is not None for key in ("metInterval", "stepInterval")) else 30
            local_date = dt.datetime.fromtimestamp(epoch_start / 1000, tz=zone).date().isoformat()
            epochs.append(
                (
                    row["deviceId"],
                    local_date,
                    epoch_start,
                    epoch_start + (interval_seconds * 1000),
                    draft.get("met"),
                    draft.get("steps"),
                    draft.get("activityClass"),
                    draft.get("activityFactor"),
                    draft.get("metInterval"),
                    draft.get("stepInterval"),
                    updated_at,
                )
            )
    if dry_run:
        return len(epochs)
    conn.execute(f"DELETE FROM activity_epoch WHERE sourceDate IN ({placeholders})", source_dates)
    conn.executemany(
        """
        INSERT INTO activity_epoch (
            deviceId, sourceDate, epochStartEpochMs, epochEndEpochMs,
            met, steps, activityClass, activityFactor,
            metRecordingIntervalSeconds, stepRecordingIntervalSeconds, updatedAtEpochMs
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        epochs,
    )
    return len(epochs)


def backfill_activity(
    conn: sqlite3.Connection,
    token: str,
    device_id: str,
    dates: list[str],
    zone: ZoneInfo,
    dry_run: bool,
) -> dict[str, Any]:
    stats: dict[str, Any] = {"days": 0, "epochs": 0, "auth_error": False}
    for day in dates:
        next_day = (dt.date.fromisoformat(day) + dt.timedelta(days=1)).isoformat()
        try:
            payload = fetch_accesslink_json(token, "activity/list", day, next_day, features=("samples",))
        except urllib.error.HTTPError as error:
            if error.code in {401, 403}:
                stats["auth_error"] = True
                print(f"Activity {day}: HTTP {error.code} (re-run polar_accesslink_oauth.py for activity:read scope)")
                break
            raise
        day_blocks = ((payload or {}).get("activities") or {}).get("activityDays") or []
        if not day_blocks:
            continue
        sessions = cloud_activity_sessions_for_day(day_blocks[0])
        local_payload = cloud_activity_to_local_payload(day, sessions, zone)
        if not local_payload["polarActivitySamplesDataList"]:
            continue
        existing_epochs = conn.execute(
            "SELECT COUNT(*) FROM activity_epoch WHERE sourceDate = ?",
            (day,),
        ).fetchone()[0]
        cloud_steps = local_payload["summary"]["totalRecordedSteps"] or 0
        if existing_epochs and cloud_steps <= existing_epochs:
            continue
        stats["days"] += 1
        key_summary = (
            f"start={local_payload['summary']['firstStartTime']}, "
            f"sessions={local_payload['summary']['sessionCount']}, "
            f"metSamples={local_payload['summary']['metSampleCount']}, "
            f"totalSteps={cloud_steps}"
        )
        if dry_run:
            print(f"Activity {day}: would import sessions={local_payload['summary']['sessionCount']} steps={cloud_steps}")
            continue
        now_ms = int(time.time() * 1000)
        conn.execute("DELETE FROM activity_samples_raw WHERE deviceId = ? AND sourceDate = ?", (device_id, day))
        conn.execute(
            """
            INSERT INTO activity_samples_raw (
                deviceId, sourceDate, requestedRange, syncTimestampEpochMs,
                keySummary, rawPayloadJson, parserVersion, parseStatus
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                device_id,
                day,
                f"{REQUESTED_RANGE_PREFIX}:activity",
                now_ms,
                key_summary,
                json.dumps(local_payload, separators=(",", ":")),
                PARSER_VERSION,
                PARSE_STATUS,
            ),
        )
        rebuilt = rebuild_activity_epochs(conn, [day], zone, dry_run=False)
        stats["epochs"] += rebuilt
        print(f"Activity {day}: imported sessions={local_payload['summary']['sessionCount']} epochs={rebuilt}")
    return stats


def cloud_skin_payload_for_day(
    device_id: str,
    source_date: str,
    periods: list[dict[str, Any]],
    zone: ZoneInfo,
) -> dict[str, Any] | None:
    samples: list[dict[str, Any]] = []
    sensor_location = None
    measurement_type = None
    day_start_ms = int(dt.datetime.combine(dt.date.fromisoformat(source_date), dt.time.min, tzinfo=zone).timestamp() * 1000)
    for period in periods:
        start_text = period.get("startTime")
        if not start_text:
            continue
        start_dt = dt.datetime.fromisoformat(start_text.replace("Z", "+00:00"))
        sensor_location = period.get("sensorLocation") or sensor_location
        measurement_type = period.get("measurementType") or measurement_type
        period_start_ms = int(start_dt.timestamp() * 1000)
        for sample in period.get("temperatureMeasurementSamples") or []:
            delta_ms = int(sample.get("recordingTimeDeltaMilliseconds") or 0)
            sample_time_ms = period_start_ms + delta_ms
            sample_date = dt.datetime.fromtimestamp(sample_time_ms / 1000, tz=zone).date().isoformat()
            if sample_date != source_date:
                continue
            samples.append(
                {
                    "recordingTimeDeltaMs": sample_time_ms - day_start_ms,
                    "temperature": float(sample["temperatureCelsius"]),
                }
            )
    if not samples:
        return None
    avg = sum(item["temperature"] for item in samples) / len(samples)
    return {
        "date": source_date,
        "result": {
            "deviceId": device_id,
            "sensorLocation": sensor_location,
            "measurementType": measurement_type,
            "skinTemperatureList": samples,
            "summary": {
                "sampleCount": len(samples),
                "minTemperature": min(item["temperature"] for item in samples),
                "maxTemperature": max(item["temperature"] for item in samples),
                "avgTemperature": avg,
            },
        },
    }


def rebuild_skin_samples(conn: sqlite3.Connection, source_dates: list[str], zone: ZoneInfo, dry_run: bool) -> int:
    placeholders = ",".join("?" for _ in source_dates)
    rows = conn.execute(
        f"""
        SELECT deviceId, sourceDate, rawPayloadJson
        FROM skin_temperature_raw
        WHERE sourceDate IN ({placeholders})
        """,
        source_dates,
    ).fetchall()
    samples: list[tuple[Any, ...]] = []
    updated_at = int(time.time() * 1000)
    for row in rows:
        payload = json.loads(row["rawPayloadJson"])
        source_date = payload.get("date") or row["sourceDate"]
        day_start = int(
            dt.datetime.combine(dt.date.fromisoformat(source_date), dt.time.min, tzinfo=zone).timestamp() * 1000
        )
        result = payload.get("result") or {}
        sensor_location = result.get("sensorLocation")
        measurement_type = result.get("measurementType")
        for sample in result.get("skinTemperatureList") or []:
            delta_ms = int(sample.get("recordingTimeDeltaMs") or 0)
            sample_time = day_start + delta_ms
            local_date = dt.datetime.fromtimestamp(sample_time / 1000, tz=zone).date().isoformat()
            samples.append(
                (
                    row["deviceId"],
                    local_date,
                    sample_time,
                    delta_ms,
                    float(sample["temperature"]),
                    sensor_location,
                    measurement_type,
                    updated_at,
                )
            )
    if dry_run:
        return len(samples)
    conn.execute(f"DELETE FROM skin_temperature_sample WHERE sourceDate IN ({placeholders})", source_dates)
    conn.executemany(
        """
        INSERT OR REPLACE INTO skin_temperature_sample (
            deviceId, sourceDate, sampleTimeEpochMs, recordingTimeDeltaMs,
            temperatureCelsius, sensorLocation, measurementType, updatedAtEpochMs
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        samples,
    )
    return len(samples)


def backfill_skin_temperature(
    conn: sqlite3.Connection,
    token: str,
    device_id: str,
    from_date: str,
    to_date: str,
    zone: ZoneInfo,
    dry_run: bool,
) -> dict[str, Any]:
    stats: dict[str, Any] = {"days": 0, "samples": 0, "auth_error": False}
    try:
        payload = fetch_accesslink_json(token, "temperature-measurements", from_date, to_date)
    except urllib.error.HTTPError as error:
        if error.code in {401, 403}:
            stats["auth_error"] = True
            print(
                f"Skin temperature: HTTP {error.code} "
                "(re-run polar_accesslink_oauth.py for temperature_measurement:read scope)"
            )
            return stats
        raise
    periods = (payload or {}).get("temperatureMeasurementPeriod") or []
    for day in date_range(from_date, to_date):
        skin_payload = cloud_skin_payload_for_day(device_id, day, periods, zone)
        if not skin_payload:
            continue
        sample_count = skin_payload["result"]["summary"]["sampleCount"]
        existing = conn.execute(
            "SELECT COUNT(*) FROM skin_temperature_sample WHERE sourceDate = ?",
            (day,),
        ).fetchone()[0]
        if existing >= sample_count:
            continue
        stats["days"] += 1
        key_summary = f"date={day}, samples={sample_count}, avg={skin_payload['result']['summary']['avgTemperature']:.2f}"
        if dry_run:
            print(f"Skin {day}: would import {sample_count} samples (had {existing})")
            continue
        now_ms = int(time.time() * 1000)
        conn.execute("DELETE FROM skin_temperature_raw WHERE deviceId = ? AND sourceDate = ?", (device_id, day))
        conn.execute(
            """
            INSERT INTO skin_temperature_raw (
                deviceId, sourceDate, requestedRange, syncTimestampEpochMs,
                keySummary, rawPayloadJson, parserVersion, parseStatus
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                device_id,
                day,
                f"{REQUESTED_RANGE_PREFIX}:skin",
                now_ms,
                key_summary,
                json.dumps(skin_payload, separators=(",", ":")),
                PARSER_VERSION,
                PARSE_STATUS,
            ),
        )
        rebuilt = rebuild_skin_samples(conn, [day], zone, dry_run=False)
        stats["samples"] += rebuilt
        print(f"Skin {day}: imported {rebuilt} samples (was {existing})")
    return stats


def default_device_id(conn: sqlite3.Connection) -> str:
    row = conn.execute(
        """
        SELECT deviceId, COUNT(*) AS n
        FROM ppi247_day_raw
        GROUP BY deviceId
        ORDER BY n DESC
        LIMIT 1
        """
    ).fetchone()
    if row:
        return row["deviceId"]
    row = conn.execute("SELECT deviceId FROM sleep_night_raw LIMIT 1").fetchone()
    if row and row["deviceId"]:
        return row["deviceId"]
    raise RuntimeError("Could not infer device id; pass --device-id.")


def coverage_report(conn: sqlite3.Connection, dates: list[str]) -> None:
    print("\nCoverage after backfill:")
    for day in dates:
        ppi = conn.execute(
            "SELECT COUNT(*), MIN(epochStartEpochMs), MAX(epochEndEpochMs) FROM ppi247_epoch WHERE sourceDate = ?",
            (day,),
        ).fetchone()
        hr = conn.execute("SELECT COUNT(*) FROM hr247_epoch WHERE sourceDate = ?", (day,)).fetchone()[0]
        skin = conn.execute("SELECT COUNT(*) FROM skin_temperature_sample WHERE sourceDate = ?", (day,)).fetchone()[0]
        activity = conn.execute(
            "SELECT COUNT(*), COALESCE(SUM(steps), 0) FROM activity_epoch WHERE sourceDate = ?",
            (day,),
        ).fetchone()
        sleep = conn.execute(
            "SELECT keySummary FROM sleep_night_raw WHERE sourceDate = ? ORDER BY syncTimestampEpochMs DESC LIMIT 1",
            (day,),
        ).fetchone()
        nightly = conn.execute(
            "SELECT 1 FROM nightly_recharge_raw WHERE sourceDate = ? LIMIT 1",
            (day,),
        ).fetchone()
        span_hours = None
        if ppi[1] is not None and ppi[2] is not None:
            span_hours = round((ppi[2] - ppi[1]) / 3_600_000, 1)
        print(
            f"  {day}: ppi_epochs={ppi[0]} hr_epochs={hr} skin_samples={skin} "
            f"activity_epochs={activity[0]} steps={activity[1]} span_hours={span_hours} "
            f"sleep={'yes' if sleep else 'no'} nightly={'yes' if nightly else 'no'}"
        )


def main() -> int:
    args = parse_args()
    env_path = Path(args.env_file)
    if not env_path.is_absolute():
        env_path = Path.cwd() / env_path
    load_local_env(env_path)
    token = get_access_token(str(env_path))
    domains = {part.strip().lower() for part in args.domains.split(",") if part.strip()}
    zone = ZoneInfo(args.timezone)

    db_path = Path(args.db)
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    device_id = args.device_id or default_device_id(conn)

    affected_dates = date_range(args.from_date, args.to_date)
    print(f"Device {device_id}; dates {args.from_date}..{args.to_date}; dry_run={args.dry_run}")

    if "ppi" in domains:
        cloud_by_day = fetch_cloud_ppi_by_day(token, args.from_date, args.to_date)
        existing_keys = existing_ppi_keys(conn, device_id)
        total_inserted = 0
        for day, samples in cloud_by_day.items():
            records = cloud_ppi_records_for_day(day, samples, "TRIGGER_TYPE_AUTOMATIC")
            inserted = insert_ppi_records(conn, device_id, records, existing_keys, args.dry_run)
            print(f"PPI {day}: cloud_samples={len(samples)} new_raw_batches={inserted}")
            total_inserted += inserted
        rebuilt = rebuild_ppi_epochs(conn, affected_dates, zone, args.dry_run)
        print(f"PPI epochs rebuilt: {rebuilt} across {len(affected_dates)} date(s)")

    if "sleep" in domains:
        sleep_stats = backfill_sleep(conn, token, device_id, args.from_date, args.to_date, args.dry_run)
        print(f"Sleep: {sleep_stats}")

    if "nightly" in domains:
        nightly_inserted = backfill_nightly(conn, token, device_id, args.from_date, args.to_date, args.dry_run)
        print(f"Nightly recharge inserted: {nightly_inserted}")

    if "hr" in domains:
        hr_stats = backfill_hr_from_ppi_epochs(conn, device_id, affected_dates, args.dry_run)
        print(f"HR from PPI: {hr_stats}")

    if "activity" in domains:
        activity_stats = backfill_activity(conn, token, device_id, affected_dates, zone, args.dry_run)
        print(f"Activity: {activity_stats}")

    if "skin" in domains:
        skin_stats = backfill_skin_temperature(
            conn, token, device_id, args.from_date, args.to_date, zone, args.dry_run
        )
        print(f"Skin temperature: {skin_stats}")

    if not args.dry_run:
        conn.commit()
    coverage_report(conn, affected_dates)
    conn.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
