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
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("daily_data_completeness.py")
SPEC = importlib.util.spec_from_file_location("daily_data_completeness", MODULE_PATH)
assert SPEC and SPEC.loader
completeness = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = completeness
SPEC.loader.exec_module(completeness)


class DailyDataCompletenessTest(unittest.TestCase):
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
            create table sleep_night_raw (
                id integer primary key autoincrement,
                deviceId text, sourceDate text, requestedRange text,
                syncTimestampEpochMs integer, keySummary text, rawPayloadJson text,
                parserVersion integer, parseStatus text
            );
            create table nightly_recharge_raw (
                id integer primary key autoincrement,
                deviceId text, sourceDate text, requestedRange text,
                syncTimestampEpochMs integer, keySummary text, rawPayloadJson text,
                parserVersion integer, parseStatus text
            );
            create table ppi247_day_raw (
                id integer primary key autoincrement,
                deviceId text, sourceDate text, requestedRange text,
                syncTimestampEpochMs integer, keySummary text, rawPayloadJson text,
                parserVersion integer, parseStatus text
            );
            create table ppi247_epoch (
                deviceId text, sourceDate text, epochStartEpochMs integer,
                epochEndEpochMs integer, epochQuality text, rmssdMs real,
                meanHrBpm real
            );
            create table hr247_day_raw (
                id integer primary key autoincrement,
                deviceId text, sourceDate text, requestedRange text,
                syncTimestampEpochMs integer, keySummary text, rawPayloadJson text,
                parserVersion integer, parseStatus text
            );
            create table hr247_epoch (
                deviceId text, sourceDate text, epochStartEpochMs integer,
                epochEndEpochMs integer, sampleCount integer, meanHrBpm real,
                medianHrBpm real, minHrBpm integer, maxHrBpm integer,
                triggerTypesCsv text, epochQuality text, updatedAtEpochMs integer
            );
            create table skin_temperature_raw (
                id integer primary key autoincrement,
                deviceId text, sourceDate text, requestedRange text,
                syncTimestampEpochMs integer, keySummary text, rawPayloadJson text,
                parserVersion integer, parseStatus text
            );
            create table skin_temperature_sample (
                deviceId text, sourceDate text, sampleTimeEpochMs integer,
                recordingTimeDeltaMs integer, temperatureCelsius real,
                sensorLocation text, measurementType text, updatedAtEpochMs integer
            );
            create table daily_summary_raw (
                id integer primary key autoincrement,
                deviceId text, sourceDate text, requestedRange text,
                syncTimestampEpochMs integer, keySummary text, rawPayloadJson text,
                parserVersion integer, parseStatus text
            );
            create table activity_samples_raw (
                id integer primary key autoincrement,
                deviceId text, sourceDate text, requestedRange text,
                syncTimestampEpochMs integer, keySummary text, rawPayloadJson text,
                parserVersion integer, parseStatus text
            );
            create table activity_epoch (
                deviceId text, sourceDate text, epochStartEpochMs integer,
                epochEndEpochMs integer, met real, steps integer,
                activityClass text, activityFactor real,
                metRecordingIntervalSeconds integer,
                stepRecordingIntervalSeconds integer,
                updatedAtEpochMs integer
            );
            create table food_daily_summary (
                sourceDate text primary key, totalCaloriesKcal integer,
                eventCount integer, teaCount integer
            );
            create table daily_check_in (
                sourceDate text primary key, eveningOutcome text,
                approachToDay text, muscleWeaknessToday integer
            );
            create table wake_marker (
                id integer primary key autoincrement, sourceDate text,
                markerEpochMs integer, markerSource text, notes text
            );
            create table sync_run (
                id integer primary key autoincrement, deviceId text,
                firmwareVersion text, appVersion text, startedAtEpochMs integer,
                endedAtEpochMs integer, status text, notes text
            );
            create table sync_domain_result (
                id integer primary key autoincrement, syncRunId integer,
                deviceId text, domain text, requestedRange text, status text,
                recordCount integer, parserVersion integer, parseStatus text,
                detailSummary text, rawPayloadJson text, manualNotes text,
                startedAtEpochMs integer, endedAtEpochMs integer,
                errorCode text, errorMessage text
            );
            """
        )

    def add_sync_run(self, notes: str) -> int:
        cursor = self.conn.execute(
            """
            insert into sync_run (
                deviceId, firmwareVersion, appVersion, startedAtEpochMs,
                endedAtEpochMs, status, notes
            ) values ('loop', null, 'test', 1, 2, 'success', ?)
            """,
            (notes,),
        )
        return int(cursor.lastrowid)

    def add_domain(
        self,
        sync_run_id: int,
        domain: str,
        status: str,
        records: int,
        requested_range: str = "2026-05-17..2026-05-21",
        error: str | None = None,
    ) -> None:
        self.conn.execute(
            """
            insert into sync_domain_result (
                syncRunId, deviceId, domain, requestedRange, status, recordCount,
                parserVersion, parseStatus, detailSummary, rawPayloadJson,
                manualNotes, startedAtEpochMs, endedAtEpochMs, errorCode,
                errorMessage
            ) values (?, 'loop', ?, ?, ?, ?, 3, 'PARSED', 'detail',
                null, null, 1, 2, ?, null)
            """,
            (sync_run_id, domain, requested_range, status, records, error),
        )

    def test_core_profile_explains_full_only_lanes_and_hr_raw_pruning(self) -> None:
        sync_run_id = self.add_sync_run("morning core sync completed")
        for domain in ("SLEEP", "NIGHTLY_RECHARGE", "PPI_247"):
            self.add_domain(sync_run_id, domain, "SUPPORTED", 1)
        self.conn.execute(
            """
            insert into hr247_epoch (
                deviceId, sourceDate, epochStartEpochMs, epochEndEpochMs,
                sampleCount, meanHrBpm, medianHrBpm, minHrBpm, maxHrBpm,
                triggerTypesCsv, epochQuality, updatedAtEpochMs
            ) values ('loop', '2026-05-18', 1, 2, 30, 61.5, 62.0, 55, 72, 'AUTO', 'good', 3)
            """
        )
        self.conn.commit()

        report = completeness.build_report_for_connection(self.conn, "2026-05-18", None)

        self.assertTrue(report["polar"]["hr247"]["present"])
        self.assertEqual(report["polar"]["hr247"]["raw_records"], 0)
        self.assertEqual(report["polar"]["hr247"]["epochs"], 1)
        self.assertIn("derived epochs", report["supporting_lane_interpretation"]["HR_247"])
        self.assertIn("only FULL manual sync", report["supporting_lane_interpretation"]["SKIN_TEMPERATURE"])
        self.assertIn("only FULL manual sync", report["supporting_lane_interpretation"]["DAILY_SUMMARY"])
        self.assertIn("disabled", report["supporting_lane_interpretation"]["ACTIVITY_SAMPLES"])
        json.dumps(report)

    def test_full_sync_result_without_rows_is_reported_as_gap(self) -> None:
        sync_run_id = self.add_sync_run("manual sync completed")
        self.add_domain(sync_run_id, "DAILY_SUMMARY", "SUPPORTED", 1)
        self.add_domain(sync_run_id, "SKIN_TEMPERATURE", "EMPTY", 0)
        self.conn.commit()

        report = completeness.build_report_for_connection(self.conn, "2026-05-18", None)

        self.assertIn("reporting gap", report["supporting_lane_interpretation"]["DAILY_SUMMARY"])
        self.assertIn("device returned no rows", report["supporting_lane_interpretation"]["SKIN_TEMPERATURE"])

    def test_range_text_includes_interpretation_summary(self) -> None:
        sync_run_id = self.add_sync_run("morning core sync completed")
        self.add_domain(sync_run_id, "PPI_247", "ERROR", 0, error="TimeoutCancellationException")
        self.conn.commit()

        reports = [
            completeness.build_report_for_connection(self.conn, "2026-05-18", None),
            completeness.build_report_for_connection(self.conn, "2026-05-19", None),
        ]
        buffer = io.StringIO()
        with redirect_stdout(buffer):
            completeness.print_range_text(reports)
        output = buffer.getvalue()

        self.assertIn("Range summary", output)
        self.assertIn("PPI failure/timeout", output)
        self.assertIn("ACTIVITY_SAMPLES missing: 2026-05-18, 2026-05-19", output)


if __name__ == "__main__":
    unittest.main()
