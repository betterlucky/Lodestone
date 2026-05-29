package com.daveharris.healthmonitor.ui

import com.daveharris.healthmonitor.data.SleepEpisodeConfidences
import com.daveharris.healthmonitor.data.SleepEpisodeEntity
import com.daveharris.healthmonitor.data.SleepEpisodeKinds
import com.daveharris.healthmonitor.data.SleepEpisodeSources
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SleepEpisodeReviewStateTest {
    private val utc = ZoneId.of("UTC")

    @Test
    fun inferredMainSleepCandidateMapsToSuggestionLabelsWithoutPrimaryFlag() {
        val episode = sleepEpisode(
            id = 1,
            startEpochMs = Instant.parse("2026-05-27T22:30:00Z").toEpochMilli(),
            endEpochMs = Instant.parse("2026-05-28T06:00:00Z").toEpochMilli(),
            episodeKind = SleepEpisodeKinds.MAIN_SLEEP,
            source = SleepEpisodeSources.PPI_INFERRED,
            confidence = SleepEpisodeConfidences.MEDIUM,
            isPrimaryForReadiness = false,
            evidenceJson = """
                {"label":"PPI-inferred main sleep candidate","durationMinutes":450,"hasExplicitWakeMarker":true}
            """.trimIndent()
        )

        val state = buildSleepEpisodeReviewState(
            activeDate = "2026-05-28",
            reviewDates = listOf("2026-05-28"),
            episodes = listOf(episode),
            zoneId = utc
        )

        val group = state.activeDateGroup!!
        val item = group.items.single()
        assertEquals(1, state.totalCandidateCount)
        assertEquals("Review possible sleep/rest windows before using them for readiness.", state.surfaceMessage)
        assertEquals("Possible sleep", item.title)
        assertEquals("22:30-06:00", item.timeRangeLabel)
        assertEquals("7h 30m", item.durationLabel)
        assertEquals("Main sleep", item.kindLabel)
        assertEquals("Suggested from PPI", item.sourceLabel)
        assertEquals("Medium signal", item.confidenceLabel)
        assertNull(item.primaryLabel)
        assertTrue(item.isCandidate)
        assertFalse(item.isConfirmed)
        assertFalse(item.canClearDecision)
        assertFalse(group.hasPrimaryReadinessWindow)
    }

    @Test
    fun confirmedNoSleepMapsToNoWindowState() {
        val episode = sleepEpisode(
            id = 2,
            startEpochMs = null,
            endEpochMs = null,
            episodeKind = SleepEpisodeKinds.NO_SLEEP,
            source = SleepEpisodeSources.MANUAL,
            confidence = SleepEpisodeConfidences.USER_CONFIRMED,
            isPrimaryForReadiness = false
        )

        val item = episode.toSleepEpisodeDisplayItem(utc)

        assertEquals("No main sleep", item.title)
        assertEquals("No timed window", item.timeRangeLabel)
        assertEquals("No main sleep", item.durationLabel)
        assertEquals("Manual", item.sourceLabel)
        assertEquals("Confirmed", item.confidenceLabel)
        assertTrue(item.isConfirmed)
        assertTrue(item.isNoSleep)
        assertTrue(item.canClearDecision)
        assertFalse(item.isPrimaryForReadiness)
    }

    @Test
    fun catchUpStateKeepsEmptyDatesAndSortsNoSleepAfterTimedRows() {
        val noSleep = sleepEpisode(
            id = 5,
            sourceDate = "2026-05-27",
            startEpochMs = null,
            endEpochMs = null,
            episodeKind = SleepEpisodeKinds.NO_SLEEP,
            source = SleepEpisodeSources.MANUAL,
            confidence = SleepEpisodeConfidences.USER_CONFIRMED
        )
        val timed = sleepEpisode(
            id = 4,
            sourceDate = "2026-05-27",
            startEpochMs = Instant.parse("2026-05-26T23:00:00Z").toEpochMilli(),
            endEpochMs = Instant.parse("2026-05-27T06:10:00Z").toEpochMilli(),
            episodeKind = SleepEpisodeKinds.MAIN_SLEEP,
            source = SleepEpisodeSources.EDITED,
            confidence = SleepEpisodeConfidences.USER_CONFIRMED,
            isPrimaryForReadiness = true
        )

        val state = buildSleepEpisodeReviewState(
            activeDate = "2026-05-28",
            reviewDates = listOf("2026-05-26", "2026-05-27", "2026-05-28"),
            episodes = listOf(noSleep, timed),
            reviewedDates = setOf("2026-05-26"),
            zoneId = utc
        )

        assertTrue(state.hasCatchUpDates)
        assertEquals(2, state.totalConfirmedCount)
        assertEquals(1, state.attentionDateCount)
        assertEquals("Review missing days from oldest to newest.", state.surfaceMessage)
        assertEquals(listOf("2026-05-26", "2026-05-27", "2026-05-28"), state.dateGroups.map { it.sourceDate })
        assertTrue(state.dateGroups[0].isEmpty)
        assertEquals("Review saved", state.dateGroups[0].repairStatusLabel)
        assertFalse(state.dateGroups[0].needsAttention)
        assertEquals(listOf("Confirmed sleep", "No main sleep"), state.dateGroups[1].items.map { it.title })
        assertEquals("Confirmed", state.dateGroups[1].repairStatusLabel)
        assertTrue(state.dateGroups[1].hasPrimaryReadinessWindow)
        assertTrue(state.dateGroups[1].items.first().canClearDecision)
        assertEquals("No candidates", state.dateGroups[2].repairStatusLabel)
        assertTrue(state.dateGroups[2].needsAttention)
    }

    private fun sleepEpisode(
        id: Long,
        sourceDate: String = "2026-05-28",
        startEpochMs: Long?,
        endEpochMs: Long?,
        episodeKind: String,
        source: String,
        confidence: String,
        isPrimaryForReadiness: Boolean = false,
        evidenceJson: String? = null
    ): SleepEpisodeEntity =
        SleepEpisodeEntity(
            id = id,
            sourceDate = sourceDate,
            startEpochMs = startEpochMs,
            endEpochMs = endEpochMs,
            episodeKind = episodeKind,
            source = source,
            confidence = confidence,
            isPrimaryForReadiness = isPrimaryForReadiness,
            deviceId = null,
            linkedSleepRawId = null,
            evidenceJson = evidenceJson,
            notes = null,
            createdAtEpochMs = 0L,
            updatedAtEpochMs = 0L
        )
}
