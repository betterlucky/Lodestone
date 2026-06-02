#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import sqlite3
from collections import Counter
from dataclasses import asdict, dataclass
from datetime import date, timedelta
from pathlib import Path
from statistics import mean
from typing import Any

STATUS_ORDER = {"GOOD": 0, "OK": 1, "UNSTEADY": 2, "CRASH": 3}
OUTCOME_ORDER = STATUS_ORDER
MIN_BASELINE_DAYS = 5
MIN_DIRECTIONAL_PAIRED_DAYS = 14
MIN_CAREFUL_TUNING_DAYS = 45
SOURCE_DATE_TABLES = (
    "ppi247_epoch",
    "sleep_night_raw",
    "nightly_recharge_raw",
    "sleep_episode",
    "morning_prediction_snapshot",
    "daily_check_in",
)


@dataclass(frozen=True)
class PpiSummary:
    source_date: str
    epoch_count: int
    usable_epoch_count: int
    good_epoch_count: int
    poor_epoch_count: int
    mean_rmssd_ms: float | None
    mean_ppi_ms: float | None
    first_epoch_ms: int | None
    last_epoch_ms: int | None

    @property
    def available(self) -> bool:
        return self.epoch_count > 0 and self.mean_rmssd_ms is not None

    @property
    def usable_ratio(self) -> float | None:
        if self.epoch_count == 0:
            return None
        return self.usable_epoch_count / self.epoch_count


@dataclass(frozen=True)
class CurrentStateDay:
    source_date: str
    autonomic_status: str | None
    functional_status: str | None
    planning_status: str | None
    current_status: str | None
    signal_robustness: str
    state_stability: str
    evidence_basis: str
    active_window_source: str | None
    baseline_days: int
    ppi_epoch_count: int
    ppi_usable_epoch_count: int
    ppi_good_epoch_count: int
    ppi_poor_epoch_count: int
    mean_rmssd_ms: float | None
    rmssd_delta_pct: float | None
    mean_ppi_ms: float | None
    ppi_delta_pct: float | None
    prior_subjective_state: str | None
    prior_subjective_penalty: int
    functional_notes: tuple[str, ...]
    day_shape_captured: bool | None
    mostly_horizontal: bool | None
    left_house: bool | None
    major_task: bool | None
    major_task_type: str | None
    pem_payback_today: bool | None
    payback_peak_today: bool | None
    payback_peak_confidence: str | None
    old_readiness_status: str | None
    old_readiness_confidence: str | None
    evening_outcome: str | None
    approach_to_day: str | None
    notes: tuple[str, ...]

    @property
    def has_current_status(self) -> bool:
        return self.current_status in STATUS_ORDER

    @property
    def has_old_status(self) -> bool:
        return self.old_readiness_status in STATUS_ORDER

    @property
    def has_outcome(self) -> bool:
        return self.evening_outcome in OUTCOME_ORDER


@dataclass(frozen=True)
class PemLagEpisode:
    trigger_date: str
    trigger_type: str
    window_dates: tuple[str, ...]
    affected_dates: tuple[str, ...]
    missing_dates: tuple[str, ...]
    pem_dates: tuple[str, ...]
    mostly_horizontal_dates: tuple[str, ...]
    peak_date: str | None
    peak_lag_days: int | None
    recovery_tail_days: int | None
    trigger_outcome: str | None
    worst_outcome: str | None
    outcome_movement: str


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Build a sleep-agnostic Lodestone current-state baseline from the "
            "local DB and compare it with saved outcomes."
        )
    )
    parser.add_argument("--health-db", required=True, help="Path to the Lodestone SQLite database.")
    parser.add_argument("--start-date", help="First source date to include, YYYY-MM-DD.")
    parser.add_argument("--end-date", default=date.today().isoformat(), help="Last source date to include.")
    parser.add_argument("--baseline-days", type=int, default=14, help="Prior days for personal baseline comparison.")
    parser.add_argument("--json", action="store_true", help="Emit JSON instead of compact text.")
    return parser.parse_args()


def connect(path: str | Path) -> sqlite3.Connection:
    conn = sqlite3.connect(path)
    conn.row_factory = sqlite3.Row
    return conn


def one(conn: sqlite3.Connection, query: str, params: tuple[Any, ...] = ()) -> sqlite3.Row | None:
    return conn.execute(query, params).fetchone()


def row_value(row: sqlite3.Row | None, column: str) -> Any:
    if row is None or column not in row.keys():
        return None
    return row[column]


def row_bool(row: sqlite3.Row | None, column: str) -> bool | None:
    value = row_value(row, column)
    if value is None:
        return None
    return bool(value)


def date_range(start: str, end: str) -> list[str]:
    first = date.fromisoformat(start)
    last = date.fromisoformat(end)
    if last < first:
        return []
    return [(first + timedelta(days=offset)).isoformat() for offset in range((last - first).days + 1)]


def days_between(first: str, second: str) -> int:
    return (date.fromisoformat(second) - date.fromisoformat(first)).days


def known_dates(conn: sqlite3.Connection, start_date: str | None, end_date: str) -> list[str]:
    if start_date:
        return date_range(start_date, end_date)

    dates: set[str] = set()
    for table in SOURCE_DATE_TABLES:
        for row in conn.execute(
            f"select distinct sourceDate as source_date from {table} where sourceDate <= ?",
            (end_date,),
        ):
            if row["source_date"]:
                dates.add(row["source_date"])
    return sorted(dates)


def ppi_summary_from_rows(source_date: str, rows: list[sqlite3.Row]) -> PpiSummary:
    usable_rows = [
        row for row in rows
        if not str(row["epochQuality"]).lower().startswith("poor")
    ]
    good_rows = [row for row in rows if str(row["epochQuality"]).lower() == "good"]
    poor_rows = [
        row for row in rows
        if str(row["epochQuality"]).lower().startswith("poor")
    ]

    def numeric_values(column: str) -> list[float]:
        return [float(row[column]) for row in usable_rows if row[column] is not None]

    rmssd_values = numeric_values("rmssdMs")
    ppi_values = numeric_values("meanPpiMs")
    return PpiSummary(
        source_date=source_date,
        epoch_count=len(rows),
        usable_epoch_count=len(usable_rows),
        good_epoch_count=len(good_rows),
        poor_epoch_count=len(poor_rows),
        mean_rmssd_ms=round(mean(rmssd_values), 2) if rmssd_values else None,
        mean_ppi_ms=round(mean(ppi_values), 2) if ppi_values else None,
        first_epoch_ms=min((row["epochStartEpochMs"] for row in rows), default=None),
        last_epoch_ms=max((row["epochEndEpochMs"] for row in rows), default=None),
    )


def ppi_rows_for_date(conn: sqlite3.Connection, source_date: str) -> list[sqlite3.Row]:
    return conn.execute(
        """
        select * from ppi247_epoch
        where sourceDate = ?
        order by epochStartEpochMs
        """,
        (source_date,),
    ).fetchall()


def ppi_summary(conn: sqlite3.Connection, source_date: str) -> PpiSummary:
    return ppi_summary_from_rows(source_date, ppi_rows_for_date(conn, source_date))


def ppi_summary_for_window(conn: sqlite3.Connection, source_date: str, start_ms: int, end_ms: int) -> PpiSummary:
    rows = conn.execute(
        """
        select * from ppi247_epoch
        where sourceDate in (?, date(?, '-1 day'))
          and epochStartEpochMs < ?
          and epochEndEpochMs > ?
        order by epochStartEpochMs
        """,
        (source_date, source_date, end_ms, start_ms),
    ).fetchall()
    return ppi_summary_from_rows(source_date, rows)


def count_for_date(conn: sqlite3.Connection, table: str, source_date: str) -> int:
    if table not in SOURCE_DATE_TABLES:
        raise ValueError(f"Unsupported source-date table: {table}")
    row = one(conn, f"select count(*) as n from {table} where sourceDate = ?", (source_date,))
    return int(row["n"]) if row else 0


def sleep_episode_rows(conn: sqlite3.Connection, source_date: str) -> list[sqlite3.Row]:
    return conn.execute(
        """
        select * from sleep_episode
        where sourceDate = ?
        order by sourceDate, startEpochMs, id
        """,
        (source_date,),
    ).fetchall()


def latest_prediction(conn: sqlite3.Connection, source_date: str) -> sqlite3.Row | None:
    return one(
        conn,
        """
        select * from morning_prediction_snapshot
        where sourceDate = ?
        order by issuedAtEpochMs desc, id desc
        limit 1
        """,
        (source_date,),
    )


def daily_review(conn: sqlite3.Connection, source_date: str) -> sqlite3.Row | None:
    return one(conn, "select * from daily_check_in where sourceDate = ?", (source_date,))


def review_outcome(row: sqlite3.Row | None) -> str | None:
    value = row_value(row, "eveningOutcome")
    return value if value in OUTCOME_ORDER else None


def outcome_movement_label(trigger_outcome: str | None, worst_outcome: str | None) -> str:
    if trigger_outcome not in OUTCOME_ORDER and worst_outcome not in OUTCOME_ORDER:
        return "outcome unknown"
    if trigger_outcome not in OUTCOME_ORDER:
        return f"unknown -> {worst_outcome}"
    if worst_outcome not in OUTCOME_ORDER:
        return f"{trigger_outcome} -> unknown"
    delta = OUTCOME_ORDER[worst_outcome] - OUTCOME_ORDER[trigger_outcome]
    if delta > 0:
        direction = "worse"
    elif delta < 0:
        direction = "better"
    else:
        direction = "same"
    return f"{trigger_outcome} -> {worst_outcome} ({direction})"


def major_task_trigger_type(row: sqlite3.Row) -> str | None:
    if row_bool(row, "majorTask") != True:
        return None
    return row_value(row, "majorTaskType") or "major_task"


def is_lag_affected_day(row: sqlite3.Row) -> bool:
    outcome = review_outcome(row)
    return (
        row_bool(row, "pemPaybackToday") == True
        or row_bool(row, "paybackPeakToday") == True
        or row_bool(row, "mostlyHorizontal") == True
        or outcome in {"UNSTEADY", "CRASH"}
    )


def first_recovery_after_peak(
    reviews_by_date: dict[str, sqlite3.Row],
    peak_date: str | None,
    window_dates: list[str],
) -> int | None:
    if peak_date is None or peak_date not in window_dates:
        return None
    peak_index = window_dates.index(peak_date)
    for candidate in window_dates[peak_index + 1:]:
        row = reviews_by_date.get(candidate)
        if row is None:
            continue
        outcome = review_outcome(row)
        if (
            row_bool(row, "pemPaybackToday") != True
            and row_bool(row, "mostlyHorizontal") != True
            and outcome in {"GOOD", "OK"}
        ):
            return days_between(peak_date, candidate)
    return None


def build_pem_lag_episodes(
    conn: sqlite3.Connection,
    start_date: str | None,
    end_date: str,
    lag_days: int = 5,
) -> list[PemLagEpisode]:
    if lag_days < 1:
        return []
    trigger_query = """
        select * from daily_check_in
        where majorTask = 1
          and sourceDate <= ?
    """
    params: list[Any] = [end_date]
    if start_date:
        trigger_query += " and sourceDate >= ?"
        params.append(start_date)
    trigger_query += " order by sourceDate"

    episodes: list[PemLagEpisode] = []
    for trigger in conn.execute(trigger_query, tuple(params)):
        trigger_date = trigger["sourceDate"]
        trigger_type = major_task_trigger_type(trigger)
        if trigger_type is None:
            continue
        trigger_day = date.fromisoformat(trigger_date)
        window_dates = []
        for offset in range(1, lag_days + 1):
            window_date = (trigger_day + timedelta(days=offset)).isoformat()
            if window_date <= end_date:
                window_dates.append(window_date)
        if not window_dates:
            continue
        placeholders = ",".join("?" for _ in window_dates)
        rows = conn.execute(
            f"select * from daily_check_in where sourceDate in ({placeholders})",
            tuple(window_dates),
        ).fetchall()
        reviews_by_date = {row["sourceDate"]: row for row in rows}
        missing_dates = tuple(day for day in window_dates if day not in reviews_by_date)
        pem_dates = tuple(
            day for day in window_dates
            if row_bool(reviews_by_date.get(day), "pemPaybackToday") == True
        )
        mostly_horizontal_dates = tuple(
            day for day in window_dates
            if row_bool(reviews_by_date.get(day), "mostlyHorizontal") == True
        )
        affected_dates = tuple(
            day for day in window_dates
            if day in reviews_by_date and is_lag_affected_day(reviews_by_date[day])
        )
        peak_dates = [
            day for day in window_dates
            if row_bool(reviews_by_date.get(day), "paybackPeakToday") == True
        ]
        peak_date = peak_dates[0] if peak_dates else None
        reviewed_outcomes = [
            review_outcome(reviews_by_date[day])
            for day in window_dates
            if review_outcome(reviews_by_date.get(day)) in OUTCOME_ORDER
        ]
        worst_outcome = max(reviewed_outcomes, key=lambda item: OUTCOME_ORDER[item]) if reviewed_outcomes else None
        trigger_outcome = review_outcome(trigger)
        episodes.append(
            PemLagEpisode(
                trigger_date=trigger_date,
                trigger_type=trigger_type,
                window_dates=tuple(window_dates),
                affected_dates=affected_dates,
                missing_dates=missing_dates,
                pem_dates=pem_dates,
                mostly_horizontal_dates=mostly_horizontal_dates,
                peak_date=peak_date,
                peak_lag_days=days_between(trigger_date, peak_date) if peak_date else None,
                recovery_tail_days=first_recovery_after_peak(reviews_by_date, peak_date, window_dates),
                trigger_outcome=trigger_outcome,
                worst_outcome=worst_outcome,
                outcome_movement=outcome_movement_label(trigger_outcome, worst_outcome),
            )
        )
    return episodes


def selected_episode(episodes: list[sqlite3.Row]) -> sqlite3.Row | None:
    primary = [
        row for row in episodes
        if row["isPrimaryForReadiness"] and row["startEpochMs"] is not None and row["endEpochMs"] is not None
    ]
    if primary:
        return primary[-1]
    confirmed_timed = [
        row for row in episodes
        if row["confidence"] == "user_confirmed" and row["startEpochMs"] is not None and row["endEpochMs"] is not None
    ]
    return confirmed_timed[-1] if confirmed_timed else None


def active_evidence(conn: sqlite3.Connection, source_date: str) -> tuple[str, str | None, PpiSummary]:
    episodes = sleep_episode_rows(conn, source_date)
    if any(row["episodeKind"] == "no_sleep" and row["confidence"] == "user_confirmed" for row in episodes):
        day_summary = ppi_summary(conn, source_date)
        return "no_main_sleep_date_ppi", "user_confirmed_no_sleep", day_summary

    episode = selected_episode(episodes)
    if episode is not None:
        window_summary = ppi_summary_for_window(
            conn,
            source_date,
            int(episode["startEpochMs"]),
            int(episode["endEpochMs"]),
        )
        if window_summary.available:
            source = f"{episode['source']}:{episode['episodeKind']}"
            return "episode_window_ppi", source, window_summary

    day_summary = ppi_summary(conn, source_date)
    if day_summary.available:
        return "source_date_ppi", "source_date_24h", day_summary
    if count_for_date(conn, "sleep_night_raw", source_date) > 0:
        return "loop_sleep_without_ppi", "loop_sleep_report", day_summary
    return "no_current_evidence", None, day_summary


def prior_baseline(conn: sqlite3.Connection, source_date: str, baseline_days: int) -> tuple[int, float | None, float | None]:
    target = date.fromisoformat(source_date)
    rmssd_values: list[float] = []
    ppi_values: list[float] = []
    for offset in range(1, baseline_days + 1):
        prior_date = (target - timedelta(days=offset)).isoformat()
        summary = ppi_summary(conn, prior_date)
        if not summary.available:
            continue
        rmssd_values.append(summary.mean_rmssd_ms)
        if summary.mean_ppi_ms is not None:
            ppi_values.append(summary.mean_ppi_ms)
    baseline_count = max(len(rmssd_values), len(ppi_values))
    return (
        baseline_count,
        round(mean(rmssd_values), 2) if rmssd_values else None,
        round(mean(ppi_values), 2) if ppi_values else None,
    )


def pct_delta(current: float | None, baseline: float | None) -> float | None:
    if current is None or baseline in (None, 0):
        return None
    return round((current - baseline) / baseline, 4)


def recent_functional_context(conn: sqlite3.Connection, source_date: str) -> tuple[str | None, int, list[str]]:
    target = date.fromisoformat(source_date)
    prior_reviews: list[sqlite3.Row] = []
    for offset in range(1, 4):
        review = daily_review(conn, (target - timedelta(days=offset)).isoformat())
        if review is not None:
            prior_reviews.append(review)
    if not prior_reviews:
        return None, 0, []

    latest_outcome = prior_reviews[0]["eveningOutcome"]
    rough_days = sum(1 for row in prior_reviews if row["eveningOutcome"] in {"UNSTEADY", "CRASH"})
    penalty = 0
    notes: list[str] = []
    if latest_outcome == "CRASH":
        penalty += 2
        notes.append("The previous saved outcome was CRASH.")
    elif latest_outcome == "UNSTEADY":
        penalty += 1
        notes.append("The previous saved outcome was UNSTEADY.")
    if rough_days >= 2:
        penalty += 1
        notes.append("Recent saved outcomes have been repeatedly rough.")
    if any(row_bool(row, "pemPaybackToday") for row in prior_reviews):
        penalty += 1
        notes.append("Recent Journal V2 markers include PEM/payback.")
    if any(row_bool(row, "paybackPeakToday") for row in prior_reviews):
        penalty += 1
        notes.append("A recent day was marked as the peak of a payback spell.")
    if any(row_bool(row, "mostlyHorizontal") for row in prior_reviews):
        penalty += 1
        notes.append("Recent day-shape markers include mostly-horizontal time.")
    if any(row_value(row, "majorTaskType") == "site_visit" for row in prior_reviews):
        notes.append("A recent major-task marker was a site visit.")
    if penalty == 0 and latest_outcome in {"GOOD", "OK"}:
        notes.append(f"The previous saved outcome was {latest_outcome}.")
    return latest_outcome, min(penalty, 3), notes


def functional_status_from_context(
    prior_state: str | None,
    penalty: int,
) -> str | None:
    if prior_state is None:
        return None
    if penalty <= 0:
        return prior_state if prior_state in {"GOOD", "OK"} else "OK"
    if penalty == 1:
        return "OK"
    if penalty == 2:
        return "UNSTEADY"
    return "CRASH"


def combine_planning_status(
    autonomic_status: str | None,
    functional_status: str | None,
    autonomic_stability: str,
) -> tuple[str | None, str]:
    if autonomic_status not in STATUS_ORDER and functional_status not in STATUS_ORDER:
        return None, "unknown"
    if autonomic_status not in STATUS_ORDER:
        return functional_status, "function_limited"
    if functional_status not in STATUS_ORDER:
        return autonomic_status, autonomic_stability

    autonomic_score = STATUS_ORDER[autonomic_status]
    functional_score = STATUS_ORDER[functional_status]
    if functional_score > autonomic_score:
        return functional_status, "function_limited"
    if autonomic_score > functional_score:
        return autonomic_status, "autonomic_limited"
    return autonomic_status, autonomic_stability


def state_from_evidence(
    summary: PpiSummary,
    baseline_count: int,
    rmssd_delta: float | None,
    ppi_delta: float | None,
    subjective_penalty: int,
    subjective_notes: list[str],
) -> tuple[str | None, str, list[str]]:
    if not summary.available:
        return None, "unknown", ["No usable PPI evidence for this source date."]

    score = 0
    notes: list[str] = []
    if baseline_count >= MIN_BASELINE_DAYS:
        if rmssd_delta is not None and rmssd_delta <= -0.25:
            score += 2
            notes.append("RMSSD is sharply below the recent personal baseline.")
        elif rmssd_delta is not None and rmssd_delta <= -0.15:
            score += 1
            notes.append("RMSSD is below the recent personal baseline.")

        if ppi_delta is not None and ppi_delta <= -0.08:
            score += 1
            notes.append("Mean PPI is lower than recent baseline, consistent with higher HR.")
    elif summary.mean_rmssd_ms is not None and summary.mean_rmssd_ms < 50:
        score += 1
        notes.append("Absolute RMSSD is low enough to treat cautiously until baseline grows.")
    else:
        notes.append("Recent personal baseline is still thin.")

    if subjective_penalty:
        score += subjective_penalty
        notes.extend(subjective_notes)

    if summary.usable_epoch_count < 24:
        notes.append("PPI coverage is short, so this is a weak current-state read.")
    if (summary.usable_ratio or 0.0) < 0.55:
        notes.append("A large share of PPI epochs were low quality.")

    if score <= 0:
        return "GOOD", "steady", notes or ["No major warning pattern stood out in current evidence."]
    if score == 1:
        return "OK", "watch", notes
    if score == 2:
        return "UNSTEADY", "strained", notes
    return "CRASH", "strained", notes


def robustness(summary: PpiSummary, baseline_count: int, nightly_available: bool) -> str:
    if not summary.available:
        return "low"
    usable_ratio = summary.usable_ratio or 0.0
    if summary.usable_epoch_count < 12 or usable_ratio < 0.45:
        return "low"
    if summary.usable_epoch_count >= 96 and baseline_count >= 7 and usable_ratio >= 0.7 and nightly_available:
        return "high"
    if summary.usable_epoch_count >= 48 and baseline_count >= MIN_BASELINE_DAYS and usable_ratio >= 0.6:
        return "medium"
    return "low"


def build_day(conn: sqlite3.Connection, source_date: str, baseline_days: int) -> CurrentStateDay:
    evidence_basis, active_window_source, summary = active_evidence(conn, source_date)
    baseline_count, baseline_rmssd, baseline_ppi = prior_baseline(conn, source_date, baseline_days)
    rmssd_delta = pct_delta(summary.mean_rmssd_ms, baseline_rmssd)
    ppi_delta = pct_delta(summary.mean_ppi_ms, baseline_ppi)
    prior_subjective_state, subjective_penalty, functional_notes = recent_functional_context(conn, source_date)
    autonomic_status, autonomic_stability, autonomic_notes = state_from_evidence(
        summary,
        baseline_count,
        rmssd_delta,
        ppi_delta,
        0,
        [],
    )
    functional_status = functional_status_from_context(prior_subjective_state, subjective_penalty)
    planning_status, stability = combine_planning_status(
        autonomic_status,
        functional_status,
        autonomic_stability,
    )
    notes = list(autonomic_notes)
    if functional_notes:
        notes.extend(functional_notes)
    nightly_available = count_for_date(conn, "nightly_recharge_raw", source_date) > 0
    signal_robustness = robustness(summary, baseline_count, nightly_available)
    if count_for_date(conn, "sleep_night_raw", source_date) > 0 and evidence_basis != "loop_sleep_without_ppi":
        notes.append("Loop sleep report exists as supporting context, but v2 did not require it.")
    if nightly_available:
        notes.append("Nightly Recharge exists as supporting context.")

    prediction = latest_prediction(conn, source_date)
    review = daily_review(conn, source_date)
    return CurrentStateDay(
        source_date=source_date,
        autonomic_status=autonomic_status,
        functional_status=functional_status,
        planning_status=planning_status,
        current_status=planning_status,
        signal_robustness=signal_robustness,
        state_stability=stability,
        evidence_basis=evidence_basis,
        active_window_source=active_window_source,
        baseline_days=baseline_count,
        ppi_epoch_count=summary.epoch_count,
        ppi_usable_epoch_count=summary.usable_epoch_count,
        ppi_good_epoch_count=summary.good_epoch_count,
        ppi_poor_epoch_count=summary.poor_epoch_count,
        mean_rmssd_ms=summary.mean_rmssd_ms,
        rmssd_delta_pct=rmssd_delta,
        mean_ppi_ms=summary.mean_ppi_ms,
        ppi_delta_pct=ppi_delta,
        prior_subjective_state=prior_subjective_state,
        prior_subjective_penalty=subjective_penalty,
        functional_notes=tuple(functional_notes[:5]),
        day_shape_captured=row_bool(review, "dayShapeCaptured"),
        mostly_horizontal=row_bool(review, "mostlyHorizontal"),
        left_house=row_bool(review, "leftHouse"),
        major_task=row_bool(review, "majorTask"),
        major_task_type=row_value(review, "majorTaskType"),
        pem_payback_today=row_bool(review, "pemPaybackToday"),
        payback_peak_today=row_bool(review, "paybackPeakToday"),
        payback_peak_confidence=row_value(review, "paybackPeakConfidence"),
        old_readiness_status=prediction["status"] if prediction else None,
        old_readiness_confidence=prediction["confidence"] if prediction else None,
        evening_outcome=review["eveningOutcome"] if review else None,
        approach_to_day=row_value(review, "approachToDay"),
        notes=tuple(notes[:5]),
    )


def status_delta(prediction: str | None, outcome: str | None) -> int | None:
    if prediction not in STATUS_ORDER or outcome not in OUTCOME_ORDER:
        return None
    return OUTCOME_ORDER[outcome] - STATUS_ORDER[prediction]


def alignment_for(days: list[CurrentStateDay], status_attr: str) -> dict[str, Any]:
    paired = [day for day in days if getattr(day, status_attr) in STATUS_ORDER and day.has_outcome]
    deltas = [status_delta(getattr(day, status_attr), day.evening_outcome) for day in paired]
    bucket_counts: Counter[str] = Counter()
    for delta in deltas:
        if delta == 0:
            bucket_counts["matched"] += 1
        elif delta is not None and delta > 0:
            bucket_counts["outcome_worse"] += 1
        elif delta is not None and delta < 0:
            bucket_counts["outcome_better"] += 1
    within_one = sum(1 for delta in deltas if delta is not None and abs(delta) <= 1)
    return {
        "paired_count": len(paired),
        "exact_matches": bucket_counts["matched"],
        "exact_match_rate": round(bucket_counts["matched"] / len(paired), 3) if paired else None,
        "within_one_step": within_one,
        "within_one_step_rate": round(within_one / len(paired), 3) if paired else None,
        "bucket_counts": dict(bucket_counts),
        "mean_ordinal_delta": round(mean(deltas), 2) if deltas else None,
    }


def current_vs_old(days: list[CurrentStateDay]) -> dict[str, Any]:
    comparable = [day for day in days if day.has_current_status and day.has_old_status]
    changed: list[str] = []
    more_cautious: list[str] = []
    less_cautious: list[str] = []
    for day in comparable:
        current = STATUS_ORDER[day.current_status or "GOOD"]
        old = STATUS_ORDER[day.old_readiness_status or "GOOD"]
        if current != old:
            changed.append(day.source_date)
        if current > old:
            more_cautious.append(day.source_date)
        elif current < old:
            less_cautious.append(day.source_date)
    return {
        "comparable_days": len(comparable),
        "changed_days": len(changed),
        "more_cautious_days": len(more_cautious),
        "less_cautious_days": len(less_cautious),
        "more_cautious_examples": more_cautious[:8],
        "less_cautious_examples": less_cautious[:8],
    }


def build_report(conn: sqlite3.Connection, start_date: str | None, end_date: str, baseline_days: int) -> dict[str, Any]:
    days = [build_day(conn, source_date, baseline_days) for source_date in known_dates(conn, start_date, end_date)]
    planning_alignment = alignment_for(days, "planning_status")
    autonomic_alignment = alignment_for(days, "autonomic_status")
    functional_alignment = alignment_for(days, "functional_status")
    old_alignment = alignment_for(days, "old_readiness_status")
    pem_lag_episodes = build_pem_lag_episodes(conn, start_date, end_date)
    return {
        "day_count": len(days),
        "current_status_days": sum(day.has_current_status for day in days),
        "autonomic_status_days": sum(day.autonomic_status in STATUS_ORDER for day in days),
        "functional_status_days": sum(day.functional_status in STATUS_ORDER for day in days),
        "planning_status_days": sum(day.planning_status in STATUS_ORDER for day in days),
        "old_readiness_days": sum(day.has_old_status for day in days),
        "outcome_days": sum(day.has_outcome for day in days),
        "paired_current_days": planning_alignment["paired_count"],
        "enough_for_directional_signal": planning_alignment["paired_count"] >= MIN_DIRECTIONAL_PAIRED_DAYS,
        "enough_for_careful_tuning": planning_alignment["paired_count"] >= MIN_CAREFUL_TUNING_DAYS,
        "basis_counts": dict(Counter(day.evidence_basis for day in days)),
        "robustness_counts": dict(Counter(day.signal_robustness for day in days)),
        "autonomic_state_counts": dict(Counter(day.autonomic_status or "missing" for day in days)),
        "functional_state_counts": dict(Counter(day.functional_status or "missing" for day in days)),
        "planning_state_counts": dict(Counter(day.planning_status or "missing" for day in days)),
        "state_counts": dict(Counter(day.current_status or "missing" for day in days)),
        "stability_counts": dict(Counter(day.state_stability for day in days)),
        "current_alignment": planning_alignment,
        "planning_alignment": planning_alignment,
        "autonomic_alignment": autonomic_alignment,
        "functional_alignment": functional_alignment,
        "old_readiness_alignment": old_alignment,
        "current_vs_old": current_vs_old(days),
        "pem_lag_episode_count": len(pem_lag_episodes),
        "pem_lag_episodes": [asdict(episode) for episode in pem_lag_episodes],
        "days": [asdict(day) for day in days],
        "humility_note": (
            "This is a baseline v2 shape test, not a trained model. It asks what "
            "Lodestone can say from autonomic and recent functional evidence without "
            "requiring a canonical sleep session."
        ),
    }


def preview(values: list[str], limit: int = 8) -> str:
    if not values:
        return "none"
    text = ", ".join(values[:limit])
    if len(values) > limit:
        text += ", ..."
    return text


def print_alignment(title: str, alignment: dict[str, Any]) -> None:
    print(title)
    print(f"  paired days: {alignment['paired_count']}")
    print(f"  exact matches: {alignment['exact_matches']} rate={alignment['exact_match_rate']}")
    print(f"  within one step: {alignment['within_one_step']} rate={alignment['within_one_step_rate']}")
    for bucket in ("matched", "outcome_worse", "outcome_better"):
        print(f"  {bucket}: {alignment['bucket_counts'].get(bucket, 0)}")
    print(f"  mean ordinal delta: {alignment['mean_ordinal_delta']}")
    print()


def print_report(report: dict[str, Any]) -> None:
    print("Current-state baseline v2")
    print(f"  days scanned: {report['day_count']}")
    print(f"  autonomic status days: {report['autonomic_status_days']}")
    print(f"  functional status days: {report['functional_status_days']}")
    print(f"  planning status days: {report['planning_status_days']}")
    print(f"  old readiness days: {report['old_readiness_days']}")
    print(f"  outcome days: {report['outcome_days']}")
    print(f"  paired planning/outcome days: {report['paired_current_days']}")
    print(f"  enough for directional signal: {'yes' if report['enough_for_directional_signal'] else 'no'}")
    print(f"  enough for careful tuning: {'yes' if report['enough_for_careful_tuning'] else 'no'}")
    print(f"  caution: {report['humility_note']}")
    print()
    print("Evidence basis")
    for key, count in sorted(report["basis_counts"].items()):
        print(f"  {key}: {count}")
    print("Robustness")
    for key, count in sorted(report["robustness_counts"].items()):
        print(f"  {key}: {count}")
    print("Autonomic statuses")
    for key, count in sorted(report["autonomic_state_counts"].items(), key=lambda item: STATUS_ORDER.get(item[0], 99)):
        print(f"  {key}: {count}")
    print("Functional statuses")
    for key, count in sorted(report["functional_state_counts"].items(), key=lambda item: STATUS_ORDER.get(item[0], 99)):
        print(f"  {key}: {count}")
    print("Planning statuses")
    for key, count in sorted(report["planning_state_counts"].items(), key=lambda item: STATUS_ORDER.get(item[0], 99)):
        print(f"  {key}: {count}")
    print()
    print_alignment("Autonomic lane alignment", report["autonomic_alignment"])
    print_alignment("Functional lane alignment", report["functional_alignment"])
    print_alignment("Planning-state alignment", report["planning_alignment"])
    print_alignment("Old readiness alignment", report["old_readiness_alignment"])
    comparison = report["current_vs_old"]
    print("Current-state vs old readiness")
    print(f"  comparable days: {comparison['comparable_days']}")
    print(f"  changed days: {comparison['changed_days']}")
    print(f"  more cautious than old: {comparison['more_cautious_days']} ({preview(comparison['more_cautious_examples'])})")
    print(f"  less cautious than old: {comparison['less_cautious_days']} ({preview(comparison['less_cautious_examples'])})")
    print()
    print("Delayed PEM lag episodes")
    print(f"  trigger episodes: {report['pem_lag_episode_count']}")
    for episode in report["pem_lag_episodes"][:8]:
        print(
            "  "
            f"{episode['trigger_date']} {episode['trigger_type']}: "
            f"affected={preview(episode['affected_dates'], 5)}, "
            f"peak={episode['peak_date'] or 'unknown'}, "
            f"lag={episode['peak_lag_days'] if episode['peak_lag_days'] is not None else 'unknown'}d, "
            f"tail={episode['recovery_tail_days'] if episode['recovery_tail_days'] is not None else 'unknown'}d, "
            f"missing={preview(episode['missing_dates'], 5)}, "
            f"outcome={episode['outcome_movement']}"
        )


def main() -> None:
    args = parse_args()
    with connect(args.health_db) as conn:
        report = build_report(conn, args.start_date, args.end_date, args.baseline_days)
    if args.json:
        print(json.dumps(report, indent=2))
    else:
        print_report(report)


if __name__ == "__main__":
    main()
