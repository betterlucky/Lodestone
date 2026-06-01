package com.daveharris.healthmonitor.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GripStrengthInputTest {
    @Test
    fun sanitizesManualGripInputForDecimalKeyboards() {
        assertEquals("25.5", sanitizeGripStrengthInput("25,5"))
        assertEquals("25.5", sanitizeGripStrengthInput("25..5"))
        assertEquals("123.45", sanitizeGripStrengthInput("123.4567"))
        assertEquals("30.0", sanitizeGripStrengthInput(" grip 30.0kg "))
    }

    @Test
    fun storesOnlyPlausibleGripStrengthValues() {
        assertEquals(28.5, gripStrengthKgOrNull("28.5"))
        assertEquals(0.1, gripStrengthKgOrNull("0.1"))
        assertEquals(150.0, gripStrengthKgOrNull("150.0"))
        assertNull(gripStrengthKgOrNull(""))
        assertNull(gripStrengthKgOrNull("."))
        assertNull(gripStrengthKgOrNull("0"))
        assertNull(gripStrengthKgOrNull("150.1"))
        assertNull(gripStrengthKgOrNull("999.0"))
    }
}
