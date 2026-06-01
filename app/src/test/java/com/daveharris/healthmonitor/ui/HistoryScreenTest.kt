package com.daveharris.healthmonitor.ui

import com.daveharris.healthmonitor.data.DailyCheckInEntity
import com.daveharris.healthmonitor.data.DailyWeightEntity
import com.daveharris.healthmonitor.data.FoodDailySummaryEntity
import com.daveharris.healthmonitor.data.MorningPredictionSnapshotEntity
import com.daveharris.healthmonitor.data.TrafficLightStatus
import java.time.LocalDate
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
        assertEquals("Morning signal and outcome differed", latest.predictionOutcomeLabel)
        assertEquals("Short/irregular sleep", latest.sleepBucketLabel)
        assertTrue(latest.dataCompletenessLabel.contains("prediction"))
        assertTrue(latest.dataCompletenessLabel.contains("journal"))
        assertTrue(latest.dataCompletenessLabel.contains("food"))
        assertTrue(latest.dataCompletenessLabel.contains("weight"))
        assertEquals("Good -> Unsteady", latest.stabilityTransitionLabel)
        assertEquals("1800 kcal, 4 items, 1 tea, 11.0h window", latest.foodSummaryLabel)
        assertEquals("70.0 kg at 08:00", latest.weightLabel)
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
        assertEquals("No morning signal", report.predictionLabel)
        assertEquals("Outcome saved without a morning signal", report.predictionOutcomeLabel)
        assertEquals("No morning signal evidence", report.robustnessLabel)
        assertEquals("journal", report.dataCompletenessLabel)
        assertEquals("No active window recorded", report.windowProvenanceLabel)
    }

    @Test
    fun reportsWhetherWindowSourceWasProvisionalOrFinal() {
        val reports = buildHistoryDayReports(
            predictions = listOf(
                prediction(
                    "2026-05-31",
                    issuedAt = 1,
                    status = TrafficLightStatus.OK.name,
                    sleepMinutes = null,
                    isInterim = true
                )
            ),
            checkIns = emptyList(),
            foodSummaries = emptyList(),
            weights = emptyList()
        )

        assertEquals(
            "raw ppi manual window pending sleep report (provisional)",
            reports.single().windowProvenanceLabel
        )
    }

    @Test
    fun reportsFormatPartialFoodAndWeightRows() {
        val reports = buildHistoryDayReports(
            predictions = emptyList(),
            checkIns = emptyList(),
            foodSummaries = listOf(
                food(
                    sourceDate = "2026-05-31",
                    totalCaloriesKcal = null,
                    eventCount = 4,
                    teaCount = null,
                    eatingWindowHours = null
                ),
                food(
                    sourceDate = "2026-05-30",
                    totalCaloriesKcal = null,
                    eventCount = null,
                    teaCount = null,
                    eatingWindowHours = null
                )
            ),
            weights = listOf(weight(sourceDate = "2026-05-31", measuredTime = null))
        )

        assertEquals("4 items", reports.first { it.sourceDate == "2026-05-31" }.foodSummaryLabel)
        assertEquals("70.0 kg", reports.first { it.sourceDate == "2026-05-31" }.weightLabel)
        assertEquals("Food import present", reports.first { it.sourceDate == "2026-05-30" }.foodSummaryLabel)
    }

    @Test
    fun reportBuilderHandlesLargerLocalHistoryWithoutDroppingRows() {
        val start = LocalDate.parse("2020-01-01")
        val dates = (0 until 2_000).map { start.plusDays(it.toLong()).toString() }
        val predictions = dates.mapIndexed { index, date ->
            prediction(
                sourceDate = date,
                issuedAt = index.toLong(),
                status = if (index % 3 == 0) TrafficLightStatus.GOOD.name else TrafficLightStatus.OK.name
            )
        }
        val checkIns = dates.filterIndexed { index, _ -> index % 2 == 0 }
            .map { date -> checkIn(date, TrafficLightStatus.OK.name) }
        val foods = dates.filterIndexed { index, _ -> index % 5 == 0 }.map(::food)
        val weights = dates.filterIndexed { index, _ -> index % 7 == 0 }.map(::weight)

        val reports = buildHistoryDayReports(
            predictions = predictions,
            checkIns = checkIns,
            foodSummaries = foods,
            weights = weights
        )

        assertEquals(2_000, reports.size)
        assertEquals(dates.last(), reports.first().sourceDate)
        assertEquals(dates.first(), reports.last().sourceDate)
        assertEquals("First recorded status", reports.last().stabilityTransitionLabel)
    }

    private fun prediction(
        sourceDate: String,
        issuedAt: Long,
        status: String,
        sleepMinutes: Int? = 480,
        isInterim: Boolean = false
    ): MorningPredictionSnapshotEntity =
        MorningPredictionSnapshotEntity(
            sourceDate = sourceDate,
            issuedAtEpochMs = issuedAt,
            snapshotOrigin = "test",
            modelVersion = "test",
            status = status,
            confidence = "medium",
            isInterim = isInterim,
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

    private fun food(
        sourceDate: String,
        totalCaloriesKcal: Int? = 1800,
        eventCount: Int? = 4,
        teaCount: Int? = 1,
        eatingWindowHours: Double? = 11.0
    ): FoodDailySummaryEntity =
        FoodDailySummaryEntity(
            sourceDate = sourceDate,
            totalCaloriesKcal = totalCaloriesKcal,
            eventCount = eventCount,
            teaCount = teaCount,
            firstIntakeTime = "08:00",
            lastIntakeTime = "19:00",
            eatingWindowHours = eatingWindowHours,
            rawItemsJson = "[]",
            importSource = "test",
            importedAtEpochMs = 1
        )

    private fun weight(sourceDate: String, measuredTime: String? = "08:00"): DailyWeightEntity =
        DailyWeightEntity(
            sourceDate = sourceDate,
            measuredTime = measuredTime,
            weightKg = 70.0,
            notes = null,
            importSource = "test",
            importedAtEpochMs = 1
        )
}
