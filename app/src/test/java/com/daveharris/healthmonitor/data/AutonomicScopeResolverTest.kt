package com.daveharris.healthmonitor.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutonomicScopeResolverTest {
    private val baseEpochMs = 1_700_000_000_000L
    private val recoveryWindow = AutonomicRecoveryWindow(
        startEpochMs = baseEpochMs,
        endEpochMs = baseEpochMs + 5 * 60 * 60_000L,
        provenanceLabel = "calibrated sleep window",
        sourceDate = "2026-06-13"
    )

    @Test
    fun recoveryScopeIncludesGoodAndUsableEpochsFullyInsideWindow() {
        val epochs = listOf(
            epoch(
                start = baseEpochMs + 5 * 60_000L,
                quality = "good",
                rmssd = 55.0
            ),
            epoch(
                start = baseEpochMs + 10 * 60_000L,
                quality = "usable",
                rmssd = 48.0
            ),
            epoch(
                start = baseEpochMs + 15 * 60_000L,
                quality = "review",
                rmssd = 40.0
            ),
            epoch(
                start = baseEpochMs - 10 * 60_000L,
                quality = "good",
                rmssd = 70.0
            )
        )

        val summary = AutonomicScopeResolver.resolve(
            scope = AutonomicDetailScope.ACTIVE_SLEEP_REST,
            epochs = epochs,
            recoveryWindow = recoveryWindow,
            sleepEpisodes = emptyList(),
            lastPpiSyncEpochMs = baseEpochMs + 6 * 60 * 60_000L,
            nowEpochMs = baseEpochMs + 6 * 60 * 60_000L
        )

        assertEquals(AutonomicPlanningInfluence.RECOVERY_LANE, summary.planningInfluence)
        assertEquals(2, summary.quality.summaryEpochCount)
        assertEquals(1, summary.quality.goodEpochCount)
        assertEquals(1, summary.quality.usableEpochCount)
        assertEquals(1, summary.quality.reviewEpochCount)
        assertEquals(2, summary.trajectoryPoints.size)
        assertTrue(summary.showStrainLabels.not())
    }

    @Test
    fun rollingScopeIsDescriptiveOnlyAndExcludesMotionHeavyEpochs() {
        val anchor = baseEpochMs + 2 * 60 * 60_000L
        val epochs = listOf(
            epoch(
                start = anchor - 50 * 60_000L,
                quality = "good",
                rmssd = 42.0,
                movementDetectedCount = 1,
                sampleCount = 10
            ),
            epoch(
                start = anchor - 45 * 60_000L,
                quality = "good",
                rmssd = 39.0,
                movementDetectedCount = 4,
                sampleCount = 10
            ),
            epoch(
                start = anchor - 40 * 60_000L,
                quality = "usable",
                rmssd = 36.0,
                movementDetectedCount = 0,
                sampleCount = 10
            )
        )

        val summary = AutonomicScopeResolver.resolve(
            scope = AutonomicDetailScope.ROLLING_1H,
            epochs = epochs,
            recoveryWindow = recoveryWindow,
            sleepEpisodes = emptyList(),
            lastPpiSyncEpochMs = anchor,
            nowEpochMs = anchor + 30 * 60_000L
        )

        assertEquals(AutonomicPlanningInfluence.DESCRIPTIVE_ONLY, summary.planningInfluence)
        assertEquals(AutonomicScopeFamily.RECENT_TREND, summary.family)
        assertEquals(2, summary.quality.summaryEpochCount)
        assertEquals(1, summary.quality.excludedMotionContactCount)
        assertFalse(summary.showStrainLabels)
        assertTrue(summary.cautionLines.any { it.contains("does not change your daily forecast") })
    }

    @Test
    fun rolling24hProvidesSleepRestShadingIntervals() {
        val anchor = baseEpochMs + 24 * 60 * 60_000L
        val sleepEpisodes = listOf(
            SleepEpisodeEntity(
                id = 7L,
                sourceDate = "2026-06-13",
                startEpochMs = anchor - 8 * 60 * 60_000L,
                endEpochMs = anchor - 6 * 60 * 60_000L,
                episodeKind = SleepEpisodeKinds.REST_CANDIDATE,
                source = SleepEpisodeSources.PPI_INFERRED,
                confidence = SleepEpisodeConfidences.MEDIUM,
                isPrimaryForReadiness = false,
                deviceId = "dev",
                linkedSleepRawId = null,
                evidenceJson = null,
                notes = null,
                createdAtEpochMs = anchor,
                updatedAtEpochMs = anchor
            )
        )
        val epochs = (0 until 6).map { index ->
            epoch(
                start = anchor - (5 - index) * 3 * 60 * 60_000L,
                quality = "good",
                rmssd = 40.0 + index
            )
        }

        val summary = AutonomicScopeResolver.resolve(
            scope = AutonomicDetailScope.ROLLING_24H,
            epochs = epochs,
            recoveryWindow = recoveryWindow,
            sleepEpisodes = sleepEpisodes,
            lastPpiSyncEpochMs = anchor,
            nowEpochMs = anchor
        )

        assertNotNull(summary.sleepRestShading)
        assertEquals(1, summary.sleepRestShading.size)
        assertNull(summary.sleepRestShading.first().let { it.endEpochMs - it.startEpochMs }.takeIf { it <= 0L })
    }

    @Test
    fun alternateEpisodeLinkSurfacesMoreRecentRecordedSleepRestEpisode() {
        val sleepEpisodes = listOf(
            SleepEpisodeEntity(
                id = 1L,
                sourceDate = "2026-06-13",
                startEpochMs = recoveryWindow.startEpochMs,
                endEpochMs = recoveryWindow.endEpochMs,
                episodeKind = SleepEpisodeKinds.MAIN_SLEEP,
                source = SleepEpisodeSources.MANUAL,
                confidence = SleepEpisodeConfidences.USER_CONFIRMED,
                isPrimaryForReadiness = true,
                deviceId = "dev",
                linkedSleepRawId = null,
                evidenceJson = null,
                notes = null,
                createdAtEpochMs = recoveryWindow.endEpochMs,
                updatedAtEpochMs = recoveryWindow.endEpochMs
            ),
            SleepEpisodeEntity(
                id = 2L,
                sourceDate = "2026-06-13",
                startEpochMs = recoveryWindow.endEpochMs + 2 * 60 * 60_000L,
                endEpochMs = recoveryWindow.endEpochMs + 3 * 60 * 60_000L,
                episodeKind = SleepEpisodeKinds.REST_CANDIDATE,
                source = SleepEpisodeSources.PPI_INFERRED,
                confidence = SleepEpisodeConfidences.MEDIUM,
                isPrimaryForReadiness = false,
                deviceId = "dev",
                linkedSleepRawId = null,
                evidenceJson = null,
                notes = null,
                createdAtEpochMs = recoveryWindow.endEpochMs,
                updatedAtEpochMs = recoveryWindow.endEpochMs
            )
        )

        val summary = AutonomicScopeResolver.resolve(
            scope = AutonomicDetailScope.ACTIVE_SLEEP_REST,
            epochs = emptyList(),
            recoveryWindow = recoveryWindow,
            sleepEpisodes = sleepEpisodes,
            lastPpiSyncEpochMs = recoveryWindow.endEpochMs,
            nowEpochMs = recoveryWindow.endEpochMs + 4 * 60 * 60_000L
        )

        assertNotNull(summary.alternateEpisodeLink)
        assertEquals(2L, summary.alternateEpisodeLink.episodeId)
        assertTrue(summary.alternateEpisodeLink.label.startsWith("Also: rest window"))
    }

    @Test
    fun resolveAllKeepsOnlyActiveSleepRestOnRecoveryLane() {
        val epochs = (0 until 15).map { index ->
            epoch(
                start = baseEpochMs + index * 5 * 60_000L,
                quality = if (index % 2 == 0) "good" else "usable",
                rmssd = 40.0 + index
            )
        }
        val summaries = AutonomicScopeResolver.resolveAll(
            epochs = epochs,
            recoveryWindow = recoveryWindow,
            sleepEpisodes = emptyList(),
            lastPpiSyncEpochMs = recoveryWindow.endEpochMs,
            nowEpochMs = recoveryWindow.endEpochMs + 60_000L
        )

        assertEquals(
            AutonomicPlanningInfluence.RECOVERY_LANE,
            summaries.getValue(AutonomicDetailScope.ACTIVE_SLEEP_REST).planningInfluence
        )
        AutonomicDetailScope.selectorOrder
            .filter { it.isRolling }
            .forEach { scope ->
                assertEquals(
                    AutonomicPlanningInfluence.DESCRIPTIVE_ONLY,
                    summaries.getValue(scope).planningInfluence
                )
            }
    }

    @Test
    fun rollingScopeIncludesEpochOverlappingLeftEdge() {
        val anchor = baseEpochMs + 2 * 60 * 60_000L
        val windowStart = anchor - 60 * 60_000L
        val epochs = listOf(
            // Starts before the window but ends 3 min inside it — should count.
            epoch(start = windowStart - 2 * 60_000L, quality = "good", rmssd = 44.0),
            // Ends before the window opens — must stay excluded.
            epoch(start = windowStart - 10 * 60_000L, quality = "good", rmssd = 50.0)
        )

        val summary = AutonomicScopeResolver.resolve(
            scope = AutonomicDetailScope.ROLLING_1H,
            epochs = epochs,
            recoveryWindow = recoveryWindow,
            sleepEpisodes = emptyList(),
            lastPpiSyncEpochMs = anchor,
            nowEpochMs = anchor
        )

        assertEquals(1, summary.quality.summaryEpochCount)
        assertEquals(1, summary.trajectoryPoints.size)
        // Its plotted timestamp is clamped to the window start, not its raw pre-window start.
        assertEquals(windowStart, summary.trajectoryPoints.first().epochStartEpochMs)
    }

    @Test
    fun historicFreshnessAgesPpiAgainstTheDateAnchorNotWallClock() {
        val dateEnd = AutonomicScopeResolver.historicDateEndEpochMs("2026-06-13")!!
        val window = recoveryWindow.copy(
            startEpochMs = dateEnd - 6 * 60 * 60_000L,
            endEpochMs = dateEnd - 60 * 60_000L
        )

        val summary = AutonomicScopeResolver.resolve(
            scope = AutonomicDetailScope.ACTIVE_SLEEP_REST,
            epochs = emptyList(),
            recoveryWindow = window,
            sleepEpisodes = emptyList(),
            lastPpiSyncEpochMs = dateEnd - 90 * 60_000L,
            nowEpochMs = dateEnd + 100L * 24 * 60 * 60_000L,
            historicDateEndEpochMs = dateEnd
        )

        // 90 min before end-of-day, not ~100 days against wall-clock now.
        assertEquals(90, summary.quality.ppiFreshnessMinutes)
    }

    private fun epoch(
        start: Long,
        quality: String,
        rmssd: Double?,
        movementDetectedCount: Int = 0,
        sampleCount: Int = 20,
        skinContactFalseCount: Int = 0,
        offlineIntervalCount: Int = 0
    ): Ppi247EpochEntity =
        Ppi247EpochEntity(
            deviceId = "dev",
            sourceDate = "2026-06-13",
            epochStartEpochMs = start,
            epochEndEpochMs = start + 5 * 60_000L,
            sampleCount = sampleCount,
            usableSampleCount = sampleCount,
            skinContactFalseCount = skinContactFalseCount,
            movementDetectedCount = movementDetectedCount,
            offlineIntervalCount = offlineIntervalCount,
            highErrorCount = 0,
            ppiLowCount = 0,
            ppiHighCount = 0,
            meanPpiMs = 800.0,
            medianPpiMs = 800.0,
            ppiP10Ms = 700.0,
            ppiP90Ms = 900.0,
            rmssdMs = rmssd,
            meanHrBpm = 60.0,
            minHrBpm = 55,
            maxHrBpm = 70,
            medianErrorEstimateMs = 5.0,
            errorEstimateP90Ms = 10.0,
            epochQuality = quality,
            triggerTypesCsv = "",
            updatedAtEpochMs = start
        )
}
