package com.daveharris.healthmonitor.ui

import com.daveharris.healthmonitor.data.DailyCheckInEntity
import com.daveharris.healthmonitor.data.PaybackPeakConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaybackEpisodePromptTest {
    @Test
    fun multiDayPemRunPromptsWhenActiveDayIsNotPem() {
        val checkIns = listOf(
            checkIn("2026-05-11", pem = true),
            checkIn("2026-05-10", pem = true),
            checkIn("2026-05-09", pem = false)
        )

        val prompt = pendingPaybackPeakPrompt(
            activeDate = "2026-05-12",
            checkIns = checkIns,
            activePemMarked = false
        )

        assertEquals(listOf("2026-05-10", "2026-05-11"), prompt?.pemDates)
        assertEquals("2026-05-11", prompt?.episodeEndDate)
    }

    @Test
    fun activePemDayDoesNotPromptForPeakYet() {
        val checkIns = listOf(
            checkIn("2026-05-11", pem = true),
            checkIn("2026-05-10", pem = true)
        )

        val prompt = pendingPaybackPeakPrompt(
            activeDate = "2026-05-12",
            checkIns = checkIns,
            activePemMarked = true
        )

        assertNull(prompt)
    }

    @Test
    fun dismissedEpisodeDoesNotPromptAgain() {
        val checkIns = listOf(
            checkIn(
                "2026-05-11",
                pem = true,
                peakConfidence = PaybackPeakConfidence.DISMISSED
            ),
            checkIn("2026-05-10", pem = true)
        )

        val prompt = pendingPaybackPeakPrompt(
            activeDate = "2026-05-12",
            checkIns = checkIns,
            activePemMarked = false
        )

        assertNull(prompt)
    }

    @Test
    fun singleDayPemRunIsDetectableButNotPrompted() {
        val checkIns = listOf(checkIn("2026-05-11", pem = true))

        val episode = findEndedPaybackEpisodeBefore(
            activeDate = "2026-05-12",
            checkIns = checkIns,
            activePemMarked = false
        )
        val prompt = pendingPaybackPeakPrompt(
            activeDate = "2026-05-12",
            checkIns = checkIns,
            activePemMarked = false
        )

        assertEquals(listOf("2026-05-11"), episode?.pemDates)
        assertNull(prompt)
    }

    private fun checkIn(
        sourceDate: String,
        pem: Boolean,
        peak: Boolean = false,
        peakConfidence: String? = null
    ): DailyCheckInEntity =
        DailyCheckInEntity(
            sourceDate = sourceDate,
            eveningOutcome = "OK",
            approachToDay = null,
            muscleWeaknessToday = false,
            notes = null,
            createdAtEpochMs = 1,
            updatedAtEpochMs = 2,
            dayShapeCaptured = true,
            pemPaybackToday = pem,
            paybackPeakToday = peak,
            paybackPeakConfidence = peakConfidence
        )
}
