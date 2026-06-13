package com.daveharris.healthmonitor.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CurrentStateFeaturesTest {

    @Test
    fun outcomeLevelMapsJournalStringsCaseInsensitively() {
        assertEquals(TrafficLightStatus.GOOD, CurrentStateFeatures.outcomeLevel("GOOD"))
        assertEquals(TrafficLightStatus.UNSTEADY, CurrentStateFeatures.outcomeLevel(" unsteady "))
        assertNull(CurrentStateFeatures.outcomeLevel("not_set"))
        assertNull(CurrentStateFeatures.outcomeLevel(null))
    }

    @Test
    fun mvActiveMinutesSumsModerateAndVigorous() {
        val json = """
            {"activityClassTimes":{
                "timeContinuousModerateActivity":{"hours":0,"minutes":20},
                "timeIntermittentModerateActivity":{"hours":0,"minutes":10},
                "timeContinuousVigorousActivity":{"hours":1,"minutes":0},
                "timeIntermittentVigorousActivity":{"hours":0,"minutes":5}
            }}
        """.trimIndent()
        assertEquals(95, CurrentStateFeatures.mvActiveMinutes(json))
    }

    @Test
    fun mvActiveMinutesReturnsNullOnGarbage() {
        assertNull(CurrentStateFeatures.mvActiveMinutes(null))
        assertNull(CurrentStateFeatures.mvActiveMinutes("not json"))
        assertEquals(0, CurrentStateFeatures.mvActiveMinutes("""{"activityClassTimes":{}}"""))
    }

    @Test
    fun hrvCv24hNullBelowMinimumEpochCount() {
        val few = List(CurrentStateFeatures.MIN_GOOD_EPOCHS_FOR_CV - 1) { 50.0 }
        assertNull(CurrentStateFeatures.hrvCv24h(few))
    }

    @Test
    fun hrvCv24hComputesCoefficientWhenEnoughEpochs() {
        // Spiky series -> non-trivial CV; flat series -> ~0.
        val spiky = listOf(20.0, 80.0, 20.0, 80.0, 20.0, 80.0, 20.0, 80.0, 20.0, 80.0, 20.0, 80.0, 20.0, 80.0)
        val flat = List(14) { 50.0 }
        val spikyCv = CurrentStateFeatures.hrvCv24h(spiky)
        val flatCv = CurrentStateFeatures.hrvCv24h(flat)
        assertTrue(spikyCv != null && spikyCv > 0.0)
        assertTrue(flatCv != null && flatCv < 0.01)
    }

    @Test
    fun daysBetweenCountsCalendarDays() {
        assertEquals(2, CurrentStateFeatures.daysBetween("2026-06-11", "2026-06-13"))
        assertEquals(0, CurrentStateFeatures.daysBetween("2026-06-13", "2026-06-13"))
        assertNull(CurrentStateFeatures.daysBetween(null, "2026-06-13"))
    }
}
