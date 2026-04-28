#!/usr/bin/env python3

from __future__ import annotations

import json
import math
import sqlite3
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any


def iso_utc(epoch_ms: int) -> str:
    return datetime.fromtimestamp(epoch_ms / 1000, tz=timezone.utc).isoformat()


def iso_local(epoch_ms: int, offset_ms: int) -> str:
    dt = datetime.fromtimestamp(epoch_ms / 1000, tz=timezone.utc) + timedelta(milliseconds=offset_ms)
    return dt.replace(tzinfo=None).isoformat()


def safe_json_load(raw: str | None) -> dict[str, Any]:
    if not raw:
        return {}
    try:
        loaded = json.loads(raw)
        return loaded if isinstance(loaded, dict) else {}
    except json.JSONDecodeError:
        return {}


def row_value(row: sqlite3.Row | None, key: str) -> Any:
    if row is None:
        return None
    try:
        return row[key]
    except (IndexError, KeyError):
        return None


def percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    if len(values) == 1:
        return values[0]
    rank = (len(values) - 1) * fraction
    lower = math.floor(rank)
    upper = math.ceil(rank)
    if lower == upper:
        return values[lower]
    weight = rank - lower
    return values[lower] * (1 - weight) + values[upper] * weight


def seconds_to_minutes(value: Any) -> float | None:
    if value is None:
        return None
    try:
        return round(float(value) / 60.0, 2)
    except (TypeError, ValueError):
        return None


def safe_float(value: Any) -> float | None:
    if value is None:
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def safe_int(value: Any) -> int | None:
    if value is None:
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def select_target_date(conn: sqlite3.Connection, source_date: str | None) -> str | None:
    if source_date:
        row = conn.execute(
            "SELECT calendar_date FROM sleep WHERE calendar_date = ?",
            (source_date,),
        ).fetchone()
        if row:
            return str(row["calendar_date"])
    row = conn.execute(
        "SELECT calendar_date FROM sleep WHERE raw_json IS NOT NULL ORDER BY calendar_date DESC LIMIT 1"
    ).fetchone()
    if row:
        return str(row["calendar_date"])
    return None


def build_hrv_series(payload: dict[str, Any]) -> tuple[list[dict[str, Any]], str | None, str | None]:
    samples = payload.get("hrvData") or []
    sleep_dto = payload.get("dailySleepDTO") or {}

    sleep_start_gmt = sleep_dto.get("sleepStartTimestampGMT")
    sleep_start_local = sleep_dto.get("sleepStartTimestampLocal")
    offset_ms = 0
    if sleep_start_gmt is not None and sleep_start_local is not None:
        try:
            offset_ms = int(sleep_start_local) - int(sleep_start_gmt)
        except (TypeError, ValueError):
            offset_ms = 0

    result: list[dict[str, Any]] = []
    for sample in samples:
        start_gmt = sample.get("startGMT")
        value = sample.get("value")
        if start_gmt is None or value is None:
            continue
        try:
            epoch_ms = int(start_gmt)
            hrv_value = float(value)
        except (TypeError, ValueError):
            continue
        result.append(
            {
                "timestamp_utc": iso_utc(epoch_ms),
                "timestamp_local": iso_local(epoch_ms, offset_ms),
                "value": hrv_value,
            }
        )

    start_utc = result[0]["timestamp_utc"] if result else None
    end_utc = result[-1]["timestamp_utc"] if result else None
    return result, start_utc, end_utc


def build_sleep_window(payload: dict[str, Any]) -> dict[str, Any] | None:
    sleep_dto = payload.get("dailySleepDTO") or {}
    start_gmt = sleep_dto.get("sleepStartTimestampGMT")
    end_gmt = sleep_dto.get("sleepEndTimestampGMT")
    start_local = sleep_dto.get("sleepStartTimestampLocal")
    end_local = sleep_dto.get("sleepEndTimestampLocal")
    if start_gmt is None or end_gmt is None:
        return None
    try:
        window = {
            "start_utc": iso_utc(int(start_gmt)),
            "end_utc": iso_utc(int(end_gmt)),
            "start_local": iso_local(int(start_gmt), int(start_local) - int(start_gmt)) if start_local is not None else None,
            "end_local": iso_local(int(end_gmt), int(end_local) - int(end_gmt)) if end_local is not None else None,
        }
        if window["start_local"] and window["end_local"]:
            window["label"] = f"{window['start_local']} -> {window['end_local']}"
        else:
            window["label"] = f"{window['start_utc']} -> {window['end_utc']}"
        return window
    except (TypeError, ValueError):
        return None


def parse_iso_loose(value: str | None) -> datetime | None:
    if not value:
        return None
    text = str(value)
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    if text.count(".") == 1 and "+" not in text and "-" not in text[10:]:
        head, frac = text.split(".", 1)
        if frac.isdigit():
            text = head
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def build_body_battery_context(body_battery_row: sqlite3.Row | None, sleep_window: dict[str, Any] | None) -> dict[str, Any]:
    if not body_battery_row:
        return {
            "charged": None,
            "drained": None,
            "highest": None,
            "lowest": None,
            "most_recent": None,
            "at_wake": None,
            "during_sleep": None,
        }

    payload = safe_json_load(row_value(body_battery_row, "bb_raw_json") or row_value(body_battery_row, "raw_json"))
    points = []
    for item in ((payload.get("bodyBattery") or {}).get("data") or []):
        if not isinstance(item, list) or len(item) < 2:
            continue
        timestamp = parse_iso_loose(item[0])
        level = safe_int(item[1])
        if timestamp is None or level is None:
            continue
        points.append((timestamp, level))

    if not points:
        return {
            "charged": safe_int(body_battery_row["charged"]),
            "drained": safe_int(row_value(body_battery_row, "drained")),
            "highest": safe_int(row_value(body_battery_row, "highest")),
            "lowest": safe_int(row_value(body_battery_row, "lowest")),
            "most_recent": safe_int(row_value(body_battery_row, "most_recent")),
            "at_wake": safe_int(row_value(body_battery_row, "at_wake")),
            "during_sleep": safe_int(row_value(body_battery_row, "during_sleep")),
        }

    start_level = points[0][1]
    end_level = points[-1][1]
    highest = max(level for _, level in points)
    lowest = min(level for _, level in points)
    wake_level = None
    sleep_delta = None
    if sleep_window and sleep_window.get("end_utc") and sleep_window.get("start_utc"):
        start_utc = parse_iso_loose(sleep_window["start_utc"])
        end_utc = parse_iso_loose(sleep_window["end_utc"])
        if start_utc and end_utc:
            before_end = [level for timestamp, level in points if timestamp <= end_utc]
            wake_level = before_end[-1] if before_end else None
            in_sleep = [level for timestamp, level in points if start_utc <= timestamp <= end_utc]
            if len(in_sleep) >= 2:
                sleep_delta = in_sleep[-1] - in_sleep[0]

    return {
        "charged": max(0, end_level - start_level),
        "drained": max(0, start_level - end_level),
        "highest": highest,
        "lowest": lowest,
        "most_recent": end_level,
        "at_wake": wake_level,
        "during_sleep": sleep_delta,
    }


def build_stress_context(stress_row: sqlite3.Row | None) -> dict[str, Any]:
    if not stress_row:
        return {
            "avg_stress": None,
            "max_stress": None,
            "qualifier": None,
        }
    payload = safe_json_load(row_value(stress_row, "st_raw_json") or row_value(stress_row, "raw_json"))
    values = []
    for item in payload.get("stressValuesArray") or payload.get("stress") or []:
        if isinstance(item, list) and len(item) >= 2:
            value = safe_int(item[1])
            if value is not None:
                values.append(value)
    avg_stress = safe_int(row_value(stress_row, "avg_stress"))
    if avg_stress is None:
        avg_stress = safe_int(payload.get("avgStressLevel")) or (round(mean(values)) if values else None)
    max_stress = safe_int(row_value(stress_row, "max_stress"))
    if max_stress is None and values:
        max_stress = max(values)
    qualifier_value = row_value(stress_row, "stress_qualifier")
    qualifier = str(qualifier_value) if qualifier_value is not None else None
    return {
        "avg_stress": avg_stress,
        "max_stress": max_stress,
        "qualifier": qualifier,
    }


def build_heart_rate_context(heart_rate_row: sqlite3.Row | None, sleep_window: dict[str, Any] | None) -> dict[str, Any]:
    if not heart_rate_row:
        return {
            "resting_hr": None,
            "avg_hr_24h": None,
            "min_hr_24h": None,
            "max_hr_24h": None,
            "avg_sleep_hr": None,
        }
    payload = safe_json_load(row_value(heart_rate_row, "hr_raw_json") or row_value(heart_rate_row, "raw_json"))
    values = []
    for item in payload.get("heartRateValues") or []:
        if isinstance(item, list) and len(item) >= 2:
            timestamp = item[0]
            hr = safe_float(item[1])
            if timestamp is not None and hr is not None:
                try:
                    values.append((datetime.fromtimestamp(int(timestamp) / 1000, tz=timezone.utc), hr))
                except (TypeError, ValueError, OSError):
                    continue
    avg_hr_24h = safe_float(row_value(heart_rate_row, "avg_hr"))
    min_hr_24h = safe_int(row_value(heart_rate_row, "min_hr"))
    max_hr_24h = safe_int(row_value(heart_rate_row, "max_hr"))
    if values:
        hr_only = [hr for _, hr in values]
        if avg_hr_24h is None:
            avg_hr_24h = round(mean(hr_only), 3)
        if min_hr_24h is None:
            min_hr_24h = int(min(hr_only))
        if max_hr_24h is None:
            max_hr_24h = int(max(hr_only))

    avg_sleep_hr = None
    if sleep_window and sleep_window.get("start_utc") and sleep_window.get("end_utc") and values:
        start_utc = parse_iso_loose(sleep_window["start_utc"])
        end_utc = parse_iso_loose(sleep_window["end_utc"])
        if start_utc and end_utc:
            sleep_values = [hr for timestamp, hr in values if start_utc <= timestamp <= end_utc]
            if sleep_values:
                avg_sleep_hr = round(mean(sleep_values), 3)

    return {
        "resting_hr": safe_int(row_value(heart_rate_row, "resting_hr")),
        "avg_hr_24h": avg_hr_24h,
        "min_hr_24h": min_hr_24h,
        "max_hr_24h": max_hr_24h,
        "avg_sleep_hr": avg_sleep_hr,
    }


def mean(values: list[float]) -> float | None:
    if not values:
        return None
    return sum(values) / len(values)


def median(values: list[float]) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    mid = len(ordered) // 2
    if len(ordered) % 2:
        return ordered[mid]
    return (ordered[mid - 1] + ordered[mid]) / 2


def build_nightly_metrics(
    sleep_row: sqlite3.Row | None,
    hrv_row: sqlite3.Row | None,
    heart_rate_row: sqlite3.Row | None,
    stress_row: sqlite3.Row | None,
    body_battery_row: sqlite3.Row | None,
) -> dict[str, Any]:
    sleep_payload = safe_json_load(sleep_row["raw_json"] if sleep_row else None)
    sleep_window = build_sleep_window(sleep_payload)
    hrv_series, _, _ = build_hrv_series(sleep_payload)
    series_values = [sample["value"] for sample in hrv_series]
    third = max(1, len(series_values) // 3) if series_values else 0
    hrv_first = mean(series_values[:third]) if third else None
    hrv_last = mean(series_values[-third:]) if third else None
    hrv_delta = (hrv_last - hrv_first) if hrv_first is not None and hrv_last is not None else None

    total_sleep_minutes = seconds_to_minutes(sleep_row["sleep_time_seconds"]) if sleep_row else None
    deep_sleep_minutes = seconds_to_minutes(sleep_row["deep_sleep_seconds"]) if sleep_row else None
    light_sleep_minutes = seconds_to_minutes(sleep_row["light_sleep_seconds"]) if sleep_row else None
    rem_sleep_minutes = seconds_to_minutes(sleep_row["rem_sleep_seconds"]) if sleep_row else None
    awake_minutes = seconds_to_minutes(sleep_row["awake_sleep_seconds"]) if sleep_row else None

    def pct(minutes: float | None) -> float | None:
        if minutes is None or total_sleep_minutes in (None, 0):
            return None
        return round(minutes / total_sleep_minutes, 3)

    heart_rate_context = build_heart_rate_context(heart_rate_row, sleep_window)
    stress_context = build_stress_context(stress_row)
    body_battery_context = build_body_battery_context(body_battery_row, sleep_window)

    return {
        "source_date": str(sleep_row["calendar_date"]) if sleep_row else (str(hrv_row["calendar_date"]) if hrv_row else None),
        "hrv_sample_count": len(series_values),
        "hrv_mean": round(mean(series_values), 3) if series_values else None,
        "hrv_min": min(series_values) if series_values else None,
        "hrv_max": max(series_values) if series_values else None,
        "hrv_first_third_mean": round(hrv_first, 3) if hrv_first is not None else None,
        "hrv_last_third_mean": round(hrv_last, 3) if hrv_last is not None else None,
        "hrv_delta": round(hrv_delta, 3) if hrv_delta is not None else None,
        "garmin_last_night_avg": safe_float(hrv_row["last_night_avg"]) if hrv_row else None,
        "garmin_weekly_avg": safe_float(hrv_row["weekly_avg"]) if hrv_row else None,
        "garmin_status": str(hrv_row["status"]) if hrv_row and hrv_row["status"] is not None else None,
        "total_sleep_minutes": total_sleep_minutes,
        "deep_sleep_minutes": deep_sleep_minutes,
        "light_sleep_minutes": light_sleep_minutes,
        "rem_sleep_minutes": rem_sleep_minutes,
        "awake_minutes": awake_minutes,
        "deep_sleep_pct": pct(deep_sleep_minutes),
        "rem_sleep_pct": pct(rem_sleep_minutes),
        "avg_sleep_hr": safe_float(sleep_row["average_hr_sleep"]) if sleep_row and sleep_row["average_hr_sleep"] is not None else heart_rate_context.get("avg_sleep_hr"),
        "avg_respiration": safe_float(sleep_row["average_respiration"]) if sleep_row else None,
        "average_spo2": safe_float(sleep_row["average_spo2"]) if sleep_row else None,
        "lowest_spo2": safe_float(sleep_row["lowest_spo2"]) if sleep_row else None,
        "resting_hr": heart_rate_context.get("resting_hr"),
        "avg_hr_24h": heart_rate_context.get("avg_hr_24h"),
        "min_hr_24h": heart_rate_context.get("min_hr_24h"),
        "max_hr_24h": heart_rate_context.get("max_hr_24h"),
        "avg_stress": stress_context.get("avg_stress"),
        "max_stress": stress_context.get("max_stress"),
        "stress_qualifier": stress_context.get("qualifier"),
        "body_battery_at_wake": body_battery_context.get("at_wake"),
        "body_battery_during_sleep": body_battery_context.get("during_sleep"),
        "body_battery_charged": body_battery_context.get("charged"),
        "body_battery_drained": body_battery_context.get("drained"),
    }


def build_baseline_summary(target_metrics: dict[str, Any], previous_metrics: list[dict[str, Any]]) -> dict[str, Any]:
    def series(key: str, window: int | None = None) -> list[float]:
        rows = previous_metrics[:window] if window is not None else previous_metrics
        return [float(row[key]) for row in rows if row.get(key) is not None]

    def summarize(key: str, current_value: float | None) -> dict[str, Any] | None:
        if current_value is None:
            return None
        windows: dict[str, Any] = {}
        for label, window in (("7d", 7), ("14d", 14), ("all_prior", None)):
            values = series(key, window)
            if not values:
                continue
            ordered = sorted(values)
            windows[label] = {
                "count": len(values),
                "mean": round(mean(values), 3),
                "median": round(median(values), 3),
                "min": round(min(values), 3),
                "max": round(max(values), 3),
                "delta_from_mean": round(current_value - mean(values), 3),
                "percentile_vs_prior": round((sum(1 for value in values if value <= current_value) / len(values)), 3),
                "p10": round(percentile(ordered, 0.10), 3),
                "p90": round(percentile(ordered, 0.90), 3),
            }
        return {
            "current": round(current_value, 3),
            "windows": windows,
        }

    return {
        "trusted_primary_signal": "overnight_hrv",
        "sleep_reliability_note": "Treat Garmin HRV as more reliable than Garmin sleep staging/timing when they disagree.",
        "available_prior_nights": len(previous_metrics),
        "current_vs_recent": {
            "hrv_mean": summarize("hrv_mean", target_metrics.get("hrv_mean")),
            "hrv_delta": summarize("hrv_delta", target_metrics.get("hrv_delta")),
            "garmin_last_night_avg": summarize("garmin_last_night_avg", target_metrics.get("garmin_last_night_avg")),
            "awake_minutes": summarize("awake_minutes", target_metrics.get("awake_minutes")),
            "deep_sleep_minutes": summarize("deep_sleep_minutes", target_metrics.get("deep_sleep_minutes")),
            "avg_sleep_hr": summarize("avg_sleep_hr", target_metrics.get("avg_sleep_hr")),
            "avg_respiration": summarize("avg_respiration", target_metrics.get("avg_respiration")),
        },
    }


def load_garmin_comparison_lane(db_path: str | None, source_date: str | None) -> dict[str, Any]:
    if not db_path:
        return {
            "available": False,
            "reason": "not_configured",
        }

    path = Path(db_path).expanduser()
    if not path.exists():
        return {
            "available": False,
            "reason": "db_not_found",
            "db_path": str(path),
        }

    conn = sqlite3.connect(path)
    conn.row_factory = sqlite3.Row
    try:
        target_date = select_target_date(conn, source_date)
        if not target_date:
            return {
                "available": False,
                "reason": "no_sleep_rows",
                "db_path": str(path),
            }

        sleep_row = conn.execute("SELECT * FROM sleep WHERE calendar_date = ?", (target_date,)).fetchone()
        hrv_row = conn.execute("SELECT * FROM hrv WHERE calendar_date = ?", (target_date,)).fetchone()
        training_row = conn.execute("SELECT * FROM training_readiness WHERE calendar_date = ?", (target_date,)).fetchone()
        heart_rate_row = conn.execute("SELECT * FROM heart_rate WHERE calendar_date = ?", (target_date,)).fetchone()
        stress_row = conn.execute("SELECT * FROM stress WHERE calendar_date = ?", (target_date,)).fetchone()
        body_battery_row = conn.execute("SELECT * FROM body_battery WHERE calendar_date = ?", (target_date,)).fetchone()

        historical_rows = conn.execute(
            """
            SELECT
                s.calendar_date AS calendar_date,
                s.raw_json AS raw_json,
                s.sleep_time_seconds,
                s.nap_time_seconds,
                s.deep_sleep_seconds,
                s.light_sleep_seconds,
                s.rem_sleep_seconds,
                s.awake_sleep_seconds,
                s.unmeasurable_sleep_seconds,
                s.awake_count,
                s.average_spo2,
                s.lowest_spo2,
                s.average_hr_sleep,
                s.average_respiration,
                s.lowest_respiration,
                s.highest_respiration,
                s.avg_sleep_stress,
                h.last_night,
                h.last_night_avg,
                h.last_night_5min_high,
                h.weekly_avg,
                h.status,
                h.baseline_low,
                h.baseline_upper,
                hr.resting_hr,
                hr.min_hr,
                hr.max_hr,
                hr.avg_hr,
                hr.raw_json AS hr_raw_json,
                st.avg_stress,
                st.max_stress,
                st.stress_qualifier,
                st.raw_json AS st_raw_json,
                bb.charged,
                bb.drained,
                bb.highest,
                bb.lowest,
                bb.most_recent,
                bb.at_wake,
                bb.during_sleep,
                bb.raw_json AS bb_raw_json
            FROM sleep s
            LEFT JOIN hrv h ON h.calendar_date = s.calendar_date
            LEFT JOIN heart_rate hr ON hr.calendar_date = s.calendar_date
            LEFT JOIN stress st ON st.calendar_date = s.calendar_date
            LEFT JOIN body_battery bb ON bb.calendar_date = s.calendar_date
            ORDER BY s.calendar_date DESC
            LIMIT 30
            """
        ).fetchall()

        sleep_payload = safe_json_load(sleep_row["raw_json"] if sleep_row else None)
        hrv_series, series_start_utc, series_end_utc = build_hrv_series(sleep_payload)
        series_values = [sample["value"] for sample in hrv_series]
        sleep_window = build_sleep_window(sleep_payload)
        target_metrics = build_nightly_metrics(sleep_row, hrv_row, heart_rate_row, stress_row, body_battery_row)

        historical_nights = []
        for row in historical_rows:
            historical_nights.append(
                build_nightly_metrics(
                    row,
                    row,
                    row,
                    row,
                    row,
                )
            )
        previous_nights = [night for night in historical_nights if night.get("source_date") != target_date]
        baseline_summary = build_baseline_summary(target_metrics, previous_nights)

        return {
            "available": bool(sleep_row or hrv_row or training_row),
            "source_date": target_date,
            "db_path": str(path),
            "signal_priority_note": "Prefer Garmin overnight HRV and trend shape over Garmin sleep staging/timing when they disagree.",
            "sleep_window": sleep_window,
            "hrv_series": {
                "sample_count": len(hrv_series),
                "start_utc": series_start_utc,
                "end_utc": series_end_utc,
                "samples": hrv_series,
            },
            "hrv_summary": {
                "avg_hrv": round(sum(series_values) / len(series_values), 3) if series_values else None,
                "min_hrv": min(series_values) if series_values else None,
                "max_hrv": max(series_values) if series_values else None,
                "p05_hrv": round(percentile(sorted(series_values), 0.05), 3) if series_values else None,
                "p95_hrv": round(percentile(sorted(series_values), 0.95), 3) if series_values else None,
                "avg_5min_hrv": safe_float(sleep_payload.get("avgOvernightHrv")),
                "max_5min_hrv": safe_float(
                    sleep_payload.get("highestOvernightHrv")
                    if sleep_payload.get("highestOvernightHrv") is not None
                    else sleep_payload.get("lastNight5MinHigh")
                ),
                "first_third_mean": target_metrics.get("hrv_first_third_mean"),
                "last_third_mean": target_metrics.get("hrv_last_third_mean"),
                "overnight_delta": target_metrics.get("hrv_delta"),
            },
            "garmin_hrv_summary": {
                "last_night": float(hrv_row["last_night"]) if hrv_row and hrv_row["last_night"] is not None else None,
                "last_night_avg": float(hrv_row["last_night_avg"]) if hrv_row and hrv_row["last_night_avg"] is not None else None,
                "last_night_5min_high": float(hrv_row["last_night_5min_high"]) if hrv_row and hrv_row["last_night_5min_high"] is not None else None,
                "weekly_avg": float(hrv_row["weekly_avg"]) if hrv_row and hrv_row["weekly_avg"] is not None else None,
                "status": str(hrv_row["status"]) if hrv_row and hrv_row["status"] is not None else None,
                "baseline_low": float(hrv_row["baseline_low"]) if hrv_row and hrv_row["baseline_low"] is not None else None,
                "baseline_upper": float(hrv_row["baseline_upper"]) if hrv_row and hrv_row["baseline_upper"] is not None else None,
                "start_timestamp": str(hrv_row["start_timestamp"]) if hrv_row and hrv_row["start_timestamp"] is not None else None,
                "end_timestamp": str(hrv_row["end_timestamp"]) if hrv_row and hrv_row["end_timestamp"] is not None else None,
            },
            "sleep_summary": {
                "total_sleep_minutes": seconds_to_minutes(sleep_row["sleep_time_seconds"]) if sleep_row else None,
                "nap_minutes": seconds_to_minutes(sleep_row["nap_time_seconds"]) if sleep_row else None,
                "deep_sleep_minutes": seconds_to_minutes(sleep_row["deep_sleep_seconds"]) if sleep_row else None,
                "light_sleep_minutes": seconds_to_minutes(sleep_row["light_sleep_seconds"]) if sleep_row else None,
                "rem_sleep_minutes": seconds_to_minutes(sleep_row["rem_sleep_seconds"]) if sleep_row else None,
                "awake_minutes": seconds_to_minutes(sleep_row["awake_sleep_seconds"]) if sleep_row else None,
                "unmeasurable_minutes": seconds_to_minutes(sleep_row["unmeasurable_sleep_seconds"]) if sleep_row else None,
                "awake_count": int(sleep_row["awake_count"]) if sleep_row and sleep_row["awake_count"] is not None else None,
                "average_spo2": float(sleep_row["average_spo2"]) if sleep_row and sleep_row["average_spo2"] is not None else None,
                "lowest_spo2": float(sleep_row["lowest_spo2"]) if sleep_row and sleep_row["lowest_spo2"] is not None else None,
                "average_sleep_hr": float(sleep_row["average_hr_sleep"]) if sleep_row and sleep_row["average_hr_sleep"] is not None else None,
                "average_respiration": float(sleep_row["average_respiration"]) if sleep_row and sleep_row["average_respiration"] is not None else None,
                "lowest_respiration": float(sleep_row["lowest_respiration"]) if sleep_row and sleep_row["lowest_respiration"] is not None else None,
                "highest_respiration": float(sleep_row["highest_respiration"]) if sleep_row and sleep_row["highest_respiration"] is not None else None,
                "average_sleep_stress": float(sleep_row["avg_sleep_stress"]) if sleep_row and sleep_row["avg_sleep_stress"] is not None else None,
                "sleep_score_feedback": str(sleep_row["sleep_score_feedback"]) if sleep_row and sleep_row["sleep_score_feedback"] is not None else None,
                "sleep_score_insight": str(sleep_row["sleep_score_insight"]) if sleep_row and sleep_row["sleep_score_insight"] is not None else None,
            },
            "comparison_context_summary": {
                "heart_rate": {
                    "resting_hr": target_metrics.get("resting_hr"),
                    "avg_hr_24h": target_metrics.get("avg_hr_24h"),
                    "min_hr_24h": target_metrics.get("min_hr_24h"),
                    "max_hr_24h": target_metrics.get("max_hr_24h"),
                    "avg_sleep_hr": target_metrics.get("avg_sleep_hr"),
                },
                "stress": {
                    "avg_stress": target_metrics.get("avg_stress"),
                    "max_stress": target_metrics.get("max_stress"),
                    "qualifier": target_metrics.get("stress_qualifier"),
                },
                "body_battery": {
                    "charged": target_metrics.get("body_battery_charged"),
                    "drained": target_metrics.get("body_battery_drained"),
                    "highest": build_body_battery_context(body_battery_row, sleep_window).get("highest"),
                    "lowest": build_body_battery_context(body_battery_row, sleep_window).get("lowest"),
                    "most_recent": build_body_battery_context(body_battery_row, sleep_window).get("most_recent"),
                    "at_wake": target_metrics.get("body_battery_at_wake"),
                    "during_sleep": target_metrics.get("body_battery_during_sleep"),
                },
            },
            "training_readiness_summary": {
                "score": float(training_row["score"]) if training_row and training_row["score"] is not None else None,
                "level": str(training_row["level"]) if training_row and training_row["level"] is not None else None,
                "feedback_short": str(training_row["feedback_short"]) if training_row and training_row["feedback_short"] is not None else None,
                "feedback_long": str(training_row["feedback_long"]) if training_row and training_row["feedback_long"] is not None else None,
                "recovery_time": float(training_row["recovery_time"]) if training_row and training_row["recovery_time"] is not None else None,
                "hrv_weekly_average": float(training_row["hrv_weekly_average"]) if training_row and training_row["hrv_weekly_average"] is not None else None,
            },
            "garmin_baseline_summary": baseline_summary,
            "historical_nights": historical_nights,
            "sync_metadata": {
                "source": "garmin.db",
                "db_path": str(path),
                "matched_source_date": target_date == source_date if source_date else True,
            },
        }
    finally:
        conn.close()
