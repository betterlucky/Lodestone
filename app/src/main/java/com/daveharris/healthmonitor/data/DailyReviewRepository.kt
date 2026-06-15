package com.daveharris.healthmonitor.data

class DailyReviewRepository(
    private val database: AppDatabase
) {
    private val dao = database.probeDao()

    val dailyCheckIns = dao.observeDailyCheckIns()

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
        paybackPeakConfidence: String?
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
                // Manual grip capture is retired from the app; preserve any value
                // already on disk rather than nulling it on edit.
                manualGripStrengthKg = existing?.manualGripStrengthKg
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
}
