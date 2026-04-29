package com.daveharris.healthmonitor.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProbeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDeviceProfile(entity: DeviceProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFtuProfile(entity: FtuProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertObservedCapability(entity: ObservedCapabilityEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAppSettings(entity: AppSettingsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDailyCheckIn(entity: DailyCheckInEntity)

    @Insert
    suspend fun insertWakeMarker(entity: WakeMarkerEntity): Long

    @Query(
        """
        SELECT * FROM wake_marker
        WHERE sourceDate = :sourceDate AND markerSource = :markerSource
        ORDER BY markerEpochMs DESC
        LIMIT 1
        """
    )
    suspend fun getLatestWakeMarker(sourceDate: String, markerSource: String): WakeMarkerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFoodDailySummaries(entities: List<FoodDailySummaryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFoodLogItems(entities: List<FoodLogItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDailyWeights(entities: List<DailyWeightEntity>)

    @Query("DELETE FROM food_log_item WHERE sourceDate IN (:sourceDates)")
    suspend fun deleteFoodLogItemsForDates(sourceDates: List<String>)

    @Query("DELETE FROM food_daily_summary WHERE sourceDate = :sourceDate")
    suspend fun deleteFoodDailySummaryForDate(sourceDate: String)

    @Query("DELETE FROM daily_weight WHERE sourceDate IN (:sourceDates)")
    suspend fun deleteDailyWeightsForDates(sourceDates: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOfflineRecordingSessions(entities: List<OfflineRecordingSessionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOfflinePpiEpochs(entities: List<OfflinePpiEpochEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPpi247Epochs(entities: List<Ppi247EpochEntity>)

    @Insert
    suspend fun insertSyncRun(entity: SyncRunEntity): Long

    @Update
    suspend fun updateSyncRun(entity: SyncRunEntity)

    @Insert
    suspend fun insertSyncDomainResult(entity: SyncDomainResultEntity): Long

    @Insert
    suspend fun insertSleepRecords(records: List<SleepNightRawEntity>)

    @Insert
    suspend fun insertNightlyRechargeRecords(records: List<NightlyRechargeRawEntity>)

    @Insert
    suspend fun insertHrRecords(records: List<Hr247DayRawEntity>)

    @Insert
    suspend fun insertPpiRecords(records: List<Ppi247DayRawEntity>)

    @Insert
    suspend fun insertSkinTemperatureRecords(records: List<SkinTemperatureRawEntity>)

    @Insert
    suspend fun insertDailySummaryRecords(records: List<DailySummaryRawEntity>)

    @Insert
    suspend fun insertActivitySampleRecords(records: List<ActivitySamplesRawEntity>)

    @Query("SELECT rawPayloadJson FROM sleep_night_raw WHERE deviceId = :deviceId")
    suspend fun getExistingSleepPayloads(deviceId: String): List<String>

    @Query("SELECT rawPayloadJson FROM nightly_recharge_raw WHERE deviceId = :deviceId")
    suspend fun getExistingNightlyRechargePayloads(deviceId: String): List<String>

    @Query("SELECT rawPayloadJson FROM hr247_day_raw WHERE deviceId = :deviceId")
    suspend fun getExistingHrPayloads(deviceId: String): List<String>

    @Query("SELECT rawPayloadJson FROM ppi247_day_raw WHERE deviceId = :deviceId")
    suspend fun getExistingPpiPayloads(deviceId: String): List<String>

    @Query("SELECT rawPayloadJson FROM skin_temperature_raw WHERE deviceId = :deviceId")
    suspend fun getExistingSkinTemperaturePayloads(deviceId: String): List<String>

    @Query("SELECT rawPayloadJson FROM daily_summary_raw WHERE deviceId = :deviceId")
    suspend fun getExistingDailySummaryPayloads(deviceId: String): List<String>

    @Query("SELECT rawPayloadJson FROM activity_samples_raw WHERE deviceId = :deviceId")
    suspend fun getExistingActivitySamplePayloads(deviceId: String): List<String>

    @Query("DELETE FROM sleep_night_raw WHERE deviceId = :deviceId AND sourceDate = :sourceDate")
    suspend fun deleteSleepRecordsForDate(deviceId: String, sourceDate: String?)

    @Query("DELETE FROM nightly_recharge_raw WHERE deviceId = :deviceId AND sourceDate = :sourceDate")
    suspend fun deleteNightlyRechargeRecordsForDate(deviceId: String, sourceDate: String?)

    @Query("DELETE FROM hr247_day_raw WHERE deviceId = :deviceId AND sourceDate = :sourceDate")
    suspend fun deleteHrRecordsForDate(deviceId: String, sourceDate: String)

    @Query("DELETE FROM ppi247_day_raw WHERE deviceId = :deviceId AND sourceDate = :sourceDate AND keySummary = :keySummary")
    suspend fun deletePpiRecordsForDateAndKeySummary(deviceId: String, sourceDate: String, keySummary: String)

    @Query("DELETE FROM ppi247_epoch WHERE sourceDate IN (:sourceDates)")
    suspend fun deletePpi247EpochsForDates(sourceDates: List<String>)

    @Query("DELETE FROM skin_temperature_raw WHERE deviceId = :deviceId AND sourceDate = :sourceDate")
    suspend fun deleteSkinTemperatureRecordsForDate(deviceId: String, sourceDate: String)

    @Query("DELETE FROM daily_summary_raw WHERE deviceId = :deviceId AND sourceDate = :sourceDate")
    suspend fun deleteDailySummaryRecordsForDate(deviceId: String, sourceDate: String)

    @Query("DELETE FROM activity_samples_raw WHERE deviceId = :deviceId AND sourceDate = :sourceDate")
    suspend fun deleteActivitySampleRecordsForDate(deviceId: String, sourceDate: String)

    @Query("SELECT * FROM device_profile ORDER BY lastSeenAtEpochMs DESC LIMIT 1")
    fun observeLatestDeviceProfile(): Flow<DeviceProfileEntity?>

    @Query("SELECT * FROM ftu_profile LIMIT 1")
    fun observeLatestFtuProfile(): Flow<FtuProfileEntity?>

    @Query("SELECT * FROM observed_capability ORDER BY domain ASC")
    fun observeObservedCapabilities(): Flow<List<ObservedCapabilityEntity>>

    @Query("SELECT * FROM sync_run ORDER BY startedAtEpochMs DESC")
    fun observeSyncRuns(): Flow<List<SyncRunEntity>>

    @Query("SELECT * FROM sync_run WHERE id = :id LIMIT 1")
    suspend fun getSyncRun(id: Long): SyncRunEntity?

    @Query(
        """
        SELECT id, syncRunId, deviceId, domain, requestedRange, status, recordCount,
               parserVersion, parseStatus, detailSummary, NULL AS rawPayloadJson,
               manualNotes, startedAtEpochMs, endedAtEpochMs, errorCode, errorMessage
        FROM sync_domain_result
        WHERE domain = 'OFFLINE_RECORDING'
          AND status = 'SUPPORTED'
          AND detailSummary LIKE '%type=PPI%'
          AND rawPayloadJson IS NOT NULL
        ORDER BY startedAtEpochMs ASC
        """
    )
    suspend fun getSupportedOfflinePpiResultSummaries(): List<SyncDomainResultEntity>

    @Query("SELECT LENGTH(rawPayloadJson) FROM sync_domain_result WHERE id = :id")
    suspend fun getSyncDomainResultPayloadLength(id: Long): Int?

    @Query("SELECT rawPayloadJson FROM sync_domain_result WHERE id = :id AND LENGTH(rawPayloadJson) <= :maxBytes")
    suspend fun getSyncDomainResultPayloadIfSmall(id: Long, maxBytes: Int = 1_500_000): String?

    @Query("SELECT * FROM sync_run WHERE status = 'running' AND notes LIKE 'training session smoke%' ORDER BY startedAtEpochMs DESC LIMIT 1")
    suspend fun getLatestRunningTrainingSmokeRun(): SyncRunEntity?

    @Query("SELECT * FROM sync_run WHERE status = 'running' AND notes LIKE 'normal offline recording smoke%' ORDER BY startedAtEpochMs DESC LIMIT 1")
    suspend fun getLatestRunningOfflineRecordingSmokeRun(): SyncRunEntity?

    @Query("SELECT * FROM sync_run WHERE status = 'running' AND notes LIKE ('normal offline recording smoke start ' || :dataType) ORDER BY startedAtEpochMs DESC LIMIT 1")
    suspend fun getLatestRunningOfflineRecordingSmokeRunForType(dataType: String): SyncRunEntity?

    @Query(
        """
        SELECT * FROM sync_run
        WHERE notes LIKE 'normal offline recording smoke%'
          AND (
              notes LIKE ('%start ' || :dataType)
              OR notes LIKE ('%type=' || :dataType || '%')
          )
        ORDER BY startedAtEpochMs DESC
        LIMIT 1
        """
    )
    suspend fun getLatestOfflineRecordingSmokeRunForType(dataType: String): SyncRunEntity?

    @Query(
        """
        SELECT id, syncRunId, deviceId, domain, requestedRange, status, recordCount,
               parserVersion, parseStatus, detailSummary, NULL AS rawPayloadJson,
               manualNotes, startedAtEpochMs, endedAtEpochMs, errorCode, errorMessage
        FROM sync_domain_result
        ORDER BY startedAtEpochMs DESC
        """
    )
    fun observeSyncDomainResults(): Flow<List<SyncDomainResultEntity>>

    @Query("SELECT * FROM app_settings WHERE id = 1")
    fun observeAppSettings(): Flow<AppSettingsEntity?>

    @Query("SELECT * FROM daily_check_in ORDER BY sourceDate DESC")
    fun observeDailyCheckIns(): Flow<List<DailyCheckInEntity>>

    @Query("SELECT * FROM food_daily_summary ORDER BY sourceDate DESC")
    fun observeFoodDailySummaries(): Flow<List<FoodDailySummaryEntity>>

    @Query("SELECT * FROM daily_weight ORDER BY sourceDate DESC")
    fun observeDailyWeights(): Flow<List<DailyWeightEntity>>

    @Query(
        """
        SELECT sourceDate,
               COUNT(*) AS epochCount,
               SUM(sampleCount) AS sampleCount,
               SUM(usableSampleCount) AS usableSampleCount,
               AVG(rmssdMs) AS averageRmssdMs,
               MAX(rmssdMs) AS maxRmssdMs,
               AVG(meanHrBpm) AS averageHrBpm,
               SUM(CASE WHEN epochQuality = 'good' THEN 1 ELSE 0 END) AS goodEpochCount,
               SUM(CASE WHEN epochQuality = 'usable' THEN 1 ELSE 0 END) AS usableEpochCount,
               SUM(CASE WHEN epochQuality = 'review' THEN 1 ELSE 0 END) AS reviewEpochCount,
               SUM(CASE WHEN epochQuality LIKE 'poor%' THEN 1 ELSE 0 END) AS poorEpochCount,
               MIN(epochStartEpochMs) AS firstEpochStartEpochMs,
               MAX(epochEndEpochMs) AS lastEpochEndEpochMs
        FROM offline_ppi_epoch
        GROUP BY sourceDate
        ORDER BY sourceDate DESC
        LIMIT 1
        """
    )
    fun observeLatestOfflinePpiNightSummary(): Flow<OfflinePpiNightSummary?>

    @Query("SELECT * FROM sleep_night_raw ORDER BY sourceDate DESC, syncTimestampEpochMs DESC LIMIT 1")
    fun observeLatestSleepRecord(): Flow<SleepNightRawEntity?>

    @Query("SELECT COUNT(*) FROM sleep_night_raw WHERE sourceDate = :sourceDate")
    suspend fun countSleepRecordsForDate(sourceDate: String): Int

    @Query("SELECT * FROM sleep_night_raw WHERE sourceDate = :sourceDate ORDER BY syncTimestampEpochMs DESC LIMIT 1")
    suspend fun getLatestSleepRecordForDate(sourceDate: String): SleepNightRawEntity?

    @Query("SELECT * FROM nightly_recharge_raw ORDER BY sourceDate DESC, syncTimestampEpochMs DESC LIMIT 1")
    fun observeLatestNightlyRechargeRecord(): Flow<NightlyRechargeRawEntity?>

    @Query(
        """
        SELECT * FROM (
            SELECT deviceId, sourceDate, requestedRange, syncTimestampEpochMs, keySummary, rawPayloadJson, parserVersion, parseStatus, 'sleep' AS domain
            FROM sleep_night_raw
            UNION ALL
            SELECT deviceId, sourceDate, requestedRange, syncTimestampEpochMs, keySummary, rawPayloadJson, parserVersion, parseStatus, 'nightly_recharge' AS domain
            FROM nightly_recharge_raw
            UNION ALL
            SELECT deviceId, sourceDate, requestedRange, syncTimestampEpochMs, keySummary, rawPayloadJson, parserVersion, parseStatus, 'hr247' AS domain
            FROM hr247_day_raw
            UNION ALL
            SELECT deviceId, sourceDate, requestedRange, syncTimestampEpochMs, keySummary, rawPayloadJson, parserVersion, parseStatus, 'ppi247' AS domain
            FROM ppi247_day_raw
            UNION ALL
            SELECT deviceId, sourceDate, requestedRange, syncTimestampEpochMs, keySummary, rawPayloadJson, parserVersion, parseStatus, 'skin_temperature' AS domain
            FROM skin_temperature_raw
            UNION ALL
            SELECT deviceId, sourceDate, requestedRange, syncTimestampEpochMs, keySummary, rawPayloadJson, parserVersion, parseStatus, 'daily_summary' AS domain
            FROM daily_summary_raw
            UNION ALL
            SELECT deviceId, sourceDate, requestedRange, syncTimestampEpochMs, keySummary, rawPayloadJson, parserVersion, parseStatus, 'activity_samples' AS domain
            FROM activity_samples_raw
            UNION ALL
            SELECT '' AS deviceId, sourceDate, '-' AS requestedRange, updatedAtEpochMs AS syncTimestampEpochMs,
                   ('outcome=' || eveningOutcome ||
                    CASE WHEN approachToDay IS NOT NULL THEN ', approach=' || approachToDay ELSE '' END ||
                    CASE WHEN muscleWeaknessToday THEN ', muscle_weakness=true' ELSE '' END ||
                    CASE WHEN notes IS NOT NULL AND notes != '' THEN ', notes=' || notes ELSE '' END) AS keySummary,
                   json_object(
                       'sourceDate', sourceDate,
                       'eveningOutcome', eveningOutcome,
                       'approachToDay', approachToDay,
                       'muscleWeaknessToday', muscleWeaknessToday,
                       'notes', notes,
                       'createdAtEpochMs', createdAtEpochMs,
                       'updatedAtEpochMs', updatedAtEpochMs
                   ) AS rawPayloadJson,
                   1 AS parserVersion,
                   'USER_INPUT' AS parseStatus,
                   'daily_check_in' AS domain
            FROM daily_check_in
            UNION ALL
            SELECT '' AS deviceId, sourceDate, '-' AS requestedRange, markerEpochMs AS syncTimestampEpochMs,
                   ('marker=' || markerSource ||
                    CASE WHEN deviceId IS NOT NULL THEN ', device=' || deviceId ELSE '' END ||
                    CASE WHEN notes IS NOT NULL AND notes != '' THEN ', notes=' || notes ELSE '' END) AS keySummary,
                   json_object(
                       'id', id,
                       'sourceDate', sourceDate,
                       'markerEpochMs', markerEpochMs,
                       'markerSource', markerSource,
                       'deviceId', deviceId,
                       'notes', notes
                   ) AS rawPayloadJson,
                   1 AS parserVersion,
                   'USER_INPUT' AS parseStatus,
                   'wake_marker' AS domain
            FROM wake_marker
            UNION ALL
            SELECT '' AS deviceId, sourceDate, '-' AS requestedRange, importedAtEpochMs AS syncTimestampEpochMs,
                   ('calories=' || COALESCE(CAST(totalCaloriesKcal AS TEXT), 'n/a') ||
                    CASE WHEN teaCount IS NOT NULL THEN ', tea=' || teaCount ELSE '' END ||
                    CASE WHEN eventCount IS NOT NULL THEN ', events=' || eventCount ELSE '' END) AS keySummary,
                   json_object(
                       'sourceDate', sourceDate,
                       'totalCaloriesKcal', totalCaloriesKcal,
                       'eventCount', eventCount,
                       'teaCount', teaCount,
                       'firstIntakeTime', firstIntakeTime,
                       'lastIntakeTime', lastIntakeTime,
                       'eatingWindowHours', eatingWindowHours,
                       'rawItemsJson', rawItemsJson,
                       'importSource', importSource,
                       'importedAtEpochMs', importedAtEpochMs
                   ) AS rawPayloadJson,
                   1 AS parserVersion,
                   'USER_INPUT' AS parseStatus,
                   'food_daily_summary' AS domain
            FROM food_daily_summary
            UNION ALL
            SELECT '' AS deviceId, sourceDate, '-' AS requestedRange, importedAtEpochMs AS syncTimestampEpochMs,
                   ('weight=' || printf('%.1f kg', weightKg)) AS keySummary,
                   json_object(
                       'sourceDate', sourceDate,
                       'measuredTime', measuredTime,
                       'weightKg', weightKg,
                       'notes', notes,
                       'importSource', importSource,
                       'importedAtEpochMs', importedAtEpochMs
                   ) AS rawPayloadJson,
                   1 AS parserVersion,
                   'USER_INPUT' AS parseStatus,
                   'daily_weight' AS domain
            FROM daily_weight
        ) ORDER BY syncTimestampEpochMs DESC
        """
    )
    fun observeInspectorRows(): Flow<List<InspectorRow>>

    @Query("SELECT COUNT(*) FROM observed_capability WHERE deviceId = :deviceId")
    suspend fun countCapabilities(deviceId: String): Int

    @Query("SELECT * FROM device_profile WHERE deviceId = :deviceId LIMIT 1")
    suspend fun getDeviceProfile(deviceId: String): DeviceProfileEntity?

    @Query("SELECT * FROM ftu_profile WHERE deviceId = :deviceId LIMIT 1")
    suspend fun getFtuProfile(deviceId: String): FtuProfileEntity?

    @Query("SELECT * FROM app_settings WHERE id = 1 LIMIT 1")
    suspend fun getAppSettings(): AppSettingsEntity?

    @Query("SELECT * FROM daily_check_in WHERE sourceDate = :sourceDate LIMIT 1")
    suspend fun getDailyCheckIn(sourceDate: String): DailyCheckInEntity?

    @Query("SELECT * FROM food_daily_summary WHERE sourceDate = :sourceDate LIMIT 1")
    suspend fun getFoodDailySummary(sourceDate: String): FoodDailySummaryEntity?

    @Query("SELECT * FROM daily_weight WHERE sourceDate = :sourceDate LIMIT 1")
    suspend fun getDailyWeight(sourceDate: String): DailyWeightEntity?

    @Query("SELECT * FROM food_log_item WHERE sourceDate = :sourceDate ORDER BY timeLocal ASC, item ASC")
    suspend fun getFoodLogItemsForDate(sourceDate: String): List<FoodLogItemEntity>

    @Query("SELECT * FROM offline_ppi_epoch WHERE sourceDate = :sourceDate ORDER BY epochStartEpochMs ASC")
    suspend fun getOfflinePpiEpochsForDate(sourceDate: String): List<OfflinePpiEpochEntity>

    @Query("SELECT * FROM ppi247_day_raw WHERE sourceDate IN (:sourceDates) ORDER BY sourceDate ASC, keySummary ASC")
    suspend fun getPpiRawRecordsForDates(sourceDates: List<String>): List<Ppi247DayRawEntity>

    @Query("SELECT * FROM ppi247_day_raw ORDER BY sourceDate ASC, keySummary ASC")
    suspend fun getAllPpiRawRecords(): List<Ppi247DayRawEntity>

    @Query("SELECT * FROM ppi247_epoch WHERE sourceDate = :sourceDate ORDER BY epochStartEpochMs ASC")
    suspend fun getPpi247EpochsForDate(sourceDate: String): List<Ppi247EpochEntity>

    @Query("SELECT * FROM offline_ppi_epoch ORDER BY epochStartEpochMs DESC LIMIT 2000")
    fun observeRecentOfflinePpiEpochs(): Flow<List<OfflinePpiEpochEntity>>

    @Query("SELECT * FROM ppi247_epoch ORDER BY epochStartEpochMs DESC LIMIT 2000")
    fun observeRecentPpi247Epochs(): Flow<List<Ppi247EpochEntity>>

}

data class InspectorRow(
    val deviceId: String,
    val sourceDate: String?,
    val requestedRange: String,
    val syncTimestampEpochMs: Long,
    val keySummary: String,
    val rawPayloadJson: String,
    val parserVersion: Int,
    val parseStatus: String,
    val domain: String
)

data class OfflinePpiNightSummary(
    val sourceDate: String,
    val epochCount: Int,
    val sampleCount: Int,
    val usableSampleCount: Int,
    val averageRmssdMs: Double?,
    val maxRmssdMs: Double?,
    val averageHrBpm: Double?,
    val goodEpochCount: Int,
    val usableEpochCount: Int,
    val reviewEpochCount: Int,
    val poorEpochCount: Int,
    val firstEpochStartEpochMs: Long?,
    val lastEpochEndEpochMs: Long?
)
