package com.daveharris.healthmonitor.ui

import java.time.LocalDate
import java.time.temporal.ChronoUnit

fun catchUpRepairSourceDates(
    today: String,
    latestReadSourceDate: String?,
    maxDays: Long = DEFAULT_MAX_CATCH_UP_CANDIDATE_DAYS
): List<String> {
    val todayDate = runCatching { LocalDate.parse(today) }.getOrNull() ?: return listOf(today)
    val latestReadDate = latestReadSourceDate
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val startDate = latestReadDate
        ?.plusDays(1)
        ?.takeIf { !it.isAfter(todayDate) }
        ?: todayDate
    val trailingDays = ChronoUnit.DAYS.between(startDate, todayDate)
        .coerceAtLeast(0L)
        .coerceAtMost(maxDays - 1L)
    return (trailingDays downTo 0L)
        .map { offset -> todayDate.minusDays(offset).toString() }
}

const val DEFAULT_MAX_CATCH_UP_CANDIDATE_DAYS = 7L
