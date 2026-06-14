package com.daveharris.healthmonitor.ui

import com.daveharris.healthmonitor.data.AnalysisWindowEvidence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TodayComponentsTest {
    @Test
    fun dataQualityWaitsWhenMorningInputsAreAbsent() {
        val summary = signalConfidenceSummary(
            stage = TodayReadinessStage.NOT_STARTED,
            morningRead = null
        )

        assertEquals(SignalConfidenceState.WAITING, summary.state)
        assertEquals(listOf("Sleep/rest window", "24/7 PPI epochs"), summary.missingInputs)
        assertTrue("Morning-read snapshot" in summary.supportingGaps)
    }

    @Test
    fun dataQualityTreatsUsablePpiWindowAsReadyWithSupportingComparisonGap() {
        val summary = signalConfidenceSummary(
            stage = TodayReadinessStage.INITIAL_PPI,
            morningRead = morningRead(
                sleepDataReady = false,
                isInterim = true,
                source = "raw_ppi_calibrated_window_pending_sleep_report",
                rawPpiGoodEpochCount = 42,
                rawPpiCoverageHours = 5.5
            )
        )

        assertEquals(SignalConfidenceState.PARTIAL, summary.state)
        assertTrue(summary.missingInputs.isEmpty())
        assertTrue("Loop sleep report comparison" in summary.supportingGaps)
    }

    @Test
    fun dataQualityStillBlocksWhenPpiHasNoUsableWindow() {
        val summary = signalConfidenceSummary(
            stage = TodayReadinessStage.INITIAL_PPI,
            morningRead = morningRead(
                sleepDataReady = false,
                isInterim = true,
                source = "raw_ppi_pending_manual_sleep_window",
                rawPpiGoodEpochCount = 42,
                rawPpiCoverageHours = 5.5
            )
        )

        assertEquals(SignalConfidenceState.PARTIAL, summary.state)
        assertEquals(listOf("Sleep/rest window"), summary.missingInputs)
    }

    @Test
    fun dataQualityStaysPartialWhenPpiCoverageIsThin() {
        val summary = signalConfidenceSummary(
            stage = TodayReadinessStage.INITIAL_PPI,
            morningRead = morningRead(
                sleepDataReady = false,
                isInterim = true,
                source = "raw_ppi_calibrated_window_pending_sleep_report",
                rawPpiGoodEpochCount = 8,
                rawPpiCoverageHours = 2.5
            )
        )

        assertEquals(SignalConfidenceState.PARTIAL, summary.state)
        assertEquals(listOf("Ready local PPI coverage"), summary.missingInputs)
    }

    @Test
    fun dataQualityReadyWhenCoreInputsArePresent() {
        val summary = signalConfidenceSummary(
            stage = TodayReadinessStage.UPDATE_COMPLETE,
            morningRead = morningRead(
                sleepDataReady = true,
                source = "ppi247_sleep_window",
                rawPpiGoodEpochCount = 60,
                rawPpiCoverageHours = 7.0,
                nightlyRmssd = 48.0
            )
        )

        assertEquals(SignalConfidenceState.READY, summary.state)
        assertTrue(summary.missingInputs.isEmpty())
        assertTrue(summary.supportingGaps.isEmpty())
    }

    private fun morningRead(
        sleepDataReady: Boolean,
        source: String,
        rawPpiGoodEpochCount: Int?,
        rawPpiCoverageHours: Double?,
        isInterim: Boolean = false,
        nightlyRmssd: Double? = null
    ): AnalysisWindowEvidence =
        AnalysisWindowEvidence(
            sourceDate = "2026-05-26",
            overnightAutonomicSource = source,
            sleepDurationMinutes = if (sleepDataReady) 420 else null,
            nightlyRmssd = nightlyRmssd,
            baselineReady = true,
            isInterim = isInterim,
            sleepDataReady = sleepDataReady,
            rawPpiGoodEpochCount = rawPpiGoodEpochCount,
            rawPpiPoorEpochCount = 0,
            rawPpiCoverageHours = rawPpiCoverageHours
        )
}
