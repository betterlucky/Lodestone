package com.daveharris.healthmonitor

import com.daveharris.healthmonitor.data.WakeMarkerEntity
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class LodestoneDayTest {
    private val zone = ZoneId.of("Europe/London")

    @Test
    fun recentWakeMarkerKeepsLateNightUiOnActiveDay() {
        val resolution = resolveLodestoneDisplayDate(
            nowEpochMs = epoch("2026-05-27T02:30:00"),
            latestAnalysisWindowSourceDate = "2026-05-26",
            wakeMarkers = listOf(marker("2026-05-26", "2026-05-26T08:00:00", "manual_im_awake")),
            zoneId = zone
        )

        assertEquals("2026-05-26", resolution.sourceDate)
        assertEquals("recent_wake_marker", resolution.reason)
    }

    @Test
    fun bedtimeBeforeMidnightTargetsNextMorning() {
        val resolution = resolveLodestoneDisplayDate(
            nowEpochMs = epoch("2026-05-26T23:30:00"),
            latestAnalysisWindowSourceDate = "2026-05-26",
            wakeMarkers = listOf(marker("2026-05-27", "2026-05-26T23:15:00", "manual_going_to_bed")),
            zoneId = zone
        )

        assertEquals("2026-05-27", resolution.sourceDate)
        assertEquals("sleep_in_progress", resolution.reason)
    }

    @Test
    fun bedtimeAfterFiveAmStillTargetsSameSleepResultDate() {
        val targetDate = sleepTargetDateForBedtime(
            markerEpochMs = epoch("2026-05-27T05:20:00"),
            zoneId = zone
        )

        assertEquals("2026-05-27", targetDate.toString())
    }

    @Test
    fun bedtimeAtNoonPivotTargetsNextMorning() {
        val targetDate = sleepTargetDateForBedtime(
            markerEpochMs = epoch("2026-05-26T12:00:00"),
            zoneId = zone
        )

        assertEquals("2026-05-27", targetDate.toString())
    }

    @Test
    fun checkInTargetIgnoresPreviousWakeMarker() {
        val resolution = resolveLodestoneCheckInDate(
            nowEpochMs = epoch("2026-06-04T10:15:00"),
            wakeMarkers = listOf(marker("2026-06-03", "2026-06-03T07:59:00", "manual_im_awake")),
            zoneId = zone
        )

        assertEquals("2026-06-04", resolution.sourceDate)
        assertEquals("wall_date_check_in", resolution.reason)
    }

    @Test
    fun checkInTargetKeepsActiveBedtimeMarkerOnSleepResultDate() {
        val resolution = resolveLodestoneCheckInDate(
            nowEpochMs = epoch("2026-05-26T23:30:00"),
            wakeMarkers = listOf(marker("2026-05-27", "2026-05-26T23:15:00", "manual_going_to_bed")),
            zoneId = zone
        )

        assertEquals("2026-05-27", resolution.sourceDate)
        assertEquals("sleep_in_progress", resolution.reason)
    }

    @Test
    fun staleWakeMarkerExpiresAfterThirtyHours() {
        val resolution = resolveLodestoneDisplayDate(
            nowEpochMs = epoch("2026-05-27T14:01:00"),
            latestAnalysisWindowSourceDate = "2026-05-26",
            wakeMarkers = listOf(marker("2026-05-26", "2026-05-26T08:00:00", "manual_im_awake")),
            zoneId = zone
        )

        assertEquals("2026-05-27", resolution.sourceDate)
        assertEquals("wall_date", resolution.reason)
    }

    @Test
    fun wallDateMorningReadBeatsPreviousWakeMarker() {
        val resolution = resolveLodestoneDisplayDate(
            nowEpochMs = epoch("2026-06-04T10:15:00"),
            latestAnalysisWindowSourceDate = "2026-06-04",
            wakeMarkers = listOf(
                marker("2026-06-03", "2026-06-03T07:59:00", "manual_im_awake"),
                marker("2026-06-03", "2026-06-04T09:00:00", "manual_im_awake")
            ),
            zoneId = zone
        )

        assertEquals("2026-06-04", resolution.sourceDate)
        assertEquals("latest_read_wall_date", resolution.reason)
    }

    @Test
    fun automationWakeMarkerDoesNotEndSleepMode() {
        val resolution = resolveLodestoneDisplayDate(
            nowEpochMs = epoch("2026-05-27T07:00:00"),
            latestAnalysisWindowSourceDate = "2026-05-26",
            wakeMarkers = listOf(
                marker("2026-05-27", "2026-05-26T23:15:00", "manual_going_to_bed"),
                marker(
                    sourceDate = "2026-05-27",
                    localDateTime = "2026-05-27T06:45:00",
                    source = "manual_im_awake",
                    notes = "manual awake command"
                )
            ),
            zoneId = zone
        )

        assertEquals("2026-05-27", resolution.sourceDate)
        assertEquals("sleep_in_progress", resolution.reason)
    }

    @Test
    fun bedtimeMarkerWithinThirtyHoursKeepsSleepInProgress() {
        val resolution = resolveLodestoneDisplayDate(
            nowEpochMs = epoch("2026-05-27T23:14:00"),
            latestAnalysisWindowSourceDate = "2026-05-26",
            wakeMarkers = listOf(marker("2026-05-27", "2026-05-26T17:15:00", "manual_going_to_bed")),
            zoneId = zone
        )

        assertEquals("2026-05-27", resolution.sourceDate)
        assertEquals("sleep_in_progress", resolution.reason)
    }

    @Test
    fun bedtimeMarkerAtThirtyHoursStillKeepsSleepInProgress() {
        val resolution = resolveLodestoneDisplayDate(
            nowEpochMs = epoch("2026-05-27T23:15:00"),
            latestAnalysisWindowSourceDate = "2026-05-26",
            wakeMarkers = listOf(marker("2026-05-27", "2026-05-26T17:15:00", "manual_going_to_bed")),
            zoneId = zone
        )

        assertEquals("2026-05-27", resolution.sourceDate)
        assertEquals("sleep_in_progress", resolution.reason)
    }

    @Test
    fun staleBedtimeMarkerDoesNotHoldDisplayDateForever() {
        val resolution = resolveLodestoneDisplayDate(
            nowEpochMs = epoch("2026-05-28T23:16:00"),
            latestAnalysisWindowSourceDate = "2026-05-27",
            wakeMarkers = listOf(marker("2026-05-28", "2026-05-27T17:15:00", "manual_going_to_bed")),
            zoneId = zone
        )

        assertEquals("2026-05-28", resolution.sourceDate)
        assertEquals("wall_date", resolution.reason)
    }

    @Test
    fun latestMorningReadCanHoldDateBeforeNoonWhenNoMarkerExists() {
        val resolution = resolveLodestoneDisplayDate(
            nowEpochMs = epoch("2026-05-27T03:00:00"),
            latestAnalysisWindowSourceDate = "2026-05-26",
            wakeMarkers = emptyList(),
            zoneId = zone
        )

        assertEquals("2026-05-26", resolution.sourceDate)
        assertEquals("pre_sleep_after_midnight", resolution.reason)
    }

    @Test
    fun wakeTargetDateUsesMarkerLocalDate() {
        val targetDate = wakeTargetDateForMarker(
            markerEpochMs = epoch("2026-06-04T09:00:00"),
            zoneId = zone
        )

        assertEquals("2026-06-04", targetDate.toString())
    }

    private fun marker(
        sourceDate: String,
        localDateTime: String,
        source: String,
        notes: String? = null
    ): WakeMarkerEntity =
        WakeMarkerEntity(
            sourceDate = sourceDate,
            markerEpochMs = epoch(localDateTime),
            markerSource = source,
            deviceId = "device",
            notes = notes
        )

    private fun epoch(localDateTime: String): Long =
        LocalDateTime.parse(localDateTime)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
}
