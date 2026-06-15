package com.daveharris.healthmonitor.ui

import com.daveharris.healthmonitor.data.CurrentStateSnapshotEntity
import com.daveharris.healthmonitor.data.DailyCheckInEntity
import com.daveharris.healthmonitor.data.TrafficLightStatus
import java.time.LocalDate
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
    fun reportsPairLatestForecastWithJournalOutcomeAndCompleteness() {
        val reports = buildHistoryDayReports(
            forecasts = listOf(
                forecast("2026-05-30", issuedAt = 1, level = TrafficLightStatus.GOOD.name),
                forecast("2026-05-31", issuedAt = 1, level = TrafficLightStatus.OK.name),
                forecast("2026-05-31", issuedAt = 2, level = TrafficLightStatus.UNSTEADY.name)
            ),
            checkIns = listOf(checkIn("2026-05-31", TrafficLightStatus.CRASH.name))
        )

        val latest = reports.first()
        assertEquals("2026-05-31", latest.sourceDate)
        assertEquals(TrafficLightStatus.UNSTEADY, latest.forecastStatus)
        assertEquals(TrafficLightStatus.CRASH, latest.outcomeStatus)
        assertEquals("Forecast was steadier than the outcome", latest.forecastOutcomeLabel)
        assertTrue(latest.dataCompletenessLabel.contains("forecast"))
        assertTrue(latest.dataCompletenessLabel.contains("journal"))
        assertEquals("Good → Unsteady", latest.stateTransitionLabel)
        assertTrue(latest.functionalContextLabel.contains("Outcome: Crash"))
        assertTrue(latest.functionalContextLabel.contains("day shape unknown"))
    }

    @Test
    fun reportsJournalOnlyDaysWithoutPretendingThereWasForecastEvidence() {
        val reports = buildHistoryDayReports(
            forecasts = emptyList(),
            checkIns = listOf(checkIn("2026-05-31", TrafficLightStatus.OK.name))
        )

        val report = reports.single()
        assertEquals("No forecast", report.forecastLabel)
        assertEquals("Outcome saved without a forecast", report.forecastOutcomeLabel)
        assertEquals("journal", report.dataCompletenessLabel)
        assertEquals("No forecast recorded", report.stateTransitionLabel)
    }

    @Test
    fun reportsMixedForecastAndFunctionalEvidenceWithoutFailureLanguage() {
        val reports = buildHistoryDayReports(
            forecasts = listOf(
                forecast("2026-05-31", issuedAt = 1, level = TrafficLightStatus.GOOD.name)
            ),
            checkIns = listOf(
                checkIn(
                    "2026-05-31",
                    TrafficLightStatus.UNSTEADY.name,
                    dayShapeCaptured = true,
                    mostlyHorizontal = true,
                    pemPaybackToday = true
                )
            )
        )

        val report = reports.single()
        assertEquals("Forecast was steadier than the outcome", report.forecastOutcomeLabel)
        assertTrue(report.functionalContextLabel.contains("Mostly horizontal"))
        assertTrue(report.functionalContextLabel.contains("PEM / payback"))
        assertTrue(report.dataCompletenessLabel.contains("day shape"))
    }

    @Test
    fun confidenceLabelShowsOnlyWhenDegraded() {
        val low = buildHistoryDayReports(
            forecasts = listOf(forecast("2026-05-31", issuedAt = 1, level = TrafficLightStatus.OK.name, confidence = "LOW")),
            checkIns = emptyList()
        ).single()
        assertEquals("Low — limited recent data", low.confidenceLabel)

        val medium = buildHistoryDayReports(
            forecasts = listOf(forecast("2026-05-31", issuedAt = 1, level = TrafficLightStatus.OK.name, confidence = "MEDIUM")),
            checkIns = emptyList()
        ).single()
        assertEquals("Medium — still gathering data", medium.confidenceLabel)

        val high = buildHistoryDayReports(
            forecasts = listOf(forecast("2026-05-31", issuedAt = 1, level = TrafficLightStatus.OK.name, confidence = "HIGH")),
            checkIns = emptyList()
        ).single()
        assertNull(high.confidenceLabel)

        val journalOnly = buildHistoryDayReports(
            forecasts = emptyList(),
            checkIns = listOf(checkIn("2026-05-31", TrafficLightStatus.OK.name))
        ).single()
        assertNull(journalOnly.confidenceLabel)
    }

    @Test
    fun reportBuilderHandlesLargerLocalHistoryWithoutDroppingRows() {
        val start = LocalDate.parse("2020-01-01")
        val dates = (0 until 2_000).map { start.plusDays(it.toLong()).toString() }
        val forecasts = dates.mapIndexed { index, date ->
            forecast(
                sourceDate = date,
                issuedAt = index.toLong(),
                level = if (index % 3 == 0) TrafficLightStatus.GOOD.name else TrafficLightStatus.OK.name
            )
        }
        val checkIns = dates.filterIndexed { index, _ -> index % 2 == 0 }
            .map { date -> checkIn(date, TrafficLightStatus.OK.name) }

        val reports = buildHistoryDayReports(
            forecasts = forecasts,
            checkIns = checkIns
        )

        assertEquals(2_000, reports.size)
        assertEquals(dates.last(), reports.first().sourceDate)
        assertEquals(dates.first(), reports.last().sourceDate)
        assertEquals("First forecast on record", reports.last().stateTransitionLabel)
    }

    @Test
    fun coverageSummaryCountsJournalAndForecastCoverage() {
        val reports = buildHistoryDayReports(
            forecasts = listOf(forecast("2026-05-31", issuedAt = 1, level = TrafficLightStatus.OK.name)),
            checkIns = listOf(checkIn("2026-05-31", TrafficLightStatus.CRASH.name))
        )

        val summary = buildHistoryCoverageSummary(reports, attentionDateCount = 2)
        assertEquals(1, summary.dayReportCount)
        assertEquals(1, summary.withJournalCount)
        assertEquals(1, summary.withForecastCount)
        assertEquals(2, summary.attentionDateCount)
    }

    @Test
    fun journalActionLabelReflectsWhetherOutcomeExists() {
        assertEquals("Edit journal", historyJournalActionLabel(hasSavedOutcome = true))
        assertEquals("Add journal", historyJournalActionLabel(hasSavedOutcome = false))
    }

    @Test
    fun sleepWindowDetailRowSurfacesOnlyActionableStatuses() {
        assertEquals(
            "Sleep window" to "Needs review — confirm candidates on Now",
            historySleepWindowDetailRow("Needs review")
        )
        assertEquals("Sleep window" to "Confirmed", historySleepWindowDetailRow("Confirmed"))
        assertEquals(null, historySleepWindowDetailRow("No candidates"))
    }

    @Test
    fun stateTransitionLabelUsesPlainLanguage() {
        assertEquals("Unchanged from prior day", stateTransitionLabelForTest("OK", "OK"))
        assertEquals("First forecast on record", stateTransitionLabelForTest(null, "GOOD"))
    }

    @Test
    fun journalOnlyDaysReportNoForecastChange() {
        val reports = buildHistoryDayReports(
            forecasts = emptyList(),
            checkIns = listOf(checkIn("2026-05-31", TrafficLightStatus.OK.name))
        )

        assertEquals("No forecast recorded", reports.single().stateTransitionLabel)
    }

    private fun stateTransitionLabelForTest(previousLevel: String?, currentLevel: String): String {
        val reports = buildHistoryDayReports(
            forecasts = buildList {
                previousLevel?.let { add(forecast("2026-05-30", issuedAt = 1, level = it)) }
                add(forecast("2026-05-31", issuedAt = 1, level = currentLevel))
            },
            checkIns = emptyList()
        )
        return reports.first { it.sourceDate == "2026-05-31" }.stateTransitionLabel
    }

    private fun forecast(
        sourceDate: String,
        issuedAt: Long,
        level: String,
        confidence: String = "LOW"
    ): CurrentStateSnapshotEntity =
        CurrentStateSnapshotEntity(
            sourceDate = sourceDate,
            issuedAtEpochMs = issuedAt,
            snapshotOrigin = "test",
            modelVersion = "test",
            forecastLevel = level,
            forecastBasis = "RECENT_OUTCOME",
            cautionLevel = "NONE",
            cautionKind = "PUSH_RISK",
            cautionReasonsJson = "[]",
            confidenceLevel = confidence,
            recentOutcomeLevel = level,
            recentOutcomeDate = sourceDate,
            exertionLoadRecent = 12,
            hrvCv24h = 0.08,
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
}
