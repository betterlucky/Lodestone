package com.daveharris.healthmonitor.data

enum class AutonomicDetailScope(val userLabel: String, val lookbackHours: Int?) {
    ACTIVE_SLEEP_REST("Active sleep/rest", null),
    ROLLING_1H("Past 1 hour", 1),
    ROLLING_2H("Past 2 hours", 2),
    ROLLING_4H("Past 4 hours", 4),
    ROLLING_8H("Past 8 hours", 8),
    ROLLING_24H("Past 24 hours", 24);

    val isRolling: Boolean get() = lookbackHours != null

    companion object {
        val selectorOrder: List<AutonomicDetailScope> = entries
    }
}

enum class AutonomicScopeFamily {
    RECOVERY_TRAJECTORY,
    RECENT_TREND
}

enum class AutonomicPlanningInfluence {
    RECOVERY_LANE,
    DESCRIPTIVE_ONLY
}

data class ClosedEpochMsRange(
    val startEpochMs: Long,
    val endEpochMs: Long
)

data class AutonomicScopeQuality(
    val summaryEpochCount: Int,
    val goodEpochCount: Int,
    val usableEpochCount: Int,
    val reviewEpochCount: Int,
    val poorEpochCount: Int,
    val coveragePercent: Int,
    val longestGapMinutes: Int,
    val excludedMotionContactCount: Int,
    val ppiFreshnessMinutes: Int?,
    val windowDurationMinutes: Int
)

data class AlternateEpisodeLink(
    val episodeId: Long,
    val label: String,
    val startEpochMs: Long,
    val endEpochMs: Long
)

data class AutonomicScopeSummary(
    val scope: AutonomicDetailScope,
    val family: AutonomicScopeFamily,
    val planningInfluence: AutonomicPlanningInfluence,
    val windowStartEpochMs: Long,
    val windowEndEpochMs: Long,
    val provenanceLabel: String?,
    val trajectoryPoints: List<HrvTrajectoryPoint>,
    val quality: AutonomicScopeQuality,
    val sleepRestShading: List<ClosedEpochMsRange>?,
    val alternateEpisodeLink: AlternateEpisodeLink?,
    val familyBanner: String,
    val cautionLines: List<String>,
    val emptyStateMessage: String?,
    val showShapeSummary: Boolean,
    val shapeSummaryIndicative: Boolean,
    val showStrainLabels: Boolean
) {
    val hasChart: Boolean get() = trajectoryPoints.size >= MIN_AUTONOMIC_CHART_EPOCHS
}

data class AutonomicRecoveryWindow(
    val startEpochMs: Long,
    val endEpochMs: Long,
    val provenanceLabel: String,
    val sourceDate: String?
)

internal const val MIN_AUTONOMIC_CHART_EPOCHS = 3
internal const val MIN_RECOVERY_SHAPE_EPOCHS = 12
internal const val MIN_ROLLING_SHAPE_EPOCHS = 8

internal val SUMMARY_EPOCH_QUALITIES = setOf("good", "usable")
internal val REVIEW_EPOCH_QUALITIES = setOf("review")
internal val POOR_EPOCH_QUALITIES = setOf("poor_sparse", "poor_contact_or_error")
