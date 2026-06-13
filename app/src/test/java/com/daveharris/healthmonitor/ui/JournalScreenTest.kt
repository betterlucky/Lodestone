package com.daveharris.healthmonitor.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import com.daveharris.healthmonitor.data.TrafficLightStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JournalScreenTest {
    @Test
    fun captureFlowStartsWithRequiredOutcomeAndCanSaveOnlyAfterOutcome() {
        assertEquals(JournalCaptureStep.OUTCOME, journalCaptureSteps.first())
        assertEquals("How did the day go?", JournalCaptureStep.OUTCOME.title)
        assertTrue(!canSaveJournalCapture(null))
        assertTrue(canSaveJournalCapture(TrafficLightStatus.OK))
    }

    @Test
    fun captureFlowMovesOneQuestionAtATime() {
        assertEquals(JournalCaptureStep.DAY_SHAPE, nextJournalCaptureStep(JournalCaptureStep.OUTCOME))
        assertEquals(JournalCaptureStep.OUTCOME, previousJournalCaptureStep(JournalCaptureStep.DAY_SHAPE))
        assertEquals(JournalCaptureStep.NOTES_IMPORTS, nextJournalCaptureStep(JournalCaptureStep.NOTES_IMPORTS))
        assertEquals(JournalCaptureStep.OUTCOME, previousJournalCaptureStep(JournalCaptureStep.OUTCOME))
    }

    @Test
    fun primaryTabsRetireJournalDestination() {
        assertEquals(listOf("Now", "Signals", "History"), primaryProbeTabTitles())
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun captureSheetBlocksSwipeToHiddenDismissal() {
        assertTrue(!shouldAllowJournalCaptureSheetValue(SheetValue.Hidden))
        assertTrue(shouldAllowJournalCaptureSheetValue(SheetValue.Expanded))
    }
}
