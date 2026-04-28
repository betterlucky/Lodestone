package com.daveharris.healthmonitor.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "device_profile")
data class DeviceProfileEntity(
    @PrimaryKey val deviceId: String,
    val name: String,
    val address: String,
    val firmwareVersion: String?,
    val batteryLevel: Int?,
    val isConnected: Boolean,
    val lastSeenAtEpochMs: Long,
    val readyFeaturesJson: String,
    val unavailableFeaturesJson: String,
    val featureSummary: String,
    val notes: String?
)

@Entity(tableName = "ftu_profile")
data class FtuProfileEntity(
    @PrimaryKey val deviceId: String,
    val gender: String,
    val birthDateIso: String,
    val heightCm: Float,
    val weightKg: Float,
    val maxHeartRate: Int,
    val vo2Max: Int,
    val restingHeartRate: Int,
    val trainingBackground: Int,
    val typicalDay: String,
    val sleepGoalMinutes: Int,
    val deviceTimeIso: String,
    val isCompleted: Boolean,
    val lastSubmittedAtEpochMs: Long?,
    val lastKnownDeviceState: String
)

@Entity(tableName = "observed_capability", primaryKeys = ["deviceId", "domain"])
data class ObservedCapabilityEntity(
    val deviceId: String,
    val domain: String,
    val status: String,
    val source: String,
    val requestedRange: String,
    val firmwareVersion: String?,
    val readyFeature: String?,
    val unavailableFeature: String?,
    val details: String,
    val lastObservedAtEpochMs: Long
)

@Entity(tableName = "sync_run")
data class SyncRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: String,
    val firmwareVersion: String?,
    val appVersion: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long?,
    val status: String,
    val notes: String?
)

@Entity(tableName = "sync_domain_result")
data class SyncDomainResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val syncRunId: Long,
    val deviceId: String,
    val domain: String,
    val requestedRange: String,
    val status: String,
    val recordCount: Int,
    val parserVersion: Int,
    val parseStatus: String,
    val detailSummary: String,
    val rawPayloadJson: String?,
    val manualNotes: String?,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
    val errorCode: String?,
    val errorMessage: String?
)

@Entity(tableName = "sleep_night_raw")
data class SleepNightRawEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: String,
    val sourceDate: String?,
    val requestedRange: String,
    val syncTimestampEpochMs: Long,
    val keySummary: String,
    val rawPayloadJson: String,
    val parserVersion: Int,
    val parseStatus: String
)

@Entity(tableName = "nightly_recharge_raw")
data class NightlyRechargeRawEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: String,
    val sourceDate: String?,
    val requestedRange: String,
    val syncTimestampEpochMs: Long,
    val keySummary: String,
    val rawPayloadJson: String,
    val parserVersion: Int,
    val parseStatus: String
)

@Entity(tableName = "hr247_day_raw")
data class Hr247DayRawEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: String,
    val sourceDate: String,
    val requestedRange: String,
    val syncTimestampEpochMs: Long,
    val keySummary: String,
    val rawPayloadJson: String,
    val parserVersion: Int,
    val parseStatus: String
)

@Entity(tableName = "ppi247_day_raw")
data class Ppi247DayRawEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: String,
    val sourceDate: String,
    val requestedRange: String,
    val syncTimestampEpochMs: Long,
    val keySummary: String,
    val rawPayloadJson: String,
    val parserVersion: Int,
    val parseStatus: String
)

@Entity(tableName = "skin_temperature_raw")
data class SkinTemperatureRawEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: String,
    val sourceDate: String,
    val requestedRange: String,
    val syncTimestampEpochMs: Long,
    val keySummary: String,
    val rawPayloadJson: String,
    val parserVersion: Int,
    val parseStatus: String
)

@Entity(tableName = "daily_summary_raw")
data class DailySummaryRawEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: String,
    val sourceDate: String,
    val requestedRange: String,
    val syncTimestampEpochMs: Long,
    val keySummary: String,
    val rawPayloadJson: String,
    val parserVersion: Int,
    val parseStatus: String
)

@Entity(tableName = "activity_samples_raw")
data class ActivitySamplesRawEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: String,
    val sourceDate: String,
    val requestedRange: String,
    val syncTimestampEpochMs: Long,
    val keySummary: String,
    val rawPayloadJson: String,
    val parserVersion: Int,
    val parseStatus: String
)

@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val selectedDeviceId: String?,
    val sleepDays: Int,
    val nightlyRechargeDays: Int,
    val hrDays: Int,
    val ppiDays: Int,
    val lastKnownFirmwareBySelectedDevice: String?
)

@Entity(tableName = "daily_check_in")
data class DailyCheckInEntity(
    @PrimaryKey val sourceDate: String,
    val eveningOutcome: String,
    val approachToDay: String?,
    val muscleWeaknessToday: Boolean,
    val notes: String?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)

@Entity(
    tableName = "wake_marker",
    indices = [Index("sourceDate")]
)
data class WakeMarkerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceDate: String,
    val markerEpochMs: Long,
    val markerSource: String,
    val deviceId: String?,
    val notes: String?
)

@Entity(tableName = "food_daily_summary")
data class FoodDailySummaryEntity(
    @PrimaryKey val sourceDate: String,
    val totalCaloriesKcal: Int?,
    val eventCount: Int?,
    val teaCount: Int?,
    val firstIntakeTime: String?,
    val lastIntakeTime: String?,
    val eatingWindowHours: Double?,
    val rawItemsJson: String?,
    val importSource: String?,
    val importedAtEpochMs: Long
)

@Entity(tableName = "food_log_item")
data class FoodLogItemEntity(
    @PrimaryKey val fingerprint: String,
    val sourceDate: String,
    val timeLocal: String,
    val item: String,
    val quantity: String,
    val caloriesKcal: Int?,
    val notes: String?,
    val importSource: String?,
    val importedAtEpochMs: Long
)

@Entity(tableName = "daily_weight")
data class DailyWeightEntity(
    @PrimaryKey val sourceDate: String,
    val measuredTime: String?,
    val weightKg: Double,
    val notes: String?,
    val importSource: String?,
    val importedAtEpochMs: Long
)

@Entity(tableName = "offline_recording_session")
data class OfflineRecordingSessionEntity(
    @PrimaryKey val recordingPath: String,
    val syncDomainResultId: Long,
    val syncRunId: Long,
    val deviceId: String,
    val dataType: String,
    val recordingListKind: String,
    val firmwareVersion: String?,
    val recordingDateLocal: String?,
    val recordingStartEpochMs: Long?,
    val recordingEndEpochMs: Long?,
    val fetchedAtEpochMs: Long,
    val sampleCount: Int,
    val usableSampleCount: Int,
    val mode: String,
    val payloadSummaryJson: String
)

@Entity(
    tableName = "offline_ppi_epoch",
    primaryKeys = ["recordingPath", "epochStartEpochMs"],
    indices = [
        Index("sourceDate"),
        Index("syncDomainResultId")
    ]
)
data class OfflinePpiEpochEntity(
    val recordingPath: String,
    val syncDomainResultId: Long,
    val syncRunId: Long,
    val deviceId: String,
    val sourceDate: String,
    val epochStartEpochMs: Long,
    val epochEndEpochMs: Long,
    val epochIndex: Int,
    val sampleCount: Int,
    val usableSampleCount: Int,
    val skinContactFalseCount: Int,
    val blockerCount: Int,
    val highErrorCount: Int,
    val ppiLowCount: Int,
    val ppiHighCount: Int,
    val meanPpiMs: Double?,
    val medianPpiMs: Double?,
    val ppiP10Ms: Double?,
    val ppiP90Ms: Double?,
    val rmssdMs: Double?,
    val meanHrBpm: Double?,
    val minHrBpm: Int?,
    val maxHrBpm: Int?,
    val medianErrorEstimateMs: Double?,
    val errorEstimateP90Ms: Double?,
    val epochQuality: String
)
