#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import io
import json
import sqlite3
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

MODULE_PATH = Path(__file__).with_name("readiness_outcome_report.py")
SPEC = importlib.util.spec_from_file_location("readiness_outcome_report", MODULE_PATH)
assert SPEC and SPEC.loader
report_module = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = report_module
SPEC.loader.exec_module(report_module)


def epoch_ms(local_datetime: str, timezone: str = "Europe/London") -> int:
    zone = ZoneInfo(timezone)
    parsed = datetime.fromisoformat(local_datetime)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=zone)
    return int(parsed.timestamp() * 1000)


class ReadinessOutcomeReportTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.db_path = Path(self.temp_dir.name) / "lodestone.db"
        self.conn = sqlite3.connect(self.db_path)
        self.conn.row_factory = sqlite3.Row
        self.create_schema()
        self.insert_fixture_data()

    def tearDown(self) -> None:
        self.conn.close()
        self.temp_dir.cleanup()

    def create_schema(self) -> None:
        self.conn.executescript(
            """
            create table morning_prediction_snapshot (
                id integer primary key autoincrement not null,
                sourceDate text not null,
                issuedAtEpochMs integer not null,
                snapshotOrigin text not null,
                modelVersion text not null,
                status text not null,
                confidence text not null,
                isInterim integer not null,
                sleepDataReady integer not null,
                overnightAutonomicSource text not null,
                sleepDurationMinutes integer,
                nightlyRmssd real,
                baselineReady integer not null,
                recoveryAvailable integer not null,
                rawPpiGoodEpochCount integer,
                rawPpiPoorEpochCount integer,
                rawPpiCoverageHours real,
                summary text not null,
                reasonsJson text not null
            );
            create table daily_check_in (
                sourceDate text primary key not null,
                eveningOutcome text not null,
                approachToDay text,
                muscleWeaknessToday integer not null,
                notes text,
                createdAtEpochMs integer not null,
                updatedAtEpochMs integer not null
            );
            create table sleep_episode (
                id integer primary key autoincrement not null,
                sourceDate text not null,
                startEpochMs integer,
                endEpochMs integer,
                episodeKind text not null,
                source text not null,
                confidence text not null,
                isPrimaryForReadiness integer not null,
                deviceId text,
                linkedSleepRawId integer,
                evidenceJson text,
                notes text,
                createdAtEpochMs integer not null,
                updatedAtEpochMs integer not null
            );
            """
        )

    def insert_prediction(
        self,
        source_date: str,
        status: str,
        issued_at: int,
        *,
        interim: bool = False,
        sleep_ready: bool = True,
        good_epochs: int | None = 12,
    ) -> None:
        self.conn.execute(
            """
            insert into morning_prediction_snapshot (
                sourceDate, issuedAtEpochMs, snapshotOrigin, modelVersion, status,
                confidence, isInterim, sleepDataReady, overnightAutonomicSource,
                sleepDurationMinutes, nightlyRmssd, baselineReady, recoveryAvailable,
                rawPpiGoodEpochCount, rawPpiPoorEpochCount, rawPpiCoverageHours,
                summary, reasonsJson
            ) values (?, ?, 'observed', 'test', ?, 'medium', ?, ?, 'raw_overnight_ppi',
                480, 42.0, 1, 1, ?, 1, 6.0, 'summary', '[]')
            """,
            (source_date, issued_at, status, int(interim), int(sleep_ready), good_epochs),
        )

    def insert_review(self, source_date: str, outcome: str, approach: str | None) -> None:
        self.conn.execute(
            """
            insert into daily_check_in (
                sourceDate, eveningOutcome, approachToDay, muscleWeaknessToday,
                notes, createdAtEpochMs, updatedAtEpochMs
            ) values (?, ?, ?, 0, null, 1, 1)
            """,
            (source_date, outcome, approach),
        )

    def insert_episode(
        self,
        source_date: str,
        kind: str,
        source: str,
        confidence: str,
        *,
        primary: bool = False,
        start: str | None = None,
        end: str | None = None,
    ) -> None:
        self.conn.execute(
            """
            insert into sleep_episode (
                sourceDate, startEpochMs, endEpochMs, episodeKind, source,
                confidence, isPrimaryForReadiness, deviceId, linkedSleepRawId,
                evidenceJson, notes, createdAtEpochMs, updatedAtEpochMs
            ) values (?, ?, ?, ?, ?, ?, ?, null, null, null, null, 1, 1)
            """,
            (
                source_date,
                epoch_ms(start) if start else None,
                epoch_ms(end) if end else None,
                kind,
                source,
                confidence,
                int(primary),
            ),
        )

    def insert_fixture_data(self) -> None:
        self.insert_prediction("2026-05-17", "CRASH", 1)
        self.insert_prediction("2026-05-17", "OK", 2)
        self.insert_prediction("2026-05-18", "OK", 3)
        self.insert_prediction("2026-05-19", "UNSTEADY", 4)
        self.insert_prediction("2026-05-20", "GOOD", 5, interim=True, sleep_ready=False, good_epochs=0)

        self.insert_review("2026-05-17", "OK", "GOOD")
        self.insert_review("2026-05-18", "UNSTEADY", "GOOD")
        self.insert_review("2026-05-19", "CRASH", "OK")
        self.insert_review("2026-05-21", "GOOD", None)

        self.insert_episode(
            "2026-05-17",
            "main_sleep",
            "edited",
            "user_confirmed",
            primary=True,
            start="2026-05-17T03:30:00",
            end="2026-05-17T11:15:00",
        )
        self.insert_episode(
            "2026-05-18",
            "nap",
            "mixed",
            "user_confirmed",
            start="2026-05-18T15:00:00",
            end="2026-05-18T16:00:00",
        )
        self.insert_episode(
            "2026-05-18",
            "rest_candidate",
            "ppi_inferred",
            "low",
            start="2026-05-18T19:00:00",
            end="2026-05-18T19:45:00",
        )
        self.insert_episode("2026-05-19", "no_sleep", "manual", "user_confirmed")
        self.conn.commit()

    def build_report(self) -> dict:
        days = report_module.load_paired_days(
            self.conn,
            "2026-05-17",
            "2026-05-22",
            ZoneInfo("Europe/London"),
        )
        return report_module.build_report(days)

    def test_report_summarises_readiness_alignment_stability_and_payback(self) -> None:
        report = self.build_report()

        self.assertEqual(report["day_count"], 6)
        self.assertEqual(report["prediction_count"], 4)
        self.assertEqual(report["outcome_count"], 4)
        self.assertEqual(report["paired_count"], 3)
        self.assertFalse(report["enough_data_for_load_budget_claims"])
        self.assertIn("No precise load-budget claim", report["humility_note"])

        alignment = report["readiness_alignment"]
        self.assertEqual(alignment["exact_matches"], 1)
        self.assertEqual(alignment["within_one_step"], 3)
        self.assertEqual(alignment["bucket_counts"]["outcome_worse"], 2)
        self.assertEqual(alignment["mean_ordinal_delta"], 0.67)

        stability = report["stability"]
        self.assertEqual(stability["consecutive_paired_transitions"], 2)
        self.assertEqual(stability["transition_buckets"]["outcome_moved_prediction_stable"], 1)
        self.assertEqual(stability["transition_buckets"]["same_direction"], 1)

        payback = report["coarse_payback"]
        self.assertEqual(payback["consecutive_outcome_transitions"], 2)
        self.assertEqual(payback["ambitious_mismatch_days"], 2)
        self.assertEqual(payback["worse_next_day_after_ambitious_mismatch"], 2)
        self.assertEqual(payback["worse_next_day_after_good_ok_approach"], 2)
        self.assertIn("not a load budget", payback["note"])

    def test_report_includes_episode_and_zero_input_calibration_groups(self) -> None:
        report = self.build_report()
        groups = report["episode_calibration"]["groups"]

        self.assertEqual(groups["dsps_like_timing"]["dates"], ["2026-05-17"])
        self.assertEqual(groups["nap_present"]["dates"], ["2026-05-18"])
        self.assertEqual(groups["rest_present"]["dates"], ["2026-05-18"])
        self.assertEqual(groups["inferred_candidate_present"]["dates"], ["2026-05-18"])
        self.assertEqual(groups["skipped_night_no_sleep"]["dates"], ["2026-05-19"])
        self.assertEqual(groups["missing_outcome"]["dates"], ["2026-05-20"])
        self.assertEqual(groups["missing_prediction"]["dates"], ["2026-05-21"])
        self.assertEqual(groups["zero_input_day"]["dates"], ["2026-05-22"])

        self.assertEqual(report["episode_calibration"]["episode_kind_counts"]["main_sleep"], 1)
        self.assertEqual(report["episode_calibration"]["confirmed_episode_days"], 3)
        json.dumps(report)

    def test_text_output_names_humility_and_calibration_sections(self) -> None:
        report = self.build_report()
        buffer = io.StringIO()
        with redirect_stdout(buffer):
            report_module.print_text(report)
        output = buffer.getvalue()

        self.assertIn("Readiness alignment", output)
        self.assertIn("Coarse payback", output)
        self.assertIn("No precise load-budget claim", output)
        self.assertIn("zero_input_day: 1 (2026-05-22)", output)


if __name__ == "__main__":
    unittest.main()
