#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import sqlite3
from collections import Counter
from datetime import date, timedelta
from pathlib import Path
from typing import Any

ACTIVITY_SAMPLE_SYNC_ENABLED = True
CLOUD_BACKFILL_PREFIX = "cloud_backfill:"

RAW_LANE_TABLES = {
    "SLEEP": "sleep_night_raw",
    "NIGHTLY_RECHARGE": "nightly_recharge_raw",
    "PPI_247": "ppi247_day_raw",
    "HR_247": "hr247_day_raw",
    "SKIN_TEMPERATURE": "skin_temperature_raw",
    "DAILY_SUMMARY": "daily_summary_raw",
    "ACTIVITY_SAMPLES": "activity_samples_raw",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Summarise whether the important daily data lanes are populated.")
    parser.add_argument("--health-db", required=True, help="Path to the Lodestone/HealthMonitor SQLite database.")
    parser.add_argument("--date", default=date.today().isoformat(), help="Source date to check, YYYY-MM-DD.")
    parser.add_argument("--start-date", help="First source date to check, YYYY-MM-DD.")
    parser.add_argument("--end-date", help="Last source date to check, YYYY-MM-DD.")
    parser.add_argument("--garmin-db", help="Optional Garmin givemydata SQLite database.")
    parser.add_argument("--json", action="store_true", help="Emit JSON instead of a compact text report.")
    return parser.parse_args()


def connect(path: str | Path) -> sqlite3.Connection:
    conn = sqlite3.connect(path)
    conn.row_factory = sqlite3.Row
    return conn


def one(conn: sqlite3.Connection, query: str, params: tuple[Any, ...] = ()) -> sqlite3.Row | None:
    return conn.execute(query, params).fetchone()


def date_range(start: str, end: str) -> list[str]:
    start_date = date.fromisoformat(start)
    end_date = date.fromisoformat(end)
    if end_date < start_date:
        return []
    return [(start_date + timedelta(days=offset)).isoformat() for offset in range((end_date - start_date).days + 1)]


def requested_dates(args: argparse.Namespace) -> list[str]:
    if args.start_date or args.end_date:
        return date_range(args.start_date or args.date, args.end_date or args.date)
    return [args.date]


def fetch_json(row: sqlite3.Row | None, column: str = "rawPayloadJson") -> dict[str, Any]:
    if not row:
        return {}
    try:
        return json.loads(row[column] or "{}")
    except json.JSONDecodeError:
        return {}


def count_rows(conn: sqlite3.Connection, table: str, source_date: str) -> int:
    return int(one(conn, f"select count(*) as n from {table} where sourceDate = ?", (source_date,))["n"])


def provenance_origin(requested_range: str | None) -> str:
    if not requested_range:
        return "unknown"
    if requested_range.lower().startswith(CLOUD_BACKFILL_PREFIX):
        return "cloud_backfill"
    return "local_loop"


def raw_table_provenance(conn: sqlite3.Connection, table: str, source_date: str) -> dict[str, Any]:
    rows = conn.execute(
        f"""
        select requestedRange, count(*) as records
        from {table}
        where sourceDate = ?
        group by requestedRange
        order by requestedRange
        """,
        (source_date,),
    ).fetchall()
    by_origin: Counter[str] = Counter()
    ranges: list[dict[str, Any]] = []
    for row in rows:
        requested_range = row["requestedRange"]
        origin = provenance_origin(requested_range)
        records = int(row["records"] or 0)
        by_origin[origin] += records
        ranges.append(
            {
                "requested_range": requested_range,
                "origin": origin,
                "records": records,
            }
        )
    return {
        "records": sum(by_origin.values()),
        "by_origin": dict(by_origin),
        "ranges": ranges,
        "has_cloud_backfill": by_origin.get("cloud_backfill", 0) > 0,
        "has_local_loop": by_origin.get("local_loop", 0) > 0,
    }


def provenance_text(provenance: dict[str, Any]) -> str:
    by_origin = provenance.get("by_origin") or {}
    if not by_origin:
        return "none"
    labels = {
        "local_loop": "local Loop",
        "cloud_backfill": "cloud/API backfill",
        "unknown": "unknown",
    }
    parts = []
    for origin in ("local_loop", "cloud_backfill", "unknown"):
        count = by_origin.get(origin)
        if count:
            parts.append(f"{labels.get(origin, origin)}={count}")
    return ", ".join(parts) if parts else "none"


def sleep_summary(conn: sqlite3.Connection, source_date: str) -> dict[str, Any]:
    row = one(conn, "select * from sleep_night_raw where sourceDate = ? order by syncTimestampEpochMs desc limit 1", (source_date,))
    payload = fetch_json(row)
    result = payload.get("result") or {}
    summary = result.get("summary") or {}
    return {
        "present": row is not None,
        "records": count_rows(conn, "sleep_night_raw", source_date),
        "window": f"{result.get('sleepStartTime')} -> {result.get('sleepEndTime')}" if result.get("sleepStartTime") else None,
        "duration_minutes": summary.get("durationMinutes"),
        "cycles": summary.get("cycleCount"),
        "provenance": raw_table_provenance(conn, "sleep_night_raw", source_date),
    }


def nightly_summary(conn: sqlite3.Connection, source_date: str) -> dict[str, Any]:
    row = one(conn, "select * from nightly_recharge_raw where sourceDate = ? order by syncTimestampEpochMs desc limit 1", (source_date,))
    payload = fetch_json(row)
    summary = payload.get("summary") or payload
    return {
        "present": row is not None,
        "records": count_rows(conn, "nightly_recharge_raw", source_date),
        "rmssd": summary.get("meanNightlyRecoveryRMSSD"),
        "rri": summary.get("meanNightlyRecoveryRRI"),
        "ans": payload.get("ansStatus") if payload.get("ansStatus") is not None else summary.get("ansCharge"),
        "baseline_ready": summary.get("baselineReady"),
        "provenance": raw_table_provenance(conn, "nightly_recharge_raw", source_date),
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
        "present": bool(raw and raw["samples"]) or bool(epochs and epochs["epochs"]),
        "raw_batches": raw["batches"] if raw else 0,
        "raw_samples": raw["samples"] if raw else 0,
        "first_start": raw["first_start"] if raw else None,
        "last_start": raw["last_start"] if raw else None,
        "epochs": epochs["epochs"] if epochs else 0,
        "good_epochs": epochs["good"] if epochs else 0,
        "review_epochs": epochs["review"] if epochs else 0,
        "poor_epochs": epochs["poor"] if epochs else 0,
        "avg_epoch_rmssd": round(epochs["avg_rmssd"], 1) if epochs and epochs["avg_rmssd"] is not None else None,
        "avg_epoch_hr": round(epochs["avg_hr"], 1) if epochs and epochs["avg_hr"] is not None else None,
        "provenance": raw_table_provenance(conn, "ppi247_day_raw", source_date),
    }


def hr_summary(conn: sqlite3.Connection, source_date: str) -> dict[str, Any]:
    epochs = one(
        conn,
        """
        select count(*) as n, avg(meanHrBpm) as avg_hr, min(minHrBpm) as min_hr, max(maxHrBpm) as max_hr
        from hr247_epoch
        where sourceDate = ?
        """,
        (source_date,),
    )
    raw_records = count_rows(conn, "hr247_day_raw", source_date)
    epoch_count = epochs["n"] if epochs else 0
    return {
        "present": raw_records > 0 or epoch_count > 0,
        "raw_records": raw_records,
        "epochs": epoch_count,
        "avg_epoch_hr": round(epochs["avg_hr"], 1) if epochs and epochs["avg_hr"] is not None else None,
        "range": f"{epochs['min_hr']}..{epochs['max_hr']}" if epochs and epochs["min_hr"] is not None and epochs["max_hr"] is not None else None,
        "provenance": raw_table_provenance(conn, "hr247_day_raw", source_date),
    }


def skin_summary(conn: sqlite3.Connection, source_date: str) -> dict[str, Any]:
    row = one(
        conn,
        "select count(*) as n, avg(temperatureCelsius) as avg_t, min(temperatureCelsius) as min_t, max(temperatureCelsius) as max_t from skin_temperature_sample where sourceDate = ?",
        (source_date,),
    )
    raw_records = count_rows(conn, "skin_temperature_raw", source_date)
    samples = row["n"] if row else 0
    return {
        "present": raw_records > 0 or samples > 0,
        "raw_records": raw_records,
        "samples": samples,
        "avg": round(row["avg_t"], 2) if row and row["avg_t"] is not None else None,
        "range": round(row["max_t"] - row["min_t"], 2) if row and row["max_t"] is not None and row["min_t"] is not None else None,
        "provenance": raw_table_provenance(conn, "skin_temperature_raw", source_date),
    }


def daily_summary(conn: sqlite3.Connection, source_date: str) -> dict[str, Any]:
    records = count_rows(conn, "daily_summary_raw", source_date)
    return {
        "present": records > 0,
        "records": records,
        "provenance": raw_table_provenance(conn, "daily_summary_raw", source_date),
    }


def activity_summary(conn: sqlite3.Connection, source_date: str) -> dict[str, Any]:
    epochs = one(
        conn,
        "select count(*) as n, sum(steps) as steps, avg(met) as avg_met from activity_epoch where sourceDate = ?",
        (source_date,),
    )
    raw_records = count_rows(conn, "activity_samples_raw", source_date)
    epoch_count = epochs["n"] if epochs else 0
    return {
        "present": raw_records > 0 or epoch_count > 0,
        "raw_records": raw_records,
        "epochs": epoch_count,
        "steps": epochs["steps"] if epochs else None,
        "avg_met": round(epochs["avg_met"], 2) if epochs and epochs["avg_met"] is not None else None,
        "provenance": raw_table_provenance(conn, "activity_samples_raw", source_date),
    }


def range_contains(requested_range: str | None, source_date: str) -> bool:
    if not requested_range:
        return False
    if ".." not in requested_range:
        return requested_range == source_date
    start, end = requested_range.split("..", 1)
    return bool(start and end and start <= source_date <= end)


def profile_from_notes(notes: str | None) -> str | None:
    if not notes:
        return None
    lowered = notes.lower()
    if "manual sync" in lowered:
        return "FULL"
    if "check-in sync" in lowered:
        return "CHECK_IN"
    if "morning core sync" in lowered:
        return "MORNING_CORE"
    if "morning ppi retry" in lowered:
        return "MORNING_PPI_RETRY"
    if "morning sleep report retry" in lowered:
        return "MORNING_SLEEP_RETRY"
    return None


def domain_sync_results(conn: sqlite3.Connection, source_date: str) -> dict[str, Any]:
    rows = conn.execute(
        """
        select dr.domain, dr.requestedRange, dr.status, dr.recordCount, dr.detailSummary,
               dr.errorCode, dr.endedAtEpochMs, sr.status as runStatus, sr.notes as runNotes
        from sync_domain_result dr
        left join sync_run sr on sr.id = dr.syncRunId
        order by dr.endedAtEpochMs desc, dr.id desc
        """
    ).fetchall()
    latest_by_domain: dict[str, dict[str, Any]] = {}
    profiles: Counter[str] = Counter()
    for row in rows:
        if not range_contains(row["requestedRange"], source_date):
            continue
        domain = str(row["domain"]).upper()
        if domain == "STORAGE_MAINTENANCE":
            continue
        profile = profile_from_notes(row["runNotes"])
        if profile:
            profiles[profile] += 1
        latest_by_domain.setdefault(
            domain,
            {
                "domain": domain,
                "status": row["status"],
                "record_count": row["recordCount"],
                "requested_range": row["requestedRange"],
                "detail": row["detailSummary"],
                "error": row["errorCode"],
                "run_status": row["runStatus"],
                "run_profile": profile,
                "run_notes": row["runNotes"],
                "ended_at_epoch_ms": row["endedAtEpochMs"],
            },
        )
    return {
        "profiles": dict(profiles),
        "domains": latest_by_domain,
        "has_full_run": profiles.get("FULL", 0) > 0,
        "has_core_run": any(profiles.get(profile, 0) > 0 for profile in ("CHECK_IN", "MORNING_CORE", "MORNING_PPI_RETRY", "FULL")),
    }


def domain_line(sync: dict[str, Any] | None) -> str:
    if not sync:
        return "not attempted"
    status = sync.get("status")
    records = sync.get("record_count")
    error = sync.get("error")
    suffix = f", error={error}" if error else ""
    return f"{status}, records={records}, profile={sync.get('run_profile') or 'unknown'}{suffix}"


def interpret_supporting_lanes(polar: dict[str, Any], sync: dict[str, Any]) -> dict[str, str]:
    domains = sync["domains"]
    has_full_run = sync["has_full_run"]
    has_core_run = sync["has_core_run"]
    interpretations: dict[str, str] = {}

    hr = polar["hr247"]
    hr_sync = domains.get("HR_247")
    ppi_sync = domains.get("PPI_247")
    if hr["epochs"] > 0 and hr["raw_records"] == 0:
        interpretations["HR_247"] = "present from derived epochs; raw records were likely pruned after epoch rebuild"
    elif hr["present"]:
        interpretations["HR_247"] = "present"
    elif hr_sync:
        if hr_sync.get("status") == "ERROR":
            interpretations["HR_247"] = f"sync issue: attempted but failed with {hr_sync.get('error') or 'error'}"
        elif hr_sync.get("status") == "EMPTY":
            interpretations["HR_247"] = "attempted but device returned no rows"
        elif hr_sync.get("record_count", 0) > 0:
            interpretations["HR_247"] = "reporting gap: sync recorded rows but raw/derived HR tables are empty"
        else:
            interpretations["HR_247"] = f"not populated after attempted sync ({hr_sync.get('status')})"
    elif has_core_run:
        if ppi_sync and ppi_sync.get("status") == "ERROR":
            interpretations["HR_247"] = "sync gap: core profile should attempt HR_247 after PPI, but no HR result was recorded after a PPI failure/timeout"
        else:
            interpretations["HR_247"] = "sync/reporting gap: core profile should attempt HR_247 but no domain result was recorded"
    else:
        interpretations["HR_247"] = "not attempted by the recorded sync profile"

    skin = polar["skin_temperature"]
    skin_sync = domains.get("SKIN_TEMPERATURE")
    if skin["samples"] > 0 and skin["raw_records"] == 0:
        interpretations["SKIN_TEMPERATURE"] = "present from derived samples; raw records were pruned after sample rebuild"
    elif skin["present"]:
        interpretations["SKIN_TEMPERATURE"] = "present"
    elif skin_sync:
        if skin_sync.get("status") == "ERROR":
            interpretations["SKIN_TEMPERATURE"] = f"sync issue: attempted but failed with {skin_sync.get('error') or 'error'}"
        elif skin_sync.get("status") == "EMPTY":
            interpretations["SKIN_TEMPERATURE"] = "attempted in FULL sync but device returned no rows"
        elif skin_sync.get("record_count", 0) > 0:
            interpretations["SKIN_TEMPERATURE"] = "reporting gap: sync recorded rows but raw/derived skin tables are empty"
        else:
            interpretations["SKIN_TEMPERATURE"] = f"not populated after attempted sync ({skin_sync.get('status')})"
    elif not has_core_run:
        interpretations["SKIN_TEMPERATURE"] = "expected for sleep-report retry: only sleep and Nightly Recharge are attempted"
    else:
        interpretations["SKIN_TEMPERATURE"] = "sync/reporting gap: primary sync profile should attempt skin temperature but no domain result was recorded"

    daily = polar["daily_summary"]
    daily_sync = domains.get("DAILY_SUMMARY")
    if daily["present"]:
        interpretations["DAILY_SUMMARY"] = "present"
    elif daily_sync:
        if daily_sync.get("status") == "ERROR":
            interpretations["DAILY_SUMMARY"] = f"sync issue: attempted but failed with {daily_sync.get('error') or 'error'}"
        elif daily_sync.get("status") == "EMPTY":
            interpretations["DAILY_SUMMARY"] = "attempted in FULL sync but device returned no rows"
        elif daily_sync.get("record_count", 0) > 0:
            interpretations["DAILY_SUMMARY"] = "reporting gap: sync recorded rows but daily_summary_raw is empty"
        else:
            interpretations["DAILY_SUMMARY"] = f"not populated after attempted sync ({daily_sync.get('status')})"
    elif not has_core_run:
        interpretations["DAILY_SUMMARY"] = "expected for sleep-report retry: only sleep and Nightly Recharge are attempted"
    else:
        interpretations["DAILY_SUMMARY"] = "sync/reporting gap: primary sync profile should attempt daily summary but no domain result was recorded"

    activity = polar["activity_samples"]
    activity_sync = domains.get("ACTIVITY_SAMPLES")
    if activity["epochs"] > 0 and activity["raw_records"] == 0:
        interpretations["ACTIVITY_SAMPLES"] = "present from derived epochs; raw records were pruned after epoch rebuild"
    elif activity["present"]:
        interpretations["ACTIVITY_SAMPLES"] = "present"
    elif activity_sync:
        if activity_sync.get("status") == "ERROR":
            interpretations["ACTIVITY_SAMPLES"] = f"sync issue: attempted but failed with {activity_sync.get('error') or 'error'}"
        elif activity_sync.get("status") == "EMPTY":
            interpretations["ACTIVITY_SAMPLES"] = "attempted but device returned no rows"
        elif activity_sync.get("record_count", 0) > 0:
            interpretations["ACTIVITY_SAMPLES"] = "reporting gap: sync recorded rows but raw/derived activity tables are empty"
        else:
            interpretations["ACTIVITY_SAMPLES"] = f"not populated after attempted sync ({activity_sync.get('status')})"
    elif not ACTIVITY_SAMPLE_SYNC_ENABLED:
        interpretations["ACTIVITY_SAMPLES"] = "expected: activity sample sync is disabled in the current app build"
    elif not has_core_run:
        interpretations["ACTIVITY_SAMPLES"] = "expected for sleep-report retry: only sleep and Nightly Recharge are attempted"
    else:
        interpretations["ACTIVITY_SAMPLES"] = "sync/reporting gap: primary sync profile should attempt activity samples but no domain result was recorded"

    return interpretations


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


def build_report_for_connection(conn: sqlite3.Connection, source_date: str, garmin_db: str | None) -> dict[str, Any]:
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
    sync = domain_sync_results(conn, source_date)
    polar = {
        "sleep": sleep_summary(conn, source_date),
        "nightly_recharge": nightly_summary(conn, source_date),
        "ppi247": ppi_summary(conn, source_date),
        "hr247": hr_summary(conn, source_date),
        "skin_temperature": skin_summary(conn, source_date),
        "daily_summary": daily_summary(conn, source_date),
        "activity_samples": activity_summary(conn, source_date),
        "latest_storage": latest_storage(conn),
    }
    return {
        "source_date": source_date,
        "polar": polar,
        "sync": sync,
        "supporting_lane_interpretation": interpret_supporting_lanes(polar, sync),
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


def build_report(health_db: str, source_date: str, garmin_db: str | None) -> dict[str, Any]:
    conn = connect(health_db)
    try:
        return build_report_for_connection(conn, source_date, garmin_db)
    finally:
        conn.close()


def build_reports(health_db: str, source_dates: list[str], garmin_db: str | None) -> list[dict[str, Any]]:
    conn = connect(health_db)
    try:
        return [build_report_for_connection(conn, source_date, garmin_db) for source_date in source_dates]
    finally:
        conn.close()


def range_summary(reports: list[dict[str, Any]]) -> dict[str, Any]:
    missing_by_lane: dict[str, list[str]] = {lane: [] for lane in ("HR_247", "SKIN_TEMPERATURE", "DAILY_SUMMARY", "ACTIVITY_SAMPLES")}
    cloud_backfilled_by_lane: dict[str, list[str]] = {lane: [] for lane in RAW_LANE_TABLES}
    interpretations: Counter[str] = Counter()
    for report in reports:
        polar = report["polar"]
        present = {
            "HR_247": polar["hr247"]["present"],
            "SKIN_TEMPERATURE": polar["skin_temperature"]["present"],
            "DAILY_SUMMARY": polar["daily_summary"]["present"],
            "ACTIVITY_SAMPLES": polar["activity_samples"]["present"],
        }
        for lane, is_present in present.items():
            if not is_present:
                missing_by_lane[lane].append(report["source_date"])
                interpretations[f"{lane}: {report['supporting_lane_interpretation'][lane]}"] += 1
        provenance_by_lane = {
            "SLEEP": polar["sleep"]["provenance"],
            "NIGHTLY_RECHARGE": polar["nightly_recharge"]["provenance"],
            "PPI_247": polar["ppi247"]["provenance"],
            "HR_247": polar["hr247"]["provenance"],
            "SKIN_TEMPERATURE": polar["skin_temperature"]["provenance"],
            "DAILY_SUMMARY": polar["daily_summary"]["provenance"],
            "ACTIVITY_SAMPLES": polar["activity_samples"]["provenance"],
        }
        for lane, provenance in provenance_by_lane.items():
            if provenance.get("has_cloud_backfill"):
                cloud_backfilled_by_lane[lane].append(report["source_date"])
    return {
        "date_count": len(reports),
        "missing_by_lane": missing_by_lane,
        "cloud_backfilled_by_lane": cloud_backfilled_by_lane,
        "interpretation_counts": dict(interpretations),
    }


def print_text(report: dict[str, Any]) -> None:
    print(f"Daily data completeness: {report['source_date']}")
    polar = report["polar"]
    sleep = polar["sleep"]
    nightly = polar["nightly_recharge"]
    ppi = polar["ppi247"]
    hr = polar["hr247"]
    skin = polar["skin_temperature"]
    daily = polar["daily_summary"]
    activity = polar["activity_samples"]
    journal = report["journal"]
    garmin = report["garmin"]
    print(f"  Polar sleep: {'yes' if sleep['present'] else 'no'} records={sleep['records']} provenance={provenance_text(sleep['provenance'])} {sleep.get('window') or ''}".rstrip())
    print(f"  Nightly Recharge: {'yes' if nightly['present'] else 'no'} records={nightly['records']} provenance={provenance_text(nightly['provenance'])} RMSSD={nightly.get('rmssd')} RRI={nightly.get('rri')} ANS={nightly.get('ans')}")
    print(f"  PPI_247: {'yes' if ppi['present'] else 'no'} rawBatches={ppi['raw_batches']} rawSamples={ppi['raw_samples']} provenance={provenance_text(ppi['provenance'])} epochs={ppi['epochs']} good/review/poor={ppi['good_epochs']}/{ppi['review_epochs']}/{ppi['poor_epochs']} avgRMSSD={ppi['avg_epoch_rmssd']}")
    print(f"  HR_247: {'yes' if hr['present'] else 'no'} rawRecords={hr['raw_records']} provenance={provenance_text(hr['provenance'])} epochs={hr['epochs']} avgHR={hr['avg_epoch_hr']}")
    print(f"  Skin temperature: {'yes' if skin['present'] else 'no'} rawRecords={skin['raw_records']} provenance={provenance_text(skin['provenance'])} samples={skin['samples']} avg={skin['avg']}")
    print(f"  Daily summary: {'yes' if daily['present'] else 'no'} records={daily['records']} provenance={provenance_text(daily['provenance'])}")
    print(f"  Activity samples: {'yes' if activity['present'] else 'no'} rawRecords={activity['raw_records']} provenance={provenance_text(activity['provenance'])} epochs={activity['epochs']} steps={activity['steps']}")
    print(f"  Journal: food={'yes' if journal['food_present'] else 'no'} calories={journal['calories']} review={'yes' if journal['review_present'] else 'no'} wakeMarker={'yes' if journal['wake_marker_present'] else 'no'}")
    if garmin.get("configured"):
        present = [key for key in ("hrv", "sleep", "stress", "body_battery", "heart_rate", "respiration", "spo2") if garmin.get(key)]
        print(f"  Garmin: {', '.join(present) or 'none'} HRV={garmin.get('hrv_last_night')} sleepMin={garmin.get('sleep_minutes')} SpO2={garmin.get('average_spo2')}")
    else:
        print("  Garmin: not configured")
    print(f"  Storage: {polar['latest_storage'].get('summary') if polar['latest_storage'].get('present') else 'unknown'}")
    print("  Supporting lane interpretation:")
    for lane, message in report["supporting_lane_interpretation"].items():
        print(f"    {lane}: {message}")
    if report["sync"]["domains"]:
        print("  Latest sync domain results covering this date:")
        for domain in sorted(report["sync"]["domains"]):
            print(f"    {domain}: {domain_line(report['sync']['domains'][domain])}")


def print_range_text(reports: list[dict[str, Any]]) -> None:
    for index, report in enumerate(reports):
        if index:
            print()
        print_text(report)
    if len(reports) > 1:
        summary = range_summary(reports)
        print()
        print("Range summary")
        print(f"  dates: {summary['date_count']}")
        for lane, dates in summary["missing_by_lane"].items():
            print(f"  {lane} missing: {', '.join(dates) if dates else 'none'}")
        print("  cloud/API backfilled lanes:")
        for lane, dates in summary["cloud_backfilled_by_lane"].items():
            print(f"    {lane}: {', '.join(dates) if dates else 'none'}")
        print("  interpretation counts:")
        for message, count in sorted(summary["interpretation_counts"].items()):
            print(f"    {count}x {message}")


def main() -> None:
    args = parse_args()
    reports = build_reports(args.health_db, requested_dates(args), args.garmin_db)
    if args.json:
        if len(reports) == 1 and not (args.start_date or args.end_date):
            print(json.dumps(reports[0], indent=2))
        else:
            print(json.dumps({"reports": reports, "range_summary": range_summary(reports)}, indent=2))
    else:
        print_range_text(reports)


if __name__ == "__main__":
    main()
