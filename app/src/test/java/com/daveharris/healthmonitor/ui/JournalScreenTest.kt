package com.daveharris.healthmonitor.ui

import com.daveharris.healthmonitor.data.DailyCheckInEntity
import com.daveharris.healthmonitor.data.TrafficLightStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JournalScreenTest {
    @Test
    fun priorDaysHintMentionsHistoryWhenEntriesExist() {
        val hint = journalPriorDaysHint(savedEntryCount = 3)
        assertTrue(hint.contains("3 saved entries"))
        assertTrue(hint.contains("History"))
    }

    @Test
    fun priorDaysHintUsesSingularEntryLabel() {
        val hint = journalPriorDaysHint(savedEntryCount = 1)
        assertTrue(hint.contains("1 saved entry"))
        assertTrue(!hint.contains("entries"))
    }

    @Test
    fun priorDaysHintExplainsFutureHistoryUseWhenEmpty() {
        val hint = journalPriorDaysHint(savedEntryCount = 0)
        assertTrue(hint.contains("After you save"))
        assertTrue(hint.contains("History"))
    }

    @Test
    fun backfillStatusMessageCoversSavedAndMissingDays() {
        assertEquals(
            "Loaded saved check-in for 2026-05-31.",
            journalBackfillStatusMessage("2026-05-31", hasSavedReview = true)
        )
        assertEquals(
            "Ready to add journal for 2026-05-30.",
            journalBackfillStatusMessage("2026-05-30", hasSavedReview = false)
        )
    }

    @Test
    fun priorEntryCountExcludesActiveDate() {
        val checkIns = listOf(
            checkIn("2026-05-30"),
            checkIn("2026-05-31")
        )

        assertEquals(1, journalPriorEntryCount(checkIns, activeDate = "2026-05-31"))
        assertEquals(2, journalPriorEntryCount(checkIns, activeDate = "2026-06-01"))
    }

    private fun checkIn(sourceDate: String): DailyCheckInEntity =
        DailyCheckInEntity(
            sourceDate = sourceDate,
            eveningOutcome = TrafficLightStatus.OK.name,
            approachToDay = null,
            muscleWeaknessToday = false,
            notes = null,
            createdAtEpochMs = 1,
            updatedAtEpochMs = 2,
            dayShapeCaptured = null,
            mostlyHorizontal = null,
            leftHouse = null,
            majorTask = null,
            majorTaskType = null,
            pemPaybackToday = null,
            paybackPeakToday = null,
            paybackPeakConfidence = null,
            manualGripStrengthKg = null
        )
}
