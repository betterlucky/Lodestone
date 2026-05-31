package com.daveharris.healthmonitor.ui

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class TimeEditorValue(
    val date: LocalDate,
    val hour: Int,
    val minute: Int
) {
    fun toEpochMs(zoneId: ZoneId): Long =
        LocalDateTime.of(date, LocalTime.of(hour, minute))
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()

    companion object {
        fun fromEpochMs(epochMs: Long, zoneId: ZoneId): TimeEditorValue {
            val local = Instant.ofEpochMilli(epochMs).atZone(zoneId)
            return TimeEditorValue(
                date = local.toLocalDate(),
                hour = local.hour,
                minute = local.minute
            )
        }
    }
}

data class WindowEditorValidation(
    val canSave: Boolean,
    val message: String?,
    val isWarning: Boolean = false
)

enum class MarkerTimeEditorKind(
    val title: String,
    val instantLabel: String,
    val saveLabel: String
) {
    BEDTIME(
        title = "Bedtime marker",
        instantLabel = "Bedtime",
        saveLabel = "Save bedtime & sync"
    ),
    WAKING(
        title = "Wake marker",
        instantLabel = "Wake time",
        saveLabel = "Save waking & sync"
    )
}

fun timeEditorValueForQuickAction(
    nowEpochMs: Long,
    minutesAgo: Long,
    zoneId: ZoneId
): TimeEditorValue =
    TimeEditorValue.fromEpochMs(nowEpochMs - Duration.ofMinutes(minutesAgo).toMillis(), zoneId)

fun validateWindowEditor(
    start: TimeEditorValue,
    end: TimeEditorValue,
    zoneId: ZoneId,
    suspiciousLongWindow: Duration = Duration.ofHours(18)
): WindowEditorValidation {
    val startEpochMs = start.toEpochMs(zoneId)
    val endEpochMs = end.toEpochMs(zoneId)
    if (endEpochMs <= startEpochMs) {
        return WindowEditorValidation(
            canSave = false,
            message = "End must be after start."
        )
    }
    val duration = Duration.ofMillis(endEpochMs - startEpochMs)
    if (duration > suspiciousLongWindow) {
        return WindowEditorValidation(
            canSave = true,
            message = "This is longer than ${formatDurationLabel(suspiciousLongWindow)}. Save it if that is right.",
            isWarning = true
        )
    }
    return WindowEditorValidation(canSave = true, message = null)
}

fun windowDurationLabel(
    start: TimeEditorValue,
    end: TimeEditorValue,
    zoneId: ZoneId
): String {
    val startEpochMs = start.toEpochMs(zoneId)
    val endEpochMs = end.toEpochMs(zoneId)
    if (endEpochMs <= startEpochMs) return "Duration unavailable"
    return formatDurationLabel(Duration.ofMillis(endEpochMs - startEpochMs))
}

fun windowPreviewLabel(
    start: TimeEditorValue,
    end: TimeEditorValue,
    zoneId: ZoneId
): String =
    "${formatDateTimeLabel(start)} -> ${formatDateTimeLabel(end)} · ${windowDurationLabel(start, end, zoneId)}"

fun markerPreviewLabel(value: TimeEditorValue): String =
    formatDateTimeLabel(value)

fun formatDateChipLabel(date: LocalDate): String =
    date.format(dateChipFormatter)

private fun formatDateTimeLabel(value: TimeEditorValue): String =
    "${value.date.format(dateFormatter)} ${value.hour.twoDigits()}:${value.minute.twoDigits()}"

private fun formatDurationLabel(duration: Duration): String {
    val minutes = duration.toMinutes()
    val hours = minutes / 60
    val remainder = minutes % 60
    return when {
        hours > 0 && remainder > 0 -> "${hours}h ${remainder}m"
        hours > 0 -> "${hours}h"
        else -> "${remainder}m"
    }
}

internal fun Int.twoDigits(): String =
    toString().padStart(2, '0')

private val dateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.UK)

private val dateChipFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
