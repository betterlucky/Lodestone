package com.daveharris.healthmonitor.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MorningReadSignalTest {
    @Test
    fun markerOnlyWindowDoesNotSatisfyMorningPpiGate() {
        assertFalse(
            snapshot(
                source = MorningReadSource.MARKER_SLEEP_WINDOW_PENDING_SLEEP_REPORT,
                goodEpochs = null
            ).hasUsableMorningPpiSignal()
        )
    }

    @Test
    fun rawPendingWithoutWindowSummaryDoesNotSatisfyMorningPpiGate() {
        assertFalse(
            snapshot(
                source = MorningReadSource.RAW_PPI_PENDING_SLEEP_WINDOW,
                goodEpochs = null
            ).hasUsableMorningPpiSignal()
        )
    }

    @Test
    fun insufficientWindowCoverageDoesNotSatisfyMorningPpiGate() {
        assertFalse(
            snapshot(
                source = MorningReadSource.PPI247_SLEEP_WINDOW,
                goodEpochs = 11
            ).hasUsableMorningPpiSignal()
        )
    }

    @Test
    fun derivedWindowCoverageSatisfiesMorningPpiGate() {
        assertTrue(
            snapshot(
                source = MorningReadSource.PPI247_SLEEP_WINDOW,
                goodEpochs = 12
            ).hasUsableMorningPpiSignal()
        )
    }

    private fun snapshot(
        source: MorningReadSource,
        goodEpochs: Int?
    ): MorningReadSnapshot =
        MorningReadSnapshot(
            sourceDate = "2026-06-12",
            status = TrafficLightStatus.OK,
            confidence = "interim",
            overnightAutonomicSource = source.key,
            sleepDurationMinutes = 420,
            nightlyRmssd = null,
            baselineReady = false,
            recoveryAvailable = false,
            summary = "test",
            reasons = emptyList(),
            isInterim = true,
            sleepDataReady = false,
            rawPpiGoodEpochCount = goodEpochs,
            rawPpiPoorEpochCount = null,
            rawPpiCoverageHours = null,
            hrvTrajectory = emptyList()
        )
}
