package com.daveharris.healthmonitor.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MorningReadSignalTest {
    @Test
    fun markerOnlyWindowDoesNotSatisfyMorningPpiGate() {
        assertFalse(
            snapshot(
                source = AnalysisWindowSource.MARKER_SLEEP_WINDOW_PENDING_SLEEP_REPORT,
                goodEpochs = null
            ).hasUsableMorningPpiSignal()
        )
    }

    @Test
    fun rawPendingWithoutWindowSummaryDoesNotSatisfyMorningPpiGate() {
        assertFalse(
            snapshot(
                source = AnalysisWindowSource.RAW_PPI_PENDING_SLEEP_WINDOW,
                goodEpochs = null
            ).hasUsableMorningPpiSignal()
        )
    }

    @Test
    fun insufficientWindowCoverageDoesNotSatisfyMorningPpiGate() {
        assertFalse(
            snapshot(
                source = AnalysisWindowSource.PPI247_SLEEP_WINDOW,
                goodEpochs = 11
            ).hasUsableMorningPpiSignal()
        )
    }

    @Test
    fun derivedWindowCoverageSatisfiesMorningPpiGate() {
        assertTrue(
            snapshot(
                source = AnalysisWindowSource.PPI247_SLEEP_WINDOW,
                goodEpochs = 12
            ).hasUsableMorningPpiSignal()
        )
    }

    private fun snapshot(
        source: AnalysisWindowSource,
        goodEpochs: Int?
    ): AnalysisWindowEvidence =
        AnalysisWindowEvidence(
            sourceDate = "2026-06-12",
            overnightAutonomicSource = source.key,
            sleepDurationMinutes = 420,
            nightlyRmssd = null,
            baselineReady = false,
            isInterim = true,
            rawPpiGoodEpochCount = goodEpochs,
            rawPpiPoorEpochCount = null,
            rawPpiCoverageHours = null,
            hrvTrajectory = emptyList()
        )
}
