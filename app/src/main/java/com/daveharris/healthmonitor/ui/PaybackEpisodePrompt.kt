package com.daveharris.healthmonitor.ui

import com.daveharris.healthmonitor.data.DailyCheckInEntity
import com.daveharris.healthmonitor.data.PaybackPeakConfidence
import java.time.LocalDate

data class PaybackEpisodePeakPrompt(
    val pemDates: List<String>
) {
    val episodeEndDate: String = pemDates.lastOrNull().orEmpty()
}

fun findEndedPaybackEpisodeBefore(
    activeDate: String,
    checkIns: List<DailyCheckInEntity>,
    activePemMarked: Boolean
): PaybackEpisodePeakPrompt? {
    if (activePemMarked) return null
    val currentDate = runCatching { LocalDate.parse(activeDate) }.getOrNull() ?: return null
    val byDate = checkIns.associateBy { it.sourceDate }
    val dates = mutableListOf<String>()
    var cursor = currentDate.minusDays(1)
    while (true) {
        val row = byDate[cursor.toString()] ?: break
        if (row.pemPaybackToday != true) break
        dates += row.sourceDate
        cursor = cursor.minusDays(1)
    }
    if (dates.isEmpty()) return null
    val episodeRows = dates.mapNotNull(byDate::get)
    val handledConfidences = setOf(
        PaybackPeakConfidence.USER_SELECTED,
        PaybackPeakConfidence.AUTO_SINGLE,
        PaybackPeakConfidence.NOT_SURE,
        PaybackPeakConfidence.DISMISSED
    )
    val alreadyHandled = episodeRows.any { row ->
        row.paybackPeakToday == true || row.paybackPeakConfidence in handledConfidences
    }
    if (alreadyHandled) return null
    return PaybackEpisodePeakPrompt(pemDates = dates.asReversed())
}

fun pendingPaybackPeakPrompt(
    activeDate: String,
    checkIns: List<DailyCheckInEntity>,
    activePemMarked: Boolean
): PaybackEpisodePeakPrompt? =
    findEndedPaybackEpisodeBefore(activeDate, checkIns, activePemMarked)
        ?.takeIf { it.pemDates.size > 1 }
