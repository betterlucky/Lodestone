package com.daveharris.healthmonitor.ui

import com.daveharris.healthmonitor.data.DailyCheckInEntity
import com.daveharris.healthmonitor.data.DailyWeightEntity
import com.daveharris.healthmonitor.data.FoodDailySummaryEntity
import com.daveharris.healthmonitor.data.GripSessionEntity
import com.daveharris.healthmonitor.data.MorningPredictionSnapshotEntity
import com.daveharris.healthmonitor.data.TrafficLightStatus
import java.time.LocalDate
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryScreenTest {
    private val defaultLocale = Locale.getDefault()

    @BeforeTest
    fun pinLocale() {
        Locale.setDefault(Locale.US)
    }

    @AfterTest
    fun restoreLocale() {
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun reportsPairLatestPredictionWithJournalOutcomeAndCompleteness() {
        val reports = buildHistoryDayReports(
            predictions = listOf(
                prediction("2026-05-30", issuedAt = 1, status = TrafficLightStatus.GOOD.name),
                prediction("2026-05-31", issuedAt = 1, status = TrafficLightStatus.OK.name, sleepMinutes = 450),
                prediction("2026-05-31", issuedAt = 2, status = TrafficLightStatus.UNSTEADY.name, sleepMinutes = 210)
            ),
            checkIns = listOf(checkIn("2026-05-31", TrafficLightStatus.CRASH.name, manualGripStrengthKg = 28.5)),
            foodSummaries = listOf(food("2026-05-31")),
            weights = listOf(weight("2026-05-31"))
        )

        val latest = reports.first()
        assertEquals("2026-05-31", latest.sourceDate)
        assertEquals(TrafficLightStatus.UNSTEADY, latest.predictionStatus)
        assertEquals(TrafficLightStatus.CRASH, latest.outcomeStatus)
        assertEquals("Autonomic signal looked steadier than functional outcome", latest.predictionOutcomeLabel)
        assertEquals("Short/irregular sleep", latest.sleepBucketLabel)
        assertTrue(latest.dataCompletenessLabel.contains("prediction"))
        assertTrue(latest.dataCompletenessLabel.contains("journal"))
        assertTrue(latest.dataCompletenessLabel.contains("food"))
        assertTrue(latest.dataCompletenessLabel.contains("weight"))
        assertEquals("Good -> Unsteady", latest.stabilityTransitionLabel)
        assertEquals("1800 kcal, 4 items, 1 tea, 11.0h window", latest.foodSummaryLabel)
        assertEquals("70.0 kg at 08:00", latest.weightLabel)
        assertEquals("28.5 kg", latest.gripStrengthLabel)
        assertTrue(latest.functionalContextLabel.contains("Outcome: Crash"))
        assertTrue(latest.functionalContextLabel.contains("day shape unknown"))
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
        assertEquals("Functional outcome saved without an autonomic signal", report.predictionOutcomeLabel)
        assertEquals("No morning signal evidence", report.robustnessLabel)
        assertEquals("journal", report.dataCompletenessLabel)
        assertEquals("No active window recorded", report.windowProvenanceLabel)
    }

    @Test
    fun reportsMixedAutonomicAndFunctionalEvidenceWithoutFailureLanguage() {
        val reports = buildHistoryDayReports(
            predictions = listOf(
                prediction("2026-05-31", issuedAt = 1, status = TrafficLightStatus.GOOD.name)
            ),
            checkIns = listOf(
                checkIn(
                    "2026-05-31",
                    TrafficLightStatus.UNSTEADY.name,
                    dayShapeCaptured = true,
                    mostlyHorizontal = true,
                    pemPaybackToday = true
                )
            ),
            foodSummaries = emptyList(),
            weights = emptyList()
        )

        val report = reports.single()
        assertEquals("Autonomic signal looked steadier than functional outcome", report.predictionOutcomeLabel)
        assertTrue(report.functionalContextLabel.contains("Mostly horizontal"))
        assertTrue(report.functionalContextLabel.contains("PEM / payback"))
        assertTrue(report.dataCompletenessLabel.contains("day shape"))
    }

    @Test
    fun reportsWhetherWindowSourceHasLoopReportContext() {
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
            "raw ppi manual window pending sleep report (Loop report pending for comparison)",
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
    fun reportsImportedGripSessionsAsFunctionalEvidence() {
        val reports = buildHistoryDayReports(
            predictions = emptyList(),
            checkIns = emptyList(),
            foodSummaries = emptyList(),
            weights = emptyList(),
            gripSessions = listOf(gripSession("2026-05-31"))
        )

        val report = reports.single()
        assertEquals("2026-05-31", report.sourceDate)
        assertEquals("grip", report.dataCompletenessLabel)
        assertTrue(report.gripStrengthLabel.contains("1 session"))
        assertTrue(report.gripStrengthLabel.contains("best 35.1 kg"))
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

    private fun checkIn(
        sourceDate: String,
        outcome: String,
        dayShapeCaptured: Boolean? = null,
        mostlyHorizontal: Boolean? = null,
        pemPaybackToday: Boolean? = null,
        manualGripStrengthKg: Double? = null
    ): DailyCheckInEntity =
        DailyCheckInEntity(
            sourceDate = sourceDate,
            eveningOutcome = outcome,
            approachToDay = null,
            muscleWeaknessToday = false,
            notes = "notes",
            createdAtEpochMs = 1,
            updatedAtEpochMs = 2,
            dayShapeCaptured = dayShapeCaptured,
            mostlyHorizontal = mostlyHorizontal,
            leftHouse = false,
            majorTask = false,
            majorTaskType = null,
            pemPaybackToday = pemPaybackToday,
            paybackPeakToday = false,
            paybackPeakConfidence = null,
            manualGripStrengthKg = manualGripStrengthKg
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

    private fun gripSession(sourceDate: String): GripSessionEntity =
        GripSessionEntity(
            sessionId = "grip-$sourceDate-test",
            sourceDate = sourceDate,
            startedAtEpochMs = 1,
            startedAtLocal = "${sourceDate}T09:00:00+01:00",
            hand = "left",
            protocolLabel = "check_2",
            pullSeconds = 3.0,
            restSeconds = 5.0,
            expectedRepCount = 2,
            setNumber = 1,
            restGapMinutes = null,
            deviceLabel = null,
            bodyPosition = null,
            armPosition = null,
            handleSetting = null,
            notes = null,
            completedRepCount = 2,
            bestValueKg = 35.1,
            meanValueKg = 32.0,
            firstValueKg = 35.1,
            lastValueKg = 29.0,
            bestToLastDropPct = 17.38,
            importSource = "test",
            importedAtEpochMs = 1
        )
}
