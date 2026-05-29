#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import sqlite3
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo

STATUS_ORDER = {"GOOD": 0, "OK": 1, "UNSTEADY": 2, "CRASH": 3}
OUTCOME_ORDER = STATUS_ORDER
MIN_PAIRED_DAYS_FOR_DIRECTIONAL_SIGNAL = 14
MIN_PAIRED_DAYS_FOR_LOAD_BUDGET_CLAIMS = 30

HUMILITY_NOTE = (
    "No precise load-budget claim should be made from this report. Treat these "
    "counts as calibration until enough paired readiness/outcome days accumulate."
)


@dataclass(frozen=True)
class PairedDay:
    source_date: str
    prediction_status: str | None
    prediction_confidence: str | None
    is_interim: bool
    sleep_data_ready: bool
    autonomic_source: str | None
    sleep_duration_minutes: int | None
    raw_ppi_good_epochs: int | None
    raw_ppi_poor_epochs: int | None
    raw_ppi_coverage_hours: float | None
    outcome: str | None
    approach_to_day: str | None
    muscle_weakness: bool | None
    episode_kinds: tuple[str, ...]
    episode_sources: tuple[str, ...]
    episode_confidences: tuple[str, ...]
    has_primary_episode: bool
    has_no_sleep: bool
    has_nap: bool
    has_rest: bool
    has_inferred_candidate: bool
    has_confirmed_episode: bool
    primary_start_hour: int | None
    primary_end_hour: int | None

    @property
    def has_prediction(self) -> bool:
        return self.prediction_status in STATUS_ORDER

    @property
    def has_outcome(self) -> bool:
        return self.outcome in OUTCOME_ORDER

    @property
    def is_paired(self) -> bool:
        return self.has_prediction and self.has_outcome

    @property
    def has_any_episode(self) -> bool:
        return bool(self.episode_kinds)

    @property
    def has_zero_inputs(self) -> bool:
        return not self.has_prediction and not self.has_outcome and not self.has_any_episode

    @property
    def has_dsps_like_timing(self) -> bool:
        start_is_late = self.primary_start_hour is not None and 2 <= self.primary_start_hour < 12
        end_is_late = self.primary_end_hour is not None and 10 <= self.primary_end_hour < 18
        return start_is_late or end_is_late


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Compare Lodestone morning readiness snapshots with evening outcomes."
    )
    parser.add_argument("--health-db", required=True, help="Path to the Lodestone SQLite database.")
    parser.add_argument("--start-date", help="First source date to include, YYYY-MM-DD.")
    parser.add_argument(
        "--end-date",
        default=date.today().isoformat(),
        help="Last source date to include, YYYY-MM-DD.",
    )
    parser.add_argument("--timezone", default="Europe/London", help="Timezone for timing calibration buckets.")
    parser.add_argument("--json", action="store_true", help="Emit JSON instead of text.")
    return parser.parse_args()


def connect(path: str | Path) -> sqlite3.Connection:
    conn = sqlite3.connect(path)
    conn.row_factory = sqlite3.Row
    return conn


def date_range(start: str, end: str) -> list[str]:
    start_date = date.fromisoformat(start)
    end_date = date.fromisoformat(end)
    if end_date < start_date:
        return []
    days = (end_date - start_date).days
    return [(start_date + timedelta(days=offset)).isoformat() for offset in range(days + 1)]


def latest_predictions(conn: sqlite3.Connection, start_date: str | None, end_date: str) -> dict[str, sqlite3.Row]:
    params: list[Any] = [end_date]
    where = "sourceDate <= ?"
    if start_date:
        where += " and sourceDate >= ?"
        params.append(start_date)
    rows = conn.execute(
        f"""
        select * from morning_prediction_snapshot
        where {where}
        order by sourceDate asc, issuedAtEpochMs desc, id desc
        """,
        params,
    ).fetchall()
    latest: dict[str, sqlite3.Row] = {}
    for row in rows:
        latest.setdefault(row["sourceDate"], row)
    return latest


def daily_reviews(conn: sqlite3.Connection, start_date: str | None, end_date: str) -> dict[str, sqlite3.Row]:
    params: list[Any] = [end_date]
    where = "sourceDate <= ?"
    if start_date:
        where += " and sourceDate >= ?"
        params.append(start_date)
    rows = conn.execute(f"select * from daily_check_in where {where} order by sourceDate", params).fetchall()
    return {row["sourceDate"]: row for row in rows}


def sleep_episode_rows(conn: sqlite3.Connection, start_date: str | None, end_date: str) -> dict[str, list[sqlite3.Row]]:
    params: list[Any] = [end_date]
    where = "sourceDate <= ?"
    if start_date:
        where += " and sourceDate >= ?"
        params.append(start_date)
    rows = conn.execute(
        f"select * from sleep_episode where {where} order by sourceDate, startEpochMs, id",
        params,
    ).fetchall()
    grouped: dict[str, list[sqlite3.Row]] = defaultdict(list)
    for row in rows:
        grouped[row["sourceDate"]].append(row)
    return grouped


def local_hour(epoch_ms: int | None, zone: ZoneInfo) -> int | None:
    if epoch_ms is None:
        return None
    return datetime.fromtimestamp(epoch_ms / 1000, tz=zone).hour


def load_paired_days(conn: sqlite3.Connection, start_date: str | None, end_date: str, zone: ZoneInfo) -> list[PairedDay]:
    predictions = latest_predictions(conn, start_date, end_date)
    reviews = daily_reviews(conn, start_date, end_date)
    episodes = sleep_episode_rows(conn, start_date, end_date)
    if start_date is None:
        all_dates = sorted(set(predictions) | set(reviews) | set(episodes))
    else:
        all_dates = date_range(start_date, end_date)
    days: list[PairedDay] = []
    for source_date in all_dates:
        prediction = predictions.get(source_date)
        review = reviews.get(source_date)
        episode_list = episodes.get(source_date, [])
        primary = next((row for row in episode_list if bool(row["isPrimaryForReadiness"])), None)
        episode_kinds = tuple(row["episodeKind"] for row in episode_list)
        episode_sources = tuple(row["source"] for row in episode_list)
        episode_confidences = tuple(row["confidence"] for row in episode_list)
        days.append(
            PairedDay(
                source_date=source_date,
                prediction_status=prediction["status"] if prediction else None,
                prediction_confidence=prediction["confidence"] if prediction else None,
                is_interim=bool(prediction["isInterim"]) if prediction else False,
                sleep_data_ready=bool(prediction["sleepDataReady"]) if prediction else False,
                autonomic_source=prediction["overnightAutonomicSource"] if prediction else None,
                sleep_duration_minutes=prediction["sleepDurationMinutes"] if prediction else None,
                raw_ppi_good_epochs=prediction["rawPpiGoodEpochCount"] if prediction else None,
                raw_ppi_poor_epochs=prediction["rawPpiPoorEpochCount"] if prediction else None,
                raw_ppi_coverage_hours=prediction["rawPpiCoverageHours"] if prediction else None,
                outcome=review["eveningOutcome"] if review else None,
                approach_to_day=review["approachToDay"] if review else None,
                muscle_weakness=bool(review["muscleWeaknessToday"]) if review else None,
                episode_kinds=episode_kinds,
                episode_sources=episode_sources,
                episode_confidences=episode_confidences,
                has_primary_episode=primary is not None,
                has_no_sleep=any(kind == "no_sleep" for kind in episode_kinds),
                has_nap=any(kind == "nap" for kind in episode_kinds),
                has_rest=any(kind == "rest_candidate" for kind in episode_kinds),
                has_inferred_candidate=any(source == "ppi_inferred" for source in episode_sources),
                has_confirmed_episode=any(confidence == "user_confirmed" for confidence in episode_confidences),
                primary_start_hour=local_hour(primary["startEpochMs"], zone) if primary else None,
                primary_end_hour=local_hour(primary["endEpochMs"], zone) if primary else None,
            )
        )
    return days


def status_delta(prediction: str | None, outcome: str | None) -> int | None:
    if prediction not in STATUS_ORDER or outcome not in OUTCOME_ORDER:
        return None
    return OUTCOME_ORDER[outcome] - STATUS_ORDER[prediction]


def ordinal_delta(before: str | None, after: str | None) -> int | None:
    if before not in OUTCOME_ORDER or after not in OUTCOME_ORDER:
        return None
    return OUTCOME_ORDER[after] - OUTCOME_ORDER[before]


def delta_bucket(delta: int | None) -> str:
    if delta is None:
        return "unpaired"
    if delta == 0:
        return "matched"
    if delta > 0:
        return "outcome_worse"
    return "outcome_better"


def sign(value: int | None) -> int | None:
    if value is None:
        return None
    if value > 0:
        return 1
    if value < 0:
        return -1
    return 0


def safe_rate(numerator: int, denominator: int) -> float | None:
    if denominator == 0:
        return None
    return round(numerator / denominator, 3)


def compact_day(day: PairedDay) -> dict[str, Any]:
    return {
        "source_date": day.source_date,
        "prediction": day.prediction_status,
        "outcome": day.outcome,
        "delta": status_delta(day.prediction_status, day.outcome),
        "approach_to_day": day.approach_to_day,
        "confidence": day.prediction_confidence,
        "autonomic_source": day.autonomic_source,
        "episode_kinds": list(day.episode_kinds),
        "episode_sources": list(day.episode_sources),
    }


def build_alignment_report(paired: list[PairedDay]) -> dict[str, Any]:
    deltas = [status_delta(day.prediction_status, day.outcome) for day in paired]
    known_deltas = [delta for delta in deltas if delta is not None]
    bucket_counts = Counter(delta_bucket(delta) for delta in deltas)
    exact_matches = bucket_counts["matched"]
    within_one = sum(1 for delta in known_deltas if abs(delta) <= 1)
    confusion: dict[str, Counter[str]] = defaultdict(Counter)
    for day in paired:
        confusion[day.prediction_status or "missing"][day.outcome or "missing"] += 1
    return {
        "paired_count": len(paired),
        "exact_matches": exact_matches,
        "exact_match_rate": safe_rate(exact_matches, len(paired)),
        "within_one_step": within_one,
        "within_one_step_rate": safe_rate(within_one, len(paired)),
        "bucket_counts": dict(bucket_counts),
        "mean_ordinal_delta": round(sum(known_deltas) / len(known_deltas), 2) if known_deltas else None,
        "confusion": {status: dict(outcomes) for status, outcomes in confusion.items()},
    }


def consecutive_pairs(days: list[PairedDay]) -> list[tuple[PairedDay, PairedDay]]:
    by_date = {day.source_date: day for day in days}
    pairs: list[tuple[PairedDay, PairedDay]] = []
    for day in days:
        next_date = (date.fromisoformat(day.source_date) + timedelta(days=1)).isoformat()
        next_day = by_date.get(next_date)
        if next_day:
            pairs.append((day, next_day))
    return pairs


def build_stability_report(days: list[PairedDay]) -> dict[str, Any]:
    pairs = [
        (today, tomorrow)
        for today, tomorrow in consecutive_pairs(days)
        if today.has_prediction and today.has_outcome and tomorrow.has_prediction and tomorrow.has_outcome
    ]
    buckets: Counter[str] = Counter()
    examples: dict[str, list[str]] = defaultdict(list)
    for today, tomorrow in pairs:
        prediction_move = ordinal_delta(today.prediction_status, tomorrow.prediction_status)
        outcome_move = ordinal_delta(today.outcome, tomorrow.outcome)
        prediction_sign = sign(prediction_move)
        outcome_sign = sign(outcome_move)
        if prediction_sign == 0 and outcome_sign == 0:
            bucket = "both_stable"
        elif prediction_sign == outcome_sign:
            bucket = "same_direction"
        elif prediction_sign == 0:
            bucket = "outcome_moved_prediction_stable"
        elif outcome_sign == 0:
            bucket = "prediction_moved_outcome_stable"
        else:
            bucket = "opposite_direction"
        buckets[bucket] += 1
        if len(examples[bucket]) < 6:
            examples[bucket].append(f"{today.source_date}->{tomorrow.source_date}")
    return {
        "consecutive_paired_transitions": len(pairs),
        "transition_buckets": dict(buckets),
        "examples": dict(examples),
        "note": "Day-to-day stability is descriptive only; it is not a validated volatility score.",
    }


def build_payback_report(days: list[PairedDay]) -> dict[str, Any]:
    pairs = [(today, tomorrow) for today, tomorrow in consecutive_pairs(days) if today.has_outcome and tomorrow.has_outcome]
    ambitious_mismatch_dates: list[str] = []
    worse_next_day_after_ambitious_mismatch: list[str] = []
    worse_next_day_after_good_ok_approach: list[str] = []
    crash_or_unsteady_next_day_after_good_ok_approach: list[str] = []
    for today, tomorrow in pairs:
        approach_delta = status_delta(today.approach_to_day, today.outcome)
        outcome_move = ordinal_delta(today.outcome, tomorrow.outcome)
        if approach_delta is not None and approach_delta > 0:
            ambitious_mismatch_dates.append(today.source_date)
            if outcome_move is not None and outcome_move > 0:
                worse_next_day_after_ambitious_mismatch.append(today.source_date)
        if today.approach_to_day in {"GOOD", "OK"}:
            if outcome_move is not None and outcome_move > 0:
                worse_next_day_after_good_ok_approach.append(today.source_date)
            if tomorrow.outcome in {"UNSTEADY", "CRASH"}:
                crash_or_unsteady_next_day_after_good_ok_approach.append(today.source_date)
    return {
        "consecutive_outcome_transitions": len(pairs),
        "ambitious_mismatch_days": len(ambitious_mismatch_dates),
        "worse_next_day_after_ambitious_mismatch": len(worse_next_day_after_ambitious_mismatch),
        "worse_next_day_after_good_ok_approach": len(worse_next_day_after_good_ok_approach),
        "crash_or_unsteady_next_day_after_good_ok_approach": len(crash_or_unsteady_next_day_after_good_ok_approach),
        "example_dates": {
            "ambitious_mismatch": ambitious_mismatch_dates[:8],
            "worse_after_ambitious_mismatch": worse_next_day_after_ambitious_mismatch[:8],
            "worse_after_good_ok_approach": worse_next_day_after_good_ok_approach[:8],
        },
        "note": "This is coarse next-day payback context, not a load budget or causal estimate.",
    }


def calibration_group(day: PairedDay) -> list[str]:
    groups: list[str] = []
    if day.has_no_sleep:
        groups.append("skipped_night_no_sleep")
    if day.has_dsps_like_timing:
        groups.append("dsps_like_timing")
    if day.has_nap:
        groups.append("nap_present")
    if day.has_rest:
        groups.append("rest_present")
    if day.has_inferred_candidate:
        groups.append("inferred_candidate_present")
    if day.has_confirmed_episode:
        groups.append("confirmed_episode_present")
    if day.has_prediction and not day.sleep_data_ready:
        groups.append("sleep_data_not_ready")
    if day.is_interim:
        groups.append("interim_prediction")
    if day.has_prediction and not day.raw_ppi_good_epochs:
        groups.append("no_good_raw_ppi_epochs")
    if day.has_prediction and not day.has_outcome:
        groups.append("missing_outcome")
    if not day.has_prediction and day.has_outcome:
        groups.append("missing_prediction")
    if not day.has_primary_episode and not day.has_no_sleep:
        groups.append("no_primary_sleep_decision")
    if day.has_zero_inputs:
        groups.append("zero_input_day")
    return groups


def build_calibration_report(days: list[PairedDay]) -> dict[str, Any]:
    grouped: dict[str, list[PairedDay]] = defaultdict(list)
    for day in days:
        for group in calibration_group(day):
            grouped[group].append(day)
    group_names = [
        "skipped_night_no_sleep",
        "dsps_like_timing",
        "nap_present",
        "rest_present",
        "inferred_candidate_present",
        "confirmed_episode_present",
        "sleep_data_not_ready",
        "interim_prediction",
        "no_good_raw_ppi_epochs",
        "missing_outcome",
        "missing_prediction",
        "no_primary_sleep_decision",
        "zero_input_day",
    ]
    return {
        name: {
            "count": len(grouped.get(name, [])),
            "dates": [day.source_date for day in grouped.get(name, [])],
            "paired_count": sum(1 for day in grouped.get(name, []) if day.is_paired),
            "bucket_counts": dict(
                Counter(delta_bucket(status_delta(day.prediction_status, day.outcome)) for day in grouped.get(name, []))
            ),
        }
        for name in group_names
    }


def build_report(days: list[PairedDay]) -> dict[str, Any]:
    paired = [day for day in days if day.is_paired]
    prediction_count = sum(1 for day in days if day.has_prediction)
    outcome_count = sum(1 for day in days if day.has_outcome)
    episode_kind_counts = Counter(kind for day in days for kind in day.episode_kinds)
    return {
        "day_count": len(days),
        "prediction_count": prediction_count,
        "outcome_count": outcome_count,
        "paired_count": len(paired),
        "enough_data_for_directional_signal": len(paired) >= MIN_PAIRED_DAYS_FOR_DIRECTIONAL_SIGNAL,
        "enough_data_for_load_budget_claims": len(paired) >= MIN_PAIRED_DAYS_FOR_LOAD_BUDGET_CLAIMS,
        "humility_note": HUMILITY_NOTE,
        "readiness_alignment": build_alignment_report(paired),
        "stability": build_stability_report(days),
        "coarse_payback": build_payback_report(days),
        "episode_calibration": {
            "episode_kind_counts": dict(episode_kind_counts),
            "primary_episode_days": sum(1 for day in days if day.has_primary_episode),
            "no_sleep_days": sum(1 for day in days if day.has_no_sleep),
            "nap_days": sum(1 for day in days if day.has_nap),
            "inferred_candidate_days": sum(1 for day in days if day.has_inferred_candidate),
            "confirmed_episode_days": sum(1 for day in days if day.has_confirmed_episode),
            "groups": build_calibration_report(days),
        },
        "paired_days": [compact_day(day) for day in paired],
    }


def date_preview(dates: list[str], limit: int = 8) -> str:
    preview = ", ".join(dates[:limit])
    if len(dates) > limit:
        preview += ", ..."
    return preview or "none"


def print_text(report: dict[str, Any]) -> None:
    alignment = report["readiness_alignment"]
    print("Readiness/outcome validation")
    print(f"  days scanned: {report['day_count']}")
    print(f"  prediction days: {report['prediction_count']}")
    print(f"  outcome days: {report['outcome_count']}")
    print(f"  paired prediction/outcome days: {report['paired_count']}")
    print(f"  enough for directional signal: {'yes' if report['enough_data_for_directional_signal'] else 'no'}")
    print(f"  enough for load-budget claims: {'yes' if report['enough_data_for_load_budget_claims'] else 'no'}")
    print(f"  caution: {report['humility_note']}")
    print()
    print("Readiness alignment")
    print(f"  exact matches: {alignment['exact_matches']} rate={alignment['exact_match_rate']}")
    print(f"  within one step: {alignment['within_one_step']} rate={alignment['within_one_step_rate']}")
    for bucket in ("matched", "outcome_worse", "outcome_better", "unpaired"):
        print(f"  {bucket}: {alignment['bucket_counts'].get(bucket, 0)}")
    print(f"  mean ordinal delta: {alignment['mean_ordinal_delta']}")
    print()
    print("Stability")
    stability = report["stability"]
    print(f"  consecutive paired transitions: {stability['consecutive_paired_transitions']}")
    for bucket, count in sorted(stability["transition_buckets"].items()):
        print(f"  {bucket}: {count} ({date_preview(stability['examples'].get(bucket, []), 4)})")
    print(f"  note: {stability['note']}")
    print()
    print("Coarse payback")
    payback = report["coarse_payback"]
    print(f"  consecutive outcome transitions: {payback['consecutive_outcome_transitions']}")
    print(f"  ambitious mismatch days: {payback['ambitious_mismatch_days']}")
    print(f"  worse next day after ambitious mismatch: {payback['worse_next_day_after_ambitious_mismatch']}")
    print(f"  worse next day after GOOD/OK approach: {payback['worse_next_day_after_good_ok_approach']}")
    print(f"  note: {payback['note']}")
    print()
    print("Episode calibration")
    episode = report["episode_calibration"]
    print(f"  episode kinds: {episode['episode_kind_counts']}")
    print(f"  primary episode days: {episode['primary_episode_days']}")
    print(f"  inferred candidate days: {episode['inferred_candidate_days']}")
    for name, group in episode["groups"].items():
        print(f"  {name}: {group['count']} ({date_preview(group['dates'])})")


def main() -> None:
    args = parse_args()
    zone = ZoneInfo(args.timezone)
    with connect(args.health_db) as conn:
        days = load_paired_days(conn, args.start_date, args.end_date, zone)
    report = build_report(days)
    if args.json:
        print(json.dumps(report, indent=2))
    else:
        print_text(report)


if __name__ == "__main__":
    main()
