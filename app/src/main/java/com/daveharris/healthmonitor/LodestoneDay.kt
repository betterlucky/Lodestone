package com.daveharris.healthmonitor

import com.daveharris.healthmonitor.data.WakeMarkerEntity
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class LodestoneDayResolution(
    val sourceDate: String,
    val reason: String
)

fun resolveLodestoneDisplayDate(
    nowEpochMs: Long = System.currentTimeMillis(),
    latestMorningReadSourceDate: String?,
    wakeMarkers: List<WakeMarkerEntity>,
    zoneId: ZoneId = ZoneId.systemDefault()
): LodestoneDayResolution {
    val now = Instant.ofEpochMilli(nowEpochMs).atZone(zoneId)
    val wallDate = now.toLocalDate()
    val latestMarker = wakeMarkers
        .asSequence()
        .filterNot { it.notes == "manual awake command" }
        .sortedBy { it.markerEpochMs }
        .lastOrNull()

    if (
        latestMarker?.markerSource == "manual_going_to_bed" &&
        Duration.between(Instant.ofEpochMilli(latestMarker.markerEpochMs), now.toInstant()) <= ACTIVE_BEDTIME_MARKER_DURATION
    ) {
        return LodestoneDayResolution(
            sourceDate = sleepTargetDateForBedtime(latestMarker.markerEpochMs, zoneId).toString(),
            reason = "sleep_in_progress"
        )
    }

    if (
        latestMarker?.markerSource == "manual_im_awake" &&
        Duration.between(Instant.ofEpochMilli(latestMarker.markerEpochMs), now.toInstant()) <= ACTIVE_WAKE_MARKER_DURATION
    ) {
        return LodestoneDayResolution(
            sourceDate = latestMarker.sourceDate,
            reason = "recent_wake_marker"
        )
    }

    val latestMorningDate = latestMorningReadSourceDate?.let {
        runCatching { LocalDate.parse(it) }.getOrNull()
    }
    if (latestMorningDate == wallDate.minusDays(1) && now.toLocalTime().isBefore(SLEEP_TARGET_PIVOT)) {
        return LodestoneDayResolution(
            sourceDate = latestMorningDate.toString(),
            reason = "pre_sleep_after_midnight"
        )
    }

    return LodestoneDayResolution(
        sourceDate = wallDate.toString(),
        reason = "wall_date"
    )
}

fun sleepTargetDateForBedtime(
    markerEpochMs: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault()
): LocalDate {
    val bedtime = Instant.ofEpochMilli(markerEpochMs).atZone(zoneId)
    return if (bedtime.toLocalTime() >= SLEEP_TARGET_PIVOT) {
        bedtime.toLocalDate().plusDays(1)
    } else {
        bedtime.toLocalDate()
    }
}

private val SLEEP_TARGET_PIVOT: LocalTime = LocalTime.NOON
internal val ACTIVE_BEDTIME_MARKER_DURATION: Duration = Duration.ofHours(30)
internal val ACTIVE_WAKE_MARKER_DURATION: Duration = Duration.ofHours(30)
