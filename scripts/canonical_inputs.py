#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
from pathlib import Path

import garmin_adapter as ga
import model_engine as me
import morning_summary as ms


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Extract canonical overnight and daytime model inputs from a Health Monitor export."
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
    parser.add_argument(
        "--pretty",
        action="store_true",
        help="Pretty-print the JSON output.",
    )
    parser.add_argument(
        "--garmin-db",
        help="Optional path to garmin.db for Garmin overnight comparison data.",
    )
    return parser.parse_args()


def round_if_number(value, digits: int = 3):
    if isinstance(value, (int, float)):
        return round(value, digits)
    return value


def latest_valid_daily_summary(rows: list[dict], source_date: str) -> dict:
    row = ms.daily_summary_row(rows, source_date)
    if not row:
        return {}
    payload = ms.parse_payload(row)
    if not payload.get("date"):
        return {}
    return payload


def latest_valid_activity(rows: list[dict], source_date: str) -> dict:
    row = ms.latest_row(ms.latest_cluster_rows(ms.rows_for_date(rows, "activity_samples", source_date)), "activity_samples")
    return ms.parse_payload(row) if row else {}


def overnight_inputs(rows: list[dict]) -> dict:
    latest_sleep_row = ms.latest_valid_sleep_row(rows)
    if not latest_sleep_row:
        return {"available": False}

    sleep_payload = ms.parse_payload(latest_sleep_row)
    sleep_result = sleep_payload.get("result") or {}
    source_date = sleep_result.get("sleepResultDate") or latest_sleep_row.get("sourceDate")

    sleep = ms.aggregate_sleep(rows, source_date) or {}
    recharge = ms.aggregate_nightly_recharge(rows, source_date) or {}
    hr = ms.aggregate_hr(rows, source_date) or {}
    ppi = ms.aggregate_ppi(rows, source_date)
    skin = ms.aggregate_skin_temperature(rows, source_date)
    daily = ms.aggregate_daily_summary(rows, source_date) or {}

    return {
        "available": True,
        "source_date": source_date,
        "sleep_window": {
            "start": sleep.get("start"),
            "end": sleep.get("end"),
            "label": ms.format_sleep_window(sleep.get("result_date"), sleep.get("start"), sleep.get("end")),
        },
        "sleep": {
            "duration_minutes": sleep.get("duration_minutes"),
            "goal_delta_minutes": sleep.get("goal_delta_minutes"),
            "cycle_count": sleep.get("cycle_count"),
            "phase_minutes": sleep.get("phase_minutes"),
        },
        "nightly_recharge": {
            "baseline_ready": recharge.get("baseline_ready"),
            "ans_available": recharge.get("ans_available"),
            "recovery_available": recharge.get("recovery_available"),
            "mean_rri": recharge.get("mean_rri"),
            "mean_rmssd": recharge.get("mean_rmssd"),
            "mean_respiration_interval": recharge.get("mean_respiration_interval"),
        },
        "hr": {
            "avg_bpm": round_if_number(hr.get("avgHr"), 2),
            "min_bpm": hr.get("minHr"),
            "max_bpm": hr.get("maxHr"),
            "sample_count": hr.get("sampleCount"),
        },
        "ppi": {
            "avg_ppi_ms": round_if_number(ppi.get("avg_ppi"), 2),
            "avg_error_estimate": round_if_number(ppi.get("avg_error"), 2),
            "interval_count": ppi.get("sample_count"),
            "skin_contact_ratio": round_if_number(
                (ppi["skin_contact_count"] / ppi["sample_count"]) if ppi.get("sample_count") else None,
                4,
            ),
            "movement_ratio": round_if_number(
                (ppi["movement_count"] / ppi["sample_count"]) if ppi.get("sample_count") else None,
                4,
            ),
        },
        "skin_temperature": {
            "avg_celsius": round_if_number(skin.get("avg_temperature"), 3),
            "min_celsius": round_if_number(skin.get("min_temperature"), 3),
            "max_celsius": round_if_number(skin.get("max_temperature"), 3),
            "sample_count": skin.get("sample_count"),
        },
        "daily_context": {
            "steps": daily.get("steps"),
            "distance_m": daily.get("activityDistance"),
            "activity_calories": daily.get("activityCalories"),
            "sleep_minutes": daily.get("sleepMinutes"),
            "sedentary_minutes": daily.get("sedentaryMinutes"),
            "light_activity_minutes": daily.get("lightActivityMinutes"),
        },
    }


def daytime_inputs(rows: list[dict]) -> dict:
    latest_sleep_row = ms.latest_valid_sleep_row(rows)
    if not latest_sleep_row:
        return {"available": False}

    sleep_payload = ms.parse_payload(latest_sleep_row)
    sleep_result = sleep_payload.get("result") or {}
    source_date = sleep_result.get("sleepResultDate") or latest_sleep_row.get("sourceDate")

    hr = ms.aggregate_hr(rows, source_date) or {}
    ppi = ms.aggregate_ppi(rows, source_date)
    skin = ms.aggregate_skin_temperature(rows, source_date)
    daily_payload = latest_valid_daily_summary(rows, source_date)
    activity_payload = latest_valid_activity(rows, source_date)
    daily = daily_payload.get("summary") or {}
    activity = activity_payload.get("summary") or {}

    return {
        "available": True,
        "source_date": source_date,
        "hr_live_candidate": {
            "avg_bpm": round_if_number(hr.get("avgHr"), 2),
            "min_bpm": hr.get("minHr"),
            "max_bpm": hr.get("maxHr"),
            "sample_count": hr.get("sampleCount"),
        },
        "ppi_live_candidate": {
            "avg_ppi_ms": round_if_number(ppi.get("avg_ppi"), 2),
            "avg_error_estimate": round_if_number(ppi.get("avg_error"), 2),
            "interval_count": ppi.get("sample_count"),
            "skin_contact_ratio": round_if_number(
                (ppi["skin_contact_count"] / ppi["sample_count"]) if ppi.get("sample_count") else None,
                4,
            ),
            "movement_ratio": round_if_number(
                (ppi["movement_count"] / ppi["sample_count"]) if ppi.get("sample_count") else None,
                4,
            ),
            "batch_count": ppi.get("batch_count"),
        },
        "skin_temperature_candidate": {
            "avg_celsius": round_if_number(skin.get("avg_temperature"), 3),
            "sample_count": skin.get("sample_count"),
        },
        "activity_context": {
            "steps": daily.get("steps"),
            "distance_m": daily.get("activityDistance"),
            "activity_calories": daily.get("activityCalories"),
            "recorded_steps": activity.get("totalRecordedSteps"),
            "avg_met": round_if_number(activity.get("avgMet"), 3),
            "met_sample_count": activity.get("metSampleCount"),
            "step_sample_count": activity.get("stepSampleCount"),
        },
        "raw_anchor_times": {
            "sleep_end": sleep_result.get("sleepEndTime"),
            "sleep_start": sleep_result.get("sleepStartTime"),
        },
    }


def main() -> None:
    args = parse_args()
    export_path, rows = ms.load_rows(args)
    model_package = me.build_model_package(rows)
    garmin_lane = ga.load_garmin_comparison_lane(args.garmin_db, model_package.get("source_date"))
    result = {
        "export_path": str(export_path),
        "overnight_summary_inputs": overnight_inputs(rows),
        "daytime_monitor_inputs": daytime_inputs(rows),
        "raw_sleep_and_context_lane": model_package.get("raw_sleep_and_context_lane"),
        "semi_derived_overnight_autonomic_lane": model_package.get("semi_derived_overnight_autonomic_lane"),
        "raw_overnight_ppi_lane": model_package.get("raw_overnight_ppi_lane"),
        "raw_daytime_autonomic_lane": model_package.get("raw_daytime_autonomic_lane"),
        "overnight_raw_ppi_available": model_package.get("overnight_raw_ppi_available"),
        "overnight_autonomic_source": model_package.get("overnight_autonomic_source"),
        "personal_baseline_features": model_package.get("personal_baseline_features"),
        "candidate_model_results": model_package.get("candidate_model_results"),
        "morning_prediction": model_package.get("morning_prediction"),
        "comparison_summary": model_package.get("comparison_summary"),
        "evening_feedback": model_package.get("evening_feedback"),
        "garmin_overnight_comparison_lane": garmin_lane,
        "metadata_only": model_package.get("metadata_only"),
    }
    if args.pretty:
        print(json.dumps(result, indent=2))
    else:
        print(json.dumps(result))


if __name__ == "__main__":
    main()
