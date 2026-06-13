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

    @Query(
        """
        UPDATE daily_check_in
        SET dayShapeCaptured = 1,
            pemPaybackToday = CASE WHEN pemPaybackToday IS NULL THEN 1 ELSE pemPaybackToday END,
            paybackPeakToday = :paybackPeakToday,
            paybackPeakConfidence = :paybackPeakConfidence,
            updatedAtEpochMs = :updatedAtEpochMs
        WHERE sourceDate = :sourceDate
        """
    )
    suspend fun updatePaybackPeakColumns(
        sourceDate: String,
        paybackPeakToday: Boolean,
        paybackPeakConfidence: String,
        updatedAtEpochMs: Long
    )

    @Insert
    suspend fun insertMorningPredictionSnapshot(entity: MorningPredictionSnapshotEntity): Long

    @Insert
    suspend fun insertCurrentStateSnapshot(entity: CurrentStateSnapshotEntity): Long

    @Insert
    suspend fun insertSleepEpisode(entity: SleepEpisodeEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSleepEpisodes(entities: List<SleepEpisodeEntity>)

    @Update
    suspend fun updateSleepEpisode(entity: SleepEpisodeEntity)

    @Query("DELETE FROM sleep_episode WHERE id = :id")
    suspend fun deleteSleepEpisode(id: Long)

    @Query("DELETE FROM sleep_episode WHERE sourceDate = :sourceDate AND episodeKind = :episodeKind")
    suspend fun deleteSleepEpisodesForDateAndKind(sourceDate: String, episodeKind: String): Int

    @Query("SELECT * FROM sleep_episode WHERE id = :id LIMIT 1")
    suspend fun getSleepEpisodeById(id: Long): SleepEpisodeEntity?

    @Query(
        """
        SELECT * FROM sleep_episode
        WHERE sourceDate = :sourceDate AND episodeKind = :episodeKind
        ORDER BY updatedAtEpochMs DESC, id DESC
        LIMIT 1
        """
    )
    suspend fun getLatestSleepEpisodeForDateAndKind(sourceDate: String, episodeKind: String): SleepEpisodeEntity?

    @Query(
        """
        UPDATE sleep_episode
        SET isPrimaryForReadiness = 0, updatedAtEpochMs = :updatedAtEpochMs
        WHERE sourceDate = :sourceDate AND isPrimaryForReadiness = 1
        """
    )
    suspend fun clearPrimarySleepEpisodeForDate(sourceDate: String, updatedAtEpochMs: Long)

    @Query(
        """
        DELETE FROM sleep_episode
        WHERE sourceDate = :sourceDate
          AND source = :source
          AND isPrimaryForReadiness = 0
          AND confidence != :confirmedConfidence
        """
    )
    suspend fun deleteUnconfirmedSleepEpisodeCandidatesForDate(
        sourceDate: String,
        source: String,
        confirmedConfidence: String
    )

    @Query(
        """
        DELETE FROM sleep_episode
        WHERE sourceDate = :sourceDate
          AND source = :source
          AND episodeKind = :episodeKind
          AND isPrimaryForReadiness = 0
          AND confidence != :confirmedConfidence
        """
    )
    suspend fun deleteUnconfirmedSleepEpisodeCandidatesForDateAndKind(
        sourceDate: String,
        source: String,
        episodeKind: String,
        confirmedConfidence: String
    )

    @Query("SELECT * FROM sleep_episode WHERE sourceDate = :sourceDate ORDER BY startEpochMs ASC, id ASC")
    suspend fun getSleepEpisodesForDate(sourceDate: String): List<SleepEpisodeEntity>

    @Query("SELECT * FROM sleep_episode WHERE sourceDate = :sourceDate AND isPrimaryForReadiness = 1 ORDER BY updatedAtEpochMs DESC, id DESC LIMIT 1")
    suspend fun getPrimarySleepEpisodeForDate(sourceDate: String): SleepEpisodeEntity?

    @Query("SELECT * FROM sleep_episode WHERE sourceDate = :sourceDate ORDER BY startEpochMs ASC, id ASC")
    fun observeSleepEpisodesForDate(sourceDate: String): Flow<List<SleepEpisodeEntity>>

    @Query("SELECT * FROM sleep_episode ORDER BY COALESCE(startEpochMs, updatedAtEpochMs) DESC LIMIT 100")
    fun observeRecentSleepEpisodes(): Flow<List<SleepEpisodeEntity>>

    @Insert
    suspend fun insertWakeMarker(entity: WakeMarkerEntity): Long

    @Query(
        """
        UPDATE wake_marker
        SET sourceDate = :sourceDate, markerEpochMs = :markerEpochMs
        WHERE id = :id
        """
    )
    suspend fun updateWakeMarkerTime(id: Long, sourceDate: String, markerEpochMs: Long): Int

    @Query(
        """
        SELECT * FROM wake_marker
        WHERE sourceDate = :sourceDate AND markerSource = :markerSource
        ORDER BY markerEpochMs DESC
        LIMIT 1
        """
    )
    suspend fun getLatestWakeMarker(sourceDate: String, markerSource: String): WakeMarkerEntity?

    @Query("SELECT * FROM wake_marker ORDER BY markerEpochMs DESC LIMIT 50")
    fun observeRecentWakeMarkers(): Flow<List<WakeMarkerEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFoodDailySummaries(entities: List<FoodDailySummaryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFoodLogItems(entities: List<FoodLogItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDailyWeights(entities: List<DailyWeightEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGripSessions(entities: List<GripSessionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGripReps(entities: List<GripRepEntity>)

    @Query("DELETE FROM food_log_item WHERE sourceDate IN (:sourceDates)")
    suspend fun deleteFoodLogItemsForDates(sourceDates: List<String>)

    @Query("DELETE FROM food_daily_summary WHERE sourceDate = :sourceDate")
    suspend fun deleteFoodDailySummaryForDate(sourceDate: String)

    @Query("DELETE FROM daily_weight WHERE sourceDate IN (:sourceDates)")
    suspend fun deleteDailyWeightsForDates(sourceDates: List<String>)

    @Query("DELETE FROM grip_rep WHERE sessionId IN (:sessionIds)")
    suspend fun deleteGripRepsForSessions(sessionIds: List<String>)

    @Query("DELETE FROM grip_rep WHERE sourceDate IN (:sourceDates)")
    suspend fun deleteGripRepsForDates(sourceDates: List<String>)

    @Query("DELETE FROM grip_session WHERE sessionId IN (:sessionIds)")
    suspend fun deleteGripSessions(sessionIds: List<String>)

    @Query("DELETE FROM grip_session WHERE sourceDate IN (:sourceDates)")
    suspend fun deleteGripSessionsForDates(sourceDates: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPpi247Epochs(entities: List<Ppi247EpochEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHr247Epochs(entities: List<Hr247EpochEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSkinTemperatureSamples(entities: List<SkinTemperatureSampleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertActivityEpochs(entities: List<ActivityEpochEntity>)

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

    @Query(
        """
        SELECT rawPayloadJson FROM sleep_night_raw
        WHERE deviceId = :deviceId AND sourceDate IN (:sourceDates)
        """
    )
    suspend fun getExistingSleepPayloadsForDates(deviceId: String, sourceDates: List<String>): List<String>

    @Query("SELECT * FROM sleep_night_raw WHERE deviceId = :deviceId AND sourceDate IN (:sourceDates)")
    suspend fun getSleepRecordsForDates(deviceId: String, sourceDates: List<String>): List<SleepNightRawEntity>

    @Query("SELECT rawPayloadJson FROM nightly_recharge_raw WHERE deviceId = :deviceId")
    suspend fun getExistingNightlyRechargePayloads(deviceId: String): List<String>

    @Query(
        """
        SELECT rawPayloadJson FROM nightly_recharge_raw
        WHERE deviceId = :deviceId AND sourceDate IN (:sourceDates)
        """
    )
    suspend fun getExistingNightlyRechargePayloadsForDates(deviceId: String, sourceDates: List<String>): List<String>

    @Query("SELECT rawPayloadJson FROM hr247_day_raw WHERE deviceId = :deviceId")
    suspend fun getExistingHrPayloads(deviceId: String): List<String>

    @Query("SELECT rawPayloadJson FROM ppi247_day_raw WHERE deviceId = :deviceId")
    suspend fun getExistingPpiPayloads(deviceId: String): List<String>

    @Query("SELECT sourceDate || '|' || keySummary FROM ppi247_day_raw WHERE deviceId = :deviceId")
    suspend fun getExistingPpiRecordKeys(deviceId: String): List<String>

    @Query(
        """
        SELECT sourceDate || '|' || keySummary
        FROM ppi247_day_raw
        WHERE deviceId = :deviceId AND sourceDate IN (:sourceDates)
        """
    )
    suspend fun getExistingPpiRecordKeysForDates(deviceId: String, sourceDates: List<String>): List<String>

    @Query(
        """
        SELECT MAX(sourceDate) FROM sleep_night_raw
        WHERE deviceId = :deviceId AND sourceDate IS NOT NULL AND sourceDate != ''
        """
    )
    suspend fun getLatestSleepSourceDate(deviceId: String): String?

    @Query(
        """
        SELECT MAX(sourceDate) FROM nightly_recharge_raw
        WHERE deviceId = :deviceId AND sourceDate IS NOT NULL AND sourceDate != ''
        """
    )
    suspend fun getLatestNightlyRechargeSourceDate(deviceId: String): String?

    @Query("SELECT MAX(sourceDate) FROM ppi247_epoch WHERE deviceId = :deviceId")
    suspend fun getLatestPpiEpochSourceDate(deviceId: String): String?

    @Query("SELECT MAX(sourceDate) FROM hr247_epoch WHERE deviceId = :deviceId")
    suspend fun getLatestHrEpochSourceDate(deviceId: String): String?

    @Query("SELECT MAX(sourceDate) FROM skin_temperature_raw WHERE deviceId = :deviceId")
    suspend fun getLatestSkinTemperatureSourceDate(deviceId: String): String?

    @Query("SELECT MAX(sourceDate) FROM daily_summary_raw WHERE deviceId = :deviceId")
    suspend fun getLatestDailySummarySourceDate(deviceId: String): String?

    @Query("SELECT MAX(sourceDate) FROM activity_samples_raw WHERE deviceId = :deviceId")
    suspend fun getLatestActivitySamplesSourceDate(deviceId: String): String?

    @Query("SELECT rawPayloadJson FROM skin_temperature_raw WHERE deviceId = :deviceId")
    suspend fun getExistingSkinTemperaturePayloads(deviceId: String): List<String>

    @Query("SELECT rawPayloadJson FROM daily_summary_raw WHERE deviceId = :deviceId")
    suspend fun getExistingDailySummaryPayloads(deviceId: String): List<String>

    @Query(
        """
        SELECT rawPayloadJson FROM daily_summary_raw
        WHERE deviceId = :deviceId AND sourceDate IN (:sourceDates)
        """
    )
    suspend fun getExistingDailySummaryPayloadsForDates(deviceId: String, sourceDates: List<String>): List<String>

    @Query("SELECT rawPayloadJson FROM activity_samples_raw WHERE deviceId = :deviceId")
    suspend fun getExistingActivitySamplePayloads(deviceId: String): List<String>

    @Query(
        """
        SELECT sourceDate FROM sleep_night_raw
        WHERE deviceId = :deviceId AND sourceDate IS NOT NULL AND sourceDate <= :cutoffDate
        UNION
        SELECT sourceDate FROM nightly_recharge_raw
        WHERE deviceId = :deviceId AND sourceDate IS NOT NULL AND sourceDate <= :cutoffDate
        UNION
        SELECT sourceDate FROM hr247_day_raw
        WHERE deviceId = :deviceId AND sourceDate <= :cutoffDate
        UNION
        SELECT sourceDate FROM hr247_epoch
        WHERE deviceId = :deviceId AND sourceDate <= :cutoffDate
        UNION
        SELECT sourceDate FROM ppi247_day_raw
        WHERE deviceId = :deviceId AND sourceDate <= :cutoffDate
        UNION
        SELECT sourceDate FROM skin_temperature_raw
        WHERE deviceId = :deviceId AND sourceDate <= :cutoffDate
        UNION
        SELECT sourceDate FROM daily_summary_raw
        WHERE deviceId = :deviceId AND sourceDate <= :cutoffDate
        UNION
        SELECT sourceDate FROM activity_samples_raw
        WHERE deviceId = :deviceId AND sourceDate <= :cutoffDate
        ORDER BY sourceDate ASC
        """
    )
    suspend fun getArchivedDeviceSourceDatesAtOrBefore(deviceId: String, cutoffDate: String): List<String>

    @Query("DELETE FROM sleep_night_raw WHERE deviceId = :deviceId AND sourceDate = :sourceDate")
    suspend fun deleteSleepRecordsForDate(deviceId: String, sourceDate: String?)

    @Query("DELETE FROM nightly_recharge_raw WHERE deviceId = :deviceId AND sourceDate = :sourceDate")
    suspend fun deleteNightlyRechargeRecordsForDate(deviceId: String, sourceDate: String?)

    @Query("DELETE FROM hr247_day_raw WHERE deviceId = :deviceId AND sourceDate = :sourceDate")
    suspend fun deleteHrRecordsForDate(deviceId: String, sourceDate: String)

    @Query("DELETE FROM hr247_day_raw WHERE (:deviceId IS NULL OR deviceId = :deviceId) AND sourceDate IN (:sourceDates)")
    suspend fun deleteHrRecordsForDates(deviceId: String?, sourceDates: List<String>)

    @Query(
        """
        DELETE FROM hr247_day_raw
        WHERE (:deviceId IS NULL OR deviceId = :deviceId)
          AND sourceDate IN (:sourceDates)
          AND (
              requestedRange IS NULL
              OR lower(requestedRange) NOT LIKE lower(:retainedRequestedRangePrefix || '%')
          )
        """
    )
    suspend fun deleteHrRecordsForDatesExceptRequestedRangePrefix(
        deviceId: String?,
        sourceDates: List<String>,
        retainedRequestedRangePrefix: String
    ): Int

    @Query("DELETE FROM hr247_epoch WHERE (:deviceId IS NULL OR deviceId = :deviceId) AND sourceDate IN (:sourceDates)")
    suspend fun deleteHr247EpochsForDates(deviceId: String?, sourceDates: List<String>)

    @Query("DELETE FROM ppi247_day_raw WHERE deviceId = :deviceId AND sourceDate = :sourceDate AND keySummary = :keySummary")
    suspend fun deletePpiRecordsForDateAndKeySummary(deviceId: String, sourceDate: String, keySummary: String)

    @Query(
        """
        SELECT DISTINCT raw.sourceDate
        FROM ppi247_day_raw raw
        WHERE raw.deviceId = :deviceId
          AND raw.sourceDate < :cutoffDate
          AND (
              raw.requestedRange IS NULL
              OR lower(raw.requestedRange) NOT LIKE lower(:retainedRequestedRangePrefix || '%')
          )
          AND EXISTS (
              SELECT 1
              FROM ppi247_epoch epoch
              WHERE epoch.sourceDate = raw.sourceDate
                AND epoch.deviceId = raw.deviceId
          )
        ORDER BY raw.sourceDate ASC
        """
    )
    suspend fun getPrunablePpiRawSourceDatesBefore(
        deviceId: String,
        cutoffDate: String,
        retainedRequestedRangePrefix: String
    ): List<String>

    @Query(
        """
        DELETE FROM ppi247_day_raw
        WHERE deviceId = :deviceId
          AND sourceDate IN (:sourceDates)
          AND (
              requestedRange IS NULL
              OR lower(requestedRange) NOT LIKE lower(:retainedRequestedRangePrefix || '%')
          )
        """
    )
    suspend fun deletePpiRawRecordsForDates(
        deviceId: String,
        sourceDates: List<String>,
        retainedRequestedRangePrefix: String
    ): Int

    @Query("DELETE FROM ppi247_epoch WHERE sourceDate IN (:sourceDates)")
    suspend fun deletePpi247EpochsForDates(sourceDates: List<String>)

    @Query("DELETE FROM skin_temperature_raw WHERE deviceId = :deviceId AND sourceDate = :sourceDate")
    suspend fun deleteSkinTemperatureRecordsForDate(deviceId: String, sourceDate: String)

    @Query("DELETE FROM skin_temperature_raw WHERE (:deviceId IS NULL OR deviceId = :deviceId) AND sourceDate IN (:sourceDates)")
    suspend fun deleteSkinTemperatureRecordsForDates(deviceId: String?, sourceDates: List<String>)

    @Query(
        """
        DELETE FROM skin_temperature_raw
        WHERE (:deviceId IS NULL OR deviceId = :deviceId)
          AND sourceDate IN (:sourceDates)
          AND (
              requestedRange IS NULL
              OR lower(requestedRange) NOT LIKE lower(:retainedRequestedRangePrefix || '%')
          )
        """
    )
    suspend fun deleteSkinTemperatureRecordsForDatesExceptRequestedRangePrefix(
        deviceId: String?,
        sourceDates: List<String>,
        retainedRequestedRangePrefix: String
    ): Int

    @Query("DELETE FROM skin_temperature_sample WHERE sourceDate IN (:sourceDates)")
    suspend fun deleteSkinTemperatureSamplesForDates(sourceDates: List<String>)

    @Query("DELETE FROM daily_summary_raw WHERE deviceId = :deviceId AND sourceDate = :sourceDate")
    suspend fun deleteDailySummaryRecordsForDate(deviceId: String, sourceDate: String)

    @Query("DELETE FROM activity_samples_raw WHERE deviceId = :deviceId AND sourceDate = :sourceDate")
    suspend fun deleteActivitySampleRecordsForDate(deviceId: String, sourceDate: String)

    @Query("DELETE FROM activity_samples_raw WHERE (:deviceId IS NULL OR deviceId = :deviceId) AND sourceDate IN (:sourceDates)")
    suspend fun deleteActivitySampleRecordsForDates(deviceId: String?, sourceDates: List<String>)

    @Query(
        """
        DELETE FROM activity_samples_raw
        WHERE (:deviceId IS NULL OR deviceId = :deviceId)
          AND sourceDate IN (:sourceDates)
          AND (
              requestedRange IS NULL
              OR lower(requestedRange) NOT LIKE lower(:retainedRequestedRangePrefix || '%')
          )
        """
    )
    suspend fun deleteActivitySampleRecordsForDatesExceptRequestedRangePrefix(
        deviceId: String?,
        sourceDates: List<String>,
        retainedRequestedRangePrefix: String
    ): Int

    @Query("DELETE FROM activity_epoch WHERE sourceDate IN (:sourceDates)")
    suspend fun deleteActivityEpochsForDates(sourceDates: List<String>)

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
        UPDATE sync_run
        SET endedAtEpochMs = :endedAtEpochMs,
            status = :status,
            notes = :notes
        WHERE status = 'running'
          AND startedAtEpochMs < :cutoffEpochMs
        """
    )
    suspend fun markStaleRunningSyncRuns(
        cutoffEpochMs: Long,
        endedAtEpochMs: Long,
        status: String,
        notes: String
    ): Int

    @Query("SELECT COUNT(*) FROM sync_run WHERE status = 'running' AND startedAtEpochMs >= :cutoffEpochMs")
    suspend fun countRecentRunningSyncRuns(cutoffEpochMs: Long): Int

    @Query("SELECT * FROM sync_run WHERE deviceId = :deviceId AND notes LIKE 'offline PPI start%' ORDER BY startedAtEpochMs DESC LIMIT 1")
    suspend fun getLatestOfflinePpiStartRun(deviceId: String): SyncRunEntity?

    @Query("SELECT LENGTH(rawPayloadJson) FROM sync_domain_result WHERE id = :id")
    suspend fun getSyncDomainResultPayloadLength(id: Long): Int?

    @Query("SELECT rawPayloadJson FROM sync_domain_result WHERE id = :id AND LENGTH(rawPayloadJson) <= :maxBytes")
    suspend fun getSyncDomainResultPayloadIfSmall(id: Long, maxBytes: Int = 1_500_000): String?

    @Query("UPDATE sync_domain_result SET rawPayloadJson = NULL WHERE rawPayloadJson IS NOT NULL AND LENGTH(rawPayloadJson) > :maxBytes")
    suspend fun pruneLargeSyncDomainResultPayloads(maxBytes: Int = 250_000): Int

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

    @Query("SELECT * FROM morning_prediction_snapshot ORDER BY issuedAtEpochMs DESC")
    fun observeMorningPredictionSnapshots(): Flow<List<MorningPredictionSnapshotEntity>>

    @Query("SELECT * FROM food_daily_summary ORDER BY sourceDate DESC")
    fun observeFoodDailySummaries(): Flow<List<FoodDailySummaryEntity>>

    @Query("SELECT * FROM daily_weight ORDER BY sourceDate DESC")
    fun observeDailyWeights(): Flow<List<DailyWeightEntity>>

    @Query("SELECT * FROM grip_session ORDER BY sourceDate DESC, startedAtEpochMs DESC, sessionId DESC")
    fun observeGripSessions(): Flow<List<GripSessionEntity>>

    @Query("SELECT * FROM sleep_night_raw ORDER BY sourceDate DESC, syncTimestampEpochMs DESC LIMIT 1")
    fun observeLatestSleepRecord(): Flow<SleepNightRawEntity?>

    @Query("SELECT COUNT(*) FROM sleep_night_raw WHERE sourceDate = :sourceDate")
    suspend fun countSleepRecordsForDate(sourceDate: String): Int

    @Query("SELECT * FROM sleep_night_raw WHERE sourceDate = :sourceDate ORDER BY syncTimestampEpochMs DESC LIMIT 1")
    suspend fun getLatestSleepRecordForDate(sourceDate: String): SleepNightRawEntity?

    @Query("SELECT * FROM nightly_recharge_raw ORDER BY sourceDate DESC, syncTimestampEpochMs DESC LIMIT 1")
    fun observeLatestNightlyRechargeRecord(): Flow<NightlyRechargeRawEntity?>

    @Query("SELECT * FROM nightly_recharge_raw WHERE sourceDate = :sourceDate ORDER BY syncTimestampEpochMs DESC LIMIT 1")
    suspend fun getLatestNightlyRechargeRecordForDate(sourceDate: String): NightlyRechargeRawEntity?

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
            SELECT '' AS deviceId, sourceDate, '-' AS requestedRange, issuedAtEpochMs AS syncTimestampEpochMs,
                   ('prediction=' || status ||
                    ', confidence=' || confidence ||
                    ', origin=' || snapshotOrigin ||
                    CASE WHEN isInterim THEN ', interim=true' ELSE ', final=true' END) AS keySummary,
                   json_object(
                       'id', id,
                       'sourceDate', sourceDate,
                       'issuedAtEpochMs', issuedAtEpochMs,
                       'snapshotOrigin', snapshotOrigin,
                       'modelVersion', modelVersion,
                       'status', status,
                       'confidence', confidence,
                       'isInterim', isInterim,
                       'sleepDataReady', sleepDataReady,
                       'overnightAutonomicSource', overnightAutonomicSource,
                       'sleepDurationMinutes', sleepDurationMinutes,
                       'nightlyRmssd', nightlyRmssd,
                       'baselineReady', baselineReady,
                       'recoveryAvailable', recoveryAvailable,
                       'rawPpiGoodEpochCount', rawPpiGoodEpochCount,
                       'rawPpiPoorEpochCount', rawPpiPoorEpochCount,
                       'rawPpiCoverageHours', rawPpiCoverageHours,
                       'summary', summary,
                       'reasons', reasonsJson
                   ) AS rawPayloadJson,
                   1 AS parserVersion,
                   'DERIVED' AS parseStatus,
                   'morning_prediction_snapshot' AS domain
            FROM morning_prediction_snapshot
            UNION ALL
            SELECT COALESCE(deviceId, '') AS deviceId, sourceDate, '-' AS requestedRange, updatedAtEpochMs AS syncTimestampEpochMs,
                   ('episode=' || episodeKind ||
                    ', source=' || source ||
                    ', confidence=' || confidence ||
                    CASE WHEN isPrimaryForReadiness THEN ', primary=true' ELSE '' END ||
                    CASE WHEN notes IS NOT NULL AND notes != '' THEN ', notes=' || notes ELSE '' END) AS keySummary,
                   json_object(
                       'id', id,
                       'sourceDate', sourceDate,
                       'startEpochMs', startEpochMs,
                       'endEpochMs', endEpochMs,
                       'episodeKind', episodeKind,
                       'source', source,
                       'confidence', confidence,
                       'isPrimaryForReadiness', isPrimaryForReadiness,
                       'deviceId', deviceId,
                       'linkedSleepRawId', linkedSleepRawId,
                       'evidence', evidenceJson,
                       'notes', notes,
                       'createdAtEpochMs', createdAtEpochMs,
                       'updatedAtEpochMs', updatedAtEpochMs
                   ) AS rawPayloadJson,
                   1 AS parserVersion,
                   'DERIVED' AS parseStatus,
                   'sleep_episode' AS domain
            FROM sleep_episode
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

    @Query(
        """
        SELECT sourceDate FROM daily_check_in
        UNION
        SELECT sourceDate FROM sleep_night_raw WHERE sourceDate IS NOT NULL
        UNION
        SELECT sourceDate FROM nightly_recharge_raw WHERE sourceDate IS NOT NULL
        UNION
        SELECT sourceDate FROM sleep_episode
        UNION
        SELECT sourceDate FROM ppi247_epoch
        ORDER BY sourceDate ASC
        """
    )
    suspend fun getMorningPredictionBackfillCandidateDates(): List<String>

    @Query(
        """
        SELECT * FROM morning_prediction_snapshot
        WHERE sourceDate = :sourceDate AND snapshotOrigin = :snapshotOrigin
        ORDER BY issuedAtEpochMs DESC
        LIMIT 1
        """
    )
    suspend fun getLatestMorningPredictionSnapshot(
        sourceDate: String,
        snapshotOrigin: String
    ): MorningPredictionSnapshotEntity?

    @Query(
        """
        SELECT COUNT(*) FROM morning_prediction_snapshot
        WHERE sourceDate = :sourceDate AND snapshotOrigin = :snapshotOrigin
          AND modelVersion = :modelVersion
        """
    )
    suspend fun countMorningPredictionSnapshots(
        sourceDate: String,
        snapshotOrigin: String,
        modelVersion: String
    ): Int

    @Query(
        """
        DELETE FROM morning_prediction_snapshot
        WHERE id NOT IN (
            SELECT MAX(id)
            FROM morning_prediction_snapshot
            GROUP BY sourceDate, snapshotOrigin, modelVersion, status, confidence,
                     isInterim, sleepDataReady, overnightAutonomicSource,
                     sleepDurationMinutes, nightlyRmssd, baselineReady, recoveryAvailable,
                     rawPpiGoodEpochCount,
                     rawPpiPoorEpochCount, rawPpiCoverageHours, summary, reasonsJson
        )
        """
    )
    suspend fun pruneDuplicateMorningPredictionSnapshots(): Int

    // --- Model-v1 current-state snapshots + feature-extraction reads ---------

    @Query("SELECT * FROM current_state_snapshot ORDER BY issuedAtEpochMs DESC")
    fun observeCurrentStateSnapshots(): Flow<List<CurrentStateSnapshotEntity>>

    @Query(
        """
        SELECT * FROM current_state_snapshot
        WHERE sourceDate = :sourceDate AND snapshotOrigin = :snapshotOrigin
        ORDER BY issuedAtEpochMs DESC
        LIMIT 1
        """
    )
    suspend fun getLatestCurrentStateSnapshot(
        sourceDate: String,
        snapshotOrigin: String
    ): CurrentStateSnapshotEntity?

    @Query("SELECT * FROM daily_check_in WHERE sourceDate <= :asOf ORDER BY sourceDate DESC LIMIT :limit")
    suspend fun getRecentDailyCheckInsAsOf(asOf: String, limit: Int): List<DailyCheckInEntity>

    @Query("SELECT * FROM daily_summary_raw WHERE sourceDate IN (:sourceDates)")
    suspend fun getDailySummariesForDates(sourceDates: List<String>): List<DailySummaryRawEntity>

    @Query("SELECT * FROM food_daily_summary WHERE sourceDate = :sourceDate LIMIT 1")
    suspend fun getFoodDailySummary(sourceDate: String): FoodDailySummaryEntity?

    @Query("SELECT * FROM daily_weight WHERE sourceDate = :sourceDate LIMIT 1")
    suspend fun getDailyWeight(sourceDate: String): DailyWeightEntity?

    @Query("SELECT * FROM food_log_item WHERE sourceDate = :sourceDate ORDER BY timeLocal ASC, item ASC")
    suspend fun getFoodLogItemsForDate(sourceDate: String): List<FoodLogItemEntity>

    @Query("SELECT * FROM grip_session WHERE sourceDate = :sourceDate ORDER BY startedAtEpochMs DESC, sessionId DESC")
    suspend fun getGripSessionsForDate(sourceDate: String): List<GripSessionEntity>

    @Query("SELECT * FROM ppi247_day_raw WHERE sourceDate IN (:sourceDates) ORDER BY sourceDate ASC, keySummary ASC")
    suspend fun getPpiRawRecordsForDates(sourceDates: List<String>): List<Ppi247DayRawEntity>

    @Query("SELECT * FROM ppi247_day_raw ORDER BY sourceDate ASC, keySummary ASC")
    suspend fun getAllPpiRawRecords(): List<Ppi247DayRawEntity>

    @Query("SELECT * FROM hr247_day_raw WHERE (:deviceId IS NULL OR deviceId = :deviceId) AND sourceDate IN (:sourceDates) ORDER BY sourceDate ASC")
    suspend fun getHrRawRecordsForDates(deviceId: String?, sourceDates: List<String>): List<Hr247DayRawEntity>

    @Query("SELECT * FROM hr247_day_raw ORDER BY sourceDate ASC")
    suspend fun getAllHrRawRecords(): List<Hr247DayRawEntity>

    @Query("SELECT * FROM skin_temperature_raw WHERE sourceDate IN (:sourceDates) ORDER BY sourceDate ASC")
    suspend fun getSkinTemperatureRawRecordsForDates(sourceDates: List<String>): List<SkinTemperatureRawEntity>

    @Query("SELECT * FROM skin_temperature_raw ORDER BY sourceDate ASC")
    suspend fun getAllSkinTemperatureRawRecords(): List<SkinTemperatureRawEntity>

    @Query("SELECT * FROM activity_samples_raw WHERE sourceDate IN (:sourceDates) ORDER BY sourceDate ASC")
    suspend fun getActivitySampleRawRecordsForDates(sourceDates: List<String>): List<ActivitySamplesRawEntity>

    @Query("SELECT * FROM activity_samples_raw ORDER BY sourceDate ASC")
    suspend fun getAllActivitySampleRawRecords(): List<ActivitySamplesRawEntity>

    @Query("SELECT * FROM ppi247_epoch WHERE sourceDate = :sourceDate ORDER BY epochStartEpochMs ASC")
    suspend fun getPpi247EpochsForDate(sourceDate: String): List<Ppi247EpochEntity>

    @Query("SELECT * FROM ppi247_epoch WHERE sourceDate IN (:sourceDates) ORDER BY epochStartEpochMs ASC")
    suspend fun getPpi247EpochsForDates(sourceDates: List<String>): List<Ppi247EpochEntity>

    @Query("SELECT COUNT(*) FROM ppi247_epoch WHERE sourceDate = :sourceDate")
    suspend fun countPpi247EpochsForDate(sourceDate: String): Int

    @Query("SELECT * FROM hr247_epoch WHERE sourceDate = :sourceDate ORDER BY epochStartEpochMs ASC")
    suspend fun getHr247EpochsForDate(sourceDate: String): List<Hr247EpochEntity>

    @Query("SELECT COUNT(*) FROM hr247_epoch WHERE sourceDate = :sourceDate")
    suspend fun countHr247EpochsForDate(sourceDate: String): Int

    @Query("SELECT * FROM ppi247_epoch ORDER BY epochStartEpochMs DESC LIMIT 2000")
    fun observeRecentPpi247Epochs(): Flow<List<Ppi247EpochEntity>>

    @Query(
        """
        SELECT * FROM wake_marker
        WHERE markerEpochMs >= :startEpochMs AND markerEpochMs <= :endEpochMs
        ORDER BY markerEpochMs ASC
        """
    )
    suspend fun getWakeMarkersBetween(startEpochMs: Long, endEpochMs: Long): List<WakeMarkerEntity>

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
