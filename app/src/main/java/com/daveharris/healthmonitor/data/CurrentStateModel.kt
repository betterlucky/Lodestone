package com.daveharris.healthmonitor.data

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Lodestone model v1 — the current-state engine. Replaces the legacy
 * `scoreMorningRead` sleep-recovery score (which was empirically non-predictive;
 * see docs/lodestone-model-v1.md).
 *
 * v1 shape, evidence-backed (June 2026 data-look):
 *  - Forecast (spine)  = persistence: carry the most recent lived outcome.
 *  - Caution (beside)  = ELEVATED when recent moderate/vigorous exertion (D-1/D-2)
 *                        OR 24h HRV CV is in the user's own upper tier.
 *  - Confidence        = from data coverage; capped, never HIGH while unvalidated.
 *
 * Invariant (anti-Visible): good autonomic data must never produce a cheerful
 * forecast that overrides a recent run of poor lived function.
 *
 * Pure by design: thresholds are precomputed and passed in via [CurrentStateInputs].
 */
object CurrentStateModel {

    // --- Persistence spine knobs (fixed defaults + backtest knobs, NOT fitted) ---
    /** Smoothing window for the persistence spine; guards against single odd entries. */
    const val PERSISTENCE_WINDOW_DAYS = 3
    /** Outcomes up to this age (days) still count as a recent, carry-forward spine. */
    const val RECENT_OUTCOME_MAX_AGE_DAYS = 3
    /** Beyond this age the outcome is too old to forecast from at all. */
    const val FORECAST_NONE_AFTER_DAYS = 7

    // --- Confidence coverage knobs ---
    /** Minimum good PPI hours for the autonomic lane to count toward confidence. */
    const val MIN_PPI_HOURS_FOR_CONFIDENCE = 12.0
    /** A sync older than this (hours) stops counting as fresh. */
    const val FRESH_SYNC_MAX_AGE_HOURS = 36.0

    // --- Adaptive threshold knobs ---
    /** Minimum trailing values before we trust a personal (adaptive) cut-point. */
    const val MIN_HISTORY_FOR_ADAPTIVE = 14

    fun deriveCurrentStateRead(inputs: CurrentStateInputs): CurrentStateRead {
        val (level, basis) = deriveForecast(inputs)
        val caution = deriveCaution(inputs)
        val confidence = deriveConfidence(inputs.coverage)

        val reasons = buildList {
            when (basis) {
                ForecastBasis.STALE_CONTEXT ->
                    add("Based on older context — no recent check-in.")
                ForecastBasis.NONE ->
                    add("No recent check-in to forecast from.")
                ForecastBasis.RECENT_OUTCOME -> Unit
            }
        }

        return CurrentStateRead(
            sourceDate = inputs.today,
            forecastLevel = level,
            forecastBasis = basis,
            caution = caution,
            confidence = confidence,
            reasons = reasons,
            recentOutcomeLevel = inputs.latestOutcome,
            recentOutcomeDate = inputs.latestOutcomeDate,
            exertionLoadRecentMvMinutes = recentExertionLoad(inputs),
            hrvCv24h = inputs.hrvCv24h
        )
    }

    /**
     * Persistence spine + anti-Visible clamp.
     *
     * The spine carries the most recent lived outcome (lag-1 persistence, rho 0.53).
     * Smoothing is asymmetric and deliberately honest:
     *  - a *worsening* is carried immediately (we never suppress a downturn);
     *  - an unsupported single-day *improvement* is damped (one good day among a
     *    worse run does not get to declare recovery).
     * Then a hard anti-Visible clamp guarantees a recent UNSTEADY/CRASH run can
     * never present as a cheerful GOOD, regardless of any other lane.
     */
    internal fun deriveForecast(inputs: CurrentStateInputs): Pair<ForecastLevel?, ForecastBasis> {
        val recent = inputs.recentOutcomes
        if (inputs.latestOutcome == null || recent.isEmpty()) {
            return null to ForecastBasis.NONE
        }

        val age = inputs.coverage.outcomeAgeDays
        if (age != null && age > FORECAST_NONE_AFTER_DAYS) {
            return null to ForecastBasis.NONE
        }
        val basis = when {
            age == null -> ForecastBasis.RECENT_OUTCOME
            age <= RECENT_OUTCOME_MAX_AGE_DAYS -> ForecastBasis.RECENT_OUTCOME
            else -> ForecastBasis.STALE_CONTEXT
        }

        var spine = recent.first()

        // Damp an unsupported single-day improvement (never damp a worsening).
        val yesterday = recent.getOrNull(1)
        if (yesterday != null && goodness(spine) > goodness(yesterday)) {
            val priorWindow = recent.drop(1).take(PERSISTENCE_WINDOW_DAYS - 1)
            val corroborated = priorWindow.any { goodness(it) >= goodness(spine) }
            if (!corroborated) spine = yesterday
        }

        // Anti-Visible guarantee: the forecast is pure lived-function persistence;
        // no other lane (HRV/sleep) may upgrade it. It can only read GOOD when the
        // most recent lived outcome is itself GOOD. Recent-instability concern is
        // NOT folded into the level here — that is the caution layer's job, surfaced
        // BESIDE the forecast (docs/lodestone-model-v1.md §2: "never a silent downgrade").
        if (spine == TrafficLightStatus.GOOD && recent.first() != TrafficLightStatus.GOOD) {
            spine = TrafficLightStatus.OK
        }

        return spine to basis
    }

    /**
     * Exertion-tier OR CV-tier -> PUSH_RISK caution (v1 detects PUSH_RISK only).
     * Sits beside the forecast; never lowers the level. A lane whose threshold is
     * unknown (insufficient history) simply cannot fire — we never fabricate.
     */
    internal fun deriveCaution(inputs: CurrentStateInputs): CautionSignal {
        val reasons = mutableListOf<String>()

        val exertion = recentExertionLoad(inputs)
        val exertionThreshold = inputs.exertionThresholdMvMinutes
        if (exertion != null && exertionThreshold != null && exertion >= exertionThreshold) {
            reasons.add("Recent activity (last 1–2 days) is in your higher range — payback tends to land ~2 days out.")
        }

        val cv = inputs.hrvCv24h
        val cvThreshold = inputs.hrvCvThreshold
        if (cv != null && cvThreshold != null && cv >= cvThreshold) {
            reasons.add("Heart-rate variability is more unsettled than usual (24h).")
        }

        val level = if (reasons.isEmpty()) CautionLevel.NONE else CautionLevel.ELEVATED
        return CautionSignal(level = level, kind = CautionKind.PUSH_RISK, reasons = reasons)
    }

    /**
     * Capped confidence from data coverage; never HIGH while unvalidated.
     * A weak or stale persistence spine forces LOW; otherwise MEDIUM only when the
     * supporting coverage (autonomic / baseline / sync freshness) is genuinely solid.
     */
    internal fun deriveConfidence(coverage: DataCoverage): ConfidenceLevel {
        if (!coverage.hasRecentOutcome) return ConfidenceLevel.LOW
        val age = coverage.outcomeAgeDays
        if (age == null || age > RECENT_OUTCOME_MAX_AGE_DAYS) return ConfidenceLevel.LOW

        var support = 0
        if (coverage.hasAutonomic && (coverage.ppiCoverageHours ?: 0.0) >= MIN_PPI_HOURS_FOR_CONFIDENCE) support++
        if (coverage.baselineReady) support++
        if ((coverage.lastSyncAgeHours ?: Double.MAX_VALUE) <= FRESH_SYNC_MAX_AGE_HOURS) support++

        // Cap: MEDIUM is the ceiling while the model is unvalidated.
        return if (support >= 2) ConfidenceLevel.MEDIUM else ConfidenceLevel.LOW
    }

    /**
     * Adaptive personal cut-point: the upper-tier (e.g. ~66th percentile) of the
     * user's own trailing values. Population fallback only until enough history,
     * and labelled provisional. Generic so it serves exertion and CV.
     */
    internal fun adaptiveUpperTier(
        trailingValues: List<Double>,
        provisionalFallback: Double?,
        tier: Double = 0.66
    ): Double? {
        if (trailingValues.size < MIN_HISTORY_FOR_ADAPTIVE) return provisionalFallback
        val sorted = trailingValues.sorted()
        val rank = tier * (sorted.size - 1)
        val lo = floor(rank).toInt()
        val hi = ceil(rank).toInt()
        if (lo == hi) return sorted[lo]
        val frac = rank - lo
        return sorted[lo] + (sorted[hi] - sorted[lo]) * frac
    }

    /** The exertion value the model acts on: the heavier of the D-1 / D-2 loads. */
    private fun recentExertionLoad(inputs: CurrentStateInputs): Int? {
        val d1 = inputs.exertionMvMinutesD1
        val d2 = inputs.exertionMvMinutesD2
        return when {
            d1 == null -> d2
            d2 == null -> d1
            else -> maxOf(d1, d2)
        }
    }

    /** Ordinal goodness: GOOD high, CRASH low (matches the backtest's ORD scale). */
    private fun goodness(level: TrafficLightStatus): Int = when (level) {
        TrafficLightStatus.CRASH -> 0
        TrafficLightStatus.UNSTEADY -> 1
        TrafficLightStatus.OK -> 2
        TrafficLightStatus.GOOD -> 3
    }
}
