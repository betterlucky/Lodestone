package com.daveharris.healthmonitor.ui

import com.daveharris.healthmonitor.data.SleepEpisodeConfidences
import com.daveharris.healthmonitor.data.SleepEpisodeEntity
import com.daveharris.healthmonitor.data.SleepEpisodeKinds
import com.daveharris.healthmonitor.data.SleepEpisodeSources
import com.google.gson.JsonParser
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class SleepEpisodeReviewState(
    val activeDate: String,
    val dateGroups: List<SleepEpisodeDateGroup>
) {
    val activeDateGroup: SleepEpisodeDateGroup?
        get() = dateGroups.firstOrNull { it.sourceDate == activeDate }

    val totalCandidateCount: Int
        get() = dateGroups.sumOf { it.candidateCount }

    val totalConfirmedCount: Int
        get() = dateGroups.sumOf { it.confirmedCount }

    val hasAnyRows: Boolean
        get() = dateGroups.any { !it.isEmpty }

    val hasCatchUpDates: Boolean
        get() = dateGroups.size > 1

    val attentionDateCount: Int
        get() = dateGroups.count { it.needsAttention }

    val surfaceMessage: String
        get() = when {
            hasCatchUpDates && attentionDateCount > 0 -> "Review missing days from oldest to newest."
            totalCandidateCount > 0 -> "Review possible sleep/rest windows before using them for readiness."
            totalConfirmedCount > 0 -> "This day has your confirmed sleep/rest decision."
            else -> "No sleep/rest candidates found yet."
        }

    companion object {
        fun empty(activeDate: String): SleepEpisodeReviewState =
            SleepEpisodeReviewState(
                activeDate = activeDate,
                dateGroups = listOf(SleepEpisodeDateGroup.empty(activeDate))
            )
    }
}

data class SleepEpisodeDateGroup(
    val sourceDate: String,
    val items: List<SleepEpisodeDisplayItem>,
    val hasSavedReview: Boolean = false
) {
    val candidateCount: Int
        get() = items.count { it.isCandidate }

    val confirmedCount: Int
        get() = items.count { it.isConfirmed }

    val hasPrimaryReadinessWindow: Boolean
        get() = items.any { it.isPrimaryForReadiness }

    val isEmpty: Boolean
        get() = items.isEmpty()

    val hasNoSleepDecision: Boolean
        get() = items.any { it.isNoSleep && it.isConfirmed }

    val repairStatusLabel: String
        get() = when {
            hasPrimaryReadinessWindow || hasNoSleepDecision -> "Confirmed"
            candidateCount > 0 -> "Needs review"
            confirmedCount > 0 -> "Context saved"
            hasSavedReview -> "Journal saved"
            else -> "No candidates"
        }

    val needsAttention: Boolean
        get() = candidateCount > 0 || (!hasSavedReview && confirmedCount == 0)

    val emptyStateMessage: String
        get() = if (hasSavedReview) {
            "No sleep/rest candidates found. The journal entry is already saved."
        } else {
            "No sleep/rest candidates found yet."
        }

    companion object {
        fun empty(sourceDate: String): SleepEpisodeDateGroup =
            SleepEpisodeDateGroup(sourceDate = sourceDate, items = emptyList())
    }
}

data class SleepEpisodeDisplayItem(
    val id: Long,
    val sourceDate: String,
    val startEpochMs: Long?,
    val endEpochMs: Long?,
    val title: String,
    val timeRangeLabel: String,
    val durationLabel: String,
    val kindLabel: String,
    val sourceLabel: String,
    val confidenceLabel: String,
    val primaryLabel: String?,
    val evidenceSummary: String,
    val isCandidate: Boolean,
    val isConfirmed: Boolean,
    val isNoSleep: Boolean,
    val canClearDecision: Boolean,
    val isPrimaryForReadiness: Boolean
)

fun buildSleepEpisodeReviewState(
    activeDate: String,
    reviewDates: List<String>,
    episodes: List<SleepEpisodeEntity>,
    reviewedDates: Set<String> = emptySet(),
    zoneId: ZoneId = ZoneId.systemDefault()
): SleepEpisodeReviewState {
    val dates = (reviewDates.ifEmpty { listOf(activeDate) })
        .distinct()
    val groupedEpisodes = episodes.groupBy { it.sourceDate }
    val dateGroups = dates.map { date ->
        SleepEpisodeDateGroup(
            sourceDate = date,
            items = groupedEpisodes[date]
                .orEmpty()
                .sortedWith(compareBy<SleepEpisodeEntity> { it.startEpochMs ?: Long.MAX_VALUE }.thenBy { it.id })
                .map { it.toSleepEpisodeDisplayItem(zoneId) },
            hasSavedReview = date in reviewedDates
        )
    }
    return SleepEpisodeReviewState(activeDate = activeDate, dateGroups = dateGroups)
}

fun SleepEpisodeEntity.toSleepEpisodeDisplayItem(
    zoneId: ZoneId = ZoneId.systemDefault()
): SleepEpisodeDisplayItem {
    val isCandidate = source == SleepEpisodeSources.PPI_INFERRED &&
        confidence != SleepEpisodeConfidences.USER_CONFIRMED
    val isConfirmed = confidence == SleepEpisodeConfidences.USER_CONFIRMED ||
        source == SleepEpisodeSources.MANUAL ||
        source == SleepEpisodeSources.EDITED
    val isNoSleep = episodeKind == SleepEpisodeKinds.NO_SLEEP
    return SleepEpisodeDisplayItem(
        id = id,
        sourceDate = sourceDate,
        startEpochMs = startEpochMs,
        endEpochMs = endEpochMs,
        title = episodeTitle(episodeKind, source, isCandidate, isConfirmed),
        timeRangeLabel = timeRangeLabel(startEpochMs, endEpochMs, zoneId),
        durationLabel = durationLabel(startEpochMs, endEpochMs, isNoSleep),
        kindLabel = kindLabel(episodeKind),
        sourceLabel = sourceLabel(source),
        confidenceLabel = confidenceLabel(confidence),
        primaryLabel = if (isPrimaryForReadiness) "Readiness window" else null,
        evidenceSummary = evidenceSummary(evidenceJson, episodeKind, source, isCandidate, isNoSleep),
        isCandidate = isCandidate,
        isConfirmed = isConfirmed,
        isNoSleep = isNoSleep,
        canClearDecision = isConfirmed && !isCandidate,
        isPrimaryForReadiness = isPrimaryForReadiness
    )
}

private val timeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.UK)

private fun timeRangeLabel(
    startEpochMs: Long?,
    endEpochMs: Long?,
    zoneId: ZoneId
): String {
    if (startEpochMs == null || endEpochMs == null) return "No timed window"
    val start = Instant.ofEpochMilli(startEpochMs).atZone(zoneId).format(timeFormatter)
    val end = Instant.ofEpochMilli(endEpochMs).atZone(zoneId).format(timeFormatter)
    return "$start-$end"
}

private fun durationLabel(
    startEpochMs: Long?,
    endEpochMs: Long?,
    isNoSleep: Boolean
): String {
    if (isNoSleep) return "No main sleep"
    if (startEpochMs == null || endEpochMs == null || endEpochMs <= startEpochMs) return "Duration unknown"
    val minutes = (endEpochMs - startEpochMs) / 60_000L
    val hours = minutes / 60
    val remainder = minutes % 60
    return when {
        hours > 0 && remainder > 0 -> "${hours}h ${remainder}m"
        hours > 0 -> "${hours}h"
        else -> "${remainder}m"
    }
}

private fun episodeTitle(
    episodeKind: String,
    source: String,
    isCandidate: Boolean,
    isConfirmed: Boolean
): String =
    when {
        isCandidate && episodeKind == SleepEpisodeKinds.MAIN_SLEEP -> "Possible sleep"
        isCandidate && episodeKind == SleepEpisodeKinds.NAP -> "Possible nap"
        isCandidate && episodeKind == SleepEpisodeKinds.REST_CANDIDATE -> "Rest-like window"
        episodeKind == SleepEpisodeKinds.NO_SLEEP -> "No main sleep"
        isConfirmed && episodeKind == SleepEpisodeKinds.MAIN_SLEEP -> "Confirmed sleep"
        isConfirmed && episodeKind == SleepEpisodeKinds.NAP -> "Confirmed nap"
        isConfirmed && episodeKind == SleepEpisodeKinds.REST_CANDIDATE -> "Confirmed rest"
        source == SleepEpisodeSources.EDITED -> "Edited sleep/rest"
        source == SleepEpisodeSources.POLAR_SLEEP -> "Final Loop sleep report"
        else -> kindLabel(episodeKind)
    }

private fun kindLabel(episodeKind: String): String =
    when (episodeKind) {
        SleepEpisodeKinds.MAIN_SLEEP -> "Main sleep"
        SleepEpisodeKinds.NAP -> "Nap"
        SleepEpisodeKinds.REST_CANDIDATE -> "Rest"
        SleepEpisodeKinds.NO_SLEEP -> "No sleep"
        else -> episodeKind.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.UK) }
    }

private fun sourceLabel(source: String): String =
    when (source) {
        SleepEpisodeSources.PPI_INFERRED -> "Suggested from PPI"
        SleepEpisodeSources.MANUAL -> "Manual"
        SleepEpisodeSources.EDITED -> "Edited"
        SleepEpisodeSources.POLAR_SLEEP -> "Loop report"
        SleepEpisodeSources.MIXED -> "Mixed evidence"
        else -> source.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.UK) }
    }

private fun confidenceLabel(confidence: String): String =
    when (confidence) {
        SleepEpisodeConfidences.LOW -> "Low signal"
        SleepEpisodeConfidences.MEDIUM -> "Medium signal"
        SleepEpisodeConfidences.HIGH -> "High signal"
        SleepEpisodeConfidences.USER_CONFIRMED -> "Confirmed"
        else -> confidence.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.UK) }
    }

private fun evidenceSummary(
    evidenceJson: String?,
    episodeKind: String,
    source: String,
    isCandidate: Boolean,
    isNoSleep: Boolean
): String {
    if (isNoSleep) return "You marked this day as having no main sleep window."
    val evidence = parseEvidence(evidenceJson)
    val label = evidence["label"]
    val durationMinutes = evidence["durationMinutes"]?.toIntOrNull()
    val wakeMarker = evidence["hasExplicitWakeMarker"]?.toBooleanStrictOrNull()
    return when {
        isCandidate && episodeKind == SleepEpisodeKinds.MAIN_SLEEP ->
            "Lodestone found a quiet low-movement window. Confirm or edit it before it drives readiness."
        isCandidate && episodeKind == SleepEpisodeKinds.REST_CANDIDATE ->
            "The signal looks restful, but Lodestone should not call it sleep without your review."
        source == SleepEpisodeSources.POLAR_SLEEP ->
            "Vendor sleep is supporting context; user-confirmed local episodes decide the primary readiness window."
        label != null && durationMinutes != null ->
            "$label, about ${durationLabelFromMinutes(durationMinutes)}${wakeMarkerSuffix(wakeMarker)}."
        label != null -> label
        else -> "Sleep/rest evidence is available for review."
    }
}

private fun parseEvidence(evidenceJson: String?): Map<String, String> {
    if (evidenceJson.isNullOrBlank()) return emptyMap()
    return runCatching {
        JsonParser.parseString(evidenceJson)
            .takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.entrySet()
            .orEmpty()
            .associate { (key, value) ->
                key to when {
                    value.isJsonPrimitive -> value.asJsonPrimitive.asString
                    else -> value.toString()
                }
            }
    }.getOrDefault(emptyMap())
}

private fun durationLabelFromMinutes(minutes: Int): String {
    val hours = minutes / 60
    val remainder = minutes % 60
    return when {
        hours > 0 && remainder > 0 -> "${hours}h ${remainder}m"
        hours > 0 -> "${hours}h"
        else -> "${remainder}m"
    }
}

private fun wakeMarkerSuffix(hasExplicitWakeMarker: Boolean?): String =
    when (hasExplicitWakeMarker) {
        true -> " with wake marker"
        false -> " without wake marker"
        null -> ""
    }
