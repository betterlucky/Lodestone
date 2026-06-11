package com.daveharris.healthmonitor.data

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Derives minimal Polar fetch windows from stored coverage plus a safety overlap.
 * [maxLookbackDays] comes from user sync settings and caps how far back we ever pull.
 */
object SyncRangePlanner {
    const val DEFAULT_OVERLAP_DAYS = 1
    const val SLEEP_OVERLAP_DAYS = 2
    const val CHECK_IN_ACTIVITY_MAX_LOOKBACK_DAYS = 3

    data class PlannedRange(
        val from: LocalDate,
        val to: LocalDate,
        val incremental: Boolean
    ) {
        val dayCountInclusive: Int
            get() = daySpanInclusive(from, to)

        fun isEmpty(): Boolean = from.isAfter(to)
    }

    fun planRange(
        today: LocalDate,
        maxLookbackDays: Int,
        latestStoredDate: String?,
        overlapDays: Int = DEFAULT_OVERLAP_DAYS
    ): PlannedRange {
        require(maxLookbackDays >= 1) { "maxLookbackDays must be at least 1" }
        val to = today
        val oldestAllowed = today.minusDays(maxLookbackDays.toLong())
        val latest = parseSourceDate(latestStoredDate)
        val from = if (latest == null) {
            oldestAllowed
        } else {
            latest.minusDays(overlapDays.toLong()).coerceAtLeast(oldestAllowed)
        }.coerceAtMost(to)
        return PlannedRange(
            from = from,
            to = to,
            incremental = latest != null
        )
    }

    fun daySpanInclusive(from: LocalDate, to: LocalDate): Int =
        if (from.isAfter(to)) {
            0
        } else {
            (ChronoUnit.DAYS.between(from, to) + 1).toInt()
        }

    fun parseSourceDate(value: String?): LocalDate? =
        value?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}

data class SyncDomainCoverage(
    val sleepLatest: String? = null,
    val nightlyRechargeLatest: String? = null,
    val ppiLatest: String? = null,
    val hrLatest: String? = null,
    val skinTemperatureLatest: String? = null,
    val dailySummaryLatest: String? = null,
    val activitySamplesLatest: String? = null
)
