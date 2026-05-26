package com.daveharris.healthmonitor.data

import java.io.BufferedReader
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals

class FoodCsvImporterTest {
    @Test
    fun parseRoundsDecimalCalories() {
        val csv = """
            date,time_local,item,quantity,calories_kcal,notes
            2026-05-26,08:15,Porridge,1 bowl,315.6,
            2026-05-26,12:30,Soup,1 bowl,184.4,
        """.trimIndent()

        val result = FoodCsvImporter.parse(
            reader = BufferedReader(StringReader(csv)),
            sourceName = "food_log_full_test.csv",
            importedAt = 1000L,
            targetDate = "2026-05-26"
        )

        assertEquals(listOf(316, 184), result.foodItems.map { it.caloriesKcal })
        assertEquals(500, FoodCsvImporter.buildDailySummary("2026-05-26", result.foodItems).totalCaloriesKcal)
    }

    @Test
    fun parseFiltersTargetDate() {
        val csv = """
            date,time_local,item,quantity,calories_kcal,notes
            2026-05-25,08:15,Toast,1 slice,90,
            2026-05-26,08:15,Porridge,1 bowl,316,
        """.trimIndent()

        val result = FoodCsvImporter.parse(
            reader = BufferedReader(StringReader(csv)),
            sourceName = "food_log_full_test.csv",
            importedAt = 1000L,
            targetDate = "2026-05-26"
        )

        assertEquals(listOf("2026-05-26"), result.touchedDates)
        assertEquals(listOf("Porridge"), result.foodItems.map { it.item })
    }
}
