package com.daveharris.healthmonitor.data

import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals

class CsvRowsTest {
    @Test
    fun treatsQuotesInsideUnquotedFieldsAsLiteralText() {
        val rows = parse(
            """
            name,notes,extra
            alpha,a"b,c
            """.trimIndent()
        )

        assertEquals(listOf("alpha", "a\"b", "c"), rows[1])
    }

    @Test
    fun parsesQuotedFieldsAtFieldStart() {
        val rows = parse(
            """
            name,notes,extra
            alpha,"a,b",c
            """.trimIndent()
        )

        assertEquals(listOf("alpha", "a,b", "c"), rows[1])
    }

    @Test
    fun parsesEscapedQuotesInsideQuotedFields() {
        val rows = parse(
            """
            name,notes,extra
            alpha,"a ""quoted"" value",c
            """.trimIndent()
        )

        assertEquals(listOf("alpha", "a \"quoted\" value", "c"), rows[1])
    }

    private fun parse(csv: String): List<List<String>> =
        CsvRows.parse(StringReader(csv).buffered())
}
