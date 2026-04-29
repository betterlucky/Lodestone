package com.daveharris.healthmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.daveharris.healthmonitor.data.SyncWindowConfig
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
        val foodDate = intent.getStringExtra(EXTRA_FOOD_DATE)

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                executeCommand(app, command, deviceId, foodDate)
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
            "manual_awake_sync" -> {
                val id = requireNotNull(selectedDeviceId) { "$command requires automation_device_id or selected device" }
                val connectedId = connectAndAwait(id)
                persistSelection(connectedId)
                repository.recordWakeMarker(
                    sourceDate = LocalDate.now().toString(),
                    deviceId = connectedId,
                    notes = "manual awake command"
                )
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
            "ppi247_rebuild_epochs" -> {
                val count = repository.rebuildPpi247EpochTables().getOrThrow()
                Log.i(TAG, "Rebuilt $count 24/7 PPI epoch rows")
            }
            "context_rebuild_epochs" -> {
                val count = repository.rebuildContextEpochTables().getOrThrow()
                Log.i(TAG, "Rebuilt $count context epoch/sample rows")
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
        const val EXTRA_FOOD_DATE = "probe_food_date"
    }
}
