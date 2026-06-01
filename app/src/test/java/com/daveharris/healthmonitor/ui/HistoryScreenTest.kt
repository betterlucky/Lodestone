package com.daveharris.healthmonitor.ui

import com.daveharris.healthmonitor.data.DailyCheckInEntity
import com.daveharris.healthmonitor.data.DailyWeightEntity
import com.daveharris.healthmonitor.data.FoodDailySummaryEntity
import com.daveharris.healthmonitor.data.MorningPredictionSnapshotEntity
import com.daveharris.healthmonitor.data.TrafficLightStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryScreenTest {
    @Test
    fun reportsPairLatestPredictionWithJournalOutcomeAndCompleteness() {
        val reports = buildHistoryDayReports(
            predictions = listOf(
                prediction("2026-05-30", issuedAt = 1, status = TrafficLightStatus.GOOD.name),
                prediction("2026-05-31", issuedAt = 1, status = TrafficLightStatus.OK.name, sleepMinutes = 450),
                prediction("2026-05-31", issuedAt = 2, status = TrafficLightStatus.UNSTEADY.name, sleepMinutes = 210)
            ),
            checkIns = listOf(checkIn("2026-05-31", TrafficLightStatus.CRASH.name)),
            foodSummaries = listOf(food("2026-05-31")),
            weights = listOf(weight("2026-05-31"))
        )

        val latest = reports.first()
        assertEquals("2026-05-31", latest.sourceDate)
        assertEquals(TrafficLightStatus.UNSTEADY, latest.predictionStatus)
        assertEquals(TrafficLightStatus.CRASH, latest.outcomeStatus)
        assertEquals("Prediction and outcome differed", latest.predictionOutcomeLabel)
        assertEquals("Short/irregular sleep", latest.sleepBucketLabel)
        assertTrue(latest.dataCompletenessLabel.contains("prediction"))
        assertTrue(latest.dataCompletenessLabel.contains("journal"))
        assertTrue(latest.dataCompletenessLabel.contains("food"))
        assertTrue(latest.dataCompletenessLabel.contains("weight"))
        assertEquals("Good -> Unsteady", latest.stabilityTransitionLabel)
    }

    @Test
    fun reportsJournalOnlyDaysWithoutPretendingThereWasPredictionEvidence() {
        val reports = buildHistoryDayReports(
            predictions = emptyList(),
            checkIns = listOf(checkIn("2026-05-31", TrafficLightStatus.OK.name)),
            foodSummaries = emptyList(),
            weights = emptyList()
        )

        val report = reports.single()
        assertEquals("No morning prediction", report.predictionLabel)
        assertEquals("Outcome saved without a morning prediction", report.predictionOutcomeLabel)
        assertEquals("No prediction evidence", report.robustnessLabel)
        assertEquals("journal", report.dataCompletenessLabel)
        assertEquals("No active window recorded", report.windowProvenanceLabel)
    }

    private fun prediction(
        sourceDate: String,
        issuedAt: Long,
        status: String,
        sleepMinutes: Int? = 480
    ): MorningPredictionSnapshotEntity =
        MorningPredictionSnapshotEntity(
            sourceDate = sourceDate,
            issuedAtEpochMs = issuedAt,
            snapshotOrigin = "test",
            modelVersion = "test",
            status = status,
            confidence = "medium",
            isInterim = false,
            sleepDataReady = sleepMinutes != null,
            overnightAutonomicSource = "raw_ppi_manual_window_pending_sleep_report",
            sleepDurationMinutes = sleepMinutes,
            nightlyRmssd = 42.0,
            baselineReady = true,
            recoveryAvailable = true,
            rawPpiGoodEpochCount = 36,
            rawPpiPoorEpochCount = 2,
            rawPpiCoverageHours = 4.5,
            summary = "test",
            reasonsJson = "[]"
        )

    private fun checkIn(sourceDate: String, outcome: String): DailyCheckInEntity =
        DailyCheckInEntity(
            sourceDate = sourceDate,
            eveningOutcome = outcome,
            approachToDay = null,
            muscleWeaknessToday = false,
            notes = "notes",
            createdAtEpochMs = 1,
            updatedAtEpochMs = 2
        )

    private fun food(sourceDate: String): FoodDailySummaryEntity =
        FoodDailySummaryEntity(
            sourceDate = sourceDate,
            totalCaloriesKcal = 1800,
            eventCount = 4,
            teaCount = 1,
            firstIntakeTime = "08:00",
            lastIntakeTime = "19:00",
            eatingWindowHours = 11.0,
            rawItemsJson = "[]",
            importSource = "test",
            importedAtEpochMs = 1
        )

    private fun weight(sourceDate: String): DailyWeightEntity =
        DailyWeightEntity(
            sourceDate = sourceDate,
            measuredTime = "08:00",
            weightKg = 70.0,
            notes = null,
            importSource = "test",
            importedAtEpochMs = 1
        )
}
