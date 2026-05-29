package com.daveharris.healthmonitor.data

fun selectedPrimaryReadinessEpisode(
    sourceDate: String,
    episodes: List<SleepEpisodeEntity>
): SleepEpisodeEntity? =
    episodes
        .asSequence()
        .filter { it.sourceDate == sourceDate }
        .filter { it.isSelectedPrimaryReadinessEpisode() }
        .sortedWith(compareByDescending<SleepEpisodeEntity> { it.updatedAtEpochMs }.thenByDescending { it.id })
        .firstOrNull()

fun hasConfirmedNoMainSleep(
    sourceDate: String,
    episodes: List<SleepEpisodeEntity>
): Boolean =
    episodes.any { it.sourceDate == sourceDate && it.isConfirmedNoMainSleep() }

fun SleepEpisodeEntity.blocksInferredCandidate(candidate: SleepEpisodeEntity): Boolean {
    if (sourceDate != candidate.sourceDate) return false
    if (confidence != SleepEpisodeConfidences.USER_CONFIRMED && source == SleepEpisodeSources.PPI_INFERRED) return false
    if (episodeKind == SleepEpisodeKinds.NO_SLEEP) return candidate.episodeKind == SleepEpisodeKinds.MAIN_SLEEP
    val start = startEpochMs ?: return false
    val end = endEpochMs ?: return false
    val candidateStart = candidate.startEpochMs ?: return false
    val candidateEnd = candidate.endEpochMs ?: return false
    return start < candidateEnd && candidateStart < end
}

private fun SleepEpisodeEntity.isSelectedPrimaryReadinessEpisode(): Boolean =
    isPrimaryForReadiness &&
        confidence == SleepEpisodeConfidences.USER_CONFIRMED &&
        episodeKind != SleepEpisodeKinds.NO_SLEEP &&
        startEpochMs != null &&
        endEpochMs != null &&
        endEpochMs > startEpochMs

private fun SleepEpisodeEntity.isConfirmedNoMainSleep(): Boolean =
    episodeKind == SleepEpisodeKinds.NO_SLEEP &&
        confidence == SleepEpisodeConfidences.USER_CONFIRMED
