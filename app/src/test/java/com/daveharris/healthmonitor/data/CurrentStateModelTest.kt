package com.daveharris.healthmonitor.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for Lodestone model v1 (see docs/lodestone-model-v1.md §Tests).
 * The model is pure: features + adaptive thresholds in, [CurrentStateRead] out.
 */
class CurrentStateModelTest {

    private fun coverage(
        hasRecentOutcome: Boolean = true,
        outcomeAgeDays: Int? = 0,
        hasAutonomic: Boolean = true,
        ppiCoverageHours: Double? = 20.0,
        baselineReady: Boolean = true,
        lastSyncAgeHours: Double? = 2.0
    ) = DataCoverage(
        hasRecentOutcome = hasRecentOutcome,
        outcomeAgeDays = outcomeAgeDays,
        hasAutonomic = hasAutonomic,
        ppiCoverageHours = ppiCoverageHours,
        baselineReady = baselineReady,
        lastSyncAgeHours = lastSyncAgeHours
    )

    private fun inputs(
        recentOutcomes: List<TrafficLightStatus>,
        outcomeAgeDays: Int? = 0,
        exertionD1: Int? = null,
        exertionD2: Int? = null,
        exertionThreshold: Int? = null,
        hrvCv24h: Double? = null,
        hrvCvThreshold: Double? = null,
        coverage: DataCoverage = coverage(outcomeAgeDays = outcomeAgeDays)
    ) = CurrentStateInputs(
        today = "2026-06-13",
        latestOutcome = recentOutcomes.firstOrNull(),
        latestOutcomeDate = if (recentOutcomes.isEmpty()) null else "2026-06-12",
        recentOutcomes = recentOutcomes,
        exertionMvMinutesD1 = exertionD1,
        exertionMvMinutesD2 = exertionD2,
        exertionThresholdMvMinutes = exertionThreshold,
        hrvCv24h = hrvCv24h,
        hrvCvThreshold = hrvCvThreshold,
        coverage = coverage
    )

    // --- Persistence spine ---------------------------------------------------

    @Test
    fun carriesMostRecentOutcomeForward() {
        val read = CurrentStateModel.deriveCurrentStateRead(
            inputs(listOf(TrafficLightStatus.OK, TrafficLightStatus.OK, TrafficLightStatus.UNSTEADY))
        )
        assertEquals(TrafficLightStatus.OK, read.forecastLevel)
        assertEquals(ForecastBasis.RECENT_OUTCOME, read.forecastBasis)
    }

    @Test
    fun worseningCarriesImmediately() {
        // A fresh downturn must never be smoothed away (honesty / anti-Visible).
        val read = CurrentStateModel.deriveCurrentStateRead(
            inputs(listOf(TrafficLightStatus.CRASH, TrafficLightStatus.GOOD, TrafficLightStatus.GOOD))
        )
        assertEquals(TrafficLightStatus.CRASH, read.forecastLevel)
    }

    @Test
    fun unsupportedSingleDayImprovementIsDamped() {
        // One good day on top of a worse run does not get to declare recovery.
        val read = CurrentStateModel.deriveCurrentStateRead(
            inputs(listOf(TrafficLightStatus.OK, TrafficLightStatus.UNSTEADY, TrafficLightStatus.UNSTEADY))
        )
        assertEquals(TrafficLightStatus.UNSTEADY, read.forecastLevel)
    }

    @Test
    fun corroboratedImprovementIsCarried() {
        val read = CurrentStateModel.deriveCurrentStateRead(
            inputs(listOf(TrafficLightStatus.GOOD, TrafficLightStatus.UNSTEADY, TrafficLightStatus.GOOD))
        )
        // prior window contains a GOOD that corroborates -> not damped
        assertEquals(TrafficLightStatus.GOOD, read.forecastLevel)
    }

    @Test
    fun staleOutcomeLowersBasisAndConfidence() {
        val read = CurrentStateModel.deriveCurrentStateRead(
            inputs(listOf(TrafficLightStatus.OK), outcomeAgeDays = 5)
        )
        assertEquals(ForecastBasis.STALE_CONTEXT, read.forecastBasis)
        assertEquals(TrafficLightStatus.OK, read.forecastLevel)
        assertEquals(ConfidenceLevel.LOW, read.confidence)
    }

    @Test
    fun veryOldOutcomeProducesNoForecast() {
        val read = CurrentStateModel.deriveCurrentStateRead(
            inputs(listOf(TrafficLightStatus.OK), outcomeAgeDays = 14)
        )
        assertNull(read.forecastLevel)
        assertEquals(ForecastBasis.NONE, read.forecastBasis)
    }

    @Test
    fun noOutcomeProducesNoForecast() {
        val read = CurrentStateModel.deriveCurrentStateRead(
            inputs(emptyList(), coverage = coverage(hasRecentOutcome = false, outcomeAgeDays = null))
        )
        assertNull(read.forecastLevel)
        assertEquals(ForecastBasis.NONE, read.forecastBasis)
        assertEquals(ConfidenceLevel.LOW, read.confidence)
    }

    // --- Anti-Visible invariant ---------------------------------------------

    @Test
    fun recentBadRunWithGoodAutonomicNeverReadsGood() {
        // The invariant from the doc: a recent UNSTEADY/CRASH run + good autonomic
        // data (low CV, well-covered) must NOT produce a cheerful GOOD forecast.
        val read = CurrentStateModel.deriveCurrentStateRead(
            inputs(
                recentOutcomes = listOf(
                    TrafficLightStatus.UNSTEADY,
                    TrafficLightStatus.CRASH,
                    TrafficLightStatus.UNSTEADY
                ),
                hrvCv24h = 0.05,            // very settled autonomic data
                hrvCvThreshold = 0.20,
                exertionD1 = 0,
                exertionD2 = 0,
                exertionThreshold = 30,
                coverage = coverage()        // fully covered, baseline ready, fresh
            )
        )
        assertNotEquals(TrafficLightStatus.GOOD, read.forecastLevel)
        assertEquals(CautionLevel.NONE, read.caution.level)
    }

    @Test
    fun recentInstabilityIsCarriedByCautionNotASilentDowngrade() {
        // Two good days after a crash: the forecast honestly reads GOOD (most recent
        // lived outcome). The recent-instability + high-load concern surfaces in the
        // caution layer BESIDE it — it must NOT silently lower the level (doc §2).
        val read = CurrentStateModel.deriveCurrentStateRead(
            inputs(
                listOf(TrafficLightStatus.GOOD, TrafficLightStatus.GOOD, TrafficLightStatus.CRASH),
                exertionD1 = 50, exertionThreshold = 30
            )
        )
        assertEquals(TrafficLightStatus.GOOD, read.forecastLevel)
        assertEquals(CautionLevel.ELEVATED, read.caution.level)
    }

    // --- Caution triggers ----------------------------------------------------

    @Test
    fun cautionNoneWhenNeitherTriggerFires() {
        val read = CurrentStateModel.deriveCurrentStateRead(
            inputs(
                listOf(TrafficLightStatus.OK),
                exertionD1 = 5, exertionD2 = 5, exertionThreshold = 30,
                hrvCv24h = 0.10, hrvCvThreshold = 0.20
            )
        )
        assertEquals(CautionLevel.NONE, read.caution.level)
        assertTrue(read.caution.reasons.isEmpty())
    }

    @Test
    fun cautionElevatedOnExertionOnly() {
        val read = CurrentStateModel.deriveCurrentStateRead(
            inputs(
                listOf(TrafficLightStatus.OK),
                exertionD1 = 10, exertionD2 = 45, exertionThreshold = 30,
                hrvCv24h = 0.10, hrvCvThreshold = 0.20
            )
        )
        assertEquals(CautionLevel.ELEVATED, read.caution.level)
        assertEquals(CautionKind.PUSH_RISK, read.caution.kind)
        assertEquals(1, read.caution.reasons.size)
    }

    @Test
    fun cautionElevatedOnCvOnly() {
        val read = CurrentStateModel.deriveCurrentStateRead(
            inputs(
                listOf(TrafficLightStatus.OK),
                exertionD1 = 5, exertionD2 = 5, exertionThreshold = 30,
                hrvCv24h = 0.30, hrvCvThreshold = 0.20
            )
        )
        assertEquals(CautionLevel.ELEVATED, read.caution.level)
        assertEquals(1, read.caution.reasons.size)
    }

    @Test
    fun cautionElevatedOnBothWithTwoReasons() {
        val read = CurrentStateModel.deriveCurrentStateRead(
            inputs(
                listOf(TrafficLightStatus.OK),
                exertionD1 = 50, exertionD2 = 5, exertionThreshold = 30,
                hrvCv24h = 0.30, hrvCvThreshold = 0.20
            )
        )
        assertEquals(CautionLevel.ELEVATED, read.caution.level)
        assertEquals(2, read.caution.reasons.size)
    }

    @Test
    fun cautionCannotFireWithoutAdaptiveThreshold() {
        // No threshold yet (insufficient history) -> never fabricate a trigger.
        val read = CurrentStateModel.deriveCurrentStateRead(
            inputs(
                listOf(TrafficLightStatus.OK),
                exertionD1 = 999, exertionThreshold = null,
                hrvCv24h = 0.99, hrvCvThreshold = null
            )
        )
        assertEquals(CautionLevel.NONE, read.caution.level)
    }

    // --- Confidence ----------------------------------------------------------

    @Test
    fun confidenceNeverHighEvenWithFullCoverage() {
        val read = CurrentStateModel.deriveCurrentStateRead(
            inputs(listOf(TrafficLightStatus.OK), coverage = coverage())
        )
        assertEquals(ConfidenceLevel.MEDIUM, read.confidence)
        assertNotEquals(ConfidenceLevel.HIGH, read.confidence)
    }

    @Test
    fun confidenceLowWhenCoverageThin() {
        val read = CurrentStateModel.deriveCurrentStateRead(
            inputs(
                listOf(TrafficLightStatus.OK),
                coverage = coverage(
                    hasAutonomic = false,
                    ppiCoverageHours = 0.0,
                    baselineReady = false,
                    lastSyncAgeHours = 100.0
                )
            )
        )
        assertEquals(ConfidenceLevel.LOW, read.confidence)
    }

    // --- Adaptive thresholds -------------------------------------------------

    @Test
    fun adaptiveThresholdFallsBackUntilEnoughHistory() {
        val few = listOf(1.0, 2.0, 3.0)
        assertEquals(99.0, CurrentStateModel.adaptiveUpperTier(few, provisionalFallback = 99.0))
    }

    @Test
    fun adaptiveThresholdUsesPersonalUpperTierWhenEnoughHistory() {
        val values = (1..20).map { it.toDouble() } // 20 values, 1..20
        val cut = CurrentStateModel.adaptiveUpperTier(values, provisionalFallback = null, tier = 0.66)
        // 66th percentile of 1..20 by linear interpolation across (n-1)=19 -> rank 12.54
        assertNotNull(cut)
        assertTrue(cut in 13.0..14.0, "expected upper-tier cut in 13..14 but was $cut")
    }
}
