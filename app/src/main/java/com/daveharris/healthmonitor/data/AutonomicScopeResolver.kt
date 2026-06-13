package com.daveharris.healthmonitor.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object AutonomicScopeResolver {
    fun resolve(
        scope: AutonomicDetailScope,
        epochs: List<Ppi247EpochEntity>,
        recoveryWindow: AutonomicRecoveryWindow?,
        sleepEpisodes: List<SleepEpisodeEntity>,
        lastPpiSyncEpochMs: Long?,
        nowEpochMs: Long = System.currentTimeMillis(),
        historicDateEndEpochMs: Long? = null,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): AutonomicScopeSummary {
        val family = if (scope == AutonomicDetailScope.ACTIVE_SLEEP_REST) {
            AutonomicScopeFamily.RECOVERY_TRAJECTORY
        } else {
            AutonomicScopeFamily.RECENT_TREND
        }
        val planningInfluence = if (scope == AutonomicDetailScope.ACTIVE_SLEEP_REST) {
            AutonomicPlanningInfluence.RECOVERY_LANE
        } else {
            AutonomicPlanningInfluence.DESCRIPTIVE_ONLY
        }
        val anchorEpochMs = historicDateEndEpochMs
            ?: minOf(nowEpochMs, lastPpiSyncEpochMs ?: nowEpochMs)
        val (windowStart, windowEnd, provenanceLabel) = when (scope) {
            AutonomicDetailScope.ACTIVE_SLEEP_REST -> {
                val window = recoveryWindow
                if (window == null) {
                    return emptySummary(
                        scope = scope,
                        family = family,
                        planningInfluence = planningInfluence,
                        emptyMessage = "No active sleep/rest window is available for recovery-context HRV."
                    )
                }
                Triple(window.startEpochMs, window.endEpochMs, window.provenanceLabel)
            }
            else -> {
                val hours = scope.lookbackHours ?: 1
                val start = anchorEpochMs - hours * 3_600_000L
                Triple(start, anchorEpochMs, null)
            }
        }
        val breakdown = Ppi247ScopeSummarizer.summarize(
            scope = scope,
            windowStartEpochMs = windowStart,
            windowEndEpochMs = windowEnd,
            epochs = epochs,
            sourceDate = recoveryWindow?.sourceDate?.takeIf { scope == AutonomicDetailScope.ACTIVE_SLEEP_REST },
            nowEpochMs = nowEpochMs
        )
        val ppiFreshnessMinutes = lastPpiSyncEpochMs?.let { syncMs ->
            ((nowEpochMs - syncMs).coerceAtLeast(0L) / 60_000L).toInt()
        }
        val quality = AutonomicScopeQuality(
            summaryEpochCount = breakdown.summaryEpochCount,
            goodEpochCount = breakdown.goodEpochCount,
            usableEpochCount = breakdown.usableEpochCount,
            reviewEpochCount = breakdown.reviewEpochCount,
            poorEpochCount = breakdown.poorEpochCount,
            coveragePercent = breakdown.coveragePercent,
            longestGapMinutes = breakdown.longestGapMinutes,
            excludedMotionContactCount = breakdown.excludedMotionContactCount,
            ppiFreshnessMinutes = ppiFreshnessMinutes,
            windowDurationMinutes = breakdown.windowDurationMinutes
        )
        val minShapeEpochs = if (scope == AutonomicDetailScope.ACTIVE_SLEEP_REST) {
            MIN_RECOVERY_SHAPE_EPOCHS
        } else {
            MIN_ROLLING_SHAPE_EPOCHS
        }
        val showShapeSummary = breakdown.summaryEpochCount >= minShapeEpochs
        val emptyStateMessage = if (breakdown.summaryEpochCount < MIN_AUTONOMIC_CHART_EPOCHS) {
            when (scope) {
                AutonomicDetailScope.ACTIVE_SLEEP_REST ->
                    "Not enough clean PPI in this sleep/rest window."
                else ->
                    "Not enough clean PPI in the past ${scope.lookbackHours} hours."
            }
        } else {
            null
        }
        val cautionLines = buildCautionLines(
            scope = scope,
            quality = quality,
            recoveryWindow = recoveryWindow,
            anchorEpochMs = anchorEpochMs,
            nowEpochMs = nowEpochMs,
            lastPpiSyncEpochMs = lastPpiSyncEpochMs
        )
        val familyBanner = when (family) {
            AutonomicScopeFamily.RECOVERY_TRAJECTORY ->
                "Recovery-context HRV — conditions during rest."
            AutonomicScopeFamily.RECENT_TREND ->
                "Recent trend only — not equivalent to sleep/rest recovery HRV."
        }
        return AutonomicScopeSummary(
            scope = scope,
            family = family,
            planningInfluence = planningInfluence,
            windowStartEpochMs = windowStart,
            windowEndEpochMs = windowEnd,
            provenanceLabel = provenanceLabel,
            trajectoryPoints = breakdown.trajectoryPoints,
            quality = quality,
            sleepRestShading = sleepRestShadingForScope(
                scope = scope,
                windowStartEpochMs = windowStart,
                windowEndEpochMs = windowEnd,
                sleepEpisodes = sleepEpisodes
            ),
            alternateEpisodeLink = alternateEpisodeLink(
                recoveryWindow = recoveryWindow,
                sleepEpisodes = sleepEpisodes,
                zoneId = zoneId
            ),
            familyBanner = familyBanner,
            cautionLines = cautionLines,
            emptyStateMessage = emptyStateMessage,
            showShapeSummary = showShapeSummary,
            shapeSummaryIndicative = scope.isRolling && showShapeSummary,
            showStrainLabels = scope == AutonomicDetailScope.ACTIVE_SLEEP_REST && showShapeSummary
        )
    }

    fun resolveAll(
        epochs: List<Ppi247EpochEntity>,
        recoveryWindow: AutonomicRecoveryWindow?,
        sleepEpisodes: List<SleepEpisodeEntity>,
        lastPpiSyncEpochMs: Long?,
        nowEpochMs: Long = System.currentTimeMillis(),
        historicDateEndEpochMs: Long? = null,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Map<AutonomicDetailScope, AutonomicScopeSummary> =
        AutonomicDetailScope.selectorOrder.associateWith { scope ->
            resolve(
                scope = scope,
                epochs = epochs,
                recoveryWindow = recoveryWindow,
                sleepEpisodes = sleepEpisodes,
                lastPpiSyncEpochMs = lastPpiSyncEpochMs,
                nowEpochMs = nowEpochMs,
                historicDateEndEpochMs = historicDateEndEpochMs,
                zoneId = zoneId
            )
        }

    private fun buildCautionLines(
        scope: AutonomicDetailScope,
        quality: AutonomicScopeQuality,
        recoveryWindow: AutonomicRecoveryWindow?,
        anchorEpochMs: Long,
        nowEpochMs: Long,
        lastPpiSyncEpochMs: Long?
    ): List<String> {
        val lines = mutableListOf<String>()
        if (scope.isRolling) {
            lines += "This view mixes wake, activity, and rest. It describes recent autonomic pattern only. Lodestone does not treat it like sleep/rest recovery HRV and it does not change your daily forecast."
        }
        if (quality.coveragePercent < 50) {
            lines += "Sparse PPI coverage — trend may not represent the full period."
        }
        if (scope.isRolling && quality.summaryEpochCount > 0) {
            val excludedRatio = quality.excludedMotionContactCount.toDouble() /
                (quality.summaryEpochCount + quality.excludedMotionContactCount).coerceAtLeast(1)
            if (excludedRatio > 0.30) {
                lines += "Many windows had movement or poor contact — interpret cautiously."
            }
        }
        recoveryWindow?.let { window ->
            if (scope.isRolling && rangesOverlap(
                    window.startEpochMs,
                    window.endEpochMs,
                    scopeWindowStart(scope, anchorEpochMs),
                    anchorEpochMs
                )
            ) {
                lines += "Includes sleep/rest time — not the same as the active recovery curve."
            }
        }
        val staleMinutes = lastPpiSyncEpochMs?.let { ((nowEpochMs - it) / 60_000L).toInt() }
        if (staleMinutes != null && staleMinutes > 120) {
            lines += "PPI may be stale; check in to refresh."
        }
        return lines
    }

    private fun scopeWindowStart(scope: AutonomicDetailScope, anchorEpochMs: Long): Long {
        val hours = scope.lookbackHours ?: return anchorEpochMs
        return anchorEpochMs - hours * 3_600_000L
    }

    private fun rangesOverlap(
        startA: Long,
        endA: Long,
        startB: Long,
        endB: Long
    ): Boolean = startA < endB && startB < endA

    private fun sleepRestShadingForScope(
        scope: AutonomicDetailScope,
        windowStartEpochMs: Long,
        windowEndEpochMs: Long,
        sleepEpisodes: List<SleepEpisodeEntity>
    ): List<ClosedEpochMsRange>? {
        if (scope != AutonomicDetailScope.ROLLING_24H) return null
        return sleepEpisodes
            .asSequence()
            .filter { it.episodeKind != SleepEpisodeKinds.NO_SLEEP }
            .mapNotNull { episode ->
                val start = episode.startEpochMs ?: return@mapNotNull null
                val end = episode.endEpochMs ?: return@mapNotNull null
                if (end <= windowStartEpochMs || start >= windowEndEpochMs) return@mapNotNull null
                ClosedEpochMsRange(
                    startEpochMs = maxOf(start, windowStartEpochMs),
                    endEpochMs = minOf(end, windowEndEpochMs)
                )
            }
            .toList()
            .takeIf { it.isNotEmpty() }
    }

    private fun alternateEpisodeLink(
        recoveryWindow: AutonomicRecoveryWindow?,
        sleepEpisodes: List<SleepEpisodeEntity>,
        zoneId: ZoneId
    ): AlternateEpisodeLink? {
        val activeEnd = recoveryWindow?.endEpochMs ?: return null
        val candidate = sleepEpisodes
            .asSequence()
            .filter { it.episodeKind != SleepEpisodeKinds.NO_SLEEP }
            .mapNotNull { episode ->
                val start = episode.startEpochMs ?: return@mapNotNull null
                val end = episode.endEpochMs ?: return@mapNotNull null
                if (end <= activeEnd) return@mapNotNull null
                episode to ClosedEpochMsRange(start, end)
            }
            .minByOrNull { it.second.startEpochMs }
            ?: return null
        val range = candidate.second
        val formatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.UK)
        val startLabel = formatter.format(Instant.ofEpochMilli(range.startEpochMs).atZone(zoneId))
        val endLabel = formatter.format(Instant.ofEpochMilli(range.endEpochMs).atZone(zoneId))
        return AlternateEpisodeLink(
            episodeId = candidate.first.id,
            label = "Also: rest window $startLabel–$endLabel",
            startEpochMs = range.startEpochMs,
            endEpochMs = range.endEpochMs
        )
    }

    private fun emptySummary(
        scope: AutonomicDetailScope,
        family: AutonomicScopeFamily,
        planningInfluence: AutonomicPlanningInfluence,
        emptyMessage: String
    ): AutonomicScopeSummary =
        AutonomicScopeSummary(
            scope = scope,
            family = family,
            planningInfluence = planningInfluence,
            windowStartEpochMs = 0L,
            windowEndEpochMs = 0L,
            provenanceLabel = null,
            trajectoryPoints = emptyList(),
            quality = AutonomicScopeQuality(
                summaryEpochCount = 0,
                goodEpochCount = 0,
                usableEpochCount = 0,
                reviewEpochCount = 0,
                poorEpochCount = 0,
                coveragePercent = 0,
                longestGapMinutes = 0,
                excludedMotionContactCount = 0,
                ppiFreshnessMinutes = null,
                windowDurationMinutes = 0
            ),
            sleepRestShading = null,
            alternateEpisodeLink = null,
            familyBanner = if (family == AutonomicScopeFamily.RECOVERY_TRAJECTORY) {
                "Recovery-context HRV — conditions during rest."
            } else {
                "Recent trend only — not equivalent to sleep/rest recovery HRV."
            },
            cautionLines = emptyList(),
            emptyStateMessage = emptyMessage,
            showShapeSummary = false,
            shapeSummaryIndicative = false,
            showStrainLabels = false
        )

    fun historicDateEndEpochMs(sourceDate: String, zoneId: ZoneId = ZoneId.systemDefault()): Long? =
        runCatching {
            LocalDate.parse(sourceDate)
                .plusDays(1)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli() - 1L
        }.getOrNull()
}
