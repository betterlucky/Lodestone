package com.daveharris.healthmonitor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MorningReadSchedulerTest {
    @Test
    fun maxAttemptsAreBoundedByRetryStage() {
        assertEquals(3, MorningReadScheduler.maxAttempts(MorningRetryStage.PPI))
        assertEquals(3, MorningReadScheduler.maxAttempts(MorningRetryStage.SLEEP_REPORT))
    }

    @Test
    fun retryStageParsesOnlyKnownWireNames() {
        assertEquals(MorningRetryStage.PPI, MorningReadScheduler.fromWireName("PPI"))
        assertEquals(MorningRetryStage.SLEEP_REPORT, MorningReadScheduler.fromWireName("SLEEP_REPORT"))
        assertNull(MorningReadScheduler.fromWireName("ppi"))
        assertNull(MorningReadScheduler.fromWireName(null))
    }
}
