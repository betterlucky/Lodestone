package com.daveharris.healthmonitor.data

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncRangePlannerTest {
    private val today = LocalDate.parse("2026-06-11")

    @Test
    fun emptyDatabaseUsesFullConfiguredLookback() {
        val range = SyncRangePlanner.planRange(
            today = today,
            maxLookbackDays = 7,
            latestStoredDate = null
        )
        assertEquals(LocalDate.parse("2026-06-04"), range.from)
        assertEquals(today, range.to)
        assertFalse(range.incremental)
        assertEquals(8, range.dayCountInclusive)
    }

    @Test
    fun recentCoverageUsesOverlapAndAlwaysIncludesToday() {
        val range = SyncRangePlanner.planRange(
            today = today,
            maxLookbackDays = 30,
            latestStoredDate = "2026-06-10",
            overlapDays = 1
        )
        assertEquals(LocalDate.parse("2026-06-09"), range.from)
        assertEquals(today, range.to)
        assertTrue(range.incremental)
        assertEquals(3, range.dayCountInclusive)
    }

    @Test
    fun sleepOverlapUsesWiderSafetyWindow() {
        val range = SyncRangePlanner.planRange(
            today = today,
            maxLookbackDays = 30,
            latestStoredDate = "2026-06-10",
            overlapDays = SyncRangePlanner.SLEEP_OVERLAP_DAYS
        )
        assertEquals(LocalDate.parse("2026-06-08"), range.from)
        assertEquals(4, range.dayCountInclusive)
    }

    @Test
    fun configuredCapLimitsLongGaps() {
        val range = SyncRangePlanner.planRange(
            today = today,
            maxLookbackDays = 7,
            latestStoredDate = "2026-05-01",
            overlapDays = 1
        )
        assertEquals(LocalDate.parse("2026-06-04"), range.from)
        assertEquals(today, range.to)
    }

    @Test
    fun sameDayRefreshStillPullsToday() {
        val range = SyncRangePlanner.planRange(
            today = today,
            maxLookbackDays = 7,
            latestStoredDate = "2026-06-11",
            overlapDays = 1
        )
        assertEquals(LocalDate.parse("2026-06-10"), range.from)
        assertEquals(today, range.to)
        assertEquals(2, range.dayCountInclusive)
    }
}
