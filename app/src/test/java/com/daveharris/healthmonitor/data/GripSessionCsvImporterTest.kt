package com.daveharris.healthmonitor.data

import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GripSessionCsvImporterTest {
    @Test
    fun parsesFullGripSessionSchema() {
        val csv = """
            session_id,started_at,hand,protocol_label,pull_seconds,rest_seconds,rep_count,set_number,rest_gap_minutes,rep_index,value_kg,rep_flag
            s1,2026-06-06T09:14:22+01:00,left,check_2,3,5,2,1,,1,31.1,
            s1,2026-06-06T09:14:22+01:00,left,check_2,3,5,2,1,,2,29.4,late
        """.trimIndent()

        val result = parse(csv, targetDate = "2026-06-06")

        assertEquals(1, result.sessions.size)
        val session = result.sessions.single()
        assertEquals("s1", session.sessionId)
        assertEquals("2026-06-06", session.sourceDate)
        assertEquals("left", session.hand)
        assertEquals("check_2", session.protocolLabel)
        assertEquals(2, session.completedRepCount)
        assertEquals(31.1, session.bestValueKg)
        assertEquals(30.25, session.meanValueKg)
        assertEquals(5.47, session.bestToLastDropPct)
        assertEquals(2, result.reps.size)
        assertEquals("late", result.reps.last().repFlag)
    }

    @Test
    fun parsesCurrentValuesOnlyExportWhenTargetDateIsProvided() {
        val csv = """
            value_kg
            35.1
            29
            33.6
        """.trimIndent()

        val result = parse(csv, sourceName = "grip_session_2026-06-06T09-14-22_left_check_2.csv", targetDate = "2026-06-06")

        assertEquals(1, result.sessions.size)
        val session = result.sessions.single()
        assertTrue(session.sessionId.startsWith("grip-2026-06-06-grip_session_2026-06-06T09-14-22_left_check_2"))
        assertEquals("2026-06-06", session.sourceDate)
        assertEquals(3, session.completedRepCount)
        assertEquals(35.1, session.bestValueKg)
        assertEquals(listOf(1, 2, 3), result.reps.map { it.repIndex })
    }

    @Test
    fun filtersFullExportToSelectedDate() {
        val csv = """
            session_id,started_at,rep_index,value_kg
            older,2026-06-05T09:14:22+01:00,1,30
            selected,2026-06-06T09:14:22+01:00,1,31
        """.trimIndent()

        val result = parse(csv, targetDate = "2026-06-06")

        assertEquals(listOf("selected"), result.sessions.map { it.sessionId })
        assertEquals(listOf("selected"), result.reps.map { it.sessionId }.distinct())
    }

    private fun parse(
        csv: String,
        sourceName: String = "grip_session_2026-06-06T09-14-22_left_check_2.csv",
        targetDate: String?
    ): GripSessionCsvImporter.GripCsvImportResult =
        GripSessionCsvImporter.parse(
            reader = StringReader(csv).buffered(),
            sourceName = sourceName,
            importedAt = 123L,
            targetDate = targetDate
        )
}
