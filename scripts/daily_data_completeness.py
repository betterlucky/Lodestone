#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import sqlite3
from datetime import date
from pathlib import Path
from typing import Any


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Summarise whether the important daily data lanes are populated.")
    parser.add_argument("--health-db", required=True, help="Path to the Lodestone/HealthMonitor SQLite database.")
    parser.add_argument("--date", default=date.today().isoformat(), help="Source date to check, YYYY-MM-DD.")
    parser.add_argument("--garmin-db", help="Optional Garmin givemydata SQLite database.")
    parser.add_argument("--json", action="store_true", help="Emit JSON instead of a compact text report.")
    return parser.parse_args()


def connect(path: str) -> sqlite3.Connection:
    conn = sqlite3.connect(path)
    conn.row_factory = sqlite3.Row
    return conn


def one(conn: sqlite3.Connection, query: str, params: tuple[Any, ...] = ()) -> sqlite3.Row | None:
    return conn.execute(query, params).fetchone()


def fetch_json(row: sqlite3.Row | None, column: str = "rawPayloadJson") -> dict[str, Any]:
    if not row:
        return {}
    try:
        return json.loads(row[column] or "{}")
    except json.JSONDecodeError:
        return {}


def sleep_summary(conn: sqlite3.Connection, source_date: str) -> dict[str, Any]:
    row = one(conn, "select * from sleep_night_raw where sourceDate = ? order by syncTimestampEpochMs desc limit 1", (source_date,))
    payload = fetch_json(row)
    result = payload.get("result") or {}
    summary = result.get("summary") or {}
    return {
        "present": row is not None,
        "window": f"{result.get('sleepStartTime')} -> {result.get('sleepEndTime')}" if result.get("sleepStartTime") else None,
        "duration_minutes": summary.get("durationMinutes"),
        "cycles": summary.get("cycleCount"),
    }


def nightly_summary(conn: sqlite3.Connection, source_date: str) -> dict[str, Any]:
    row = one(conn, "select * from nightly_recharge_raw where sourceDate = ? order by syncTimestampEpochMs desc limit 1", (source_date,))
    payload = fetch_json(row)
    summary = payload.get("summary") or payload
    return {
        "present": row is not None,
        "rmssd": summary.get("meanNightlyRecoveryRMSSD"),
        "rri": summary.get("meanNightlyRecoveryRRI"),
        "ans": payload.get("ansStatus") if payload.get("ansStatus") is not None else summary.get("ansCharge"),
        "baseline_ready": summary.get("baselineReady"),
    }


def ppi_summary(conn: sqlite3.Connection, source_date: str) -> dict[str, Any]:
    raw = one(
        conn,
        """
        select count(*) as batches, coalesce(sum(json_array_length(json_extract(rawPayloadJson, '$.samples.ppiValueList'))), 0) as samples,
               min(json_extract(rawPayloadJson, '$.samples.startTime')) as first_start,
               max(json_extract(rawPayloadJson, '$.samples.startTime')) as last_start
        from ppi247_day_raw
        where sourceDate = ?
        """,
        (source_date,),
    )
    epochs = one(
        conn,
        """
        select count(*) as epochs,
               sum(case when lower(epochQuality) = 'good' then 1 else 0 end) as good,
               sum(case when lower(epochQuality) = 'review' then 1 else 0 end) as review,
               sum(case when lower(epochQuality) like 'poor%' then 1 else 0 end) as poor,
               avg(rmssdMs) as avg_rmssd,
               avg(meanHrBpm) as avg_hr
        from ppi247_epoch
        where sourceDate = ?
        """,
        (source_date,),
    )
    return {
        "present": bool(raw and raw["samples"]),
        "batches": raw["batches"] if raw else 0,
        "samples": raw["samples"] if raw else 0,
        "first_start": raw["first_start"] if raw else None,
        "last_start": raw["last_start"] if raw else None,
        "epochs": epochs["epochs"] if epochs else 0,
        "good_epochs": epochs["good"] if epochs else 0,
        "review_epochs": epochs["review"] if epochs else 0,
        "poor_epochs": epochs["poor"] if epochs else 0,
        "avg_epoch_rmssd": round(epochs["avg_rmssd"], 1) if epochs and epochs["avg_rmssd"] is not None else None,
        "avg_epoch_hr": round(epochs["avg_hr"], 1) if epochs and epochs["avg_hr"] is not None else None,
    }


def simple_count(conn: sqlite3.Connection, table: str, source_date: str) -> int:
    return int(one(conn, f"select count(*) as n from {table} where sourceDate = ?", (source_date,))["n"])


def skin_summary(conn: sqlite3.Connection, source_date: str) -> dict[str, Any]:
    row = one(
        conn,
        "select count(*) as n, avg(temperatureCelsius) as avg_t, min(temperatureCelsius) as min_t, max(temperatureCelsius) as max_t from skin_temperature_sample where sourceDate = ?",
        (source_date,),
    )
    return {
        "present": bool(row and row["n"]),
        "samples": row["n"] if row else 0,
        "avg": round(row["avg_t"], 2) if row and row["avg_t"] is not None else None,
        "range": round(row["max_t"] - row["min_t"], 2) if row and row["max_t"] is not None and row["min_t"] is not None else None,
    }


def latest_storage(conn: sqlite3.Connection) -> dict[str, Any]:
    row = one(
        conn,
        "select detailSummary from sync_domain_result where domain = 'storage_maintenance' order by endedAtEpochMs desc limit 1",
    )
    if not row:
        return {"present": False}
    details = row["detailSummary"] or ""
    return {"present": True, "summary": details}


def garmin_summary(path: str | None, source_date: str) -> dict[str, Any]:
    if not path or not Path(path).exists():
        return {"configured": False}
    conn = connect(path)
    try:
        tables = ["hrv", "sleep", "stress", "body_battery", "heart_rate", "respiration", "spo2", "daily_summary"]
        result = {"configured": True}
        for table in tables:
            column = "calendar_date"
            count = one(conn, f"select count(*) as n from {table} where {column} = ?", (source_date,))
            result[table] = bool(count and count["n"])
        hrv = one(conn, "select coalesce(last_night, last_night_avg) as hrv_value, status from hrv where calendar_date = ?", (source_date,))
        sleep = one(conn, "select sleep_time_seconds, average_spo2, average_hr_sleep, average_respiration from sleep where calendar_date = ?", (source_date,))
        result["hrv_last_night"] = hrv["hrv_value"] if hrv else None
        result["hrv_status"] = hrv["status"] if hrv else None
        result["sleep_minutes"] = round(sleep["sleep_time_seconds"] / 60) if sleep and sleep["sleep_time_seconds"] else None
        result["average_spo2"] = sleep["average_spo2"] if sleep else None
        result["average_sleep_hr"] = sleep["average_hr_sleep"] if sleep else None
        result["average_respiration"] = sleep["average_respiration"] if sleep else None
        return result
    finally:
        conn.close()


def build_report(health_db: str, source_date: str, garmin_db: str | None) -> dict[str, Any]:
    conn = connect(health_db)
    try:
        food = one(conn, "select totalCaloriesKcal, eventCount, teaCount from food_daily_summary where sourceDate = ?", (source_date,))
        review = one(conn, "select eveningOutcome, approachToDay, muscleWeaknessToday from daily_check_in where sourceDate = ?", (source_date,))
        wake = one(
            conn,
            """
            select markerEpochMs, markerSource from wake_marker
            where sourceDate = ?
              and coalesce(notes, '') != 'manual awake command'
            order by markerEpochMs desc limit 1
            """,
            (source_date,),
        )
        return {
            "source_date": source_date,
            "polar": {
                "sleep": sleep_summary(conn, source_date),
                "nightly_recharge": nightly_summary(conn, source_date),
                "ppi247": ppi_summary(conn, source_date),
                "hr247_raw_records": simple_count(conn, "hr247_day_raw", source_date),
                "skin_temperature": skin_summary(conn, source_date),
                "daily_summary_records": simple_count(conn, "daily_summary_raw", source_date),
                "activity_records": simple_count(conn, "activity_samples_raw", source_date),
                "latest_storage": latest_storage(conn),
            },
            "journal": {
                "food_present": food is not None,
                "calories": food["totalCaloriesKcal"] if food else None,
                "food_events": food["eventCount"] if food else None,
                "tea_count": food["teaCount"] if food else None,
                "review_present": review is not None,
                "outcome": review["eveningOutcome"] if review else None,
                "approach": review["approachToDay"] if review else None,
                "muscle_weakness": bool(review["muscleWeaknessToday"]) if review else None,
                "wake_marker_present": wake is not None,
                "wake_marker_source": wake["markerSource"] if wake else None,
            },
            "garmin": garmin_summary(garmin_db, source_date),
        }
    finally:
        conn.close()


def print_text(report: dict[str, Any]) -> None:
    print(f"Daily data completeness: {report['source_date']}")
    polar = report["polar"]
    sleep = polar["sleep"]
    nightly = polar["nightly_recharge"]
    ppi = polar["ppi247"]
    skin = polar["skin_temperature"]
    journal = report["journal"]
    garmin = report["garmin"]
    print(f"  Polar sleep: {'yes' if sleep['present'] else 'no'} {sleep.get('window') or ''}".rstrip())
    print(f"  Nightly Recharge: {'yes' if nightly['present'] else 'no'} RMSSD={nightly.get('rmssd')} RRI={nightly.get('rri')} ANS={nightly.get('ans')}")
    print(f"  PPI_247: {'yes' if ppi['present'] else 'no'} samples={ppi['samples']} epochs={ppi['epochs']} good/review/poor={ppi['good_epochs']}/{ppi['review_epochs']}/{ppi['poor_epochs']} avgRMSSD={ppi['avg_epoch_rmssd']}")
    print(f"  HR/Skin/Activity: hrRaw={polar['hr247_raw_records']} skinSamples={skin['samples']} skinAvg={skin['avg']} daily={polar['daily_summary_records']} activity={polar['activity_records']}")
    print(f"  Journal: food={'yes' if journal['food_present'] else 'no'} calories={journal['calories']} review={'yes' if journal['review_present'] else 'no'} wakeMarker={'yes' if journal['wake_marker_present'] else 'no'}")
    if garmin.get("configured"):
        present = [key for key in ("hrv", "sleep", "stress", "body_battery", "heart_rate", "respiration", "spo2") if garmin.get(key)]
        print(f"  Garmin: {', '.join(present) or 'none'} HRV={garmin.get('hrv_last_night')} sleepMin={garmin.get('sleep_minutes')} SpO2={garmin.get('average_spo2')}")
    else:
        print("  Garmin: not configured")
    print(f"  Storage: {polar['latest_storage'].get('summary') if polar['latest_storage'].get('present') else 'unknown'}")


def main() -> None:
    args = parse_args()
    report = build_report(args.health_db, args.date, args.garmin_db)
    if args.json:
        print(json.dumps(report, indent=2))
    else:
        print_text(report)


if __name__ == "__main__":
    main()
