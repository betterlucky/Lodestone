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
        DailySummaryRawEntity::class,
        ActivitySamplesRawEntity::class,
        AppSettingsEntity::class,
        DailyCheckInEntity::class,
        WakeMarkerEntity::class,
        FoodDailySummaryEntity::class,
        FoodLogItemEntity::class,
        DailyWeightEntity::class,
        OfflineRecordingSessionEntity::class,
        OfflinePpiEpochEntity::class
    ],
    version = 14,
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

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "health-monitor-probe.db"
        ).addMigrations(MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14)
            .build()
    }
}
