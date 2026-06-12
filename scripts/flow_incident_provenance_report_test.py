#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path

from daily_data_completeness_test import DailyDataCompletenessTest

MODULE_PATH = Path(__file__).with_name("flow_incident_provenance_report.py")
SPEC = importlib.util.spec_from_file_location("flow_incident_provenance_report", MODULE_PATH)
assert SPEC and SPEC.loader
report_module = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = report_module
SPEC.loader.exec_module(report_module)


class FlowIncidentProvenanceReportTest(unittest.TestCase):
    def setUp(self) -> None:
        self.fixture = DailyDataCompletenessTest(methodName="test_cloud_backfill_provenance_is_reported")
        self.fixture.setUp()
        self.conn = self.fixture.conn
        self.conn.executescript(
            """
            create table device_profile (
                deviceId text primary key, name text, address text, firmwareVersion text,
                batteryLevel integer, isConnected integer, lastSeenAtEpochMs integer,
                readyFeaturesJson text, unavailableFeaturesJson text, featureSummary text,
                notes text
            );
            create table app_settings (
                id integer primary key, selectedDeviceId text, sleepDays integer,
                nightlyRechargeDays integer, hrDays integer, ppiDays integer,
                markerMode text, journalFocusMode text, journalFocusFixedTimeMinutes integer,
                lastKnownFirmwareBySelectedDevice text
            );
            """
        )

    def tearDown(self) -> None:
        self.fixture.tearDown()

    def add_firmware_sources(self) -> None:
        self.conn.execute(
            """
            insert into device_profile (
                deviceId, name, address, firmwareVersion, batteryLevel,
                isConnected, lastSeenAtEpochMs, readyFeaturesJson,
                unavailableFeaturesJson, featureSummary, notes
            ) values ('loop', 'Loop', '', '5.0.55', null, 0, 123, '[]', '[]', '', null)
            """
        )
        self.conn.execute(
            """
            insert into app_settings (
                id, selectedDeviceId, sleepDays, nightlyRechargeDays, hrDays, ppiDays,
                markerMode, journalFocusMode, journalFocusFixedTimeMinutes,
                lastKnownFirmwareBySelectedDevice
            ) values (1, 'loop', 7, 7, 7, 7, 'BEDTIME_AND_WAKING', 'AUTO_FROM_WAKE', 1080, '5.0.55')
            """
        )

    def test_report_summarises_mixed_provenance_and_firmware_sources(self) -> None:
        self.fixture.add_raw_record("ppi247_day_raw", "2026-06-03", "2026-06-03..2026-06-03")
        self.fixture.add_raw_record("ppi247_day_raw", "2026-06-03", "Cloud_Backfill:ppi")
        self.fixture.add_raw_record("activity_samples_raw", "2026-06-03", "cloud_backfill:activity")
        self.add_firmware_sources()
        self.conn.commit()

        args = type(
            "Args",
            (),
            {
                "health_db": str(self.fixture.db_path),
                "start_date": "2026-06-03",
                "end_date": "2026-06-03",
                "incident_label": "fixture incident",
                "flow_visible_firmware": "5.0.55",
                "flow_observed_date": "2026-06-12",
                "public_firmware_version": "6.0.57",
                "public_firmware_release_date": "2026-05-13",
                "public_firmware_source": "official support page",
                "public_checked_date": "2026-06-12",
            },
        )()

        report = report_module.build_report(args)

        self.assertEqual(report["summary"]["cloud_backfilled_by_lane"]["PPI_247"], ["2026-06-03"])
        self.assertEqual(report["summary"]["mixed_local_and_cloud_by_lane"]["PPI_247"], ["2026-06-03"])
        self.assertEqual(report["summary"]["cloud_backfilled_by_lane"]["ACTIVITY_SAMPLES"], ["2026-06-03"])
        firmware_sources = {item["source"] for item in report["firmware_observations"]}
        self.assertIn("saved_device_profile", firmware_sources)
        self.assertIn("saved_selected_device_setting", firmware_sources)
        self.assertIn("manual_polar_flow_observation", firmware_sources)
        self.assertIn("manual_public_firmware_reference", firmware_sources)


if __name__ == "__main__":
    unittest.main()
