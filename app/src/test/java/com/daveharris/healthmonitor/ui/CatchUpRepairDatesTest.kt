package com.daveharris.healthmonitor.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class CatchUpRepairDatesTest {
    @Test
    fun noLatestReadUsesTodayOnly() {
        assertEquals(
            listOf("2026-05-28"),
            catchUpRepairSourceDates(today = "2026-05-28", latestReadSourceDate = null)
        )
    }

    @Test
    fun latestReadBeforeTodayReturnsMissingDatesOldestToNewest() {
        assertEquals(
            listOf("2026-05-26", "2026-05-27", "2026-05-28"),
            catchUpRepairSourceDates(today = "2026-05-28", latestReadSourceDate = "2026-05-25")
        )
    }

    @Test
    fun longGapsAreCappedToSevenDates() {
        assertEquals(
            listOf(
                "2026-05-22",
                "2026-05-23",
                "2026-05-24",
                "2026-05-25",
                "2026-05-26",
                "2026-05-27",
                "2026-05-28"
            ),
            catchUpRepairSourceDates(today = "2026-05-28", latestReadSourceDate = "2026-05-01")
        )
    }

    @Test
    fun futureLatestReadFallsBackToTodayOnly() {
        assertEquals(
            listOf("2026-05-28"),
            catchUpRepairSourceDates(today = "2026-05-28", latestReadSourceDate = "2026-05-29")
        )
    }
}
