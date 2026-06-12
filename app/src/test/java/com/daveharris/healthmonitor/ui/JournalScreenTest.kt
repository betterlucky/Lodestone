package com.daveharris.healthmonitor.ui

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
            "No saved check-in for 2026-05-30. Current draft left unchanged.",
            journalBackfillStatusMessage("2026-05-30", hasSavedReview = false)
        )
    }
}
