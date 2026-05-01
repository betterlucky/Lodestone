#!/usr/bin/env python3

from __future__ import annotations

from datetime import date
from math import sqrt
from statistics import mean, median
from typing import Any

import morning_summary as ms


def round_number(value: Any, digits: int = 3):
    if isinstance(value, (int, float)):
        return round(value, digits)
    return value


def parse_local_date(value: str | None) -> date | None:
    if not value:
        return None
    try:
        return date.fromisoformat(value)
    except ValueError:
        return None


def safe_mean(values: list[float]) -> float | None:
    return mean(values) if values else None


def rmssd(values: list[float]) -> float | None:
    if len(values) < 2:
        return None
    diffs = [values[index] - values[index - 1] for index in range(1, len(values))]
    return sqrt(mean([diff * diff for diff in diffs])) if diffs else None


def latest_recharge_payload(rows: list[dict[str, Any]], source_date: str) -> dict[str, Any]:
    row = ms.latest_row(ms.latest_cluster_rows(ms.rows_for_date(rows, "nightly_recharge", source_date)), "nightly_recharge")
    return ms.parse_payload(row) if row else {}


def latest_sleep_payload(rows: list[dict[str, Any]], source_date: str) -> dict[str, Any]:
    for row in ms.latest_cluster_rows(ms.rows_for_date(rows, "sleep", source_date)):
        payload = ms.parse_payload(row)
        result = payload.get("result") or {}
        if (result.get("sleepResultDate") or row.get("sourceDate")) == source_date:
            return payload
    return {}


def sleep_window_for_date(rows: list[dict[str, Any]], source_date: str) -> dict[str, Any] | None:
    payload = latest_sleep_payload(rows, source_date)
    result = payload.get("result") or {}
    start = result.get("sleepStartTime")
    end = result.get("sleepEndTime")
    if not start or not end:
        return None
    return {
        "start": start,
        "end": end,
        "label": ms.format_sleep_window(source_date, start, end),
        "valid": True,
    }


def is_after(timestamp_value: str | None, boundary_value: str | None) -> bool:
    ts = ms.parse_iso_datetime(timestamp_value)
    boundary = ms.parse_iso_datetime(boundary_value)
    if not ts or not boundary:
        return False
    if ts.tzinfo is None and boundary.tzinfo is not None:
        ts = ts.replace(tzinfo=boundary.tzinfo)
    elif ts.tzinfo is not None and boundary.tzinfo is None:
        boundary = boundary.replace(tzinfo=ts.tzinfo)
    return ts > boundary


def is_in_window(timestamp_value: str | None, start_value: str | None, end_value: str | None) -> bool:
    ts = ms.parse_iso_datetime(timestamp_value)
    start = ms.parse_iso_datetime(start_value)
    end = ms.parse_iso_datetime(end_value)
    if not ts or not start or not end:
        return False
    if ts.tzinfo is None and start.tzinfo is not None:
        ts = ts.replace(tzinfo=start.tzinfo)
    elif ts.tzinfo is not None and start.tzinfo is None:
        start = start.replace(tzinfo=ts.tzinfo)
    if end.tzinfo is None and ts.tzinfo is not None:
        end = end.replace(tzinfo=ts.tzinfo)
    elif end.tzinfo is not None and ts.tzinfo is None:
        ts = ts.replace(tzinfo=end.tzinfo)
    return start <= ts <= end


def sample_time_for_date(source_date: str, timestamp_value: str | None) -> str | None:
    if not timestamp_value:
        return None
    if "T" in timestamp_value:
        return timestamp_value
    return f"{source_date}T{timestamp_value}"


def hours_between(first_value: str | None, last_value: str | None) -> float | None:
    first = ms.parse_iso_datetime(first_value)
    last = ms.parse_iso_datetime(last_value)
    if not first or not last:
        return None
    if first.tzinfo is None and last.tzinfo is not None:
        first = first.replace(tzinfo=last.tzinfo)
    elif first.tzinfo is not None and last.tzinfo is None:
        last = last.replace(tzinfo=first.tzinfo)
    if last < first:
        return None
    return (last - first).total_seconds() / 3600.0


def score_from_thresholds(value: float | None, bands: tuple[float, float, float], inverse: bool = False) -> float | None:
    if value is None:
        return None
    low, medium, high = bands
    if inverse:
        if value >= high:
            return 0.0
        if value >= medium:
            return 1.0
        if value >= low:
            return 2.0
        return 3.0
    if value <= low:
        return 0.0
    if value <= medium:
        return 1.0
    if value <= high:
        return 2.0
    return 3.0


def weighted_average(scores: dict[str, float | None], weights: dict[str, float]) -> float | None:
    available = {key: value for key, value in scores.items() if value is not None}
    if not available:
        return None
    total_weight = sum(weights[key] for key in available)
    return sum(available[key] * weights[key] for key in available) / total_weight


def build_raw_sleep_and_context_lane(rows: list[dict[str, Any]], source_date: str) -> dict[str, Any]:
    sleep = ms.aggregate_sleep(rows, source_date) or {}
    skin = ms.aggregate_skin_temperature(rows, source_date) or {}
    sleep_payload = latest_sleep_payload(rows, source_date)
    sleep_result = sleep_payload.get("result") or {}
    source = parse_local_date(source_date)
    previous_date = source.fromordinal(source.toordinal() - 1).isoformat() if source else None
    previous_daily = ms.aggregate_daily_summary(rows, previous_date) or {}
    previous_activity = ms.aggregate_activity(rows, previous_date) or {}
    window = sleep_window_for_date(rows, source_date)

    hr_values: list[float] = []
    hr_session_count = 0
    if window:
        for row in ms.latest_cluster_rows(ms.rows_for_date(rows, "hr247", source_date)):
            payload = ms.parse_payload(row)
            for session in payload.get("samples") or []:
                if is_in_window(session.get("startTime"), window["start"], window["end"]):
                    hr_values.extend(float(value) for value in (session.get("hrSamples") or []))
                    hr_session_count += 1

    return {
        "sleep_window": window,
        "sleep_structure": {
            "total_sleep_minutes": sleep.get("duration_minutes"),
            "sleep_goal_minutes": sleep_result.get("sleepGoalMinutes"),
            "wake_minutes": (sleep.get("phase_minutes") or {}).get("WAKE"),
            "cycle_count": sleep.get("cycle_count"),
            "phase_minutes": sleep.get("phase_minutes"),
            "sleep_start": sleep.get("start"),
            "sleep_end": sleep.get("end"),
        },
        "overnight_hr_context": {
            "session_count": hr_session_count,
            "avg_hr_bpm": round_number(safe_mean(hr_values), 2),
            "min_hr_bpm": round_number(min(hr_values), 2) if hr_values else None,
            "max_hr_bpm": round_number(max(hr_values), 2) if hr_values else None,
        },
        "skin_temperature": {
            "avg_celsius": round_number(skin.get("avg_temperature"), 3),
            "min_celsius": round_number(skin.get("min_temperature"), 3),
            "max_celsius": round_number(skin.get("max_temperature"), 3),
            "range_celsius": round_number(
                (skin.get("max_temperature") - skin.get("min_temperature"))
                if skin.get("max_temperature") is not None and skin.get("min_temperature") is not None
                else None,
                3,
            ),
            "sample_count": skin.get("sample_count"),
        },
        "previous_day_activity_context": {
            "source_date": previous_date,
            "steps": previous_daily.get("steps"),
            "distance_m": previous_daily.get("activityDistance"),
            "activity_calories": previous_daily.get("activityCalories"),
            "sedentary_minutes": previous_daily.get("sedentaryMinutes"),
            "light_activity_minutes": previous_daily.get("lightActivityMinutes"),
            "recorded_steps": previous_activity.get("totalRecordedSteps"),
            "avg_met": round_number(previous_activity.get("avgMet"), 3),
            "met_sample_count": previous_activity.get("metSampleCount"),
        },
    }


def build_semi_derived_overnight_autonomic_lane(rows: list[dict[str, Any]], source_date: str) -> dict[str, Any]:
    summary = ms.aggregate_nightly_recharge(rows, source_date) or {}
    payload = latest_recharge_payload(rows, source_date)
    return {
        "available": bool(summary),
        "mean_nightly_recovery_rri": summary.get("mean_rri"),
        "mean_nightly_recovery_rmssd": summary.get("mean_rmssd"),
        "mean_nightly_recovery_respiration_interval": summary.get("mean_respiration_interval"),
        "baseline_ready": summary.get("baseline_ready"),
        "ans_available": summary.get("ans_available"),
        "recovery_available": summary.get("recovery_available"),
        "ans_status": payload.get("ansStatus"),
        "recovery_indicator": payload.get("recoveryIndicator"),
        "recovery_indicator_sub_level": payload.get("recoveryIndicatorSubLevel"),
    }


def build_raw_daytime_autonomic_lane(rows: list[dict[str, Any]], source_date: str, sleep_window: dict[str, Any] | None) -> dict[str, Any]:
    if not sleep_window:
        return {
            "available": False,
            "post_wake_ppi_batch_count": 0,
            "post_wake_hr_session_count": 0,
        }

    ppi_values: list[float] = []
    ppi_errors: list[float] = []
    movement_count = 0
    skin_contact_count = 0
    online_count = 0
    ppi_batch_count = 0
    ppi_start_times: list[str] = []
    for row in ms.unique_rows(ms.rows_for_date(rows, "ppi247", source_date)):
        payload = ms.parse_payload(row)
        samples = payload.get("samples") or {}
        start_time = sample_time_for_date(source_date, samples.get("startTime"))
        if is_after(start_time, sleep_window["end"]):
            ppi_batch_count += 1
            ppi_start_times.append(start_time)
            ppi_values.extend(float(value) for value in (samples.get("ppiValueList") or []))
            ppi_errors.extend(float(value) for value in (samples.get("ppiErrorEstimateList") or []))
            statuses = samples.get("statusList") or []
            movement_count += sum(1 for item in statuses if item.get("movement") != "MOVING_NOT_DETECTED")
            skin_contact_count += sum(1 for item in statuses if item.get("skinContact") == "SKIN_CONTACT_DETECTED")
            online_count += sum(1 for item in statuses if item.get("intervalStatus") == "INTERVAL_IS_ONLINE")

    hr_values: list[float] = []
    hr_start_times: list[str] = []
    hr_session_count = 0
    for row in ms.latest_cluster_rows(ms.rows_for_date(rows, "hr247", source_date)):
        payload = ms.parse_payload(row)
        for session in payload.get("samples") or []:
            if is_after(session.get("startTime"), sleep_window["end"]):
                hr_session_count += 1
                hr_start_times.append(session.get("startTime"))
                hr_values.extend(float(value) for value in (session.get("hrSamples") or []))

    interval_count = len(ppi_values)
    return {
        "available": bool(ppi_batch_count or hr_session_count),
        "post_wake_ppi_batch_count": ppi_batch_count,
        "post_wake_ppi_start_times": ppi_start_times,
        "post_wake_interval_count": interval_count,
        "post_wake_avg_ppi_ms": round_number(safe_mean(ppi_values), 2),
        "post_wake_avg_error_estimate": round_number(safe_mean(ppi_errors), 2),
        "post_wake_skin_contact_ratio": round_number((skin_contact_count / interval_count) if interval_count else None, 4),
        "post_wake_movement_ratio": round_number((movement_count / interval_count) if interval_count else None, 4),
        "post_wake_online_ratio": round_number((online_count / interval_count) if interval_count else None, 4),
        "post_wake_hr_session_count": hr_session_count,
        "post_wake_hr_start_times": hr_start_times,
        "post_wake_avg_hr_bpm": round_number(safe_mean(hr_values), 2),
        "post_wake_min_hr_bpm": round_number(min(hr_values), 2) if hr_values else None,
        "post_wake_max_hr_bpm": round_number(max(hr_values), 2) if hr_values else None,
    }


def build_raw_overnight_ppi_lane(rows: list[dict[str, Any]], source_date: str, sleep_window: dict[str, Any] | None) -> dict[str, Any]:
    if not sleep_window:
        return {
            "available": False,
            "batch_count": 0,
            "interval_count": 0,
        }

    ppi_values: list[float] = []
    error_values: list[float] = []
    movement_count = 0
    skin_contact_count = 0
    online_count = 0
    batch_count = 0
    start_times: list[str] = []
    per_batch_rmssd: list[float] = []

    for row in ms.unique_rows(ms.rows_for_date(rows, "ppi247", source_date)):
        payload = ms.parse_payload(row)
        samples = payload.get("samples") or {}
        start_time = sample_time_for_date(source_date, samples.get("startTime"))
        if not is_in_window(start_time, sleep_window["start"], sleep_window["end"]):
            continue
        values = [float(value) for value in (samples.get("ppiValueList") or [])]
        if not values:
            continue
        batch_count += 1
        start_times.append(start_time)
        ppi_values.extend(values)
        error_values.extend(float(value) for value in (samples.get("ppiErrorEstimateList") or []))
        statuses = samples.get("statusList") or []
        movement_count += sum(1 for item in statuses if item.get("movement") != "MOVING_NOT_DETECTED")
        skin_contact_count += sum(1 for item in statuses if item.get("skinContact") == "SKIN_CONTACT_DETECTED")
        online_count += sum(1 for item in statuses if item.get("intervalStatus") == "INTERVAL_IS_ONLINE")
        batch_rmssd = rmssd(values)
        if batch_rmssd is not None:
            per_batch_rmssd.append(batch_rmssd)

    interval_count = len(ppi_values)
    observed_hours = hours_between(min(start_times) if start_times else None, max(start_times) if start_times else None)
    return {
        "available": interval_count > 0,
        "batch_count": batch_count,
        "start_times": start_times,
        "interval_count": interval_count,
        "avg_ppi_ms": round_number(safe_mean(ppi_values), 2),
        "rmssd_ms": round_number(rmssd(ppi_values), 2),
        "mean_batch_rmssd_ms": round_number(safe_mean(per_batch_rmssd), 2),
        "avg_error_estimate": round_number(safe_mean(error_values), 2),
        "skin_contact_ratio": round_number((skin_contact_count / interval_count) if interval_count else None, 4),
        "movement_ratio": round_number((movement_count / interval_count) if interval_count else None, 4),
        "online_ratio": round_number((online_count / interval_count) if interval_count else None, 4),
        "coverage_hours_estimate": round_number(observed_hours, 2),
    }


def compare_to_baseline(current: float | None, baseline_values: list[float]) -> dict[str, Any] | None:
    if current is None or not baseline_values:
        return None
    baseline_mean = safe_mean(baseline_values)
    baseline_median = median(baseline_values) if baseline_values else None
    baseline_range = (max(baseline_values) - min(baseline_values)) if len(baseline_values) > 1 else 0.0
    return {
        "current": round_number(current, 3),
        "mean": round_number(baseline_mean, 3),
        "median": round_number(baseline_median, 3) if baseline_median is not None else None,
        "delta_from_mean": round_number(current - baseline_mean, 3) if baseline_mean is not None else None,
        "delta_pct_from_mean": round_number(((current - baseline_mean) / baseline_mean) if baseline_mean else None, 4),
        "range": round_number(baseline_range, 3),
    }


def build_night_lanes(rows: list[dict[str, Any]], source_date: str) -> dict[str, Any]:
    raw_sleep = build_raw_sleep_and_context_lane(rows, source_date)
    nightly = build_semi_derived_overnight_autonomic_lane(rows, source_date)
    daytime = build_raw_daytime_autonomic_lane(rows, source_date, raw_sleep.get("sleep_window"))
    raw_overnight_ppi = build_raw_overnight_ppi_lane(rows, source_date, raw_sleep.get("sleep_window"))
    overnight_hr = raw_sleep.get("overnight_hr_context") or {}
    overnight_raw_ppi_available = raw_overnight_ppi.get("available") is True
    overnight_autonomic_source = "nightly_recharge_summary" if nightly.get("available") else "none"
    if overnight_raw_ppi_available:
        overnight_autonomic_source = "raw_overnight_ppi_plus_nightly_recharge_summary" if nightly.get("available") else "raw_overnight_ppi"
    return {
        "raw_sleep_and_context_lane": raw_sleep,
        "semi_derived_overnight_autonomic_lane": nightly,
        "raw_overnight_ppi_lane": raw_overnight_ppi,
        "raw_daytime_autonomic_lane": daytime,
        "overnight_raw_ppi_available": overnight_raw_ppi_available,
        "overnight_autonomic_source": overnight_autonomic_source,
        "overnight_raw_hr_available": overnight_hr.get("session_count", 0) > 0,
    }


def build_personal_baseline_features(rows: list[dict[str, Any]], target_source_date: str) -> dict[str, Any]:
    target_date = parse_local_date(target_source_date)
    if not target_date:
        return {}

    seen_dates: set[str] = set()
    prior_dates: list[str] = []
    for row in rows:
        if row.get("domain") != "sleep":
            continue
        payload = ms.parse_payload(row)
        result = payload.get("result") or {}
        source_date = result.get("sleepResultDate") or row.get("sourceDate")
        if not source_date or source_date in seen_dates:
            continue
        parsed_date = parse_local_date(source_date)
        if not parsed_date or parsed_date >= target_date:
            continue
        seen_dates.add(source_date)
        prior_dates.append(source_date)

    prior_dates.sort()
    current_lanes = build_night_lanes(rows, target_source_date)

    def in_window(day_count: int) -> list[str]:
        result: list[str] = []
        for source_date in prior_dates:
            parsed = parse_local_date(source_date)
            if parsed and 0 < (target_date - parsed).days <= day_count:
                result.append(source_date)
        return result

    def collect_metric(samples: list[str], extractor) -> list[float]:
        values: list[float] = []
        for source_date in samples:
            lane_bundle = build_night_lanes(rows, source_date)
            value = extractor(lane_bundle)
            if isinstance(value, (int, float)):
                values.append(float(value))
        return values

    def block(day_count: int, min_required: int) -> dict[str, Any] | None:
        sample_dates = in_window(day_count)
        if len(sample_dates) < min_required:
            return None

        metrics = {
            "total_sleep_minutes": compare_to_baseline(
                (current_lanes["raw_sleep_and_context_lane"]["sleep_structure"] or {}).get("total_sleep_minutes"),
                collect_metric(sample_dates, lambda lanes: (lanes["raw_sleep_and_context_lane"]["sleep_structure"] or {}).get("total_sleep_minutes")),
            ),
            "wake_minutes": compare_to_baseline(
                (current_lanes["raw_sleep_and_context_lane"]["sleep_structure"] or {}).get("wake_minutes"),
                collect_metric(sample_dates, lambda lanes: (lanes["raw_sleep_and_context_lane"]["sleep_structure"] or {}).get("wake_minutes")),
            ),
            "skin_temperature_avg_c": compare_to_baseline(
                (current_lanes["raw_sleep_and_context_lane"]["skin_temperature"] or {}).get("avg_celsius"),
                collect_metric(sample_dates, lambda lanes: (lanes["raw_sleep_and_context_lane"]["skin_temperature"] or {}).get("avg_celsius")),
            ),
            "nightly_rmssd": compare_to_baseline(
                current_lanes["semi_derived_overnight_autonomic_lane"].get("mean_nightly_recovery_rmssd"),
                collect_metric(sample_dates, lambda lanes: lanes["semi_derived_overnight_autonomic_lane"].get("mean_nightly_recovery_rmssd")),
            ),
            "nightly_rri": compare_to_baseline(
                current_lanes["semi_derived_overnight_autonomic_lane"].get("mean_nightly_recovery_rri"),
                collect_metric(sample_dates, lambda lanes: lanes["semi_derived_overnight_autonomic_lane"].get("mean_nightly_recovery_rri")),
            ),
            "nightly_respiration_interval": compare_to_baseline(
                current_lanes["semi_derived_overnight_autonomic_lane"].get("mean_nightly_recovery_respiration_interval"),
                collect_metric(sample_dates, lambda lanes: lanes["semi_derived_overnight_autonomic_lane"].get("mean_nightly_recovery_respiration_interval")),
            ),
        }

        worse_count = 0
        for metric_name, metric in metrics.items():
            if not metric:
                continue
            delta = metric.get("delta_from_mean")
            delta_pct = metric.get("delta_pct_from_mean")
            if metric_name == "nightly_rmssd" and isinstance(delta_pct, (int, float)) and delta_pct <= -0.15:
                worse_count += 1
            elif metric_name in {"wake_minutes", "skin_temperature_avg_c"} and isinstance(delta, (int, float)) and delta > 0:
                worse_count += 1

        return {
            "available": True,
            "night_count": len(sample_dates),
            "metrics": {key: value for key, value in metrics.items() if value is not None},
            "worse_than_recent_baseline_count": worse_count,
        }

    return {
        "baseline_3_day": block(3, 2),
        "baseline_7_day": block(7, 4),
    }


def build_sleep_subscore(raw_lane: dict[str, Any], reasons: list[tuple[float, str]]) -> float | None:
    sleep = raw_lane.get("sleep_structure") or {}
    duration = sleep.get("total_sleep_minutes")
    wake_minutes = sleep.get("wake_minutes")
    cycle_count = sleep.get("cycle_count")
    duration_score = None
    if duration is not None:
        duration_score = 0.0 if 420 <= duration <= 660 else 1.0 if 390 <= duration <= 720 else 2.0 if 360 <= duration <= 780 else 3.0
        if duration_score >= 2.0:
            reasons.append((duration_score, f"Sleep duration looked atypical at {ms.format_minutes(duration)}."))
    wake_score = score_from_thresholds(wake_minutes, (30, 60, 90))
    if wake_score is not None and wake_score >= 2.0:
        reasons.append((wake_score, f"Wake time during sleep was elevated at {ms.format_minutes(wake_minutes)}."))
    cycle_score = 0.0 if cycle_count and 4 <= cycle_count <= 7 else 1.5 if cycle_count is not None else None
    components = [value for value in [duration_score, wake_score, cycle_score] if value is not None]
    return round_number(safe_mean(components), 3) if components else None


def build_nightly_autonomic_subscore(
    nightly_lane: dict[str, Any],
    raw_overnight_ppi_lane: dict[str, Any],
    baseline_features: dict[str, Any],
    reasons: list[tuple[float, str]],
) -> float | None:
    rmssd = nightly_lane.get("mean_nightly_recovery_rmssd")
    rri = nightly_lane.get("mean_nightly_recovery_rri")
    respiration = nightly_lane.get("mean_nightly_recovery_respiration_interval")
    raw_ppi_rmssd = raw_overnight_ppi_lane.get("mean_batch_rmssd_ms") or raw_overnight_ppi_lane.get("rmssd_ms")
    if rmssd is None and rri is None and respiration is None and raw_ppi_rmssd is None:
        return None

    baseline_metrics = []
    for block_name in ("baseline_3_day", "baseline_7_day"):
        block = baseline_features.get(block_name) or {}
        baseline_metrics.append(block.get("metrics") or {})

    def metric_delta(metric_name: str) -> float | None:
        for metrics in reversed(baseline_metrics):
            metric = metrics.get(metric_name) or {}
            value = metric.get("delta_pct_from_mean")
            if isinstance(value, (int, float)):
                return float(value)
        return None

    rmssd_score = None
    rmssd_delta = metric_delta("nightly_rmssd")
    if raw_ppi_rmssd is not None:
        rmssd_score = score_from_thresholds(raw_ppi_rmssd, (30, 45, 60), inverse=True)
        if raw_overnight_ppi_lane.get("coverage_hours_estimate", 0) < 3:
            reasons.append((1.0, "Raw overnight PPI was present, but coverage was short enough to treat it cautiously."))
    elif rmssd_delta is not None:
        rmssd_score = 0.0 if rmssd_delta >= -0.05 else 1.0 if rmssd_delta >= -0.15 else 2.0 if rmssd_delta >= -0.25 else 3.0
    else:
        rmssd_score = score_from_thresholds(rmssd, (30, 45, 60), inverse=True)
    if rmssd_score is not None and rmssd_score >= 2.0:
        source_label = "raw overnight PPI RMSSD" if raw_ppi_rmssd is not None else "nightly recovery RMSSD"
        value = raw_ppi_rmssd if raw_ppi_rmssd is not None else rmssd
        reasons.append((rmssd_score, f"{source_label.capitalize()} looked subdued at {round_number(value, 1)} ms."))

    rri_score = None
    if rri is not None:
        rri_delta = metric_delta("nightly_rri")
        if rri_delta is not None:
            rri_score = 0.0 if rri_delta >= -0.03 else 1.0 if rri_delta >= -0.08 else 2.0 if rri_delta >= -0.15 else 3.0
        else:
            rri_score = score_from_thresholds(rri, (900, 1050, 1200), inverse=True)

    respiration_score = None
    if respiration is not None:
        respiration_score = score_from_thresholds(respiration, (3500, 4200, 5000), inverse=True)

    components = [value for value in [rmssd_score, rri_score, respiration_score] if value is not None]
    return round_number(safe_mean(components), 3) if components else None


def build_context_subscore(raw_lane: dict[str, Any], reasons: list[tuple[float, str]]) -> float | None:
    skin = raw_lane.get("skin_temperature") or {}
    context = raw_lane.get("previous_day_activity_context") or {}
    overnight_hr = raw_lane.get("overnight_hr_context") or {}
    temp_range = skin.get("range_celsius")
    steps = context.get("steps")
    avg_met = context.get("avg_met")
    overnight_hr_avg = overnight_hr.get("avg_hr_bpm")

    temp_score = score_from_thresholds(temp_range, (1.5, 3.0, 5.0))
    if temp_score is not None and temp_score >= 2.0:
        reasons.append((temp_score, "Overnight skin temperature range looked wider than usual for a stable night."))

    activity_score = None
    if isinstance(steps, (int, float)) and isinstance(avg_met, (int, float)):
        if steps <= 3000 and avg_met <= 1.2:
            activity_score = 0.0
        elif steps <= 7000:
            activity_score = 1.0
        elif steps <= 10000:
            activity_score = 2.0
        else:
            activity_score = 3.0

    hr_score = None
    if overnight_hr_avg is not None:
        hr_score = score_from_thresholds(overnight_hr_avg, (60, 68, 76))

    components = [value for value in [temp_score, activity_score, hr_score] if value is not None]
    return round_number(safe_mean(components), 3) if components else None


def build_baseline_subscore(baseline_features: dict[str, Any], reasons: list[tuple[float, str]]) -> float | None:
    blocks = [block for block in [baseline_features.get("baseline_3_day"), baseline_features.get("baseline_7_day")] if block and block.get("available")]
    if not blocks:
        return None
    worst = max(block.get("worse_than_recent_baseline_count", 0) for block in blocks)
    score = 0.0 if worst == 0 else 1.0 if worst == 1 else 2.0 if worst <= 3 else 3.0
    if score >= 2.0:
        reasons.append((score, "Several overnight features looked worse than your recent personal baseline."))
    return score


def determine_confidence(
    raw_lane: dict[str, Any],
    nightly_lane: dict[str, Any],
    raw_overnight_ppi_lane: dict[str, Any],
    baseline_features: dict[str, Any],
    include_baseline: bool,
) -> str:
    if not (raw_lane.get("sleep_window") or {}).get("valid"):
        return "low"
    if not nightly_lane.get("available") and not raw_overnight_ppi_lane.get("available"):
        return "low"

    confidence = "high"
    if not nightly_lane.get("baseline_ready") or not nightly_lane.get("ans_available") or not nightly_lane.get("recovery_available"):
        confidence = "medium"
    if include_baseline and not ((baseline_features.get("baseline_3_day") or {}).get("available") or (baseline_features.get("baseline_7_day") or {}).get("available")):
        confidence = "medium"
    if not (raw_lane.get("skin_temperature") or {}).get("sample_count"):
        confidence = "medium"
    return confidence


def map_status(score: float | None, confidence: str) -> str:
    if score is None:
        return "Unsteady" if confidence == "low" else "OK"
    if score < 0.75:
        status = "Good"
    elif score < 1.50:
        status = "OK"
    elif score < 2.25:
        status = "Unsteady"
    else:
        status = "Crash"
    if confidence == "low" and status == "Good":
        return "OK"
    return status


def build_candidate_result(
    model_name: str,
    raw_lane: dict[str, Any],
    nightly_lane: dict[str, Any],
    raw_overnight_ppi_lane: dict[str, Any],
    daytime_lane: dict[str, Any],
    baseline_features: dict[str, Any],
    include_nightly: bool,
    include_baseline: bool,
    include_daytime_augmented: bool,
) -> dict[str, Any]:
    reasons: list[tuple[float, str]] = []
    missing_inputs: list[str] = []

    sleep_subscore = build_sleep_subscore(raw_lane, reasons)
    if sleep_subscore is None:
        missing_inputs.append("raw_sleep_and_context_lane.sleep_structure")

    nightly_autonomic_subscore = build_nightly_autonomic_subscore(nightly_lane, raw_overnight_ppi_lane, baseline_features, reasons) if include_nightly else None
    if include_nightly and nightly_autonomic_subscore is None:
        missing_inputs.append("overnight_autonomic_lane")
        reasons.append((3.0, "Overnight autonomic evidence was not available."))

    context_subscore = build_context_subscore(raw_lane, reasons)
    if include_daytime_augmented and daytime_lane.get("available"):
        post_wake_movement = daytime_lane.get("post_wake_movement_ratio")
        post_wake_error = daytime_lane.get("post_wake_avg_error_estimate")
        daytime_penalty = score_from_thresholds(post_wake_movement, (0.2, 0.4, 0.6))
        if daytime_penalty is not None:
            context_subscore = safe_mean([value for value in [context_subscore, daytime_penalty] if value is not None])
        if post_wake_error is not None and post_wake_error > 150:
            reasons.append((1.5, "Immediate post-wake PPI looked noisy, so the daytime augmentation is only weak evidence."))

    baseline_subscore = build_baseline_subscore(baseline_features, reasons) if include_baseline else None
    if include_baseline and baseline_subscore is None:
        missing_inputs.append("personal_baseline_features")

    subscores = {
        "sleep_subscore": sleep_subscore,
        "nightly_autonomic_subscore": nightly_autonomic_subscore,
        "context_subscore": round_number(context_subscore, 3) if context_subscore is not None else None,
        "baseline_subscore": baseline_subscore,
    }
    total_score = weighted_average(
        subscores,
        {
            "sleep_subscore": 0.30,
            "nightly_autonomic_subscore": 0.35,
            "context_subscore": 0.20,
            "baseline_subscore": 0.15,
        },
    )
    confidence = determine_confidence(raw_lane, nightly_lane, raw_overnight_ppi_lane, baseline_features, include_baseline)
    if model_name == "sleep_context_only" and not nightly_lane.get("available") and not raw_overnight_ppi_lane.get("available"):
        confidence = "medium" if (raw_lane.get("sleep_window") or {}).get("valid") else "low"
    if model_name == "comparison_raw_daytime_augmented" and daytime_lane.get("available") and confidence == "high":
        confidence = "medium"

    status = map_status(total_score, confidence)
    if not include_nightly and status == "Good":
        status = "OK"

    top_reasons = [message for _, message in sorted(reasons, key=lambda item: item[0], reverse=True)[:3]]
    if not top_reasons:
        top_reasons = ["No major warning pattern stood out in the available overnight evidence."]

    if (
        not raw_overnight_ppi_lane.get("available")
        and (
            not nightly_lane.get("baseline_ready")
            or not nightly_lane.get("ans_available")
            or not nightly_lane.get("recovery_available")
        )
    ):
        top_reasons.insert(0, "Nightly autonomic summary values are present, but Polar's higher-level readiness interpretation is not mature yet.")
        top_reasons = top_reasons[:3]

    if not include_nightly:
        autonomic_source = "sleep_context_only"
    elif model_name == "comparison_raw_daytime_augmented":
        autonomic_source = "raw_overnight_ppi_plus_nightly_recharge_plus_post_wake_daytime" if raw_overnight_ppi_lane.get("available") else "nightly_recharge_summary_plus_post_wake_daytime"
    elif raw_overnight_ppi_lane.get("available") and include_nightly and nightly_lane.get("available"):
        autonomic_source = "raw_overnight_ppi_plus_nightly_recharge_summary"
    elif raw_overnight_ppi_lane.get("available"):
        autonomic_source = "raw_overnight_ppi"
    elif include_nightly and nightly_lane.get("available"):
        autonomic_source = "nightly_recharge_summary"
    else:
        autonomic_source = "sleep_context_only"

    return {
        "model_name": model_name,
        "status": status,
        "confidence": confidence,
        "reasons": top_reasons,
        "missing_inputs": sorted(set(missing_inputs)),
        "model_version": "v1",
        "overnight_autonomic_source": autonomic_source,
        "overnight_raw_ppi_available": raw_overnight_ppi_lane.get("available") is True,
        "subscores": {key: round_number(value, 3) for key, value in subscores.items() if value is not None},
        "total_score": round_number(total_score, 3),
    }


def build_evening_feedback(rows: list[dict[str, Any]], source_date: str) -> dict[str, Any] | None:
    row = ms.latest_row(ms.rows_for_date(rows, "daily_check_in", source_date), "daily_check_in")
    if not row:
        return None
    payload = ms.parse_payload(row)
    if not payload:
        return None
    return {
        "source_date": payload.get("sourceDate") or source_date,
        "evening_outcome": payload.get("eveningOutcome"),
        "approach_to_day": payload.get("approachToDay"),
        "created_at_epoch_ms": payload.get("createdAtEpochMs"),
        "updated_at_epoch_ms": payload.get("updatedAtEpochMs"),
    }


def build_model_package(rows: list[dict[str, Any]]) -> dict[str, Any]:
    target_row = ms.latest_valid_sleep_row(rows)
    if not target_row:
        return {
            "available": False,
            "candidate_model_results": [],
            "comparison_summary": {
                "primary_model": None,
                "consensus_status": None,
                "agreement_count": 0,
            },
        }

    payload = ms.parse_payload(target_row)
    sleep_result = payload.get("result") or {}
    source_date = sleep_result.get("sleepResultDate") or target_row.get("sourceDate")
    if not source_date:
        return {
            "available": False,
            "candidate_model_results": [],
            "comparison_summary": {
                "primary_model": None,
                "consensus_status": None,
                "agreement_count": 0,
            },
        }

    lanes = build_night_lanes(rows, source_date)
    baseline_features = build_personal_baseline_features(rows, source_date)
    baseline_available = bool((baseline_features.get("baseline_3_day") or {}).get("available") or (baseline_features.get("baseline_7_day") or {}).get("available"))

    candidates = [
        build_candidate_result(
            "sleep_context_only",
            lanes["raw_sleep_and_context_lane"],
            lanes["semi_derived_overnight_autonomic_lane"],
            lanes["raw_overnight_ppi_lane"],
            lanes["raw_daytime_autonomic_lane"],
            baseline_features,
            include_nightly=False,
            include_baseline=False,
            include_daytime_augmented=False,
        ),
        build_candidate_result(
            "sleep_plus_nightly_autonomic",
            lanes["raw_sleep_and_context_lane"],
            lanes["semi_derived_overnight_autonomic_lane"],
            lanes["raw_overnight_ppi_lane"],
            lanes["raw_daytime_autonomic_lane"],
            baseline_features,
            include_nightly=True,
            include_baseline=False,
            include_daytime_augmented=False,
        ),
        build_candidate_result(
            "sleep_plus_nightly_autonomic_plus_baseline",
            lanes["raw_sleep_and_context_lane"],
            lanes["semi_derived_overnight_autonomic_lane"],
            lanes["raw_overnight_ppi_lane"],
            lanes["raw_daytime_autonomic_lane"],
            baseline_features,
            include_nightly=True,
            include_baseline=True,
            include_daytime_augmented=False,
        ),
        build_candidate_result(
            "comparison_raw_daytime_augmented",
            lanes["raw_sleep_and_context_lane"],
            lanes["semi_derived_overnight_autonomic_lane"],
            lanes["raw_overnight_ppi_lane"],
            lanes["raw_daytime_autonomic_lane"],
            baseline_features,
            include_nightly=True,
            include_baseline=baseline_available,
            include_daytime_augmented=True,
        ),
    ]

    primary_model = "sleep_plus_nightly_autonomic_plus_baseline" if baseline_available else "sleep_plus_nightly_autonomic"
    primary_result = next(item for item in candidates if item["model_name"] == primary_model)
    statuses = [item["status"] for item in candidates]
    consensus_status = max(set(statuses), key=statuses.count) if statuses else None
    agreement_count = statuses.count(consensus_status) if consensus_status else 0

    return {
        "available": True,
        "source_date": source_date,
        "raw_sleep_and_context_lane": lanes["raw_sleep_and_context_lane"],
        "semi_derived_overnight_autonomic_lane": lanes["semi_derived_overnight_autonomic_lane"],
        "raw_overnight_ppi_lane": lanes["raw_overnight_ppi_lane"],
        "raw_daytime_autonomic_lane": lanes["raw_daytime_autonomic_lane"],
        "overnight_raw_ppi_available": lanes["overnight_raw_ppi_available"],
        "overnight_autonomic_source": lanes["overnight_autonomic_source"],
        "personal_baseline_features": baseline_features,
        "candidate_model_results": candidates,
        "morning_prediction": {
            "primary_model": primary_model,
            "status": primary_result["status"],
            "confidence": primary_result["confidence"],
            "reasons": primary_result["reasons"],
        },
        "comparison_summary": {
            "primary_model": primary_model,
            "consensus_status": consensus_status,
            "agreement_count": agreement_count,
        },
        "evening_feedback": build_evening_feedback(rows, source_date),
        "metadata_only": {
            "sleep_goal_minutes": (lanes["raw_sleep_and_context_lane"].get("sleep_structure") or {}).get("sleep_goal_minutes"),
        },
    }
