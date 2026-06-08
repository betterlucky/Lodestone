#!/usr/bin/env python3

from __future__ import annotations

import argparse
import importlib.util
import json
import math
import sqlite3
import sys
from collections import defaultdict
from dataclasses import asdict, dataclass
from datetime import date, timedelta
from pathlib import Path
from statistics import mean, median
from typing import Any

STATUS_ORDER = {"GOOD": 0, "OK": 1, "UNSTEADY": 2, "CRASH": 3}
STATUS_BY_ORDINAL = {value: key for key, value in STATUS_ORDER.items()}
MIN_DIRECTIONAL_PAIRS = 14
MIN_CAREFUL_TUNING_PAIRS = 45
FULL_DAY_COVERAGE_MINUTES = 20 * 60

HUMILITY_NOTE = (
    "This is an exploratory exertional-load report, not a trained load budget. "
    "Use it to watch whether EL features keep explaining later SF/PEM outcomes "
    "as more paired data accumulates."
)


@dataclass(frozen=True)
class ActivityDay:
    source_date: str
    steps: float | None
    activity_calories: float | None
    achieved_activity: float | None
    distance_m: float | None
    epoch_steps: float | None
    coverage_minutes: float | None
    avg_met: float | None
    met2_minutes: float | None
    met15_minutes: float | None

    @property
    def has_activity(self) -> bool:
        return self.steps is not None or self.epoch_steps is not None

    @property
    def has_full_epoch_coverage(self) -> bool:
        return (self.coverage_minutes or 0.0) >= FULL_DAY_COVERAGE_MINUTES


@dataclass(frozen=True)
class ReviewDay:
    source_date: str
    outcome: str | None
    approach: str | None
    muscle_weakness: bool | None
    mostly_horizontal: bool | None
    left_house: bool | None
    major_task: bool | None
    major_task_type: str | None
    pem_payback: bool | None
    payback_peak: bool | None


@dataclass(frozen=True)
class SnapshotDay:
    source_date: str
    status: str | None
    confidence: str | None


@dataclass(frozen=True)
class ReportDay:
    source_date: str
    steps: float | None
    activity_calories: float | None
    achieved_activity: float | None
    distance_m: float | None
    coverage_minutes: float | None
    coverage_ok: bool
    avg_met: float | None
    met2_minutes: float | None
    met15_minutes: float | None
    rolling_step_sum_2d: float | None
    rolling_step_sum_3d: float | None
    rolling_step_sum_7d: float | None
    step_spike_ratio_7d: float | None
    step_delta_vs_prior_3d: float | None
    outcome: str | None
    approach: str | None
    muscle_weakness: bool | None
    mostly_horizontal: bool | None
    left_house: bool | None
    major_task: bool | None
    major_task_type: str | None
    pem_payback: bool | None
    payback_peak: bool | None
    snapshot_status: str | None
    current_status: str | None
    planning_status: str | None
    autonomic_status: str | None
    functional_status: str | None
    stability: str | None
    autonomic_context: str | None


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Explore whether exertional load predicts later Lodestone outcomes "
            "and whether state/recovery signals predict actual activity."
        )
    )
    parser.add_argument("--health-db", required=True, help="Path to the Lodestone SQLite database.")
    parser.add_argument("--start-date", help="First source date to include, YYYY-MM-DD.")
    parser.add_argument("--end-date", default=date.today().isoformat(), help="Last source date to include, YYYY-MM-DD.")
    parser.add_argument("--json", action="store_true", help="Emit JSON instead of compact text.")
    return parser.parse_args()


def connect(path: str | Path) -> sqlite3.Connection:
    conn = sqlite3.connect(path)
    conn.row_factory = sqlite3.Row
    return conn


def table_exists(conn: sqlite3.Connection, table_name: str) -> bool:
    row = conn.execute(
        "select 1 from sqlite_master where type = 'table' and name = ?",
        (table_name,),
    ).fetchone()
    return row is not None


def row_value(row: sqlite3.Row | None, column: str) -> Any:
    if row is None or column not in row.keys():
        return None
    return row[column]


def row_bool(row: sqlite3.Row | None, column: str) -> bool | None:
    value = row_value(row, column)
    if value is None:
        return None
    return bool(value)


def valid_iso_date(value: str | None) -> bool:
    if not value:
        return False
    try:
        date.fromisoformat(value)
        return True
    except ValueError:
        return False


def add_days(source_date: str, offset: int) -> str:
    return (date.fromisoformat(source_date) + timedelta(days=offset)).isoformat()


def date_range(start_date: str, end_date: str) -> list[str]:
    start = date.fromisoformat(start_date)
    end = date.fromisoformat(end_date)
    if end < start:
        return []
    return [(start + timedelta(days=offset)).isoformat() for offset in range((end - start).days + 1)]


def numeric(value: Any) -> float | None:
    if value is None:
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def status_ordinal(value: str | None) -> int | None:
    return STATUS_ORDER.get(value or "")


def quantile(values: list[float], percentile: float) -> float | None:
    clean = sorted(value for value in values if value is not None)
    if not clean:
        return None
    bounded = max(0.0, min(1.0, percentile))
    index = (len(clean) - 1) * bounded
    lower = int(index)
    upper = min(lower + 1, len(clean) - 1)
    if lower == upper:
        return clean[lower]
    upper_weight = index - lower
    return clean[lower] * (1.0 - upper_weight) + clean[upper] * upper_weight


def ordinal_label(value: float | None) -> str | None:
    if value is None:
        return None
    rounded = int(round(value))
    return STATUS_BY_ORDINAL.get(max(0, min(3, rounded)))


def spearman(xs: list[float | int | None], ys: list[float | int | None]) -> tuple[float | None, int]:
    pairs = [(float(x), float(y)) for x, y in zip(xs, ys) if x is not None and y is not None]
    count = len(pairs)
    if count < 3:
        return None, count

    def ranks(values: list[float]) -> list[float]:
        sorted_values = sorted((value, index) for index, value in enumerate(values))
        output = [0.0] * len(values)
        index = 0
        while index < len(sorted_values):
            end = index
            while end < len(sorted_values) and sorted_values[end][0] == sorted_values[index][0]:
                end += 1
            rank = (index + end - 1) / 2.0 + 1.0
            for _, original_index in sorted_values[index:end]:
                output[original_index] = rank
            index = end
        return output

    x_ranks = ranks([pair[0] for pair in pairs])
    y_ranks = ranks([pair[1] for pair in pairs])
    x_mean = mean(x_ranks)
    y_mean = mean(y_ranks)
    x_scale = math.sqrt(sum((value - x_mean) ** 2 for value in x_ranks))
    y_scale = math.sqrt(sum((value - y_mean) ** 2 for value in y_ranks))
    if x_scale == 0.0 or y_scale == 0.0:
        return None, count
    coefficient = sum((x - x_mean) * (y - y_mean) for x, y in zip(x_ranks, y_ranks)) / (x_scale * y_scale)
    return round(coefficient, 3), count


def safe_median(values: list[float]) -> float | None:
    return float(median(values)) if values else None


def safe_mean(values: list[float]) -> float | None:
    return float(mean(values)) if values else None


def parse_json_payload(raw_payload: str | None) -> dict[str, Any]:
    if not raw_payload:
        return {}
    try:
        payload = json.loads(raw_payload)
    except json.JSONDecodeError:
        return {}
    return payload if isinstance(payload, dict) else {}


def load_daily_summary_activity(conn: sqlite3.Connection, start_date: str | None, end_date: str) -> dict[str, dict[str, float | None]]:
    if not table_exists(conn, "daily_summary_raw"):
        return {}
    params: list[Any] = [end_date]
    where = "sourceDate is not null and sourceDate <= ?"
    if start_date:
        where += " and sourceDate >= ?"
        params.append(start_date)
    rows = conn.execute(
        f"select sourceDate, rawPayloadJson from daily_summary_raw where {where} order by sourceDate",
        params,
    ).fetchall()
    activity: dict[str, dict[str, float | None]] = {}
    for row in rows:
        source_date = row["sourceDate"]
        if not valid_iso_date(source_date):
            continue
        payload = parse_json_payload(row["rawPayloadJson"])
        goal = payload.get("activityGoalSummary") if isinstance(payload.get("activityGoalSummary"), dict) else {}
        activity[source_date] = {
            "steps": numeric(payload.get("steps")),
            "activity_calories": numeric(payload.get("activityCalories")),
            "distance_m": numeric(payload.get("activityDistance")),
            "achieved_activity": numeric(goal.get("achievedActivity")),
        }
    return activity


def load_activity_epochs(conn: sqlite3.Connection, start_date: str | None, end_date: str) -> dict[str, sqlite3.Row]:
    if not table_exists(conn, "activity_epoch"):
        return {}
    params: list[Any] = [end_date]
    where = "sourceDate <= ?"
    if start_date:
        where += " and sourceDate >= ?"
        params.append(start_date)
    rows = conn.execute(
        f"""
        select
            sourceDate,
            count(*) as epoch_count,
            sum((epochEndEpochMs - epochStartEpochMs) / 60000.0) as coverage_minutes,
            sum(coalesce(steps, 0)) as epoch_steps,
            avg(met) as avg_met,
            sum(case when met >= 2.0 then (epochEndEpochMs - epochStartEpochMs) / 60000.0 else 0 end) as met2_minutes,
            sum(case when met >= 1.5 then (epochEndEpochMs - epochStartEpochMs) / 60000.0 else 0 end) as met15_minutes
        from activity_epoch
        where {where}
        group by sourceDate
        order by sourceDate
        """,
        params,
    ).fetchall()
    return {row["sourceDate"]: row for row in rows if valid_iso_date(row["sourceDate"])}


def load_activity_days(conn: sqlite3.Connection, start_date: str | None, end_date: str) -> dict[str, ActivityDay]:
    daily = load_daily_summary_activity(conn, start_date, end_date)
    epochs = load_activity_epochs(conn, start_date, end_date)
    source_dates = sorted(set(daily) | set(epochs))
    result: dict[str, ActivityDay] = {}
    for source_date in source_dates:
        daily_row = daily.get(source_date, {})
        epoch_row = epochs.get(source_date)
        steps = daily_row.get("steps")
        epoch_steps = numeric(row_value(epoch_row, "epoch_steps"))
        result[source_date] = ActivityDay(
            source_date=source_date,
            steps=steps if steps is not None else epoch_steps,
            activity_calories=daily_row.get("activity_calories"),
            achieved_activity=daily_row.get("achieved_activity"),
            distance_m=daily_row.get("distance_m"),
            epoch_steps=epoch_steps,
            coverage_minutes=numeric(row_value(epoch_row, "coverage_minutes")),
            avg_met=numeric(row_value(epoch_row, "avg_met")),
            met2_minutes=numeric(row_value(epoch_row, "met2_minutes")),
            met15_minutes=numeric(row_value(epoch_row, "met15_minutes")),
        )
    return result


def load_reviews(conn: sqlite3.Connection, start_date: str | None, end_date: str) -> dict[str, ReviewDay]:
    if not table_exists(conn, "daily_check_in"):
        return {}
    params: list[Any] = [end_date]
    where = "sourceDate <= ?"
    if start_date:
        where += " and sourceDate >= ?"
        params.append(start_date)
    rows = conn.execute(f"select * from daily_check_in where {where} order by sourceDate", params).fetchall()
    result: dict[str, ReviewDay] = {}
    for row in rows:
        source_date = row["sourceDate"]
        if not valid_iso_date(source_date):
            continue
        result[source_date] = ReviewDay(
            source_date=source_date,
            outcome=row_value(row, "eveningOutcome"),
            approach=row_value(row, "approachToDay"),
            muscle_weakness=row_bool(row, "muscleWeaknessToday"),
            mostly_horizontal=row_bool(row, "mostlyHorizontal"),
            left_house=row_bool(row, "leftHouse"),
            major_task=row_bool(row, "majorTask"),
            major_task_type=row_value(row, "majorTaskType"),
            pem_payback=row_bool(row, "pemPaybackToday"),
            payback_peak=row_bool(row, "paybackPeakToday"),
        )
    return result


def load_latest_snapshots(conn: sqlite3.Connection, start_date: str | None, end_date: str) -> dict[str, SnapshotDay]:
    if not table_exists(conn, "morning_prediction_snapshot"):
        return {}
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
    latest: dict[str, SnapshotDay] = {}
    for row in rows:
        source_date = row["sourceDate"]
        if not valid_iso_date(source_date) or source_date in latest:
            continue
        latest[source_date] = SnapshotDay(
            source_date=source_date,
            status=row_value(row, "status"),
            confidence=row_value(row, "confidence"),
        )
    return latest


def load_current_state_days(
    conn: sqlite3.Connection,
    start_date: str | None,
    end_date: str,
) -> dict[str, dict[str, Any]]:
    try:
        current_state = load_current_state_module()
        report = current_state.build_report(conn, start_date, end_date, baseline_days=14)
    except Exception:
        return {}
    return {
        day["source_date"]: day
        for day in report.get("days", [])
        if valid_iso_date(day.get("source_date"))
    }


def load_current_state_module() -> Any:
    try:
        import current_state_model_report as current_state

        return current_state
    except ModuleNotFoundError:
        module_path = Path(__file__).with_name("current_state_model_report.py")
        spec = importlib.util.spec_from_file_location("current_state_model_report", module_path)
        if spec is None or spec.loader is None:
            raise
        current_state = importlib.util.module_from_spec(spec)
        sys.modules[current_state.__name__] = current_state
        spec.loader.exec_module(current_state)
        return current_state


def known_dates(
    activity: dict[str, ActivityDay],
    reviews: dict[str, ReviewDay],
    snapshots: dict[str, SnapshotDay],
    current_state: dict[str, dict[str, Any]],
    start_date: str | None,
    end_date: str,
) -> list[str]:
    if start_date:
        return date_range(start_date, end_date)
    return sorted(
        source_date
        for source_date in (set(activity) | set(reviews) | set(snapshots) | set(current_state))
        if source_date <= end_date
    )


def rolling_step_sum(source_date: str, by_date: dict[str, ActivityDay], days: int) -> float | None:
    values = [
        by_date[day].steps
        for offset in range(days)
        for day in [add_days(source_date, -offset)]
        if day in by_date and by_date[day].steps is not None
    ]
    if len(values) < max(2, days - 1):
        return None
    return float(sum(values))


def step_spike_ratio(source_date: str, by_date: dict[str, ActivityDay], days: int = 7) -> float | None:
    today = by_date.get(source_date)
    if today is None or today.steps is None:
        return None
    prior = [
        by_date[day].steps
        for offset in range(1, days + 1)
        for day in [add_days(source_date, -offset)]
        if day in by_date and by_date[day].steps is not None
    ]
    if len(prior) < 3:
        return None
    baseline = median(prior)
    if baseline <= 0:
        return None
    return float(today.steps / baseline)


def step_delta_vs_prior(source_date: str, by_date: dict[str, ActivityDay], days: int = 3) -> float | None:
    today = by_date.get(source_date)
    if today is None or today.steps is None:
        return None
    prior = [
        by_date[day].steps
        for offset in range(1, days + 1)
        for day in [add_days(source_date, -offset)]
        if day in by_date and by_date[day].steps is not None
    ]
    if len(prior) < 2:
        return None
    return float(today.steps - median(prior))


def build_days(conn: sqlite3.Connection, start_date: str | None, end_date: str) -> list[ReportDay]:
    activity = load_activity_days(conn, start_date, end_date)
    reviews = load_reviews(conn, start_date, end_date)
    snapshots = load_latest_snapshots(conn, start_date, end_date)
    current_state = load_current_state_days(conn, start_date, end_date)
    days = known_dates(activity, reviews, snapshots, current_state, start_date, end_date)

    report_days: list[ReportDay] = []
    for source_date in days:
        activity_day = activity.get(source_date)
        review = reviews.get(source_date)
        snapshot = snapshots.get(source_date)
        current = current_state.get(source_date, {})
        report_days.append(
            ReportDay(
                source_date=source_date,
                steps=activity_day.steps if activity_day else None,
                activity_calories=activity_day.activity_calories if activity_day else None,
                achieved_activity=activity_day.achieved_activity if activity_day else None,
                distance_m=activity_day.distance_m if activity_day else None,
                coverage_minutes=activity_day.coverage_minutes if activity_day else None,
                coverage_ok=activity_day.has_full_epoch_coverage if activity_day else False,
                avg_met=activity_day.avg_met if activity_day else None,
                met2_minutes=activity_day.met2_minutes if activity_day else None,
                met15_minutes=activity_day.met15_minutes if activity_day else None,
                rolling_step_sum_2d=rolling_step_sum(source_date, activity, 2),
                rolling_step_sum_3d=rolling_step_sum(source_date, activity, 3),
                rolling_step_sum_7d=rolling_step_sum(source_date, activity, 7),
                step_spike_ratio_7d=step_spike_ratio(source_date, activity),
                step_delta_vs_prior_3d=step_delta_vs_prior(source_date, activity),
                outcome=review.outcome if review else None,
                approach=review.approach if review else None,
                muscle_weakness=review.muscle_weakness if review else None,
                mostly_horizontal=review.mostly_horizontal if review else None,
                left_house=review.left_house if review else None,
                major_task=review.major_task if review else None,
                major_task_type=review.major_task_type if review else None,
                pem_payback=review.pem_payback if review else None,
                payback_peak=review.payback_peak if review else None,
                snapshot_status=snapshot.status if snapshot else None,
                current_status=current.get("current_status"),
                planning_status=current.get("planning_status"),
                autonomic_status=current.get("autonomic_status"),
                functional_status=current.get("functional_status"),
                stability=current.get("state_stability"),
                autonomic_context=current.get("autonomic_context_label"),
            )
        )
    return report_days


def day_by_date(days: list[ReportDay]) -> dict[str, ReportDay]:
    return {day.source_date: day for day in days}


def metric_value(day: ReportDay, metric: str) -> float | int | None:
    value = getattr(day, metric)
    if isinstance(value, bool):
        return int(value)
    return value


def target_value(day: ReportDay, target: str, days_by_date: dict[str, ReportDay]) -> float | int | None:
    if target.startswith("outcome_d_plus_"):
        offset = int(target.removeprefix("outcome_d_plus_"))
        future = days_by_date.get(add_days(day.source_date, offset))
        return status_ordinal(future.outcome if future else None)
    if target.startswith("weakness_d_plus_"):
        offset = int(target.removeprefix("weakness_d_plus_"))
        future = days_by_date.get(add_days(day.source_date, offset))
        value = future.muscle_weakness if future else None
        return int(value) if value is not None else None
    if target == "outcome_d0":
        return status_ordinal(day.outcome)
    if target == "same_day_steps":
        return day.steps
    if target == "next_day_steps":
        future = days_by_date.get(add_days(day.source_date, 1))
        return future.steps if future else None
    raise ValueError(f"Unsupported target: {target}")


def correlation_rows(
    days: list[ReportDay],
    metrics: list[str],
    targets: list[str],
    min_pairs: int = 8,
) -> list[dict[str, Any]]:
    by_date = day_by_date(days)
    rows: list[dict[str, Any]] = []
    for metric in metrics:
        for target in targets:
            coefficient, count = spearman(
                [metric_value(day, metric) for day in days],
                [target_value(day, target, by_date) for day in days],
            )
            if count >= min_pairs:
                rows.append(
                    {
                        "metric": metric,
                        "target": target,
                        "spearman_r": coefficient,
                        "pair_count": count,
                    }
                )
    return rows


def group_numeric(days: list[ReportDay], group_field: str, value_field: str, lag_days: int = 0) -> dict[str, dict[str, Any]]:
    by_date = day_by_date(days)
    groups: dict[str, list[float]] = defaultdict(list)
    for day in days:
        group = getattr(day, group_field)
        if group is None:
            continue
        value_day = by_date.get(add_days(day.source_date, lag_days)) if lag_days else day
        value = getattr(value_day, value_field) if value_day else None
        if value is not None:
            groups[str(group)].append(float(value))
    return {
        group: {
            "count": len(values),
            "median": round(safe_median(values), 3),
            "mean": round(safe_mean(values), 3),
        }
        for group, values in sorted(groups.items(), key=lambda item: (STATUS_ORDER.get(item[0], 99), item[0]))
        if values
    }


def bucket_summary(name: str, days: list[ReportDay], all_days: list[ReportDay]) -> dict[str, Any]:
    by_date = day_by_date(all_days)
    next_outcomes = [
        status_ordinal(by_date[add_days(day.source_date, 1)].outcome)
        for day in days
        if add_days(day.source_date, 1) in by_date and by_date[add_days(day.source_date, 1)].outcome in STATUS_ORDER
    ]
    next2_outcomes = [
        status_ordinal(by_date[add_days(day.source_date, 2)].outcome)
        for day in days
        if add_days(day.source_date, 2) in by_date and by_date[add_days(day.source_date, 2)].outcome in STATUS_ORDER
    ]
    weakness_known = []
    worsened_next_day = 0
    comparable_next_day = 0
    for day in days:
        known_weakness = [
            by_date[future_date].muscle_weakness
            for offset in (1, 2)
            for future_date in [add_days(day.source_date, offset)]
            if future_date in by_date and by_date[future_date].muscle_weakness is not None
        ]
        if known_weakness:
            weakness_known.append(any(known_weakness))

        future = by_date.get(add_days(day.source_date, 1))
        if day.outcome in STATUS_ORDER and future is not None and future.outcome in STATUS_ORDER:
            comparable_next_day += 1
            if STATUS_ORDER[future.outcome] > STATUS_ORDER[day.outcome]:
                worsened_next_day += 1

    step_values = [day.steps for day in days if day.steps is not None]
    return {
        "name": name,
        "count": len(days),
        "median_steps": round(safe_median(step_values), 3) if step_values else None,
        "mean_steps": round(safe_mean(step_values), 3) if step_values else None,
        "next_day_outcome_count": len(next_outcomes),
        "next_day_median_outcome_ordinal": safe_median([float(value) for value in next_outcomes]),
        "next_day_median_outcome": ordinal_label(safe_median([float(value) for value in next_outcomes])),
        "two_day_outcome_count": len(next2_outcomes),
        "two_day_median_outcome_ordinal": safe_median([float(value) for value in next2_outcomes]),
        "two_day_median_outcome": ordinal_label(safe_median([float(value) for value in next2_outcomes])),
        "weakness_within_two_days": sum(weakness_known),
        "weakness_within_two_days_known": len(weakness_known),
        "worsened_next_day": worsened_next_day,
        "worsened_next_day_known": comparable_next_day,
        "dates": [day.source_date for day in days],
    }


def build_load_buckets(days: list[ReportDay]) -> dict[str, Any]:
    activity_days = [day for day in days if day.steps is not None]
    step_values = [float(day.steps) for day in activity_days]
    q25 = quantile(step_values, 0.25)
    q75 = quantile(step_values, 0.75)
    buckets: list[dict[str, Any]] = []
    if q75 is not None:
        buckets.append(bucket_summary(f"high_steps_gte_q75_{round(q75)}", [day for day in activity_days if day.steps >= q75], days))
        buckets.append(bucket_summary("below_q75_steps", [day for day in activity_days if day.steps < q75], days))
    if q25 is not None:
        buckets.append(bucket_summary(f"low_steps_lte_q25_{round(q25)}", [day for day in activity_days if day.steps <= q25], days))
    return {
        "step_q25": q25,
        "step_q50": quantile(step_values, 0.50),
        "step_q75": q75,
        "buckets": buckets,
    }


def build_dataset_summary(days: list[ReportDay]) -> dict[str, Any]:
    activity_days = [day for day in days if day.steps is not None]
    full_met_days = [day for day in days if day.coverage_ok and day.met2_minutes is not None]
    by_date = day_by_date(days)
    same_day_pairs = [
        day for day in activity_days
        if day.outcome in STATUS_ORDER
    ]
    next_day_pairs = [
        day for day in activity_days
        if by_date.get(add_days(day.source_date, 1)) and by_date[add_days(day.source_date, 1)].outcome in STATUS_ORDER
    ]
    two_day_pairs = [
        day for day in activity_days
        if by_date.get(add_days(day.source_date, 2)) and by_date[add_days(day.source_date, 2)].outcome in STATUS_ORDER
    ]
    step_values = [float(day.steps) for day in activity_days]
    return {
        "day_count": len(days),
        "activity_step_days": len(activity_days),
        "activity_date_start": activity_days[0].source_date if activity_days else None,
        "activity_date_end": activity_days[-1].source_date if activity_days else None,
        "full_met_days": len(full_met_days),
        "same_day_activity_outcome_pairs": len(same_day_pairs),
        "next_day_activity_outcome_pairs": len(next_day_pairs),
        "two_day_activity_outcome_pairs": len(two_day_pairs),
        "enough_for_directional_signal": len(next_day_pairs) >= MIN_DIRECTIONAL_PAIRS,
        "enough_for_careful_tuning": len(next_day_pairs) >= MIN_CAREFUL_TUNING_PAIRS,
        "step_q25": quantile(step_values, 0.25),
        "step_q50": quantile(step_values, 0.50),
        "step_q75": quantile(step_values, 0.75),
    }


def build_report(conn: sqlite3.Connection, start_date: str | None, end_date: str) -> dict[str, Any]:
    days = build_days(conn, start_date, end_date)
    el_metrics = [
        "steps",
        "activity_calories",
        "achieved_activity",
        "met2_minutes",
        "met15_minutes",
        "avg_met",
        "rolling_step_sum_2d",
        "rolling_step_sum_3d",
        "rolling_step_sum_7d",
        "step_spike_ratio_7d",
        "step_delta_vs_prior_3d",
    ]
    state_metrics = [
        "current_status",
        "planning_status",
        "autonomic_status",
        "functional_status",
        "snapshot_status",
        "approach",
        "outcome",
    ]
    state_ordinals = []
    for metric in state_metrics:
        ordinal_name = f"{metric}_ordinal"
        state_ordinals.append(ordinal_name)
    day_dicts = [asdict(day) for day in days]
    for day in day_dicts:
        for metric in state_metrics:
            day[f"{metric}_ordinal"] = status_ordinal(day.get(metric))

    # Rehydrate ReportDay-like metric access for status ordinals without mutating the dataclass.
    class MetricProxy:
        def __init__(self, day: ReportDay, extras: dict[str, Any]) -> None:
            self._day = day
            self._extras = extras
            self.source_date = day.source_date

        def __getattr__(self, name: str) -> Any:
            if name in self._extras:
                return self._extras[name]
            return getattr(self._day, name)

    proxied_days = [
        MetricProxy(day, {key: value for key, value in extras.items() if key.endswith("_ordinal")})
        for day, extras in zip(days, day_dicts)
    ]
    el_to_outcomes = correlation_rows(
        days,
        el_metrics,
        ["outcome_d0", "outcome_d_plus_1", "outcome_d_plus_2", "outcome_d_plus_3"],
    )
    state_to_activity = correlation_rows(
        proxied_days,
        state_ordinals,
        ["same_day_steps", "next_day_steps"],
    )
    group_fields = [
        "outcome",
        "approach",
        "snapshot_status",
        "planning_status",
        "current_status",
        "autonomic_status",
        "functional_status",
        "stability",
        "autonomic_context",
        "left_house",
        "mostly_horizontal",
        "muscle_weakness",
        "major_task",
        "major_task_type",
    ]
    grouped_steps = {
        field: group_numeric(days, field, "steps")
        for field in group_fields
        if group_numeric(days, field, "steps")
    }
    grouped_next_steps = {
        field: group_numeric(days, field, "steps", lag_days=1)
        for field in group_fields
        if group_numeric(days, field, "steps", lag_days=1)
    }
    return {
        "dataset": build_dataset_summary(days),
        "el_to_outcomes": el_to_outcomes,
        "load_buckets": build_load_buckets(days),
        "state_to_activity": {
            "correlations": state_to_activity,
            "same_day_step_groups": grouped_steps,
            "next_day_step_groups": grouped_next_steps,
        },
        "days": day_dicts,
        "humility_note": HUMILITY_NOTE,
    }


def correlation_preview(rows: list[dict[str, Any]], limit: int = 8) -> list[dict[str, Any]]:
    usable = [row for row in rows if row["spearman_r"] is not None]
    return sorted(usable, key=lambda row: abs(row["spearman_r"]), reverse=True)[:limit]


def fmt(value: Any, digits: int = 2) -> str:
    if value is None:
        return "n/a"
    if isinstance(value, float):
        return f"{value:.{digits}f}"
    return str(value)


def print_group(title: str, groups: dict[str, dict[str, Any]]) -> None:
    if not groups:
        return
    print(title)
    for name, summary in groups.items():
        print(
            f"  {name}: n={summary['count']}, "
            f"median steps={fmt(summary['median'], 0)}, mean steps={fmt(summary['mean'], 0)}"
        )


def print_report(report: dict[str, Any]) -> None:
    dataset = report["dataset"]
    print("Exertional Load Report")
    print(f"  activity step days: {dataset['activity_step_days']} ({dataset['activity_date_start']}..{dataset['activity_date_end']})")
    print(f"  full MET days: {dataset['full_met_days']}")
    print(
        "  paired activity/outcome days: "
        f"D0={dataset['same_day_activity_outcome_pairs']}, "
        f"D+1={dataset['next_day_activity_outcome_pairs']}, "
        f"D+2={dataset['two_day_activity_outcome_pairs']}"
    )
    print(
        "  steps q25/q50/q75: "
        f"{fmt(dataset['step_q25'], 0)} / {fmt(dataset['step_q50'], 0)} / {fmt(dataset['step_q75'], 0)}"
    )
    print(f"  enough for directional signal: {dataset['enough_for_directional_signal']}")
    print(f"  enough for careful tuning: {dataset['enough_for_careful_tuning']}")
    print()

    print("EL -> Outcome Correlations")
    for row in correlation_preview(report["el_to_outcomes"], limit=10):
        print(f"  {row['metric']} vs {row['target']}: r={fmt(row['spearman_r'])}, n={row['pair_count']}")
    print()

    print("Load Buckets")
    for bucket in report["load_buckets"]["buckets"]:
        print(
            f"  {bucket['name']}: n={bucket['count']}, median steps={fmt(bucket['median_steps'], 0)}, "
            f"D+1 median={bucket['next_day_median_outcome'] or 'n/a'} "
            f"({bucket['next_day_outcome_count']} pairs), "
            f"D+2 median={bucket['two_day_median_outcome'] or 'n/a'} "
            f"({bucket['two_day_outcome_count']} pairs), "
            f"weakness next2={bucket['weakness_within_two_days']}/{bucket['weakness_within_two_days_known']}"
        )
    print()

    print("State -> Activity Correlations")
    for row in correlation_preview(report["state_to_activity"]["correlations"], limit=8):
        print(f"  {row['metric']} vs {row['target']}: r={fmt(row['spearman_r'])}, n={row['pair_count']}")
    print()

    same_day_groups = report["state_to_activity"]["same_day_step_groups"]
    print_group("Same-day steps by outcome", same_day_groups.get("outcome", {}))
    print_group("Same-day steps by approach", same_day_groups.get("approach", {}))
    print_group("Same-day steps by planning status", same_day_groups.get("planning_status", {}))
    print_group("Same-day steps by autonomic context", same_day_groups.get("autonomic_context", {}))
    print()
    print(f"Note: {report['humility_note']}")


def main() -> None:
    args = parse_args()
    with connect(args.health_db) as conn:
        report = build_report(conn, args.start_date, args.end_date)
    if args.json:
        print(json.dumps(report, indent=2, sort_keys=True))
    else:
        print_report(report)


if __name__ == "__main__":
    main()
