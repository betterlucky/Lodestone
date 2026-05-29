package com.daveharris.healthmonitor.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SleepEpisodeReadinessRulesTest {
    @Test
    fun unconfirmedCandidatesNeverBecomePrimaryReadinessEpisodes() {
        val candidate = episode(
            id = 1,
            source = SleepEpisodeSources.PPI_INFERRED,
            confidence = SleepEpisodeConfidences.MEDIUM,
            isPrimaryForReadiness = true
        )

        assertNull(selectedPrimaryReadinessEpisode("2026-05-28", listOf(candidate)))
    }

    @Test
    fun selectedPrimaryCanBeMainSleepNapOrRestWhenUserConfirmed() {
        val nap = episode(
            id = 2,
            episodeKind = SleepEpisodeKinds.NAP,
            source = SleepEpisodeSources.MIXED,
            confidence = SleepEpisodeConfidences.USER_CONFIRMED,
            isPrimaryForReadiness = true,
            updatedAtEpochMs = 20L
        )
        val mainSleepContext = episode(
            id = 3,
            episodeKind = SleepEpisodeKinds.MAIN_SLEEP,
            source = SleepEpisodeSources.MIXED,
            confidence = SleepEpisodeConfidences.USER_CONFIRMED,
            isPrimaryForReadiness = false,
            updatedAtEpochMs = 30L
        )

        assertEquals(nap, selectedPrimaryReadinessEpisode("2026-05-28", listOf(mainSleepContext, nap)))
    }

    @Test
    fun latestSelectedPrimaryWinsWhenMultipleRowsAreFlagged() {
        val older = episode(
            id = 6,
            source = SleepEpisodeSources.MIXED,
            confidence = SleepEpisodeConfidences.USER_CONFIRMED,
            isPrimaryForReadiness = true,
            updatedAtEpochMs = 10L
        )
        val newer = episode(
            id = 7,
            source = SleepEpisodeSources.EDITED,
            confidence = SleepEpisodeConfidences.USER_CONFIRMED,
            isPrimaryForReadiness = true,
            updatedAtEpochMs = 20L
        )

        assertEquals(newer, selectedPrimaryReadinessEpisode("2026-05-28", listOf(older, newer)))
    }

    @Test
    fun confirmedNoSleepIsRepresentedWithoutAWindow() {
        val noSleep = episode(
            id = 4,
            episodeKind = SleepEpisodeKinds.NO_SLEEP,
            source = SleepEpisodeSources.MANUAL,
            confidence = SleepEpisodeConfidences.USER_CONFIRMED,
            startEpochMs = null,
            endEpochMs = null
        )

        assertTrue(hasConfirmedNoMainSleep("2026-05-28", listOf(noSleep)))
        assertNull(selectedPrimaryReadinessEpisode("2026-05-28", listOf(noSleep)))
    }

    @Test
    fun noSleepDecisionBlocksMainSleepCandidatesButKeepsRestContextReviewable() {
        val noSleep = episode(
            id = 8,
            episodeKind = SleepEpisodeKinds.NO_SLEEP,
            source = SleepEpisodeSources.MANUAL,
            confidence = SleepEpisodeConfidences.USER_CONFIRMED,
            startEpochMs = null,
            endEpochMs = null
        )
        val mainSleepCandidate = episode(
            id = 0,
            source = SleepEpisodeSources.PPI_INFERRED,
            confidence = SleepEpisodeConfidences.LOW,
            startEpochMs = null,
            endEpochMs = null
        )
        val restCandidate = episode(
            id = 0,
            episodeKind = SleepEpisodeKinds.REST_CANDIDATE,
            source = SleepEpisodeSources.PPI_INFERRED,
            confidence = SleepEpisodeConfidences.LOW,
            startEpochMs = null,
            endEpochMs = null
        )

        assertTrue(noSleep.blocksInferredCandidate(mainSleepCandidate))
        assertFalse(noSleep.blocksInferredCandidate(restCandidate))
    }

    @Test
    fun confirmedDecisionsBlockOverlappingInferredCandidates() {
        val confirmedRest = episode(
            id = 5,
            episodeKind = SleepEpisodeKinds.REST_CANDIDATE,
            source = SleepEpisodeSources.MIXED,
            confidence = SleepEpisodeConfidences.USER_CONFIRMED,
            startEpochMs = 1_000L,
            endEpochMs = 3_000L
        )
        val overlappingCandidate = episode(
            id = 0,
            source = SleepEpisodeSources.PPI_INFERRED,
            confidence = SleepEpisodeConfidences.MEDIUM,
            startEpochMs = 2_000L,
            endEpochMs = 4_000L
        )
        val laterCandidate = episode(
            id = 0,
            source = SleepEpisodeSources.PPI_INFERRED,
            confidence = SleepEpisodeConfidences.MEDIUM,
            startEpochMs = 3_000L,
            endEpochMs = 4_000L
        )

        assertTrue(confirmedRest.blocksInferredCandidate(overlappingCandidate))
        assertFalse(confirmedRest.blocksInferredCandidate(laterCandidate))
    }

    private fun episode(
        id: Long,
        sourceDate: String = "2026-05-28",
        startEpochMs: Long? = 1_000L,
        endEpochMs: Long? = 2_000L,
        episodeKind: String = SleepEpisodeKinds.MAIN_SLEEP,
        source: String,
        confidence: String,
        isPrimaryForReadiness: Boolean = false,
        updatedAtEpochMs: Long = 0L
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
            evidenceJson = null,
            notes = null,
            createdAtEpochMs = 0L,
            updatedAtEpochMs = updatedAtEpochMs
        )
}
