package com.daveharris.healthmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.daveharris.healthmonitor.data.SyncRunProfile
import com.daveharris.healthmonitor.data.SyncWindowConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate

class ProbeCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val command = intent.getStringExtra(EXTRA_COMMAND)?.lowercase().orEmpty()
        if (SyncCommandWorker.shouldHandle(command)) {
            SyncCommandWorker.enqueue(context.applicationContext, intent)
            Log.i(TAG, "Command '$command' handed off to sync worker")
            return
        }

        val pendingResult = goAsync()
        val app = context.applicationContext as HealthMonitorApp
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                executeCommandFromIntent(app, intent)
                Log.i(TAG, "Command '$command' completed")
            } catch (t: Throwable) {
                Log.e(TAG, "Command '$command' failed: ${t.message}", t)
                handleCommandFailure(app, command, intent, t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    internal suspend fun executeCommandFromIntent(app: HealthMonitorApp, intent: Intent) {
        val command = intent.getStringExtra(EXTRA_COMMAND)?.lowercase().orEmpty()
        executeCommand(
            app = app,
            command = command,
            deviceId = intent.getStringExtra(EXTRA_DEVICE_ID),
            foodDate = intent.getStringExtra(EXTRA_FOOD_DATE),
            fromDate = intent.getStringExtra(EXTRA_FROM_DATE),
            toDate = intent.getStringExtra(EXTRA_TO_DATE),
            triggerAtEpochMs = intent.getLongExtra(EXTRA_TRIGGER_AT_EPOCH_MS, -1L),
            overrideSleepDays = intent.getIntExtra(EXTRA_SLEEP_DAYS, -1).takeIf { it > 0 },
            overrideNightlyRechargeDays = intent.getIntExtra(EXTRA_NIGHTLY_RECHARGE_DAYS, -1).takeIf { it > 0 },
            overrideHrDays = intent.getIntExtra(EXTRA_HR_DAYS, -1).takeIf { it > 0 },
            overridePpiDays = intent.getIntExtra(EXTRA_PPI_DAYS, -1).takeIf { it > 0 },
            morningReadGeneration = intent.getLongExtra(EXTRA_MORNING_READ_GENERATION, -1L),
            morningRetryStage = intent.getStringExtra(EXTRA_MORNING_RETRY_STAGE),
            morningRetryAttempt = intent.getIntExtra(EXTRA_MORNING_RETRY_ATTEMPT, 1)
        )
    }

    internal suspend fun handleCommandFailure(
        app: HealthMonitorApp,
        command: String,
        intent: Intent,
        error: Throwable
    ) {
        if (error is StaleMorningReadCheckException) return
        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
        val attempt = intent.getIntExtra(EXTRA_ATTEMPT, 1)
        if (command == "morning_read_check" && error.message != "Sync already running") {
            val selectedDeviceId = deviceId ?: app.container.repository.getAppSettings()?.selectedDeviceId
            val stage = MorningReadScheduler.fromWireName(intent.getStringExtra(EXTRA_MORNING_RETRY_STAGE))
                ?: MorningRetryStage.PPI
            val nextRetry = MorningReadScheduler.scheduleNextAttempt(
                context = app.applicationContext,
                targetDate = LocalDate.now().toString(),
                deviceId = selectedDeviceId,
                stage = stage,
                currentAttempt = intent.getIntExtra(EXTRA_MORNING_RETRY_ATTEMPT, 1)
            )
            if (nextRetry != null) {
                Log.i(TAG, "Rescheduled $stage morning read check after failure.")
            } else if (stage == MorningRetryStage.PPI) {
                MorningReadScheduler.scheduleSleepReportRetry(
                    context = app.applicationContext,
                    targetDate = LocalDate.now().toString(),
                    deviceId = selectedDeviceId
                )
                Log.e(TAG, "PPI morning read check exhausted after failure; moving to sleep report retry.")
            } else {
                MorningReadScheduler.cancel(app.applicationContext)
                Log.e(TAG, "$stage morning read check exhausted retry attempts after failure.")
            }
        }
        if (command == "offline_ppi_start") {
            val selectedDeviceId = deviceId ?: app.container.repository.getAppSettings()?.selectedDeviceId
            val nextRetry = OfflinePpiScheduler.scheduleRetry(app.applicationContext, selectedDeviceId, attempt)
            if (nextRetry != null) {
                Log.i(TAG, "Rescheduled offline PPI start attempt ${attempt + 1} for $nextRetry")
            } else {
                Log.e(TAG, "Offline PPI start exhausted ${OfflinePpiScheduler.MAX_START_ATTEMPTS} attempts")
            }
        }
    }

    private suspend fun scheduleNextMorningStageIfNeeded(
        app: HealthMonitorApp,
        deviceId: String,
        targetDate: String,
        stage: MorningRetryStage,
        attempt: Int
    ) {
        val repository = app.container.repository
        if (repository.hasSleepRecordForDate(targetDate)) {
            MorningReadScheduler.cancel(app.applicationContext)
            Log.i(TAG, "Morning retry complete: final sleep report is present for $targetDate")
            return
        }

        when (stage) {
            MorningRetryStage.PPI -> {
                if (repository.hasPpiRecordForDate(targetDate)) {
                    MorningReadScheduler.scheduleSleepReportRetry(app.applicationContext, targetDate, deviceId)
                    Log.i(TAG, "Morning retry advanced: PPI present, scheduled sleep report retry for $targetDate")
                    return
                }
                val nextRetry = MorningReadScheduler.scheduleNextAttempt(
                    context = app.applicationContext,
                    targetDate = targetDate,
                    deviceId = deviceId,
                    stage = stage,
                    currentAttempt = attempt
                )
                if (nextRetry != null) {
                    Log.i(TAG, "Morning PPI retry scheduled for $targetDate after missing PPI")
                } else {
                    MorningReadScheduler.scheduleSleepReportRetry(app.applicationContext, targetDate, deviceId)
                    Log.e(TAG, "Morning PPI retry exhausted for $targetDate; moving to sleep report retry")
                }
            }
            MorningRetryStage.SLEEP_REPORT -> {
                val nextRetry = MorningReadScheduler.scheduleNextAttempt(
                    context = app.applicationContext,
                    targetDate = targetDate,
                    deviceId = deviceId,
                    stage = stage,
                    currentAttempt = attempt
                )
                if (nextRetry != null) {
                    Log.i(TAG, "Morning sleep report retry scheduled for $targetDate")
                } else {
                    MorningReadScheduler.cancel(app.applicationContext)
                    Log.e(TAG, "Morning sleep report retry exhausted for $targetDate")
                }
            }
        }
    }

    private suspend fun executeCommand(
        app: HealthMonitorApp,
        command: String,
        deviceId: String?,
        foodDate: String?,
        fromDate: String?,
        toDate: String?,
        triggerAtEpochMs: Long,
        overrideSleepDays: Int?,
        overrideNightlyRechargeDays: Int?,
        overrideHrDays: Int?,
        overridePpiDays: Int?,
        morningReadGeneration: Long,
        morningRetryStage: String?,
        morningRetryAttempt: Int
    ) {
        val repository = app.container.repository
        val syncCoordinator = app.container.syncCoordinator
        val dailyReviewRepository = app.container.dailyReviewRepository
        val healthConnectAnalysisExporter = app.container.healthConnectAnalysisExporter
        val settings = repository.getAppSettings()
        val selectedDeviceId = deviceId ?: settings?.selectedDeviceId
        val syncConfig = settings?.let {
            SyncWindowConfig(
                sleepDays = overrideSleepDays ?: it.sleepDays,
                nightlyRechargeDays = overrideNightlyRechargeDays ?: it.nightlyRechargeDays,
                hrDays = overrideHrDays ?: it.hrDays,
                ppiDays = overridePpiDays ?: it.ppiDays
            )
        } ?: SyncWindowConfig(
            sleepDays = overrideSleepDays ?: SyncWindowConfig().sleepDays,
            nightlyRechargeDays = overrideNightlyRechargeDays ?: SyncWindowConfig().nightlyRechargeDays,
            hrDays = overrideHrDays ?: SyncWindowConfig().hrDays,
            ppiDays = overridePpiDays ?: SyncWindowConfig().ppiDays
        )

        suspend fun persistSelection(id: String) {
            repository.saveAppSettings(
                selectedDeviceId = id,
                syncWindowConfig = syncConfig,
                lastKnownFirmwareBySelectedDevice = settings?.lastKnownFirmwareBySelectedDevice
            )
        }

        when (command) {
            "scan" -> repository.search()
            "connect" -> {
                val id = requireNotNull(selectedDeviceId) { "connect requires automation_device_id or selected device" }
                val connectedId = syncCoordinator.runExclusiveDeviceOperation(id) { it }
                persistSelection(connectedId)
            }
            "disconnect" -> {
                val id = requireNotNull(selectedDeviceId) { "disconnect requires automation_device_id or selected device" }
                repository.disconnect(id)
            }
            "discover" -> {
                val id = requireNotNull(selectedDeviceId) { "discover requires automation_device_id or selected device" }
                val connectedId = syncCoordinator.runExclusiveDeviceOperation(id) { connectedId ->
                    repository.runCapabilityDiscovery(connectedId).getOrThrow()
                    connectedId
                }
                persistSelection(connectedId)
            }
            "sync" -> {
                val id = requireNotNull(selectedDeviceId) { "sync requires automation_device_id or selected device" }
                val result = syncCoordinator.runSync(id, syncConfig, SyncRunProfile.FULL)
                persistSelection(result.connectedDeviceId)
            }
            "export_json" -> {
                val file = repository.exportInspectorData(app.applicationContext).getOrThrow()
                Log.i(TAG, "Exported inspector data to ${file.absolutePath}")
            }
            "full_probe" -> {
                val id = requireNotNull(selectedDeviceId) { "full_probe requires automation_device_id or selected device" }
                val result = syncCoordinator.runSync(id, syncConfig, SyncRunProfile.FULL)
                persistSelection(result.connectedDeviceId)
            }
            "manual_awake_sync" -> {
                val id = requireNotNull(selectedDeviceId) { "$command requires automation_device_id or selected device" }
                val result = syncCoordinator.runSync(
                    deviceId = id,
                    config = syncConfig,
                    profile = SyncRunProfile.MORNING_CORE,
                    scheduleMorningRetryIfNeeded = true,
                    cancelMorningRetryFirst = true,
                    wakeMarkerNotes = "manual awake command"
                )
                persistSelection(result.connectedDeviceId)
            }
            "morning_read_check" -> {
                val id = requireNotNull(selectedDeviceId) { "morning_read_check requires automation_device_id or selected device" }
                val targetDate = LocalDate.now().toString()
                val stage = MorningReadScheduler.fromWireName(morningRetryStage) ?: MorningRetryStage.PPI
                val attempt = morningRetryAttempt.coerceAtLeast(1)
                if (!MorningReadScheduler.isCurrentCheck(app.applicationContext, targetDate, morningReadGeneration, stage)) {
                    Log.i(TAG, "Morning read check skipped: stale $stage generation for $targetDate")
                    return
                }
                if (repository.hasSleepRecordForDate(targetDate)) {
                    MorningReadScheduler.cancel(app.applicationContext)
                    Log.i(TAG, "Morning read check skipped: final sleep report is already present for $targetDate")
                    return
                }
                if (stage == MorningRetryStage.PPI && repository.hasPpiRecordForDate(targetDate)) {
                    MorningReadScheduler.scheduleSleepReportRetry(app.applicationContext, targetDate, id)
                    Log.i(TAG, "Morning PPI retry skipped: PPI is already present for $targetDate")
                    return
                }
                val result = syncCoordinator.runSync(
                    deviceId = id,
                    config = syncConfig,
                    profile = when (stage) {
                        MorningRetryStage.PPI -> SyncRunProfile.MORNING_PPI_RETRY
                        MorningRetryStage.SLEEP_REPORT -> SyncRunProfile.MORNING_SLEEP_RETRY
                    },
                    morningReadGuard = MorningReadGuard(targetDate, morningReadGeneration, stage)
                )
                persistSelection(result.connectedDeviceId)
                scheduleNextMorningStageIfNeeded(app, result.connectedDeviceId, targetDate, stage, attempt)
            }
            "ppi247_rebuild_epochs" -> {
                val count = repository.rebuildPpi247EpochTables().getOrThrow()
                Log.i(TAG, "Rebuilt $count 24/7 PPI epoch rows")
            }
            "ppi247_range_probe" -> {
                val id = requireNotNull(selectedDeviceId) { "ppi247_range_probe requires automation_device_id or selected device" }
                val from = LocalDate.parse(requireNotNull(fromDate) { "ppi247_range_probe requires probe_from_date" })
                val to = LocalDate.parse(toDate ?: fromDate)
                val result = syncCoordinator.runExclusiveDeviceOperation(id) { connectedId ->
                    persistSelection(connectedId)
                    repository.runPpi247RangeProbe(connectedId, from, to).getOrThrow()
                }
                val runId = result
                Log.i(TAG, "PPI_247 range probe completed: runId=$runId, range=$from..$to")
            }
            "offline_ppi_schedule_start" -> {
                val id = requireNotNull(selectedDeviceId) { "offline_ppi_schedule_start requires automation_device_id or selected device" }
                require(triggerAtEpochMs > 0L) { "offline_ppi_schedule_start requires probe_trigger_at_epoch_ms" }
                OfflinePpiScheduler.scheduleStart(app.applicationContext, triggerAtEpochMs, id)
                Log.i(TAG, "Scheduled offline PPI start for $triggerAtEpochMs")
            }
            "offline_ppi_start" -> {
                val id = requireNotNull(selectedDeviceId) { "offline_ppi_start requires automation_device_id or selected device" }
                val runId = syncCoordinator.runExclusiveDeviceOperation(id) { connectedId ->
                    persistSelection(connectedId)
                    repository.startOfflinePpiProbe(connectedId).getOrThrow()
                }
                Log.i(TAG, "Offline PPI started: runId=$runId")
            }
            "offline_ppi_stop_fetch" -> {
                val id = requireNotNull(selectedDeviceId) { "offline_ppi_stop_fetch requires automation_device_id or selected device" }
                val runId = syncCoordinator.runExclusiveDeviceOperation(id) { connectedId ->
                    persistSelection(connectedId)
                    repository.stopAndFetchOfflinePpiProbe(connectedId).getOrThrow()
                }
                Log.i(TAG, "Offline PPI stopped/fetched: runId=$runId")
            }
            "disk_space_probe" -> {
                val id = requireNotNull(selectedDeviceId) { "disk_space_probe requires automation_device_id or selected device" }
                val runId = syncCoordinator.runExclusiveDeviceOperation(id) { connectedId ->
                    persistSelection(connectedId)
                    repository.runDiskSpaceProbe(connectedId).getOrThrow()
                }
                Log.i(TAG, "Disk space probe completed: runId=$runId")
            }
            "offline_ppg_cleanup" -> {
                val id = requireNotNull(selectedDeviceId) { "offline_ppg_cleanup requires automation_device_id or selected device" }
                val runId = syncCoordinator.runExclusiveDeviceOperation(id) { connectedId ->
                    persistSelection(connectedId)
                    repository.removeOfflinePpgExperimentRecords(connectedId).getOrThrow()
                }
                Log.i(TAG, "Offline PPG cleanup completed: runId=$runId")
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
            "health_connect_export" -> {
                val date = LocalDate.parse(foodDate ?: fromDate ?: LocalDate.now().toString())
                val file = healthConnectAnalysisExporter.exportSleepAnalysis(date)
                Log.i(TAG, "Exported Health Connect sleep analysis to ${file.absolutePath}")
            }
            else -> error("Unknown command '$command'")
        }
    }

    companion object {
        private const val TAG = "ProbeCommandReceiver"
        const val EXTRA_COMMAND = "probe_command"
        const val EXTRA_DEVICE_ID = "probe_device_id"
        const val EXTRA_FOOD_DATE = "probe_food_date"
        const val EXTRA_FROM_DATE = "probe_from_date"
        const val EXTRA_TO_DATE = "probe_to_date"
        const val EXTRA_TRIGGER_AT_EPOCH_MS = "probe_trigger_at_epoch_ms"
        const val EXTRA_ATTEMPT = "probe_attempt"
        const val EXTRA_SLEEP_DAYS = "probe_sleep_days"
        const val EXTRA_NIGHTLY_RECHARGE_DAYS = "probe_nightly_recharge_days"
        const val EXTRA_HR_DAYS = "probe_hr_days"
        const val EXTRA_PPI_DAYS = "probe_ppi_days"
        const val EXTRA_MORNING_READ_GENERATION = "probe_morning_read_generation"
        const val EXTRA_MORNING_RETRY_STAGE = "probe_morning_retry_stage"
        const val EXTRA_MORNING_RETRY_ATTEMPT = "probe_morning_retry_attempt"
    }
}
