package com.daveharris.healthmonitor.data

import com.daveharris.healthmonitor.util.GsonProvider
import com.google.gson.JsonObject
import java.time.LocalDate
import kotlin.math.sqrt

/**
 * Pure feature extraction for Lodestone model v1. Turns raw entities / vendor JSON
 * into the precomputed [CurrentStateInputs] that [CurrentStateModel] consumes.
 *
 * Kept separate from the model so the model stays pure (anti-Visible invariant,
 * backtest) and both layers are independently unit-testable. The repository owns
 * the I/O (DAO reads); everything here is a pure function of its arguments.
 *
 * See docs/lodestone-model-v1.md §Inputs and §Thresholds.
 */
object CurrentStateFeatures {

    /** Population fallbacks, used ONLY until enough personal history exists. */
    const val PROVISIONAL_MV_MINUTES_THRESHOLD = 30.0
    const val PROVISIONAL_HRV_CV_THRESHOLD = 0.22

    /** A day needs at least this many good PPI epochs for a usable 24h CV. */
    const val MIN_GOOD_EPOCHS_FOR_CV = 12

    /** Trailing window for the personal (adaptive) threshold distributions. */
    const val ADAPTIVE_TRAILING_DAYS = 30

    /** Map the journal's evening outcome string onto the shared traffic-light enum. */
    fun outcomeLevel(eveningOutcome: String?): TrafficLightStatus? =
        when (eveningOutcome?.trim()?.uppercase()) {
            "GOOD" -> TrafficLightStatus.GOOD
            "OK" -> TrafficLightStatus.OK
            "UNSTEADY" -> TrafficLightStatus.UNSTEADY
            "CRASH" -> TrafficLightStatus.CRASH
            else -> null
        }

    /**
     * Moderate + vigorous active minutes from a `daily_summary_raw` payload's
     * `activityClassTimes` (matches local/analysis/deepdive2.py). Null if unparseable.
     */
    fun mvActiveMinutes(rawPayloadJson: String?): Int? {
        val json = rawPayloadJson ?: return null
        val obj = runCatching { GsonProvider.gson.fromJson(json, JsonObject::class.java) }.getOrNull() ?: return null
        val act = obj.getAsJsonObject("activityClassTimes") ?: return null
        fun mins(key: String): Int {
            val o = runCatching { act.getAsJsonObject(key) }.getOrNull() ?: return 0
            val h = o.get("hours")?.takeUnless { it.isJsonNull }?.asInt ?: 0
            val m = o.get("minutes")?.takeUnless { it.isJsonNull }?.asInt ?: 0
            return h * 60 + m
        }
        return mins("timeContinuousModerateActivity") +
            mins("timeIntermittentModerateActivity") +
            mins("timeContinuousVigorousActivity") +
            mins("timeIntermittentVigorousActivity")
    }

    /** Rolling median (odd window) — the analysis script's ultra-smoothing. */
    internal fun rollingMedian(values: List<Double>, window: Int = 7): List<Double> {
        if (values.size <= 2) return values
        val half = window / 2
        return values.indices.map { i ->
            val slice = values.subList(maxOf(0, i - half), minOf(values.size, i + half + 1)).sorted()
            slice[slice.size / 2]
        }
    }

    internal fun coefficientOfVariation(values: List<Double>): Double? {
        if (values.size < 2) return null
        val mean = values.average()
        if (mean == 0.0) return null
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        return sqrt(variance) / mean
    }

    /**
     * Whole-day HRV coefficient of variation from good PPI epoch RMSSDs — no sleep
     * window dependence (matters given bradycardia + unreliable vendor sleep staging).
     */
    fun hrvCv24h(goodEpochRmssd: List<Double>): Double? {
        if (goodEpochRmssd.size < MIN_GOOD_EPOCHS_FOR_CV) return null
        return coefficientOfVariation(rollingMedian(goodEpochRmssd))
    }

    /** Whole calendar-day inclusive span between two ISO dates, or null if unparseable. */
    fun daysBetween(fromDate: String?, toDate: String): Int? {
        val from = runCatching { LocalDate.parse(fromDate) }.getOrNull() ?: return null
        val to = runCatching { LocalDate.parse(toDate) }.getOrNull() ?: return null
        return (to.toEpochDay() - from.toEpochDay()).toInt()
    }
}
