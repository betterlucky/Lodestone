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

data class MorningReadSnapshot(
    val sourceDate: String?,
    val status: TrafficLightStatus?,
    val confidence: String,
    val overnightAutonomicSource: String,
    val sleepDurationMinutes: Int?,
    val nightlyRmssd: Double?,
    val baselineReady: Boolean,
    val recoveryAvailable: Boolean,
    val summary: String,
    val reasons: List<String>,
    val isInterim: Boolean = false,
    val sleepDataReady: Boolean = !isInterim && sleepDurationMinutes != null,
    val rawPpiGoodEpochCount: Int? = null,
    val rawPpiPoorEpochCount: Int? = null,
    val rawPpiCoverageHours: Double? = null,
    val hrvTrajectory: List<HrvTrajectoryPoint> = emptyList()
)

data class HrvTrajectoryPoint(
    val epochStartEpochMs: Long,
    val rmssdMs: Double,
    val epochQuality: String
)

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

const val PARSER_VERSION = 3
const val APP_VERSION = BuildConfig.VERSION_NAME
