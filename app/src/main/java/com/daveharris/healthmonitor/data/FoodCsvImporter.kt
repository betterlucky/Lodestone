package com.daveharris.healthmonitor.data

import com.daveharris.healthmonitor.util.GsonProvider
import java.io.BufferedReader
import kotlin.math.roundToInt

object FoodCsvImporter {
    private val expectedHeaders = listOf("date", "time_local", "item", "quantity", "calories_kcal", "notes")

    fun isFoodLogCsvName(name: String): Boolean =
        name.matches(Regex("""food_log_\d{4}-\d{2}-\d{2}.*\.csv""")) ||
            name.startsWith("food_log_full_") && name.endsWith(".csv")

    fun nameMatchesDate(name: String, targetDate: String): Boolean =
        name.matches(Regex("""food_log_${Regex.escape(targetDate)}.*\.csv""")) ||
            name.startsWith("food_log_full_")

    fun parse(
        reader: BufferedReader,
        sourceName: String,
        importedAt: Long,
        targetDate: String? = null
    ): FoodCsvImportResult {
        val rows = parseCsv(reader)
        if (rows.isEmpty()) error("CSV is empty.")
        val headers = rows.first().map { it.trim() }
        if (headers != expectedHeaders) {
            error("CSV format not recognised. Expected the full food log export columns.")
        }
        val allEntries = parseFoodFullCsv(rows.drop(1), sourceName, importedAt)
        val entries = targetDate?.let { date ->
            allEntries.filter { it.sourceDate == date }
        } ?: allEntries
        if (entries.isEmpty()) {
            val dateMessage = targetDate?.let { " for $it" }.orEmpty()
            error("No usable food or weight rows$dateMessage were found in the CSV.")
        }
        return FoodCsvImportResult(
            foodItems = entries.mapNotNull { it.foodItem },
            weights = entries.mapNotNull { it.weight }
        )
    }

    fun buildDailySummary(
        sourceDate: String,
        items: List<FoodLogItemEntity>
    ): FoodDailySummaryEntity {
        val sortedTimes = items.mapNotNull { it.timeLocal.ifBlank { null } }.sorted()
        val firstTime = sortedTimes.firstOrNull()
        val lastTime = sortedTimes.lastOrNull()
        val latestImport = items.maxByOrNull { it.importedAtEpochMs }
        return FoodDailySummaryEntity(
            sourceDate = sourceDate,
            totalCaloriesKcal = items.sumOf { it.caloriesKcal ?: 0 },
            eventCount = items.size,
            teaCount = items.count { it.item.equals("Tea", ignoreCase = true) },
            firstIntakeTime = firstTime,
            lastIntakeTime = lastTime,
            eatingWindowHours = if (firstTime != null && lastTime != null && firstTime != lastTime) {
                timeDifferenceHours(firstTime, lastTime)
            } else {
                0.0
            },
            rawItemsJson = GsonProvider.gson.toJson(
                items.map {
                    FoodLogEntry(
                        date = it.sourceDate,
                        timeLocal = it.timeLocal.ifBlank { null },
                        item = it.item,
                        quantity = it.quantity,
                        caloriesKcal = it.caloriesKcal,
                        notes = it.notes
                    )
                }
            ),
            importSource = latestImport?.importSource,
            importedAtEpochMs = latestImport?.importedAtEpochMs ?: System.currentTimeMillis()
        )
    }

    private fun parseFoodFullCsv(
        rows: List<List<String>>,
        sourceName: String,
        importedAt: Long
    ): List<ParsedFoodCsvEntry> =
        rows.mapNotNull { row ->
            val date = row.getOrNull(0)?.trim().orEmpty()
            val item = row.getOrNull(2)?.trim().orEmpty()
            if (date.isBlank() || item.isBlank()) return@mapNotNull null
            val timeLocal = row.getOrNull(1)?.trim().orEmpty()
            val quantity = row.getOrNull(3)?.trim().orEmpty()
            val notes = row.getOrNull(5)?.trim().orEmpty().ifBlank { null }
            if (item.equals("weight", ignoreCase = true)) {
                val weightKg = parseWeightKg(quantity)
                    ?: error("Weight row for $date does not include a usable kg value.")
                ParsedFoodCsvEntry(
                    sourceDate = date,
                    foodItem = null,
                    weight = DailyWeightEntity(
                        sourceDate = date,
                        measuredTime = timeLocal.ifBlank { null },
                        weightKg = weightKg,
                        notes = notes,
                        importSource = sourceName,
                        importedAtEpochMs = importedAt
                    )
                )
            } else {
                ParsedFoodCsvEntry(
                    sourceDate = date,
                    foodItem = FoodLogItemEntity(
                        fingerprint = foodFingerprint(
                            sourceDate = date,
                            timeLocal = timeLocal,
                            item = item,
                            quantity = quantity
                        ),
                        sourceDate = date,
                        timeLocal = timeLocal,
                        item = item,
                        quantity = quantity,
                        caloriesKcal = parseCaloriesKcal(row.getOrNull(4)),
                        notes = notes,
                        importSource = sourceName,
                        importedAtEpochMs = importedAt
                    ),
                    weight = null
                )
            }
        }

    private fun parseCaloriesKcal(value: String?): Int? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isBlank()) return null
        return trimmed.toIntOrNull() ?: trimmed.toDoubleOrNull()?.roundToInt()
    }

    private fun parseWeightKg(quantity: String): Double? {
        val normalized = quantity.trim().lowercase()
        val kgMatch = Regex("""([-+]?\d+(?:\.\d+)?)\s*kg\b""").find(normalized)
        return (kgMatch ?: Regex("""([-+]?\d+(?:\.\d+)?)""").find(normalized))
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
            ?.takeIf { it > 0.0 }
    }

    data class FoodCsvImportResult(
        val foodItems: List<FoodLogItemEntity>,
        val weights: List<DailyWeightEntity>
    ) {
        val touchedDates: List<String> = (foodItems.map { it.sourceDate } + weights.map { it.sourceDate })
            .distinct()
    }

    private data class ParsedFoodCsvEntry(
        val sourceDate: String,
        val foodItem: FoodLogItemEntity?,
        val weight: DailyWeightEntity?
    )

    private fun foodFingerprint(
        sourceDate: String,
        timeLocal: String,
        item: String,
        quantity: String
    ): String = listOf(
        sourceDate.trim(),
        timeLocal.trim(),
        item.trim().lowercase(),
        quantity.trim().lowercase()
    ).joinToString("|")

    private fun parseCsv(reader: BufferedReader): List<List<String>> {
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        val rows = mutableListOf<List<String>>()

        fun commitCell() {
            cells += cell.toString()
            cell.clear()
        }

        fun commitRow() {
            if (cells.isEmpty() && cell.isEmpty()) return
            commitCell()
            if (cells.any { it.isNotBlank() }) {
                rows += cells.toList()
            }
            cells.clear()
        }

        var next = reader.read()
        while (next != -1) {
            val char = next.toChar()
            when {
                char == '"' && inQuotes -> {
                    reader.mark(1)
                    val peek = reader.read()
                    if (peek == '"'.code) {
                        cell.append('"')
                    } else {
                        inQuotes = false
                        if (peek != -1) {
                            reader.reset()
                        }
                    }
                }
                char == '"' -> inQuotes = true
                char == ',' && !inQuotes -> commitCell()
                (char == '\n' || char == '\r') && !inQuotes -> {
                    commitRow()
                    if (char == '\r') {
                        reader.mark(1)
                        val peek = reader.read()
                        if (peek != '\n'.code && peek != -1) {
                            reader.reset()
                        }
                    }
                }
                else -> cell.append(char)
            }
            next = reader.read()
        }
        commitRow()
        return rows
    }

    private fun timeDifferenceHours(start: String, end: String): Double {
        val startParts = start.split(":").mapNotNull { it.toIntOrNull() }
        val endParts = end.split(":").mapNotNull { it.toIntOrNull() }
        if (startParts.size != 2 || endParts.size != 2) return 0.0
        val startMinutes = (startParts[0] * 60) + startParts[1]
        val endMinutes = (endParts[0] * 60) + endParts[1]
        return ((endMinutes - startMinutes).coerceAtLeast(0)) / 60.0
    }

    private data class FoodLogEntry(
        val date: String,
        val timeLocal: String?,
        val item: String,
        val quantity: String,
        val caloriesKcal: Int?,
        val notes: String?
    )
}
