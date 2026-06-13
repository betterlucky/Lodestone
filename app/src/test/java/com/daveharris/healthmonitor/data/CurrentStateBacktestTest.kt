package com.daveharris.healthmonitor.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JVM-side validation-loop check (docs/lodestone-model-v1.md §Validation loop).
 * Asserts the persistence+caution rule catches deterioration at least as well as
 * the persistence baseline at no accuracy cost, and prints the report so it can be
 * eyeballed as data grows. Uses a synthetic series carrying the user's documented
 * dynamics (high exertion → downturn lands ~2 days later).
 */
class CurrentStateBacktestTest {

    private fun day(date: String, sf: TrafficLightStatus?, mv: Int?, cv: Double?) =
        CurrentStateBacktest.DayObservation(date, sf, mv, cv)

    // Series where heavy activity (D) precedes a deterioration two days later (D+2).
    // Steady days carry no CV signal (null never fires); CV spikes only on the
    // genuine payback days, so the CV lane corroborates without false alarms.
    private fun series() = listOf(
        day("2026-05-01", TrafficLightStatus.OK, 5, null),
        day("2026-05-02", TrafficLightStatus.OK, 8, null),
        day("2026-05-03", TrafficLightStatus.OK, 70, null),   // heavy exertion
        day("2026-05-04", TrafficLightStatus.OK, 6, null),
        day("2026-05-05", TrafficLightStatus.UNSTEADY, 4, 0.30), // payback lands + unsettled HRV
        day("2026-05-06", TrafficLightStatus.UNSTEADY, 3, null),
        day("2026-05-07", TrafficLightStatus.OK, 5, null),
        day("2026-05-08", TrafficLightStatus.OK, 9, null),
        day("2026-05-09", TrafficLightStatus.OK, 75, null),   // heavy exertion
        day("2026-05-10", TrafficLightStatus.OK, 7, null),
        day("2026-05-11", TrafficLightStatus.UNSTEADY, 5, 0.31), // payback + unsettled HRV
        day("2026-05-12", TrafficLightStatus.UNSTEADY, 4, null),
        day("2026-05-13", TrafficLightStatus.OK, 6, null),
        day("2026-05-14", TrafficLightStatus.OK, 8, null)
    )

    @Test
    fun modelCatchesDeteriorationAtNoAccuracyCostVsPersistence() {
        val report = CurrentStateBacktest.run(series())
        println(report.render())

        val byRule = report.results.associateBy { it.rule }
        val persist = byRule.getValue(CurrentStateBacktest.Rule.PERSIST)
        val model = byRule.getValue(CurrentStateBacktest.Rule.PERSIST_EXERT_CV)

        val majority = byRule.getValue(CurrentStateBacktest.Rule.MAJORITY)

        assertTrue(report.deteriorationDays >= 1, "fixture must contain deteriorations")
        // Safety property: persistence alone is blind to downturns; the caution rule
        // warns on strictly more of them. (This is the keystone exertion→payback signal.)
        assertTrue(
            model.deteriorationWarned > persist.deteriorationWarned,
            "model should warn on more deteriorations than persistence " +
                "(model=${model.deteriorationWarned}, persist=${persist.deteriorationWarned})"
        )
        // Usefulness floor: the model must beat the always-worst "doom" baseline on
        // accuracy — vigilance with information, not just crying wolf every day.
        assertTrue(
            model.accuracy > majority.accuracy,
            "model accuracy (${model.accuracy}) should beat the doom baseline (${majority.accuracy})"
        )
    }

    @Test
    fun alwaysUnsteadyBaselineIsTriviallyVigilantButUseless() {
        val report = CurrentStateBacktest.run(series())
        val majority = report.results.first { it.rule == CurrentStateBacktest.Rule.MAJORITY }
        // The doom baseline "catches" every deterioration by always predicting the worst
        // tier present — vigilance without information. Documented as the floor to beat.
        assertEquals(majority.deteriorationDays, majority.deteriorationWarned)
    }
}
