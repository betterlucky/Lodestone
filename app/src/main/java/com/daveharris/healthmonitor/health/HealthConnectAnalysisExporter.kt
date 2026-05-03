package com.daveharris.healthmonitor.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.daveharris.healthmonitor.util.GsonProvider
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class HealthConnectAnalysisExporter(
    private val context: Context,
    private val clockZone: ZoneId = ZoneId.systemDefault()
) {
    private val client: HealthConnectClient by lazy {
        HealthConnectClient.getOrCreate(context.applicationContext)
    }

    suspend fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context.applicationContext) == HealthConnectClient.SDK_AVAILABLE

    suspend fun grantedPermissions(): Set<String> =
        if (isAvailable()) client.permissionController.getGrantedPermissions() else emptySet()

    suspend fun hasRequiredPermissions(): Boolean =
        grantedPermissions().containsAll(REQUIRED_PERMISSIONS)

    suspend fun exportSleepAnalysis(targetDate: LocalDate): File {
        check(isAvailable()) { "Health Connect is not available on this device." }
        val granted = grantedPermissions()
        val missing = REQUIRED_PERMISSIONS - granted
        check(missing.isEmpty()) {
            "Health Connect permissions missing: ${missing.joinToString()}"
        }

        val start = targetDate.minusDays(1).atTime(LocalTime.of(18, 0)).atZone(clockZone).toInstant()
        val end = targetDate.plusDays(1).atTime(LocalTime.of(18, 0)).atZone(clockZone).toInstant()
        val timeRange = TimeRangeFilter.between(start, end)
        val sleepSessions = client.readRecords(
            ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = timeRange
            )
        ).records
        val heartRateRecords = client.readRecords(
            ReadRecordsRequest(
                recordType = HeartRateRecord::class,
                timeRangeFilter = timeRange
            )
        ).records
        val hrvRecords = client.readRecords(
            ReadRecordsRequest(
                recordType = HeartRateVariabilityRmssdRecord::class,
                timeRangeFilter = timeRange
            )
        ).records

        val payload = mapOf(
            "purpose" to "analysis_only_health_connect_sleep_export",
            "targetDate" to targetDate.toString(),
            "queryWindow" to mapOf(
                "start" to start.toString(),
                "end" to end.toString(),
                "zone" to clockZone.id
            ),
            "permissionsGranted" to granted.sorted(),
            "sleepSessions" to sleepSessions.map { session ->
                mapOf(
                    "startTime" to session.startTime.toString(),
                    "endTime" to session.endTime.toString(),
                    "startZoneOffset" to session.startZoneOffset?.toString(),
                    "endZoneOffset" to session.endZoneOffset?.toString(),
                    "title" to session.title,
                    "notes" to session.notes,
                    "metadata" to metadataPayload(session.metadata),
                    "stages" to session.stages.map { stage ->
                        mapOf(
                            "startTime" to stage.startTime.toString(),
                            "endTime" to stage.endTime.toString(),
                            "stage" to stage.stage,
                            "stageLabel" to sleepStageLabel(stage.stage)
                        )
                    }
                )
            },
            "heartRateRecords" to heartRateRecords.map { record ->
                mapOf(
                    "startTime" to record.startTime.toString(),
                    "endTime" to record.endTime.toString(),
                    "startZoneOffset" to record.startZoneOffset?.toString(),
                    "endZoneOffset" to record.endZoneOffset?.toString(),
                    "metadata" to metadataPayload(record.metadata),
                    "samples" to record.samples.map { sample ->
                        mapOf(
                            "time" to sample.time.toString(),
                            "beatsPerMinute" to sample.beatsPerMinute
                        )
                    }
                )
            },
            "heartRateVariabilityRmssdRecords" to hrvRecords.map { record ->
                mapOf(
                    "time" to record.time.toString(),
                    "zoneOffset" to record.zoneOffset?.toString(),
                    "metadata" to metadataPayload(record.metadata),
                    "heartRateVariabilityMillis" to record.heartRateVariabilityMillis
                )
            }
        )

        val dir = File(context.getExternalFilesDir(null), "analysis-health-connect").apply { mkdirs() }
        val file = File(dir, "health-connect-sleep-${targetDate}.json")
        file.writeText(GsonProvider.gson.toJson(payload))
        return file
    }

    private fun metadataPayload(metadata: androidx.health.connect.client.records.metadata.Metadata): Map<String, Any?> =
        mapOf(
            "id" to metadata.id,
            "clientRecordId" to metadata.clientRecordId,
            "dataOriginPackageName" to metadata.dataOrigin.packageName,
            "lastModifiedTime" to metadata.lastModifiedTime.toString(),
            "recordingMethod" to metadata.recordingMethod
        )

    private fun sleepStageLabel(stage: Int): String = when (stage) {
        SleepSessionRecord.STAGE_TYPE_AWAKE -> "awake"
        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> "awake_in_bed"
        SleepSessionRecord.STAGE_TYPE_SLEEPING -> "sleeping"
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "out_of_bed"
        SleepSessionRecord.STAGE_TYPE_LIGHT -> "light"
        SleepSessionRecord.STAGE_TYPE_DEEP -> "deep"
        SleepSessionRecord.STAGE_TYPE_REM -> "rem"
        SleepSessionRecord.STAGE_TYPE_UNKNOWN -> "unknown"
        else -> "stage_$stage"
    }

    companion object {
        val REQUIRED_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class)
        )

        fun requestPermissionContract(): androidx.activity.result.contract.ActivityResultContract<Set<String>, Set<String>> =
            PermissionController.createRequestPermissionResultContract()
    }
}
