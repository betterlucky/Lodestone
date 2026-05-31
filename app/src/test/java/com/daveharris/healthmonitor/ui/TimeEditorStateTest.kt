package com.daveharris.healthmonitor.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimeEditorStateTest {
    private val zone = ZoneId.of("Europe/London")

    @Test
    fun windowPreviewFormatsCrossingMidnight() {
        val start = TimeEditorValue(LocalDate.parse("2026-05-26"), hour = 22, minute = 45)
        val end = TimeEditorValue(LocalDate.parse("2026-05-27"), hour = 6, minute = 5)

        assertEquals("7h 20m", windowDurationLabel(start, end, zone))
        assertEquals(
            "2026-05-26 22:45 -> 2026-05-27 06:05 · 7h 20m",
            windowPreviewLabel(start, end, zone)
        )
    }

    @Test
    fun exactMinuteAndTwentyFourHourEntryRoundTripsToEpoch() {
        val value = TimeEditorValue(LocalDate.parse("2026-05-27"), hour = 13, minute = 7)
        val epochMs = value.toEpochMs(zone)

        assertEquals("2026-05-27T12:07:00Z", Instant.ofEpochMilli(epochMs).toString())
        assertEquals(value, TimeEditorValue.fromEpochMs(epochMs, zone))
    }

    @Test
    fun endBeforeStartIsInvalid() {
        val start = TimeEditorValue(LocalDate.parse("2026-05-27"), hour = 6, minute = 0)
        val end = TimeEditorValue(LocalDate.parse("2026-05-27"), hour = 5, minute = 59)

        val validation = validateWindowEditor(start, end, zone)

        assertFalse(validation.canSave)
        assertFalse(validation.isWarning)
        assertEquals("End must be after start.", validation.message)
    }

    @Test
    fun suspiciouslyLongWindowWarnsButCanSave() {
        val start = TimeEditorValue(LocalDate.parse("2026-05-26"), hour = 10, minute = 0)
        val end = TimeEditorValue(LocalDate.parse("2026-05-27"), hour = 7, minute = 30)

        val validation = validateWindowEditor(start, end, zone)

        assertTrue(validation.canSave)
        assertTrue(validation.isWarning)
        assertEquals("This is longer than 18h. Save it if that is right.", validation.message)
    }

    @Test
    fun quickActionsUseRelativeTimeWithoutDroppingMinutes() {
        val now = TimeEditorValue(LocalDate.parse("2026-05-27"), hour = 0, minute = 10).toEpochMs(zone)

        val thirtyAgo = timeEditorValueForQuickAction(now, minutesAgo = 30, zone)

        assertEquals(TimeEditorValue(LocalDate.parse("2026-05-26"), hour = 23, minute = 40), thirtyAgo)
    }
}
