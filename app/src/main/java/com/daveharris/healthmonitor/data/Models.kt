package com.daveharris.healthmonitor.data

import com.daveharris.healthmonitor.BuildConfig

enum class ProbeDomain(val label: String) {
    SLEEP("Sleep"),
    NIGHTLY_RECHARGE("Nightly recharge"),
    HR_247("24/7 HR"),
    PPI_247("24/7 PPi"),
    SKIN_TEMPERATURE("Skin temperature"),
    DAILY_SUMMARY("Daily summary"),
    ACTIVITY_SAMPLES("Activity samples")
}

enum class ProbeStatus {
    UNKNOWN,
    SUPPORTED,
    UNSUPPORTED,
    EMPTY,
    DELAYED,
    ERROR,
    PARSED,
    PARTIAL,
    RAW_ONLY
}

data class SyncWindowConfig(
    val sleepDays: Int = 7,
    val nightlyRechargeDays: Int = 7,
    val hrDays: Int = 3,
    val ppiDays: Int = 3
) {
    fun normalized(): SyncWindowConfig =
        copy(
            sleepDays = sleepDays.coerceIn(1, 30),
            nightlyRechargeDays = nightlyRechargeDays.coerceIn(1, 30),
            hrDays = hrDays.coerceIn(1, 30),
            ppiDays = ppiDays.coerceIn(1, 30)
        )
}

enum class SyncRunProfile(
    val runNotes: String,
    val successNotes: String
) {
    CHECK_IN("check-in sync", "check-in sync completed"),
    MORNING_CORE("morning core sync", "morning core sync completed"),
    MORNING_PPI_RETRY("morning PPI retry sync", "morning PPI retry completed"),
    MORNING_SLEEP_RETRY("morning sleep report retry sync", "morning sleep report retry completed"),
    FULL("manual sync", "manual sync completed")
}

/**
 * Sleep/PPI provenance for the active analysis window — the evidence Signals shows
 * (window source, duration, autonomic coverage, HRV trajectory). It carries NO
 * forecast: the forecast is [CurrentStateRead], derived natively by the model.
 * See docs/lodestone-redesign-architecture.md §"Active Analysis Window".
 */
data class AnalysisWindowEvidence(
    val sourceDate: String?,
    val overnightAutonomicSource: String,
    val sleepDurationMinutes: Int?,
    val nightlyRmssd: Double?,
    val baselineReady: Boolean,
    val isInterim: Boolean = false,
    val sleepDataReady: Boolean = !isInterim && sleepDurationMinutes != null,
    val rawPpiGoodEpochCount: Int? = null,
    val rawPpiPoorEpochCount: Int? = null,
    val rawPpiCoverageHours: Double? = null,
    val hrvTrajectory: List<HrvTrajectoryPoint> = emptyList()
)

internal fun AnalysisWindowEvidence?.hasUsableMorningPpiSignal(): Boolean =
    (this?.rawPpiGoodEpochCount ?: 0) >= MIN_MORNING_PPI_GOOD_EPOCHS

data class HrvTrajectoryPoint(
    val epochStartEpochMs: Long,
    val rmssdMs: Double,
    val epochQuality: String
)

enum class AnalysisWindowSource(
    val key: String,
    val hasEstablishedSleepWindow: Boolean = false
) {
    USER_CONFIRMED_NO_SLEEP("user_confirmed_no_sleep"),
    EDITED_SLEEP_EPISODE_PRIMARY("edited_sleep_episode_primary", hasEstablishedSleepWindow = true),
    MIXED_SLEEP_EPISODE_PRIMARY("mixed_sleep_episode_primary", hasEstablishedSleepWindow = true),
    MANUAL_SLEEP_EPISODE_PRIMARY("manual_sleep_episode_primary", hasEstablishedSleepWindow = true),
    CONFIRMED_SLEEP_EPISODE_PRIMARY("confirmed_sleep_episode_primary", hasEstablishedSleepWindow = true),
    PPI247_SLEEP_WINDOW("ppi247_sleep_window", hasEstablishedSleepWindow = true),
    RAW_PPI_CALIBRATED_WINDOW_PENDING_SLEEP_REPORT("raw_ppi_calibrated_window_pending_sleep_report", hasEstablishedSleepWindow = true),
    RAW_PPI_MANUAL_WINDOW_PENDING_SLEEP_REPORT("raw_ppi_manual_window_pending_sleep_report", hasEstablishedSleepWindow = true),
    RAW_PPI_INFERRED_WINDOW_PENDING_SLEEP_REPORT("raw_ppi_inferred_window_pending_sleep_report", hasEstablishedSleepWindow = true),
    MARKER_SLEEP_WINDOW_PENDING_SLEEP_REPORT("marker_sleep_window_pending_sleep_report", hasEstablishedSleepWindow = true),
    RAW_PPI_CALIBRATED_WINDOW_PRIMARY_WITH_SLEEP_REPORT("raw_ppi_calibrated_window_primary_with_sleep_report", hasEstablishedSleepWindow = true),
    RAW_PPI_MANUAL_WINDOW_PRIMARY_WITH_SLEEP_REPORT("raw_ppi_manual_window_primary_with_sleep_report", hasEstablishedSleepWindow = true),
    RAW_PPI_INFERRED_WINDOW_PRIMARY_WITH_SLEEP_REPORT("raw_ppi_inferred_window_primary_with_sleep_report", hasEstablishedSleepWindow = true),
    NIGHTLY_RECHARGE_SUMMARY("nightly_recharge_summary"),
    SLEEP_CONTEXT_ONLY("sleep_context_only", hasEstablishedSleepWindow = true),
    RAW_PPI_PENDING_MANUAL_SLEEP_WINDOW("raw_ppi_pending_manual_sleep_window"),
    RAW_PPI_PENDING_SLEEP_WINDOW("raw_ppi_pending_sleep_window"),
    AWAITING_SLEEP_DATA("awaiting_sleep_data");

    companion object {
        fun fromKey(key: String?): AnalysisWindowSource? =
            entries.firstOrNull { it.key == key }
    }
}

private const val MIN_MORNING_PPI_GOOD_EPOCHS = 12

enum class TrafficLightStatus {
    GOOD,
    OK,
    UNSTEADY,
    CRASH
}

object SleepEpisodeKinds {
    const val MAIN_SLEEP = "main_sleep"
    const val NAP = "nap"
    const val REST_CANDIDATE = "rest_candidate"
    const val NO_SLEEP = "no_sleep"
}

object SleepEpisodeSources {
    const val MANUAL = "manual"
    const val EDITED = "edited"
    const val PPI_INFERRED = "ppi_inferred"
    const val POLAR_SLEEP = "polar_sleep"
    const val MIXED = "mixed"
}

object SleepEpisodeConfidences {
    const val USER_CONFIRMED = "user_confirmed"
    const val HIGH = "high"
    const val MEDIUM = "medium"
    const val LOW = "low"
}

object WakeMarkerSources {
    const val GOING_TO_BED = "manual_going_to_bed"
    const val IM_AWAKE = "manual_im_awake"
}

object JournalMajorTaskTypes {
    const val WORK_FROM_HOME = "work_from_home"
    const val SITE_VISIT = "site_visit"
    const val ADMIN_ASSESSMENT = "admin_assessment"
    const val OTHER_MAJOR_TASK = "other_major_task"
}

object PaybackPeakConfidence {
    const val USER_SELECTED = "user_selected"
    const val AUTO_SINGLE = "auto_single"
    const val NOT_SURE = "not_sure"
    const val DISMISSED = "dismissed"
}

const val PARSER_VERSION = 3
const val APP_VERSION = BuildConfig.VERSION_NAME
