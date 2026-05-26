package com.daveharris.healthmonitor.ui

import com.daveharris.healthmonitor.data.MorningReadSnapshot
import com.daveharris.healthmonitor.data.TrafficLightStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TodayComponentsTest {
    @Test
    fun dataQualityWaitsWhenMorningInputsAreAbsent() {
        val summary = todayDataQualitySummary(
            stage = TodayReadinessStage.NOT_STARTED,
            morningRead = null
        )

        assertEquals(TodayDataQualityState.WAITING, summary.state)
        assertEquals(listOf("Final Loop sleep report", "24/7 PPI epochs"), summary.missingInputs)
        assertTrue("Morning-read snapshot" in summary.supportingGaps)
    }

    @Test
    fun dataQualityIsPartialWhenPpiArrivesBeforeFinalSleep() {
        val summary = todayDataQualitySummary(
            stage = TodayReadinessStage.INITIAL_PPI,
            morningRead = morningRead(
                sleepDataReady = false,
                isInterim = true,
                source = "raw_ppi_calibrated_window_pending_sleep_report",
                rawPpiGoodEpochCount = 42,
                rawPpiCoverageHours = 5.5
            )
        )

        assertEquals(TodayDataQualityState.PARTIAL, summary.state)
        assertEquals(listOf("Final Loop sleep report"), summary.missingInputs)
    }

    @Test
    fun dataQualityReadyWhenCoreInputsArePresent() {
        val summary = todayDataQualitySummary(
            stage = TodayReadinessStage.UPDATE_COMPLETE,
            morningRead = morningRead(
                sleepDataReady = true,
                source = "ppi247_sleep_window",
                rawPpiGoodEpochCount = 60,
                rawPpiCoverageHours = 7.0,
                nightlyRmssd = 48.0
            )
        )

        assertEquals(TodayDataQualityState.READY, summary.state)
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
    ): MorningReadSnapshot =
        MorningReadSnapshot(
            sourceDate = "2026-05-26",
            status = TrafficLightStatus.OK,
            confidence = "medium",
            overnightAutonomicSource = source,
            sleepDurationMinutes = if (sleepDataReady) 420 else null,
            nightlyRmssd = nightlyRmssd,
            baselineReady = true,
            recoveryAvailable = true,
            summary = "summary",
            reasons = emptyList(),
            isInterim = isInterim,
            sleepDataReady = sleepDataReady,
            rawPpiGoodEpochCount = rawPpiGoodEpochCount,
            rawPpiPoorEpochCount = 0,
            rawPpiCoverageHours = rawPpiCoverageHours
        )
}
