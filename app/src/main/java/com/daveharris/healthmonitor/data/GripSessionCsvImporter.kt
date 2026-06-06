package com.daveharris.healthmonitor.data

import java.io.BufferedReader
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.math.round

object GripSessionCsvImporter {
    fun isGripSessionCsvName(name: String): Boolean =
        name.matches(Regex("""grip_session_.*\.csv""", RegexOption.IGNORE_CASE)) ||
            name.matches(Regex("""grip_log_full.*\.csv""", RegexOption.IGNORE_CASE))

    fun nameMatchesDate(name: String, targetDate: String): Boolean =
        name.contains(targetDate) || name.matches(Regex("""grip_log_full.*\.csv""", RegexOption.IGNORE_CASE))

    fun parse(
        reader: BufferedReader,
        sourceName: String,
        importedAt: Long,
        targetDate: String? = null
    ): GripCsvImportResult {
        val rows = CsvRows.parse(reader)
        if (rows.isEmpty()) error("CSV is empty.")
        val headerIndex = rows.first()
            .mapIndexed { index, header -> normalizeHeader(header) to index }
            .toMap()
        if (!headerIndex.hasAny("value_kg", "valuekg", "kg", "value")) {
            error("CSV format not recognised. Expected a value_kg column.")
        }

        val parsedRows = rows.drop(1).mapIndexedNotNull { index, row ->
            parseRow(
                row = row,
                headerIndex = headerIndex,
                sourceName = sourceName,
                importedAt = importedAt,
                targetDate = targetDate,
                fallbackRepIndex = index + 1
            )
        }
        val filteredRows = targetDate?.let { date ->
            parsedRows.filter { it.sourceDate == date }
        } ?: parsedRows
        if (filteredRows.isEmpty()) {
            val dateMessage = targetDate?.let { " for $it" }.orEmpty()
            error("No usable grip rows$dateMessage were found in the CSV.")
        }

        val sessions = mutableListOf<GripSessionEntity>()
        val reps = mutableListOf<GripRepEntity>()
        filteredRows.groupBy { it.sessionId }.forEach { (sessionId, rowsForSession) ->
            val sortedRows = rowsForSession.sortedBy { it.repIndex }
            sessions += buildSession(sessionId, sortedRows, sourceName, importedAt)
            reps += sortedRows.map { row ->
                GripRepEntity(
                    sessionId = sessionId,
                    sourceDate = row.sourceDate,
                    repIndex = row.repIndex,
                    valueKg = row.valueKg,
                    repFlag = row.repFlag,
                    importedAtEpochMs = importedAt
                )
            }
        }
        return GripCsvImportResult(sessions = sessions, reps = reps)
    }

    private fun parseRow(
        row: List<String>,
        headerIndex: Map<String, Int>,
        sourceName: String,
        importedAt: Long,
        targetDate: String?,
        fallbackRepIndex: Int
    ): GripCsvRow? {
        val rawValue = row.value(headerIndex, "value_kg", "valuekg", "kg", "value")
        if (rawValue.isBlank()) return null
        val valueKg = rawValue.parsePositiveDouble()
            ?.takeIf { it <= 150.0 }
            ?: error("Grip value '$rawValue' is not a usable kg value.")
        val startedAt = row.value(headerIndex, "started_at", "startedat").ifBlank { null }
        val parsedStartedAt = startedAt?.let(::parseStartedAt)
        val sourceDate = parsedStartedAt?.sourceDate
            ?: targetDate
            ?: error("Grip CSV must include started_at when no target date is supplied.")
        val setNumber = row.value(headerIndex, "set_number", "setnumber").parseIntOrNull()
        val sessionId = row.value(headerIndex, "session_id", "sessionid").ifBlank {
            derivedSessionId(
                sourceName = sourceName,
                sourceDate = sourceDate,
                startedAt = startedAt,
                setNumber = setNumber
            )
        }
        val repIndex = row.value(headerIndex, "rep_index", "repindex", "rep").parseIntOrNull()
            ?: fallbackRepIndex
        if (repIndex < 1) error("Grip rep_index must be 1 or greater.")
        return GripCsvRow(
            sessionId = sessionId,
            sourceDate = sourceDate,
            startedAtEpochMs = parsedStartedAt?.epochMs,
            startedAtLocal = startedAt,
            hand = row.value(headerIndex, "hand").ifBlank { null },
            protocolLabel = row.value(headerIndex, "protocol_label", "protocollabel", "protocol").ifBlank { null },
            pullSeconds = row.value(headerIndex, "pull_seconds", "pullseconds").parsePositiveDouble(),
            restSeconds = row.value(headerIndex, "rest_seconds", "restseconds").parsePositiveDouble(),
            expectedRepCount = row.value(headerIndex, "rep_count", "repcount", "expected_rep_count").parseIntOrNull(),
            setNumber = setNumber,
            restGapMinutes = row.value(headerIndex, "rest_gap_minutes", "restgapminutes").parsePositiveDouble(),
            deviceLabel = row.value(headerIndex, "device_label", "devicelabel", "device").ifBlank { null },
            bodyPosition = row.value(headerIndex, "body_position", "bodyposition").ifBlank { null },
            armPosition = row.value(headerIndex, "arm_position", "armposition").ifBlank { null },
            handleSetting = row.value(headerIndex, "handle_setting", "handlesetting").ifBlank { null },
            notes = row.value(headerIndex, "notes", "note").ifBlank { null },
            repIndex = repIndex,
            valueKg = valueKg,
            repFlag = row.value(headerIndex, "rep_flag", "repflag", "flag").ifBlank { null },
            importedAtEpochMs = importedAt
        )
    }

    private fun buildSession(
        sessionId: String,
        rowsForSession: List<GripCsvRow>,
        sourceName: String,
        importedAt: Long
    ): GripSessionEntity {
        val first = rowsForSession.first()
        val values = rowsForSession.map { it.valueKg }
        val best = values.maxOrNull()
        val firstValue = values.firstOrNull()
        val lastValue = values.lastOrNull()
        val dropPct = if (best != null && best > 0.0 && lastValue != null) {
            roundToTwo(((best - lastValue) / best) * 100.0)
        } else {
            null
        }
        return GripSessionEntity(
            sessionId = sessionId,
            sourceDate = first.sourceDate,
            startedAtEpochMs = rowsForSession.firstNotNullOfOrNull { it.startedAtEpochMs },
            startedAtLocal = rowsForSession.firstNotNullOfOrNull { it.startedAtLocal },
            hand = rowsForSession.firstNotNullOfOrNull { it.hand },
            protocolLabel = rowsForSession.firstNotNullOfOrNull { it.protocolLabel },
            pullSeconds = rowsForSession.firstNotNullOfOrNull { it.pullSeconds },
            restSeconds = rowsForSession.firstNotNullOfOrNull { it.restSeconds },
            expectedRepCount = rowsForSession.firstNotNullOfOrNull { it.expectedRepCount },
            setNumber = rowsForSession.firstNotNullOfOrNull { it.setNumber },
            restGapMinutes = rowsForSession.firstNotNullOfOrNull { it.restGapMinutes },
            deviceLabel = rowsForSession.firstNotNullOfOrNull { it.deviceLabel },
            bodyPosition = rowsForSession.firstNotNullOfOrNull { it.bodyPosition },
            armPosition = rowsForSession.firstNotNullOfOrNull { it.armPosition },
            handleSetting = rowsForSession.firstNotNullOfOrNull { it.handleSetting },
            notes = rowsForSession.firstNotNullOfOrNull { it.notes },
            completedRepCount = values.size,
            bestValueKg = best,
            meanValueKg = values.takeIf { it.isNotEmpty() }?.average()?.let(::roundToTwo),
            firstValueKg = firstValue,
            lastValueKg = lastValue,
            bestToLastDropPct = dropPct,
            importSource = sourceName,
            importedAtEpochMs = importedAt
        )
    }

    private fun parseStartedAt(value: String): ParsedStartedAt {
        val offsetDateTime = runCatching { OffsetDateTime.parse(value) }.getOrNull()
        if (offsetDateTime != null) {
            return ParsedStartedAt(
                sourceDate = offsetDateTime.toLocalDate().toString(),
                epochMs = offsetDateTime.toInstant().toEpochMilli()
            )
        }
        val instant = runCatching { Instant.parse(value) }.getOrNull()
        if (instant != null) {
            return ParsedStartedAt(
                sourceDate = instant.atZone(ZoneId.systemDefault()).toLocalDate().toString(),
                epochMs = instant.toEpochMilli()
            )
        }
        val localDateTime = runCatching { LocalDateTime.parse(value) }.getOrNull()
        if (localDateTime != null) {
            val zoned = localDateTime.atZone(ZoneId.systemDefault())
            return ParsedStartedAt(
                sourceDate = zoned.toLocalDate().toString(),
                epochMs = zoned.toInstant().toEpochMilli()
            )
        }
        val localDate = runCatching { LocalDate.parse(value) }.getOrNull()
        if (localDate != null) {
            val zoned = localDate.atStartOfDay(ZoneId.systemDefault())
            return ParsedStartedAt(
                sourceDate = localDate.toString(),
                epochMs = zoned.toInstant().toEpochMilli()
            )
        }
        error("started_at '$value' is not a recognised ISO date/time.")
    }

    private fun derivedSessionId(
        sourceName: String,
        sourceDate: String,
        startedAt: String?,
        setNumber: Int?
    ): String {
        val basis = listOfNotNull(sourceName.substringBeforeLast('.'), startedAt, setNumber?.let { "set-$it" })
            .joinToString("-")
            .ifBlank { "grip-session" }
        return "grip-$sourceDate-${basis.replace(Regex("""[^A-Za-z0-9._-]+"""), "-").trim('-')}"
    }

    private fun normalizeHeader(value: String): String =
        value.trim().lowercase().replace(Regex("""[^a-z0-9]+"""), "_").trim('_')

    private fun Map<String, Int>.hasAny(vararg names: String): Boolean =
        names.any { containsKey(it) }

    private fun List<String>.value(headerIndex: Map<String, Int>, vararg names: String): String =
        names.firstNotNullOfOrNull { name ->
            headerIndex[name]?.let { index -> getOrNull(index)?.trim() }
        }.orEmpty()

    private fun String.parsePositiveDouble(): Double? =
        trim()
            .replace(",", ".")
            .replace(Regex("""(?i)\bkg\b|\bkilograms?\b"""), "")
            .trim()
            .toDoubleOrNull()
            ?.takeIf { it > 0.0 }

    private fun String.parseIntOrNull(): Int? = trim().toIntOrNull()

    private fun roundToTwo(value: Double): Double = round(value * 100.0) / 100.0

    data class GripCsvImportResult(
        val sessions: List<GripSessionEntity>,
        val reps: List<GripRepEntity>
    ) {
        val touchedDates: List<String> = sessions.map { it.sourceDate }.distinct()
        val sessionIds: List<String> = sessions.map { it.sessionId }.distinct()
    }

    private data class ParsedStartedAt(
        val sourceDate: String,
        val epochMs: Long
    )

    private data class GripCsvRow(
        val sessionId: String,
        val sourceDate: String,
        val startedAtEpochMs: Long?,
        val startedAtLocal: String?,
        val hand: String?,
        val protocolLabel: String?,
        val pullSeconds: Double?,
        val restSeconds: Double?,
        val expectedRepCount: Int?,
        val setNumber: Int?,
        val restGapMinutes: Double?,
        val deviceLabel: String?,
        val bodyPosition: String?,
        val armPosition: String?,
        val handleSetting: String?,
        val notes: String?,
        val repIndex: Int,
        val valueKg: Double,
        val repFlag: String?,
        val importedAtEpochMs: Long
    )
}
