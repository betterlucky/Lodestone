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
    val rawPpiCoverageHours: Double? = null
)

enum class TrafficLightStatus {
    GOOD,
    OK,
    UNSTEADY,
    CRASH
}

const val PARSER_VERSION = 3
const val APP_VERSION = BuildConfig.VERSION_NAME
