#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("current_state_model_report.py")
SPEC = importlib.util.spec_from_file_location("current_state_model_report", MODULE_PATH)
assert SPEC and SPEC.loader
report_module = importlib.util.module_from_spec(SPEC)
sys.modules[report_module.__name__] = report_module
SPEC.loader.exec_module(report_module)


class CurrentStateModelReportTest(unittest.TestCase):
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
            create table ppi247_epoch (
                sourceDate text not null,
                epochStartEpochMs integer not null,
                epochEndEpochMs integer not null,
                epochQuality text not null,
                rmssdMs real,
                meanPpiMs real
            );
            create table sleep_episode (
                id integer primary key autoincrement not null,
                sourceDate text not null,
                startEpochMs integer,
                endEpochMs integer,
                episodeKind text not null,
                source text not null,
                confidence text not null,
                isPrimaryForReadiness integer not null
            );
            create table morning_prediction_snapshot (
                id integer primary key autoincrement not null,
                sourceDate text not null,
                issuedAtEpochMs integer not null,
                status text not null,
                confidence text not null
            );
            create table daily_check_in (
                sourceDate text primary key not null,
                eveningOutcome text not null,
                approachToDay text,
                dayShapeCaptured integer,
                mostlyHorizontal integer,
                leftHouse integer,
                majorTask integer,
                majorTaskType text,
                pemPaybackToday integer,
                paybackPeakToday integer,
                paybackPeakConfidence text
            );
            create table sleep_night_raw (
                sourceDate text not null
            );
            create table nightly_recharge_raw (
                sourceDate text not null
            );
            """
        )

    def insert_ppi(
        self,
        source_date: str,
        start_ms: int,
        rmssd_ms: float = 90.0,
        mean_ppi_ms: float = 1100.0,
        quality: str = "good",
    ) -> None:
        self.conn.execute(
            """
            insert into ppi247_epoch (
                sourceDate, epochStartEpochMs, epochEndEpochMs, epochQuality,
                rmssdMs, meanPpiMs
            ) values (?, ?, ?, ?, ?, ?)
            """,
            (source_date, start_ms, start_ms + 300000, quality, rmssd_ms, mean_ppi_ms),
        )

    def add_ppi_day(self, source_date: str, rmssd_ms: float = 90.0, mean_ppi_ms: float = 1100.0) -> None:
        for index in range(48):
            self.insert_ppi(source_date, index * 300000, rmssd_ms=rmssd_ms, mean_ppi_ms=mean_ppi_ms)

    def insert_review(
        self,
        source_date: str,
        outcome: str,
        approach: str = "OK",
        day_shape_captured: bool | None = None,
        mostly_horizontal: bool | None = None,
        left_house: bool | None = None,
        major_task: bool | None = None,
        major_task_type: str | None = None,
        pem_payback_today: bool | None = None,
        payback_peak_today: bool | None = None,
        payback_peak_confidence: str | None = None,
    ) -> None:
        self.conn.execute(
            """
            insert into daily_check_in (
                sourceDate, eveningOutcome, approachToDay, dayShapeCaptured,
                mostlyHorizontal, leftHouse, majorTask, majorTaskType,
                pemPaybackToday, paybackPeakToday, paybackPeakConfidence
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                source_date,
                outcome,
                approach,
                int(day_shape_captured) if day_shape_captured is not None else None,
                int(mostly_horizontal) if mostly_horizontal is not None else None,
                int(left_house) if left_house is not None else None,
                int(major_task) if major_task is not None else None,
                major_task_type,
                int(pem_payback_today) if pem_payback_today is not None else None,
                int(payback_peak_today) if payback_peak_today is not None else None,
                payback_peak_confidence,
            ),
        )

    def insert_prediction(self, source_date: str, status: str) -> None:
        self.conn.execute(
            """
            insert into morning_prediction_snapshot (
                sourceDate, issuedAtEpochMs, status, confidence
            ) values (?, 1, ?, 'medium')
            """,
            (source_date, status),
        )

    def test_source_date_ppi_does_not_require_sleep_window(self) -> None:
        self.add_ppi_day("2026-04-30")
        self.add_ppi_day("2026-05-02")
        self.insert_review("2026-05-01", "UNSTEADY")
        self.insert_review("2026-05-02", "UNSTEADY")
        self.conn.commit()

        day = report_module.build_day(self.conn, "2026-05-02", baseline_days=14)

        self.assertEqual(day.evidence_basis, "source_date_ppi")
        self.assertEqual(day.current_status, "UNSTEADY")
        self.assertEqual(day.autonomic_status, "GOOD")
        self.assertEqual(day.functional_status, "UNSTEADY")
        self.assertEqual(day.prior_subjective_state, "UNSTEADY")
        self.assertEqual(day.prior_subjective_penalty, 2)

    def test_selected_episode_window_is_preferred_when_ppi_overlaps(self) -> None:
        self.add_ppi_day("2026-04-30")
        self.insert_ppi("2026-05-02", 1_000_000, rmssd_ms=120.0)
        self.insert_ppi("2026-05-02", 4_500, rmssd_ms=60.0)
        self.insert_ppi("2026-05-02", 4_800, rmssd_ms=62.0)
        self.conn.execute(
            """
            insert into sleep_episode (
                sourceDate, startEpochMs, endEpochMs, episodeKind, source,
                confidence, isPrimaryForReadiness
            ) values ('2026-05-02', 4000, 6000, 'main_sleep', 'mixed', 'user_confirmed', 1)
            """
        )
        self.conn.commit()

        day = report_module.build_day(self.conn, "2026-05-02", baseline_days=14)

        self.assertEqual(day.evidence_basis, "episode_window_ppi")
        self.assertEqual(day.active_window_source, "mixed:main_sleep")
        self.assertEqual(day.ppi_epoch_count, 2)
        self.assertEqual(day.mean_rmssd_ms, 61.0)

    def test_selected_episode_ignores_primary_without_timestamps(self) -> None:
        self.add_ppi_day("2026-04-30")
        self.insert_ppi("2026-05-02", 4_500, rmssd_ms=60.0)
        self.insert_ppi("2026-05-02", 4_800, rmssd_ms=62.0)
        self.conn.execute(
            """
            insert into sleep_episode (
                sourceDate, startEpochMs, endEpochMs, episodeKind, source,
                confidence, isPrimaryForReadiness
            ) values ('2026-05-02', null, null, 'main_sleep', 'manual', 'user_confirmed', 1)
            """
        )
        self.conn.execute(
            """
            insert into sleep_episode (
                sourceDate, startEpochMs, endEpochMs, episodeKind, source,
                confidence, isPrimaryForReadiness
            ) values ('2026-05-02', 4000, 6000, 'main_sleep', 'mixed', 'user_confirmed', 0)
            """
        )
        self.conn.commit()

        day = report_module.build_day(self.conn, "2026-05-02", baseline_days=14)

        self.assertEqual(day.evidence_basis, "episode_window_ppi")
        self.assertEqual(day.ppi_epoch_count, 2)

    def test_count_for_date_rejects_unknown_table_names(self) -> None:
        with self.assertRaises(ValueError):
            report_module.count_for_date(self.conn, "daily_check_in; drop table daily_check_in", "2026-05-02")

    def test_report_compares_current_state_with_old_readiness(self) -> None:
        self.add_ppi_day("2026-05-01")
        self.add_ppi_day("2026-05-02")
        self.insert_review("2026-05-01", "UNSTEADY")
        self.insert_review("2026-05-02", "UNSTEADY")
        self.insert_prediction("2026-05-02", "GOOD")
        self.conn.commit()

        report = report_module.build_report(
            self.conn,
            start_date="2026-05-01",
            end_date="2026-05-02",
            baseline_days=14,
        )

        self.assertEqual(report["current_alignment"]["paired_count"], 2)
        self.assertEqual(report["planning_alignment"]["paired_count"], 2)
        self.assertEqual(report["autonomic_alignment"]["paired_count"], 2)
        self.assertEqual(report["old_readiness_alignment"]["paired_count"], 1)
        self.assertEqual(report["current_vs_old"]["more_cautious_days"], 1)

    def test_functional_lane_drags_planning_state_when_autonomic_is_good(self) -> None:
        self.add_ppi_day("2026-05-01")
        self.add_ppi_day("2026-05-02")
        self.insert_review(
            "2026-05-01",
            "UNSTEADY",
            day_shape_captured=True,
            pem_payback_today=True,
        )
        self.conn.commit()

        day = report_module.build_day(self.conn, "2026-05-02", baseline_days=14)

        self.assertEqual(day.autonomic_status, "GOOD")
        self.assertEqual(day.functional_status, "UNSTEADY")
        self.assertEqual(day.planning_status, "UNSTEADY")
        self.assertEqual(day.current_status, "UNSTEADY")

    def test_functional_lane_allows_cautious_recovery_after_stable_day(self) -> None:
        self.add_ppi_day("2026-05-01")
        self.add_ppi_day("2026-05-02")
        self.add_ppi_day("2026-05-03")
        self.insert_review("2026-05-01", "UNSTEADY")
        self.insert_review("2026-05-02", "OK")
        self.conn.commit()

        day = report_module.build_day(self.conn, "2026-05-03", baseline_days=14)

        self.assertEqual(day.autonomic_status, "GOOD")
        self.assertEqual(day.functional_status, "OK")
        self.assertEqual(day.planning_status, "OK")
        self.assertTrue(any("cautious recovery" in note for note in day.functional_notes))

    def test_autonomic_context_tracks_strain_and_recovery_momentum_without_status_override(self) -> None:
        values = [48.0, 50.0, 52.0, 54.0, 55.0, 58.0, 62.0, 65.0, 70.0, 76.0, 82.0, 88.0]
        for index, value in enumerate(values):
            self.insert_ppi("2026-05-02", index * 300000, rmssd_ms=value)
        self.conn.commit()

        day = report_module.build_day(self.conn, "2026-05-02", baseline_days=14)

        self.assertEqual(day.autonomic_status, "GOOD")
        self.assertEqual(day.autonomic_context_label, "Strained, recovering")
        self.assertTrue(day.autonomic_strain_flag)
        self.assertTrue(day.autonomic_recovery_momentum)
        self.assertLess(day.p25_rmssd_ms, 60.0)

    def test_report_summarises_autonomic_context_outcomes_and_next_day_movement(self) -> None:
        strained_values = [48.0, 50.0, 52.0, 54.0, 55.0, 58.0, 62.0, 65.0, 70.0, 76.0, 82.0, 88.0]
        steady_values = [82.0, 84.0, 86.0, 88.0, 90.0, 92.0, 94.0, 96.0, 98.0, 100.0, 102.0, 104.0]
        for index, value in enumerate(strained_values):
            self.insert_ppi("2026-05-01", index * 300000, rmssd_ms=value)
        for index, value in enumerate(steady_values):
            self.insert_ppi("2026-05-02", index * 300000, rmssd_ms=value)
        self.insert_review("2026-05-01", "UNSTEADY")
        self.insert_review("2026-05-02", "OK")
        self.conn.commit()

        report = report_module.build_report(
            self.conn,
            start_date="2026-05-01",
            end_date="2026-05-02",
            baseline_days=14,
        )

        strain = report["autonomic_context_outcomes"]["strain"]
        self.assertEqual(strain["flagged_count"], 1)
        self.assertEqual(strain["flagged_rough_count"], 1)
        self.assertEqual(strain["flagged_rough_rate"], 1.0)
        self.assertEqual(
            report["autonomic_context_next_day"]["strain"]["flagged_movements"],
            {"next_better": 1},
        )

    def test_day_shape_fields_are_reported_when_present(self) -> None:
        self.insert_review(
            "2026-05-02",
            "OK",
            day_shape_captured=True,
            left_house=True,
            major_task=True,
            major_task_type="site_visit",
            pem_payback_today=True,
            payback_peak_today=True,
            payback_peak_confidence="user_selected",
        )
        self.conn.commit()

        day = report_module.build_day(self.conn, "2026-05-02", baseline_days=14)

        self.assertTrue(day.day_shape_captured)
        self.assertTrue(day.left_house)
        self.assertTrue(day.major_task)
        self.assertEqual(day.major_task_type, "site_visit")
        self.assertTrue(day.pem_payback_today)
        self.assertTrue(day.payback_peak_today)
        self.assertEqual(day.payback_peak_confidence, "user_selected")

    def test_pem_lag_episode_reports_peak_tail_and_missing_dates(self) -> None:
        self.insert_review(
            "2026-05-01",
            "OK",
            day_shape_captured=True,
            major_task=True,
            major_task_type="site_visit",
        )
        self.insert_review("2026-05-02", "OK")
        self.insert_review(
            "2026-05-03",
            "UNSTEADY",
            day_shape_captured=True,
            pem_payback_today=True,
        )
        self.insert_review(
            "2026-05-04",
            "CRASH",
            day_shape_captured=True,
            mostly_horizontal=True,
            pem_payback_today=True,
            payback_peak_today=True,
            payback_peak_confidence="user_selected",
        )
        self.insert_review("2026-05-06", "OK")
        self.conn.commit()

        report = report_module.build_report(
            self.conn,
            start_date="2026-05-01",
            end_date="2026-05-06",
            baseline_days=14,
        )

        episode = report["pem_lag_episodes"][0]
        self.assertEqual(report["pem_lag_episode_count"], 1)
        self.assertEqual(episode["trigger_date"], "2026-05-01")
        self.assertEqual(episode["trigger_type"], "site_visit")
        self.assertEqual(episode["affected_dates"], ("2026-05-03", "2026-05-04"))
        self.assertEqual(episode["missing_dates"], ("2026-05-05",))
        self.assertEqual(episode["pem_dates"], ("2026-05-03", "2026-05-04"))
        self.assertEqual(episode["mostly_horizontal_dates"], ("2026-05-04",))
        self.assertEqual(episode["peak_date"], "2026-05-04")
        self.assertEqual(episode["peak_lag_days"], 3)
        self.assertEqual(episode["recovery_tail_days"], 2)
        self.assertEqual(episode["outcome_movement"], "OK -> CRASH (worse)")

    def test_pem_lag_episode_handles_no_peak_marker(self) -> None:
        self.insert_review(
            "2026-05-01",
            "OK",
            day_shape_captured=True,
            major_task=True,
        )
        self.insert_review(
            "2026-05-02",
            "UNSTEADY",
            day_shape_captured=True,
            pem_payback_today=True,
        )
        self.insert_review("2026-05-03", "OK")
        self.conn.commit()

        episodes = report_module.build_pem_lag_episodes(
            self.conn,
            start_date="2026-05-01",
            end_date="2026-05-03",
        )

        self.assertEqual(len(episodes), 1)
        self.assertEqual(episodes[0].trigger_type, "major_task")
        self.assertEqual(episodes[0].affected_dates, ("2026-05-02",))
        self.assertIsNone(episodes[0].peak_date)
        self.assertIsNone(episodes[0].peak_lag_days)
        self.assertIsNone(episodes[0].recovery_tail_days)


if __name__ == "__main__":
    unittest.main()
