#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import json
import sqlite3
import sys
import tempfile
import unittest
from datetime import date, timedelta
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("exertional_load_report.py")
SPEC = importlib.util.spec_from_file_location("exertional_load_report", MODULE_PATH)
assert SPEC and SPEC.loader
report_module = importlib.util.module_from_spec(SPEC)
sys.modules[report_module.__name__] = report_module
SPEC.loader.exec_module(report_module)


class ExertionalLoadReportTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.db_path = Path(self.temp_dir.name) / "lodestone.db"
        self.conn = sqlite3.connect(self.db_path)
        self.conn.row_factory = sqlite3.Row
        self.create_schema()

    def tearDown(self) -> None:
        self.conn.close()
        self.temp_dir.cleanup()

    def create_schema(self) -> None:
        self.conn.executescript(
            """
            create table daily_summary_raw (
                sourceDate text,
                rawPayloadJson text not null
            );
            create table activity_epoch (
                sourceDate text not null,
                epochStartEpochMs integer not null,
                epochEndEpochMs integer not null,
                steps integer,
                met real
            );
            create table daily_check_in (
                sourceDate text primary key not null,
                eveningOutcome text not null,
                approachToDay text,
                muscleWeaknessToday integer,
                mostlyHorizontal integer,
                leftHouse integer,
                majorTask integer,
                majorTaskType text,
                pemPaybackToday integer,
                paybackPeakToday integer
            );
            create table morning_prediction_snapshot (
                id integer primary key autoincrement not null,
                sourceDate text not null,
                issuedAtEpochMs integer not null,
                status text not null,
                confidence text not null
            );
            """
        )

    def insert_activity_day(
        self,
        source_date: str,
        steps: int,
        activity_calories: int | None = None,
        achieved_activity: float | None = None,
        met: float = 2.1,
    ) -> None:
        payload = {
            "date": source_date,
            "steps": steps,
            "activityCalories": activity_calories if activity_calories is not None else steps // 10,
            "activityDistance": steps * 0.7,
            "activityGoalSummary": {"achievedActivity": achieved_activity if achieved_activity is not None else steps / 10.0},
        }
        self.conn.execute(
            "insert into daily_summary_raw (sourceDate, rawPayloadJson) values (?, ?)",
            (source_date, json.dumps(payload)),
        )
        self.conn.execute(
            """
            insert into activity_epoch (
                sourceDate, epochStartEpochMs, epochEndEpochMs, steps, met
            ) values (?, 0, 86400000, ?, ?)
            """,
            (source_date, steps, met),
        )

    def insert_review(
        self,
        source_date: str,
        outcome: str,
        approach: str,
        weakness: bool,
        left_house: bool,
    ) -> None:
        self.conn.execute(
            """
            insert into daily_check_in (
                sourceDate, eveningOutcome, approachToDay, muscleWeaknessToday,
                mostlyHorizontal, leftHouse, majorTask, majorTaskType,
                pemPaybackToday, paybackPeakToday
            ) values (?, ?, ?, ?, 0, ?, 0, null, 0, 0)
            """,
            (source_date, outcome, approach, int(weakness), int(left_house)),
        )

    def insert_snapshot(self, source_date: str, status: str, issued_at: int = 1) -> None:
        self.conn.execute(
            """
            insert into morning_prediction_snapshot (
                sourceDate, issuedAtEpochMs, status, confidence
            ) values (?, ?, ?, 'medium')
            """,
            (source_date, issued_at, status),
        )

    def add_correlated_fixture(self) -> None:
        start = date.fromisoformat("2026-01-01")
        for index in range(10):
            source_date = (start + timedelta(days=index)).isoformat()
            steps = (index + 1) * 100
            high_load = index >= 5
            outcome = "UNSTEADY" if high_load else "OK"
            approach = "UNSTEADY" if high_load else "OK"
            snapshot_status = "UNSTEADY" if high_load else "GOOD"
            self.insert_activity_day(source_date, steps, met=2.2 if high_load else 1.2)
            self.insert_review(source_date, outcome, approach, weakness=high_load, left_house=high_load)
            self.insert_snapshot(source_date, snapshot_status)
        self.conn.execute(
            "insert into daily_summary_raw (sourceDate, rawPayloadJson) values ('null', ?)",
            (json.dumps({"date": None, "steps": 99999}),),
        )
        self.conn.commit()

    def test_report_excludes_invalid_dates_and_summarises_activity_coverage(self) -> None:
        self.add_correlated_fixture()

        report = report_module.build_report(self.conn, None, "2026-01-10")

        self.assertEqual(report["dataset"]["activity_step_days"], 10)
        self.assertEqual(report["dataset"]["full_met_days"], 10)
        self.assertEqual(report["dataset"]["activity_date_start"], "2026-01-01")
        self.assertEqual(report["dataset"]["activity_date_end"], "2026-01-10")
        self.assertTrue(all(day["source_date"] != "null" for day in report["days"]))

    def test_el_to_outcome_correlations_and_buckets_are_reported(self) -> None:
        self.add_correlated_fixture()

        report = report_module.build_report(self.conn, None, "2026-01-10")
        correlations = {
            (row["metric"], row["target"]): row
            for row in report["el_to_outcomes"]
        }

        self.assertGreater(correlations[("steps", "outcome_d0")]["spearman_r"], 0.8)
        self.assertGreater(correlations[("met2_minutes", "outcome_d0")]["spearman_r"], 0.8)
        high_bucket = report["load_buckets"]["buckets"][0]
        self.assertTrue(high_bucket["name"].startswith("high_steps_gte_q75"))
        self.assertEqual(high_bucket["count"], 3)

    def test_reverse_direction_groups_state_and_markers_by_activity(self) -> None:
        self.add_correlated_fixture()

        report = report_module.build_report(self.conn, None, "2026-01-10")
        same_day = report["state_to_activity"]["same_day_step_groups"]

        self.assertGreater(same_day["snapshot_status"]["UNSTEADY"]["median"], same_day["snapshot_status"]["GOOD"]["median"])
        self.assertGreater(same_day["left_house"]["True"]["median"], same_day["left_house"]["False"]["median"])
        state_correlations = {
            (row["metric"], row["target"]): row
            for row in report["state_to_activity"]["correlations"]
        }
        self.assertGreater(state_correlations[("snapshot_status_ordinal", "same_day_steps")]["spearman_r"], 0.8)


if __name__ == "__main__":
    unittest.main()
