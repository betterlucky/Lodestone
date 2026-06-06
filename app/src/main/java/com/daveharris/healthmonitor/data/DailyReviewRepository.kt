package com.daveharris.healthmonitor.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DailyReviewRepository(
    private val database: AppDatabase
) {
    private val dao = database.probeDao()

    val dailyCheckIns = dao.observeDailyCheckIns()
    val foodDailySummaries = dao.observeFoodDailySummaries()
    val dailyWeights = dao.observeDailyWeights()
    val gripSessions = dao.observeGripSessions()

    suspend fun saveDailyCheckIn(
        sourceDate: String,
        eveningOutcome: String,
        approachToDay: String?,
        muscleWeaknessToday: Boolean,
        notes: String?,
        dayShapeCaptured: Boolean?,
        mostlyHorizontal: Boolean?,
        leftHouse: Boolean?,
        majorTask: Boolean?,
        majorTaskType: String?,
        pemPaybackToday: Boolean?,
        paybackPeakToday: Boolean?,
        paybackPeakConfidence: String?,
        manualGripStrengthKg: Double?
    ) {
        val now = System.currentTimeMillis()
        val existing = dao.getDailyCheckIn(sourceDate)
        dao.upsertDailyCheckIn(
            DailyCheckInEntity(
                sourceDate = sourceDate,
                eveningOutcome = eveningOutcome,
                approachToDay = approachToDay,
                muscleWeaknessToday = muscleWeaknessToday,
                notes = notes?.takeIf { it.isNotBlank() },
                createdAtEpochMs = existing?.createdAtEpochMs ?: now,
                updatedAtEpochMs = now,
                dayShapeCaptured = dayShapeCaptured,
                mostlyHorizontal = mostlyHorizontal,
                leftHouse = leftHouse,
                majorTask = majorTask,
                majorTaskType = majorTaskType,
                pemPaybackToday = pemPaybackToday,
                paybackPeakToday = paybackPeakToday,
                paybackPeakConfidence = paybackPeakConfidence,
                manualGripStrengthKg = manualGripStrengthKg
            )
        )
    }

    suspend fun getDailyCheckIn(sourceDate: String): DailyCheckInEntity? =
        dao.getDailyCheckIn(sourceDate)

    suspend fun updatePaybackPeakMarker(
        sourceDate: String,
        paybackPeakToday: Boolean,
        paybackPeakConfidence: String
    ) {
        dao.updatePaybackPeakColumns(
            sourceDate = sourceDate,
            paybackPeakToday = paybackPeakToday,
            paybackPeakConfidence = paybackPeakConfidence,
            updatedAtEpochMs = System.currentTimeMillis()
        )
    }

    suspend fun getFoodDailySummary(sourceDate: String): FoodDailySummaryEntity? =
        dao.getFoodDailySummary(sourceDate)

    suspend fun getDailyWeight(sourceDate: String): DailyWeightEntity? =
        dao.getDailyWeight(sourceDate)

    suspend fun getGripSessions(sourceDate: String): List<GripSessionEntity> =
        dao.getGripSessionsForDate(sourceDate)

    suspend fun clearFoodImportForDate(sourceDate: String) = withContext(Dispatchers.IO) {
        database.withTransaction {
            dao.deleteFoodLogItemsForDates(listOf(sourceDate))
            dao.deleteFoodDailySummaryForDate(sourceDate)
            dao.deleteDailyWeightsForDates(listOf(sourceDate))
        }
    }

    suspend fun importFoodCsv(context: Context, uri: Uri, targetDate: String? = null): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val displayName = resolveDisplayName(context, uri)
            val importedAt = System.currentTimeMillis()
            val result = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                FoodCsvImporter.parse(
                    reader = reader,
                    sourceName = displayName,
                    importedAt = importedAt,
                    targetDate = targetDate
                )
            } ?: error("Unable to open CSV.")
            val touchedDates = result.touchedDates
            val latestWeightsByDate = result.weights
                .groupBy { it.sourceDate }
                .values
                .map { weights -> weights.maxBy { it.measuredTime.orEmpty() } }
            database.withTransaction {
                dao.deleteFoodLogItemsForDates(touchedDates)
                touchedDates.forEach { dao.deleteFoodDailySummaryForDate(it) }
                dao.deleteDailyWeightsForDates(touchedDates)
                if (result.foodItems.isNotEmpty()) {
                    dao.upsertFoodLogItems(result.foodItems)
                }
                if (latestWeightsByDate.isNotEmpty()) {
                    dao.upsertDailyWeights(latestWeightsByDate)
                }
                val summaries = touchedDates.mapNotNull { date ->
                    val items = dao.getFoodLogItemsForDate(date)
                    if (items.isEmpty()) {
                        null
                    } else {
                        FoodCsvImporter.buildDailySummary(sourceDate = date, items = items)
                    }
                }
                if (summaries.isNotEmpty()) {
                    dao.upsertFoodDailySummaries(summaries)
                }
            }
            touchedDates.size
        }
    }

    suspend fun importLatestFoodCsvFromDownloads(context: Context, targetDate: String? = null): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            importLatestFoodCsvFromSavedFolder(context, targetDate).getOrNull()
                ?: error("No food_log CSV was found in the authorised FoodLogData folder.")
        }
    }

    fun saveFoodFolder(context: Context, uri: Uri) {
        context.getSharedPreferences("food_import", Context.MODE_PRIVATE)
            .edit()
            .putString("folder_uri", uri.toString())
            .apply()
    }

    suspend fun importGripCsv(context: Context, uri: Uri, targetDate: String? = null): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val displayName = resolveDisplayName(context, uri)
            val importedAt = System.currentTimeMillis()
            val result = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                GripSessionCsvImporter.parse(
                    reader = reader,
                    sourceName = displayName,
                    importedAt = importedAt,
                    targetDate = targetDate
                )
            } ?: error("Unable to open grip CSV.")
            database.withTransaction {
                dao.deleteGripRepsForSessions(result.sessionIds)
                dao.deleteGripSessions(result.sessionIds)
                dao.upsertGripSessions(result.sessions)
                dao.upsertGripReps(result.reps)
            }
            result.sessions.size
        }
    }

    suspend fun importLatestGripCsvFromSavedFolder(context: Context, targetDate: String? = null): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val folderUri = context.getSharedPreferences("grip_import", Context.MODE_PRIVATE)
                .getString("folder_uri", null)
                ?.let(Uri::parse)
                ?: error("No GripRecorderData folder has been authorised.")
            val folder = DocumentFile.fromTreeUri(context, folderUri)
                ?: error("Unable to read the authorised GripRecorderData folder.")
            val candidates = folder.listFiles()
                .filter { it.isFile && GripSessionCsvImporter.isGripSessionCsvName(it.name.orEmpty()) }
            val datedCandidates = targetDate?.let { date ->
                candidates.filter { GripSessionCsvImporter.nameMatchesDate(it.name.orEmpty(), date) }
            } ?: candidates
            val latest = datedCandidates.maxByOrNull { it.lastModified() }
                ?: error("No grip_session CSV was found in the authorised GripRecorderData folder.")
            importGripCsv(context, latest.uri, targetDate).getOrThrow()
        }
    }

    fun saveGripFolder(context: Context, uri: Uri) {
        context.getSharedPreferences("grip_import", Context.MODE_PRIVATE)
            .edit()
            .putString("folder_uri", uri.toString())
            .apply()
    }

    private suspend fun importLatestFoodCsvFromSavedFolder(context: Context, targetDate: String?): Result<Int?> = withContext(Dispatchers.IO) {
        runCatching {
            val folderUri = context.getSharedPreferences("food_import", Context.MODE_PRIVATE)
                .getString("folder_uri", null)
                ?.let(Uri::parse)
                ?: return@runCatching null
            val folder = DocumentFile.fromTreeUri(context, folderUri) ?: return@runCatching null
            val candidates = folder.listFiles()
                .filter { it.isFile && FoodCsvImporter.isFoodLogCsvName(it.name.orEmpty()) }
            val latest = if (targetDate != null) {
                candidates
                    .filter { FoodCsvImporter.nameMatchesDate(it.name.orEmpty(), targetDate) }
                    .maxByOrNull { it.lastModified() }
            } else {
                candidates.maxByOrNull { it.lastModified() }
            } ?: return@runCatching null
            importFoodCsv(context, latest.uri, targetDate).getOrThrow()
        }
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (columnIndex >= 0) {
                    return cursor.getString(columnIndex)
                }
            }
        }
        return uri.lastPathSegment ?: "food-import.csv"
    }
}
