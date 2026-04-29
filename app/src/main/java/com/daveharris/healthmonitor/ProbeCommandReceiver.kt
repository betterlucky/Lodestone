package com.daveharris.healthmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.daveharris.healthmonitor.data.SyncWindowConfig
import com.polar.sdk.api.PolarBleApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.LocalDate

class ProbeCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val app = context.applicationContext as HealthMonitorApp
        val command = intent.getStringExtra(EXTRA_COMMAND)?.lowercase().orEmpty()
        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
        val durationSeconds = intent.getIntExtra(EXTRA_DURATION_SECONDS, 300)
        val offlineDataType = intent.getStringExtra(EXTRA_DATA_TYPE)
        val foodDate = intent.getStringExtra(EXTRA_FOOD_DATE)

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                executeCommand(app, command, deviceId, durationSeconds, offlineDataType, foodDate)
                if (command == "offline_start" || command == "offline_stop_fetch") {
                    OvernightPpiScheduler.rescheduleEnabled(app.applicationContext, command)
                }
                Log.i(TAG, "Command '$command' completed for device=${deviceId ?: "selected"}")
            } catch (t: Throwable) {
                Log.e(TAG, "Command '$command' failed: ${t.message}", t)
                if (command == "morning_read_check") {
                    val selectedDeviceId = deviceId ?: app.container.repository.getAppSettings()?.selectedDeviceId
                    MorningReadScheduler.scheduleNextCheck(
                        context = app.applicationContext,
                        targetDate = LocalDate.now().toString(),
                        deviceId = selectedDeviceId
                    )
                    Log.i(TAG, "Rescheduled morning read check after failure.")
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun executeCommand(
        app: HealthMonitorApp,
        command: String,
        deviceId: String?,
        durationSeconds: Int,
        offlineDataType: String?,
        foodDate: String?
    ) {
        val repository = app.container.repository
        val dailyReviewRepository = app.container.dailyReviewRepository
        val settings = repository.getAppSettings()
        val selectedDeviceId = deviceId ?: settings?.selectedDeviceId
        val syncConfig = settings?.let {
            SyncWindowConfig(
                sleepDays = it.sleepDays,
                nightlyRechargeDays = it.nightlyRechargeDays,
                hrDays = it.hrDays,
                ppiDays = it.ppiDays
            )
        } ?: SyncWindowConfig()

        suspend fun persistSelection(id: String) {
            repository.saveAppSettings(
                selectedDeviceId = id,
                syncWindowConfig = syncConfig,
                lastKnownFirmwareBySelectedDevice = settings?.lastKnownFirmwareBySelectedDevice
            )
        }

        suspend fun connectAndAwait(id: String): String {
            fun com.daveharris.healthmonitor.polar.DeviceRuntimeState.matchesConnectedDevice(): Boolean {
                val device = connectedDevice
                return connectionPhase == "connected" &&
                    (
                        device?.deviceId.equals(id, ignoreCase = true) ||
                            device?.address.equals(id, ignoreCase = true)
                        )
            }

            repository.connect(id)
            withTimeout(45_000) {
                repository.runtimeState.first { runtime ->
                    runtime.matchesConnectedDevice()
                }
            }
            withTimeout(20_000) {
                repository.runtimeState.first { runtime ->
                    runtime.matchesConnectedDevice() &&
                        (
                            runtime.firmwareVersion != null ||
                                runtime.readyFeatures.isNotEmpty() ||
                                runtime.unavailableFeatures.isNotEmpty()
                            )
                }
            }
            return repository.runtimeState.value.connectedDevice?.deviceId ?: id
        }

        fun parsedOfflineDataType(): PolarBleApi.PolarDeviceDataType =
            offlineDataType
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { PolarBleApi.PolarDeviceDataType.valueOf(it.uppercase()) }
                ?: PolarBleApi.PolarDeviceDataType.PPI

        when (command) {
            "scan" -> repository.search()
            "connect" -> {
                val id = requireNotNull(selectedDeviceId) { "connect requires automation_device_id or selected device" }
                val connectedId = connectAndAwait(id)
                persistSelection(connectedId)
            }
            "disconnect" -> {
                val id = requireNotNull(selectedDeviceId) { "disconnect requires automation_device_id or selected device" }
                repository.disconnect(id)
            }
            "discover" -> {
                val id = requireNotNull(selectedDeviceId) { "discover requires automation_device_id or selected device" }
                val connectedId = connectAndAwait(id)
                persistSelection(connectedId)
                repository.runCapabilityDiscovery(connectedId).getOrThrow()
            }
            "sync" -> {
                val id = requireNotNull(selectedDeviceId) { "sync requires automation_device_id or selected device" }
                val connectedId = connectAndAwait(id)
                persistSelection(connectedId)
                repository.runManualSync(connectedId, syncConfig).getOrThrow()
            }
            "export_json" -> {
                val file = repository.exportInspectorData(app.applicationContext).getOrThrow()
                Log.i(TAG, "Exported inspector data to ${file.absolutePath}")
            }
            "full_probe" -> {
                val id = requireNotNull(selectedDeviceId) { "full_probe requires automation_device_id or selected device" }
                val connectedId = connectAndAwait(id)
                persistSelection(connectedId)
                repository.runManualSync(connectedId, syncConfig).getOrThrow()
            }
            "training_smoke" -> {
                val id = requireNotNull(selectedDeviceId) { "training_smoke requires automation_device_id or selected device" }
                val connectedId = connectAndAwait(id)
                persistSelection(connectedId)
                repository.runTrainingSessionSmokeTest(connectedId, durationSeconds).getOrThrow()
            }
            "training_start" -> {
                val id = requireNotNull(selectedDeviceId) { "training_start requires automation_device_id or selected device" }
                val connectedId = connectAndAwait(id)
                persistSelection(connectedId)
                repository.startTrainingSessionSmoke(connectedId).getOrThrow()
            }
            "training_stop_fetch" -> {
                val id = requireNotNull(selectedDeviceId) { "training_stop_fetch requires automation_device_id or selected device" }
                val connectedId = connectAndAwait(id)
                persistSelection(connectedId)
                repository.stopAndFetchTrainingSessionSmoke(connectedId).getOrThrow()
            }
            "offline_start" -> {
                val id = requireNotNull(selectedDeviceId) { "offline_start requires automation_device_id or selected device" }
                val connectedId = connectAndAwait(id)
                persistSelection(connectedId)
                repository.startNormalOfflineRecordingSmoke(connectedId, parsedOfflineDataType()).getOrThrow()
            }
            "offline_stop_fetch" -> {
                val id = requireNotNull(selectedDeviceId) { "offline_stop_fetch requires automation_device_id or selected device" }
                val connectedId = connectAndAwait(id)
                persistSelection(connectedId)
                repository.stopAndFetchNormalOfflineRecordingSmoke(connectedId, parsedOfflineDataType()).getOrThrow()
                repository.runManualSync(connectedId, syncConfig).getOrThrow()
                scheduleMorningReadCheckIfNeeded(app, connectedId)
            }
            "manual_awake_stop_fetch" -> {
                val id = requireNotNull(selectedDeviceId) { "manual_awake_stop_fetch requires automation_device_id or selected device" }
                val connectedId = connectAndAwait(id)
                persistSelection(connectedId)
                repository.recordWakeMarker(
                    sourceDate = LocalDate.now().toString(),
                    deviceId = connectedId,
                    notes = "manual awake command"
                )
                repository.stopAndFetchNormalOfflineRecordingSmoke(connectedId, parsedOfflineDataType()).getOrThrow()
                repository.runManualSync(connectedId, syncConfig).getOrThrow()
                scheduleMorningReadCheckIfNeeded(app, connectedId)
            }
            "morning_read_check" -> {
                val id = requireNotNull(selectedDeviceId) { "morning_read_check requires automation_device_id or selected device" }
                val connectedId = connectAndAwait(id)
                persistSelection(connectedId)
                repository.runManualSync(connectedId, syncConfig).getOrThrow()
                scheduleMorningReadCheckIfNeeded(app, connectedId)
            }
            "offline_rebuild_epochs" -> {
                val count = repository.rebuildOfflinePpiEpochTables().getOrThrow()
                Log.i(TAG, "Rebuilt $count offline PPI epoch rows")
            }
            "ppi247_rebuild_epochs" -> {
                val count = repository.rebuildPpi247EpochTables().getOrThrow()
                Log.i(TAG, "Rebuilt $count 24/7 PPI epoch rows")
            }
            "food_sync" -> {
                val date = foodDate ?: java.time.LocalDate.now().toString()
                val count = dailyReviewRepository.importLatestFoodCsvFromDownloads(app.applicationContext, date).getOrThrow()
                Log.i(TAG, "Imported $count food day summaries from food CSV for $date")
            }
            else -> error("Unknown command '$command'")
        }
    }

    private suspend fun scheduleMorningReadCheckIfNeeded(app: HealthMonitorApp, deviceId: String) {
        val targetDate = LocalDate.now().toString()
        if (app.container.repository.hasSleepRecordForDate(targetDate)) {
            MorningReadScheduler.cancel(app.applicationContext)
        } else {
            MorningReadScheduler.scheduleNextCheck(app.applicationContext, targetDate, deviceId)
        }
    }

    companion object {
        private const val TAG = "ProbeCommandReceiver"
        const val EXTRA_COMMAND = "probe_command"
        const val EXTRA_DEVICE_ID = "probe_device_id"
        const val EXTRA_DURATION_SECONDS = "probe_duration_seconds"
        const val EXTRA_DATA_TYPE = "probe_data_type"
        const val EXTRA_FOOD_DATE = "probe_food_date"
    }
}
