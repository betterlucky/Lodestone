package com.daveharris.healthmonitor.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        DeviceProfileEntity::class,
        FtuProfileEntity::class,
        ObservedCapabilityEntity::class,
        SyncRunEntity::class,
        SyncDomainResultEntity::class,
        SleepNightRawEntity::class,
        NightlyRechargeRawEntity::class,
        Hr247DayRawEntity::class,
        Ppi247DayRawEntity::class,
        SkinTemperatureRawEntity::class,
        Ppi247EpochEntity::class,
        Hr247EpochEntity::class,
        SkinTemperatureSampleEntity::class,
        DailySummaryRawEntity::class,
        ActivitySamplesRawEntity::class,
        ActivityEpochEntity::class,
        AppSettingsEntity::class,
        DailyCheckInEntity::class,
        MorningPredictionSnapshotEntity::class,
        WakeMarkerEntity::class,
        FoodDailySummaryEntity::class,
        FoodLogItemEntity::class,
        DailyWeightEntity::class
    ],
    version = 20,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun probeDao(): ProbeDao

    companion object {
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Reserve v9 after the short-lived direct weight-entry experiment.
                // Weight will be harvested from the food-log CSV once that export is stable.
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS daily_weight")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS offline_recording_session (
                        recordingPath TEXT NOT NULL PRIMARY KEY,
                        syncDomainResultId INTEGER NOT NULL,
                        syncRunId INTEGER NOT NULL,
                        deviceId TEXT NOT NULL,
                        dataType TEXT NOT NULL,
                        recordingListKind TEXT NOT NULL,
                        firmwareVersion TEXT,
                        recordingDateLocal TEXT,
                        recordingStartEpochMs INTEGER,
                        recordingEndEpochMs INTEGER,
                        fetchedAtEpochMs INTEGER NOT NULL,
                        sampleCount INTEGER NOT NULL,
                        usableSampleCount INTEGER NOT NULL,
                        mode TEXT NOT NULL,
                        payloadSummaryJson TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS offline_ppi_epoch (
                        recordingPath TEXT NOT NULL,
                        syncDomainResultId INTEGER NOT NULL,
                        syncRunId INTEGER NOT NULL,
                        deviceId TEXT NOT NULL,
                        sourceDate TEXT NOT NULL,
                        epochStartEpochMs INTEGER NOT NULL,
                        epochEndEpochMs INTEGER NOT NULL,
                        epochIndex INTEGER NOT NULL,
                        sampleCount INTEGER NOT NULL,
                        usableSampleCount INTEGER NOT NULL,
                        skinContactFalseCount INTEGER NOT NULL,
                        blockerCount INTEGER NOT NULL,
                        highErrorCount INTEGER NOT NULL,
                        ppiLowCount INTEGER NOT NULL,
                        ppiHighCount INTEGER NOT NULL,
                        meanPpiMs REAL,
                        medianPpiMs REAL,
                        ppiP10Ms REAL,
                        ppiP90Ms REAL,
                        rmssdMs REAL,
                        meanHrBpm REAL,
                        minHrBpm INTEGER,
                        maxHrBpm INTEGER,
                        medianErrorEstimateMs REAL,
                        errorEstimateP90Ms REAL,
                        epochQuality TEXT NOT NULL,
                        PRIMARY KEY(recordingPath, epochStartEpochMs)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_offline_ppi_epoch_sourceDate ON offline_ppi_epoch(sourceDate)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_offline_ppi_epoch_syncDomainResultId ON offline_ppi_epoch(syncDomainResultId)")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE daily_check_in ADD COLUMN muscleWeaknessToday INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS daily_weight (
                        sourceDate TEXT NOT NULL PRIMARY KEY,
                        measuredTime TEXT,
                        weightKg REAL NOT NULL,
                        notes TEXT,
                        importSource TEXT,
                        importedAtEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS wake_marker (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sourceDate TEXT NOT NULL,
                        markerEpochMs INTEGER NOT NULL,
                        markerSource TEXT NOT NULL,
                        deviceId TEXT,
                        notes TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_wake_marker_sourceDate ON wake_marker(sourceDate)")
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ppi247_epoch (
                        deviceId TEXT NOT NULL,
                        sourceDate TEXT NOT NULL,
                        epochStartEpochMs INTEGER NOT NULL,
                        epochEndEpochMs INTEGER NOT NULL,
                        sampleCount INTEGER NOT NULL,
                        usableSampleCount INTEGER NOT NULL,
                        skinContactFalseCount INTEGER NOT NULL,
                        movementDetectedCount INTEGER NOT NULL,
                        offlineIntervalCount INTEGER NOT NULL,
                        highErrorCount INTEGER NOT NULL,
                        ppiLowCount INTEGER NOT NULL,
                        ppiHighCount INTEGER NOT NULL,
                        meanPpiMs REAL,
                        medianPpiMs REAL,
                        ppiP10Ms REAL,
                        ppiP90Ms REAL,
                        rmssdMs REAL,
                        meanHrBpm REAL,
                        minHrBpm INTEGER,
                        maxHrBpm INTEGER,
                        medianErrorEstimateMs REAL,
                        errorEstimateP90Ms REAL,
                        epochQuality TEXT NOT NULL,
                        triggerTypesCsv TEXT NOT NULL,
                        updatedAtEpochMs INTEGER NOT NULL,
                        PRIMARY KEY(sourceDate, epochStartEpochMs)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ppi247_epoch_sourceDate ON ppi247_epoch(sourceDate)")
            }
        }

        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS skin_temperature_sample (
                        deviceId TEXT NOT NULL,
                        sourceDate TEXT NOT NULL,
                        sampleTimeEpochMs INTEGER NOT NULL,
                        recordingTimeDeltaMs INTEGER NOT NULL,
                        temperatureCelsius REAL NOT NULL,
                        sensorLocation TEXT,
                        measurementType TEXT,
                        updatedAtEpochMs INTEGER NOT NULL,
                        PRIMARY KEY(deviceId, sampleTimeEpochMs)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_skin_temperature_sample_sourceDate ON skin_temperature_sample(sourceDate)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS activity_epoch (
                        deviceId TEXT NOT NULL,
                        sourceDate TEXT NOT NULL,
                        epochStartEpochMs INTEGER NOT NULL,
                        epochEndEpochMs INTEGER NOT NULL,
                        met REAL,
                        steps INTEGER,
                        activityClass TEXT,
                        activityFactor REAL,
                        metRecordingIntervalSeconds INTEGER,
                        stepRecordingIntervalSeconds INTEGER,
                        updatedAtEpochMs INTEGER NOT NULL,
                        PRIMARY KEY(deviceId, epochStartEpochMs)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_activity_epoch_sourceDate ON activity_epoch(sourceDate)")
            }
        }

        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS offline_ppi_epoch")
                db.execSQL("DROP TABLE IF EXISTS offline_recording_session")
                db.execSQL(
                    """
                    DELETE FROM sync_domain_result
                    WHERE domain = 'OFFLINE_RECORDING'
                       OR domain = 'TRAINING_SESSION_SMOKE'
                       OR requestedRange LIKE '%offline%'
                       OR requestedRange LIKE '%training%'
                       OR detailSummary LIKE '%offline%'
                       OR detailSummary LIKE '%training%'
                       OR manualNotes LIKE '%offline%'
                       OR manualNotes LIKE '%training%'
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    DELETE FROM sync_run
                    WHERE notes LIKE '%offline%'
                       OR notes LIKE '%training session smoke%'
                       OR notes LIKE '%training smoke%'
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    UPDATE sync_domain_result
                    SET rawPayloadJson = NULL
                    WHERE rawPayloadJson IS NOT NULL
                      AND LENGTH(rawPayloadJson) > 250000
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS hr247_epoch (
                        deviceId TEXT NOT NULL,
                        sourceDate TEXT NOT NULL,
                        epochStartEpochMs INTEGER NOT NULL,
                        epochEndEpochMs INTEGER NOT NULL,
                        sampleCount INTEGER NOT NULL,
                        meanHrBpm REAL,
                        medianHrBpm REAL,
                        minHrBpm INTEGER,
                        maxHrBpm INTEGER,
                        triggerTypesCsv TEXT NOT NULL,
                        epochQuality TEXT NOT NULL,
                        updatedAtEpochMs INTEGER NOT NULL,
                        PRIMARY KEY(deviceId, epochStartEpochMs)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_hr247_epoch_sourceDate ON hr247_epoch(sourceDate)")
            }
        }

        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS morning_prediction_snapshot (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sourceDate TEXT NOT NULL,
                        issuedAtEpochMs INTEGER NOT NULL,
                        snapshotOrigin TEXT NOT NULL,
                        modelVersion TEXT NOT NULL,
                        status TEXT NOT NULL,
                        confidence TEXT NOT NULL,
                        isInterim INTEGER NOT NULL,
                        sleepDataReady INTEGER NOT NULL,
                        overnightAutonomicSource TEXT NOT NULL,
                        sleepDurationMinutes INTEGER,
                        nightlyRmssd REAL,
                        baselineReady INTEGER NOT NULL,
                        recoveryAvailable INTEGER NOT NULL,
                        rawPpiGoodEpochCount INTEGER,
                        rawPpiPoorEpochCount INTEGER,
                        rawPpiCoverageHours REAL,
                        summary TEXT NOT NULL,
                        reasonsJson TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_morning_prediction_snapshot_sourceDate ON morning_prediction_snapshot(sourceDate)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_morning_prediction_snapshot_sourceDate_snapshotOrigin ON morning_prediction_snapshot(sourceDate, snapshotOrigin)")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "health-monitor-probe.db"
        ).addMigrations(
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15,
            MIGRATION_15_16,
            MIGRATION_16_17,
            MIGRATION_17_18,
            MIGRATION_18_19,
            MIGRATION_19_20
        )
            .build()
    }
}
