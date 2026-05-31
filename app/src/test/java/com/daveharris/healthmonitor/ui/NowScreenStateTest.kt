package com.daveharris.healthmonitor.ui

import com.daveharris.healthmonitor.data.DailyCheckInEntity
import com.daveharris.healthmonitor.data.MorningReadSnapshot
import com.daveharris.healthmonitor.data.SleepEpisodeConfidences
import com.daveharris.healthmonitor.data.SleepEpisodeEntity
import com.daveharris.healthmonitor.data.SleepEpisodeKinds
import com.daveharris.healthmonitor.data.SleepEpisodeSources
import com.daveharris.healthmonitor.data.SyncRunEntity
import com.daveharris.healthmonitor.data.TrafficLightStatus
import com.daveharris.healthmonitor.data.WakeMarkerEntity
import com.daveharris.healthmonitor.polar.DeviceRuntimeState
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NowScreenStateTest {
    private val zone = ZoneId.of("Europe/London")
    private val now = epoch("2026-05-31T09:00:00")

    @Test
    fun noDataKeepsCheckInAvailableAndMarksInputsMissing() {
        val state = nowState(morningRead = null)

        assertEquals(NowCurrentStateKind.WAITING_FOR_DATA, state.currentState.kind)
        assertEquals(NowDataAvailability.MISSING, state.signalRobustness.sleepReport.availability)
        assertEquals(NowDataAvailability.MISSING, state.signalRobustness.ppi.availability)
        assertEquals(NowMarkerState.NONE, state.markerStatus.state)
        assertTrue(state.primaryActions.checkIn.enabled)
    }

    @Test
    fun partialPpiSeparatesProvisionalCurrentStateFromMissingFinalReport() {
        val state = nowState(
            morningRead = morningRead(
                sleepDataReady = false,
                isInterim = true,
                source = "raw_ppi_calibrated_window_pending_sleep_report",
                rawPpiGoodEpochCount = 36,
                rawPpiCoverageHours = 4.5
            )
        )

        assertEquals(NowCurrentStateKind.PROVISIONAL_READ, state.currentState.kind)
        assertEquals(NowDataAvailability.PARTIAL, state.currentState.availability)
        assertEquals(NowDataAvailability.PENDING, state.signalRobustness.sleepReport.availability)
        assertEquals(NowDataAvailability.PRESENT, state.signalRobustness.ppi.availability)
    }

    @Test
    fun finalReportProducesReadyCurrentState() {
        val state = nowState(
            morningRead = morningRead(
                sleepDataReady = true,
                source = "ppi247_sleep_window",
                rawPpiGoodEpochCount = 64,
                rawPpiCoverageHours = 7.25,
                nightlyRmssd = 48.0
            )
        )

        assertEquals(NowCurrentStateKind.READY, state.currentState.kind)
        assertEquals(NowDataAvailability.PRESENT, state.signalRobustness.sleepReport.availability)
        assertEquals(NowDataAvailability.PRESENT, state.signalRobustness.availability)
        assertEquals("Stable", state.stateStability.label)
    }

    @Test
    fun activeBedtimeIsMarkerStateButDoesNotDisableCheckIn() {
        val state = nowState(
            morningRead = null,
            wakeMarkers = listOf(marker("2026-05-31", "2026-05-31T00:30:00", "manual_going_to_bed"))
        )

        assertEquals(NowMarkerState.ACTIVE_BEDTIME, state.markerStatus.state)
        assertEquals(NowCurrentStateKind.SLEEP_MARKED, state.currentState.kind)
        assertNull(state.activeMorningRead)
        assertTrue(state.primaryActions.checkIn.enabled)
    }

    @Test
    fun bedtimeMarkerBecomesResolvedEvidenceWhenSleepWindowExists() {
        val state = nowState(
            morningRead = morningRead(
                sleepDataReady = true,
                source = "ppi247_sleep_window",
                rawPpiGoodEpochCount = 64,
                rawPpiCoverageHours = 7.25
            ),
            wakeMarkers = listOf(marker("2026-05-31", "2026-05-31T00:30:00", "manual_going_to_bed"))
        )

        assertEquals(NowMarkerState.RESOLVED_BEDTIME, state.markerStatus.state)
        assertEquals(NowCurrentStateKind.READY, state.currentState.kind)
        assertTrue(state.activeMorningRead != null)
        assertTrue(state.primaryActions.checkIn.enabled)
    }

    @Test
    fun provisionalWindowAlsoResolvesBedtimeMarker() {
        val state = nowState(
            morningRead = morningRead(
                sleepDataReady = false,
                isInterim = true,
                source = "raw_ppi_manual_window_pending_sleep_report",
                rawPpiGoodEpochCount = 40,
                rawPpiCoverageHours = 5.0
            ),
            wakeMarkers = listOf(marker("2026-05-31", "2026-05-31T00:30:00", "manual_going_to_bed"))
        )

        assertEquals(NowMarkerState.RESOLVED_BEDTIME, state.markerStatus.state)
        assertEquals(NowCurrentStateKind.PROVISIONAL_READ, state.currentState.kind)
        assertTrue(state.activeMorningRead != null)
        assertTrue(state.primaryActions.checkIn.enabled)
    }

    @Test
    fun staleBedtimeDoesNotBlockCheckInWhenNoWindowResolved() {
        val state = nowState(
            morningRead = null,
            wakeMarkers = listOf(marker("2026-05-31", "2026-05-30T02:30:00", "manual_going_to_bed"))
        )

        assertEquals(NowMarkerState.STALE_BEDTIME, state.markerStatus.state)
        assertEquals(NowCurrentStateKind.WAITING_FOR_DATA, state.currentState.kind)
        assertEquals(NowDataAvailability.STALE, state.markerStatus.availability)
        assertEquals(NowDataAvailability.STALE, state.markerStatus.bedtime.availability)
        assertEquals(null, state.activeMorningRead)
        assertTrue(state.primaryActions.checkIn.enabled)
    }

    @Test
    fun noMarkerModeHidesMarkerActionsWithoutChangingCheckIn() {
        val state = nowState(markerMode = NowMarkerMode.NO_MARKERS)

        assertEquals(NowMarkerState.NOT_APPLICABLE, state.markerStatus.state)
        assertFalse(state.primaryActions.bedtime.visible)
        assertFalse(state.primaryActions.waking.visible)
        assertTrue(state.primaryActions.checkIn.visible)
        assertTrue(state.primaryActions.checkIn.enabled)
    }

    @Test
    fun noMainSleepIsExplicitAndDoesNotFabricateSleepReport() {
        val state = nowState(
            sleepEpisodeReviewState = buildSleepEpisodeReviewState(
                activeDate = "2026-05-31",
                reviewDates = listOf("2026-05-31"),
                episodes = listOf(noMainSleepEpisode()),
                zoneId = zone
            )
        )

        assertEquals(NowCurrentStateKind.NO_MAIN_SLEEP, state.currentState.kind)
        assertEquals(NowDataAvailability.NOT_APPLICABLE, state.signalRobustness.sleepReport.availability)
        assertEquals("No main sleep decision", state.signalRobustness.basisLabel)
        assertEquals(NowDataAvailability.NOT_APPLICABLE, state.stateStability.availability)
        assertTrue(state.primaryActions.checkIn.enabled)
    }

    private fun nowState(
        morningRead: MorningReadSnapshot? = null,
        syncRuns: List<SyncRunEntity> = emptyList(),
        wakeMarkers: List<WakeMarkerEntity> = emptyList(),
        dailyCheckIns: List<DailyCheckInEntity> = emptyList(),
        sleepEpisodeReviewState: SleepEpisodeReviewState = SleepEpisodeReviewState.empty("2026-05-31"),
        markerMode: NowMarkerMode = NowMarkerMode.BEDTIME_AND_WAKING,
        selectedDeviceId: String? = "loop-1",
        runtime: DeviceRuntimeState = DeviceRuntimeState(bluetoothPowered = true)
    ): NowScreenState =
        buildNowScreenState(
            today = "2026-05-31",
            morningRead = morningRead,
            syncRuns = syncRuns,
            wakeMarkers = wakeMarkers,
            dailyCheckIns = dailyCheckIns,
            sleepEpisodeReviewState = sleepEpisodeReviewState,
            runtime = runtime,
            selectedDeviceId = selectedDeviceId,
            isBusy = false,
            markerMode = markerMode,
            nowEpochMs = now,
            zoneId = zone
        )

    private fun morningRead(
        sleepDataReady: Boolean,
        source: String,
        rawPpiGoodEpochCount: Int?,
        rawPpiCoverageHours: Double?,
        isInterim: Boolean = false,
        nightlyRmssd: Double? = null
    ): MorningReadSnapshot =
        MorningReadSnapshot(
            sourceDate = "2026-05-31",
            status = TrafficLightStatus.OK,
            confidence = "medium",
            overnightAutonomicSource = source,
            sleepDurationMinutes = if (sleepDataReady) 420 else null,
            nightlyRmssd = nightlyRmssd,
            baselineReady = true,
            recoveryAvailable = true,
            summary = "summary",
            reasons = emptyList(),
            isInterim = isInterim,
            sleepDataReady = sleepDataReady,
            rawPpiGoodEpochCount = rawPpiGoodEpochCount,
            rawPpiPoorEpochCount = 0,
            rawPpiCoverageHours = rawPpiCoverageHours
        )

    private fun marker(
        sourceDate: String,
        localDateTime: String,
        source: String
    ): WakeMarkerEntity =
        WakeMarkerEntity(
            sourceDate = sourceDate,
            markerEpochMs = epoch(localDateTime),
            markerSource = source,
            deviceId = "loop-1",
            notes = null
        )

    private fun noMainSleepEpisode(): SleepEpisodeEntity =
        SleepEpisodeEntity(
            id = 1,
            sourceDate = "2026-05-31",
            startEpochMs = null,
            endEpochMs = null,
            episodeKind = SleepEpisodeKinds.NO_SLEEP,
            source = SleepEpisodeSources.MANUAL,
            confidence = SleepEpisodeConfidences.USER_CONFIRMED,
            isPrimaryForReadiness = false,
            deviceId = null,
            linkedSleepRawId = null,
            evidenceJson = null,
            notes = "No main sleep",
            createdAtEpochMs = 0L,
            updatedAtEpochMs = 0L
        )

    private fun epoch(localDateTime: String): Long =
        LocalDateTime.parse(localDateTime)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
}
