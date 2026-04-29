#!/usr/bin/env python3

from __future__ import annotations

import argparse

import canonical_inputs as ci
import garmin_adapter as ga
import morning_summary as ms


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Print side-by-side candidate morning model results from a Health Monitor export."
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
        "--garmin-db",
        help="Optional path to garmin.db for Garmin overnight comparison data.",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    export_path, rows = ms.load_rows(args)
    payload = {
        "export_path": str(export_path),
        "overnight_summary_inputs": ci.overnight_inputs(rows),
        "daytime_monitor_inputs": ci.daytime_inputs(rows),
    }
    canonical = ci.me.build_model_package(rows)
    garmin_lane = ga.load_garmin_comparison_lane(args.garmin_db, canonical.get("source_date"))

    print(f"Export: {payload['export_path']}")
    overnight = payload["overnight_summary_inputs"]
    if overnight.get("available"):
        print(f"Overnight: {overnight['sleep_window']['label']}")
    else:
        print("Overnight: unavailable")
    print()
    print("Candidate models")
    for result in canonical.get("candidate_model_results") or []:
        print(
            f"  {result['model_name']}: {result['status']} "
            f"(confidence={result['confidence']}, score={result.get('total_score', 'n/a')})"
        )
        print(f"    - overnight source: {result.get('overnight_autonomic_source')}")
        for reason in result.get("reasons") or []:
            print(f"    - {reason}")
        missing = result.get("missing_inputs") or []
        if missing:
            print(f"    - missing: {', '.join(missing)}")
    print()
    summary = canonical.get("comparison_summary") or {}
    primary = canonical.get("morning_prediction") or {}
    print("Comparison summary")
    print(f"  primary model: {summary.get('primary_model')}")
    print(f"  consensus: {summary.get('consensus_status')}")
    print(f"  chosen status: {primary.get('status')} ({primary.get('confidence')})")
    print(f"  overnight autonomic source: {canonical.get('overnight_autonomic_source')}")
    print(f"  raw overnight PPI available: {canonical.get('overnight_raw_ppi_available')}")
    feedback = canonical.get("evening_feedback")
    if feedback:
        print()
        print("Evening feedback")
        print(f"  outcome: {feedback.get('evening_outcome')}")
        print(f"  approach: {feedback.get('approach_to_day') or 'skipped'}")
    print()
    print("Garmin comparison lane")
    if garmin_lane.get("available"):
        sleep_window = garmin_lane.get("sleep_window") or {}
        hrv_summary = garmin_lane.get("hrv_summary") or {}
        garmin_summary = garmin_lane.get("garmin_hrv_summary") or {}
        sleep_summary = garmin_lane.get("sleep_summary") or {}
        context_summary = garmin_lane.get("comparison_context_summary") or {}
        baseline_summary = garmin_lane.get("garmin_baseline_summary") or {}
        hrv_baseline = (baseline_summary.get("current_vs_recent") or {}).get("hrv_mean") or {}
        hrv_delta_baseline = (baseline_summary.get("current_vs_recent") or {}).get("hrv_delta") or {}
        hrv_windows = hrv_baseline.get("windows") or {}
        delta_windows = hrv_delta_baseline.get("windows") or {}
        print(f"  source date: {garmin_lane.get('source_date')}")
        print(f"  signal note: {garmin_lane.get('signal_priority_note')}")
        print(f"  sleep window: {sleep_window.get('label') or 'n/a'}")
        print(
            f"  HRV series: {garmin_lane.get('hrv_series', {}).get('sample_count')} samples "
            f"({garmin_lane.get('hrv_series', {}).get('start_utc')} -> {garmin_lane.get('hrv_series', {}).get('end_utc')})"
        )
        print(
            f"  HRV summary: avg={hrv_summary.get('avg_hrv')} "
            f"p05={hrv_summary.get('p05_hrv')} p95={hrv_summary.get('p95_hrv')} "
            f"delta={hrv_summary.get('overnight_delta')}"
        )
        print(
            f"  Garmin nightly HRV: last_night={garmin_summary.get('last_night')} "
            f"weekly_avg={garmin_summary.get('weekly_avg')} status={garmin_summary.get('status')}"
        )
        print(
            f"  Sleep summary: total={sleep_summary.get('total_sleep_minutes')} min "
            f"deep={sleep_summary.get('deep_sleep_minutes')} light={sleep_summary.get('light_sleep_minutes')} "
            f"rem={sleep_summary.get('rem_sleep_minutes')} awake={sleep_summary.get('awake_minutes')}"
        )
        print(
            f"  Context: RHR={context_summary.get('heart_rate', {}).get('resting_hr')} "
            f"avgHR24h={context_summary.get('heart_rate', {}).get('avg_hr_24h')} "
            f"resp={sleep_summary.get('average_respiration')} "
            f"SpO2={sleep_summary.get('average_spo2')} "
            f"stress={context_summary.get('stress', {}).get('avg_stress')} "
            f"BB_wake={context_summary.get('body_battery', {}).get('at_wake')}"
        )
        if hrv_windows.get("7d"):
            print(
                f"  Garmin baseline (HRV mean): current={hrv_baseline.get('current')} "
                f"vs 7d_mean={hrv_windows['7d'].get('mean')} "
                f"delta={hrv_windows['7d'].get('delta_from_mean')} "
                f"pct={hrv_windows['7d'].get('percentile_vs_prior')}"
            )
        if delta_windows.get("7d"):
            print(
                f"  Garmin baseline (HRV delta): current={hrv_delta_baseline.get('current')} "
                f"vs 7d_mean={delta_windows['7d'].get('mean')} "
                f"delta={delta_windows['7d'].get('delta_from_mean')} "
                f"pct={delta_windows['7d'].get('percentile_vs_prior')}"
            )
        print(
            f"  Garmin history: {len(garmin_lane.get('historical_nights') or [])} nights "
            f"({baseline_summary.get('available_prior_nights')} prior before current)"
        )
    else:
        print(f"  unavailable: {garmin_lane.get('reason', 'not_configured')}")


if __name__ == "__main__":
    main()
