package com.daveharris.healthmonitor.data

/**
 * Lodestone model v1 — output and input types.
 *
 * See docs/lodestone-model-v1.md and docs/lodestone-naming-contract.md.
 *
 * The model output is a [CurrentStateRead]: a continuity Forecast (spine) +
 * a [CautionSignal] beside it + an honest, capped [ConfidenceLevel]. It is
 * continuity of recent lived function, NOT a measured objective-function score.
 */

/**
 * The forecast level reuses the journal-outcome vocabulary
 * (GOOD / OK / UNSTEADY / CRASH) so the forecast speaks the same language as the
 * thing it predicts. The display layer may soften wording (esp. CRASH).
 */
typealias ForecastLevel = TrafficLightStatus

/** Where the forecast value came from — kept honest, never fabricated. */
enum class ForecastBasis {
    /** Carried from a recent lived outcome (the persistence spine). */
    RECENT_OUTCOME,
    /** Only older context available; say so, lower confidence. */
    STALE_CONTEXT,
    /** No usable lived outcome yet. */
    NONE
}

/**
 * The caution element sits BESIDE the forecast — it never silently lowers the
 * level. When [level] is ELEVATED the hero leads with the caution framing.
 */
data class CautionSignal(
    val level: CautionLevel,
    val kind: CautionKind,
    val reasons: List<String>
)

enum class CautionLevel { NONE, ELEVATED }

/**
 * Two genuinely different messages the caution element may carry. v1 only
 * detects [PUSH_RISK]; [BRITTLE_RECOVERY] is the reserved "stability" concept and
 * waits on a retrospective PEM-end signal (see docs).
 */
enum class CautionKind {
    /** Forward: load/instability up — easing may be wise; payback ~2 days out. */
    PUSH_RISK,
    /** Recovery tail: functional but reserve is thin — don't bank on stamina. */
    BRITTLE_RECOVERY
}

/**
 * Whether the read is well supported by data (coverage/quality/freshness/
 * baseline). This is what the old mislabelled "stability" actually measured.
 * Capped: never HIGH while the model is unvalidated. Shown only when degraded.
 */
enum class ConfidenceLevel { LOW, MEDIUM, HIGH }

/**
 * The model output. Carries the raw feature values it used so the UI/Signals
 * surface can be transparent about the basis without re-deriving anything.
 */
data class CurrentStateRead(
    val sourceDate: String,
    val forecastLevel: ForecastLevel?,
    val forecastBasis: ForecastBasis,
    val caution: CautionSignal,
    val confidence: ConfidenceLevel,
    val reasons: List<String>,
    // transparency / provenance of the features used
    val recentOutcomeLevel: ForecastLevel? = null,
    val recentOutcomeDate: String? = null,
    val exertionLoadRecentMvMinutes: Int? = null,
    val hrvCv24h: Double? = null
)

/**
 * Pure inputs to the v1 model — precomputed features + adaptive thresholds, so
 * [CurrentStateModel] stays pure and unit-testable (anti-Visible invariant,
 * backtest). Feature extraction from entities/epochs lives outside the model.
 */
data class CurrentStateInputs(
    val today: String,
    /** Most-recent lived outcome and its date (persistence spine + staleness). */
    val latestOutcome: ForecastLevel?,
    val latestOutcomeDate: String?,
    /** Recent run of outcomes, newest first — for the anti-Visible check. */
    val recentOutcomes: List<ForecastLevel>,
    /** Moderate/vigorous active-minutes on D-1 and D-2 (phone or Loop sourced). */
    val exertionMvMinutesD1: Int?,
    val exertionMvMinutesD2: Int?,
    /** Adaptive (personal, trailing-window) upper-tier exertion cut-point. */
    val exertionThresholdMvMinutes: Int?,
    /** Whole-day HRV coefficient of variation (no sleep-window dependence). */
    val hrvCv24h: Double?,
    /** Adaptive (personal, trailing-window) upper-tier CV cut-point. */
    val hrvCvThreshold: Double?,
    /** Coverage signals that drive confidence. */
    val coverage: DataCoverage
)

/** Inputs to the confidence judgement. */
data class DataCoverage(
    val hasRecentOutcome: Boolean,
    val outcomeAgeDays: Int?,
    val hasAutonomic: Boolean,
    val ppiCoverageHours: Double?,
    val baselineReady: Boolean,
    val lastSyncAgeHours: Double?
)
