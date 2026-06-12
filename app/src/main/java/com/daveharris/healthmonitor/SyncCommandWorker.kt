package com.daveharris.healthmonitor

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Data
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters

class SyncCommandWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val command = inputData.getString(ProbeCommandReceiver.EXTRA_COMMAND).orEmpty()

        val app = appContext.applicationContext as HealthMonitorApp
        val receiver = ProbeCommandReceiver()
        val intent = inputData.toCommandIntent(appContext)
        return try {
            receiver.executeCommandFromIntent(app, intent)
            Log.i(TAG, "Worker command '$command' completed")
            Result.success()
        } catch (t: Throwable) {
            if (t is StaleMorningReadCheckException) {
                Log.i(TAG, "Worker command '$command' skipped: ${t.message}")
                Result.success()
            } else {
                Log.e(TAG, "Worker command '$command' failed: ${t.message}", t)
                receiver.handleCommandFailure(app, command, intent, t)
                // Failures are already recorded/rescheduled by the command path.
                // Returning success prevents one transient BLE failure poisoning
                // the unique WorkManager chain and cancelling later commands.
                Result.success()
            }
        }
    }

    companion object {
        private const val TAG = "SyncCommandWorker"
        private const val BULK_WORK_NAME = "lodestone_loop_command"
        private const val MORNING_CHECK_WORK_NAME = "lodestone_morning_check_command"
        private const val MANUAL_AWAKE_WORK_NAME = "lodestone_manual_awake_command"

        private val WORKER_COMMANDS = setOf(
            "connect",
            "discover",
            "sync",
            "full_probe",
            "manual_awake_sync",
            "morning_read_check",
            "ppi247_range_probe",
            "offline_ppi_start",
            "offline_ppi_stop_fetch",
            "disk_space_probe",
            "autos_file_probe",
            "offline_ppg_cleanup"
        )

        fun shouldHandle(command: String): Boolean = command in WORKER_COMMANDS

        fun enqueue(context: Context, sourceIntent: Intent) {
            val command = sourceIntent.getStringExtra(ProbeCommandReceiver.EXTRA_COMMAND)?.lowercase().orEmpty()
            val requestBuilder = OneTimeWorkRequestBuilder<SyncCommandWorker>()
                .setInputData(sourceIntent.toWorkerData())
            if (command.isMorningCriticalCommand()) {
                // Save expedited quota for morning readiness checks; bulk syncs
                // can be deferred without blocking the user's day-start flow.
                requestBuilder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            val workName = command.uniqueWorkName()
            val request = requestBuilder
                .addTag(workName)
                .build()
            val workManager = WorkManager.getInstance(context)
            if (command.isMorningCriticalCommand()) {
                cancelBulkWork(workManager)
                if (command == "manual_awake_sync") {
                    workManager.cancelUniqueWork(MORNING_CHECK_WORK_NAME)
                }
            }
            workManager.enqueueUniqueWork(
                workName,
                command.existingWorkPolicy(),
                request
            )
        }

        fun cancelBulkWork(context: Context) {
            cancelBulkWork(WorkManager.getInstance(context))
        }

        private fun cancelBulkWork(workManager: WorkManager) {
            // Morning readiness wins over background enrichment. If a bulk sync
            // is queued or running, cancel it so the BLE mutex can be released
            // for the day-start path as soon as possible.
            workManager.cancelUniqueWork(BULK_WORK_NAME)
        }

        private fun String.isMorningCriticalCommand(): Boolean =
            this == "morning_read_check" || this == "manual_awake_sync"

        private fun String.uniqueWorkName(): String =
            when (this) {
                "morning_read_check" -> MORNING_CHECK_WORK_NAME
                "manual_awake_sync" -> MANUAL_AWAKE_WORK_NAME
                else -> BULK_WORK_NAME
            }

        private fun String.existingWorkPolicy(): ExistingWorkPolicy =
            when (this) {
                "manual_awake_sync" -> ExistingWorkPolicy.KEEP
                "morning_read_check" -> ExistingWorkPolicy.REPLACE
                else -> ExistingWorkPolicy.REPLACE
            }
    }
}

private fun Intent.toWorkerData(): Data =
    Data.Builder()
        .putOptionalString(ProbeCommandReceiver.EXTRA_COMMAND, getStringExtra(ProbeCommandReceiver.EXTRA_COMMAND))
        .putOptionalString(ProbeCommandReceiver.EXTRA_DEVICE_ID, getStringExtra(ProbeCommandReceiver.EXTRA_DEVICE_ID))
        .putOptionalString(ProbeCommandReceiver.EXTRA_FOOD_DATE, getStringExtra(ProbeCommandReceiver.EXTRA_FOOD_DATE))
        .putOptionalString(ProbeCommandReceiver.EXTRA_FROM_DATE, getStringExtra(ProbeCommandReceiver.EXTRA_FROM_DATE))
        .putOptionalString(ProbeCommandReceiver.EXTRA_TO_DATE, getStringExtra(ProbeCommandReceiver.EXTRA_TO_DATE))
        .putLong(ProbeCommandReceiver.EXTRA_TRIGGER_AT_EPOCH_MS, getLongExtra(ProbeCommandReceiver.EXTRA_TRIGGER_AT_EPOCH_MS, -1L))
        .putInt(ProbeCommandReceiver.EXTRA_ATTEMPT, getIntExtra(ProbeCommandReceiver.EXTRA_ATTEMPT, 1))
        .putInt(ProbeCommandReceiver.EXTRA_SLEEP_DAYS, getIntExtra(ProbeCommandReceiver.EXTRA_SLEEP_DAYS, -1))
        .putInt(ProbeCommandReceiver.EXTRA_NIGHTLY_RECHARGE_DAYS, getIntExtra(ProbeCommandReceiver.EXTRA_NIGHTLY_RECHARGE_DAYS, -1))
        .putInt(ProbeCommandReceiver.EXTRA_HR_DAYS, getIntExtra(ProbeCommandReceiver.EXTRA_HR_DAYS, -1))
        .putInt(ProbeCommandReceiver.EXTRA_PPI_DAYS, getIntExtra(ProbeCommandReceiver.EXTRA_PPI_DAYS, -1))
        .putLong(ProbeCommandReceiver.EXTRA_MORNING_READ_GENERATION, getLongExtra(ProbeCommandReceiver.EXTRA_MORNING_READ_GENERATION, -1L))
        .putOptionalString(ProbeCommandReceiver.EXTRA_MORNING_TARGET_DATE, getStringExtra(ProbeCommandReceiver.EXTRA_MORNING_TARGET_DATE))
        .putOptionalString(ProbeCommandReceiver.EXTRA_MORNING_RETRY_STAGE, getStringExtra(ProbeCommandReceiver.EXTRA_MORNING_RETRY_STAGE))
        .putInt(ProbeCommandReceiver.EXTRA_MORNING_RETRY_ATTEMPT, getIntExtra(ProbeCommandReceiver.EXTRA_MORNING_RETRY_ATTEMPT, 1))
        .build()

private fun Data.Builder.putOptionalString(key: String, value: String?): Data.Builder =
    apply {
        value?.let { putString(key, it) }
    }

private fun androidx.work.Data.toCommandIntent(context: Context): Intent =
    Intent(context, ProbeCommandReceiver::class.java).apply {
        putExtra(ProbeCommandReceiver.EXTRA_COMMAND, getString(ProbeCommandReceiver.EXTRA_COMMAND))
        getString(ProbeCommandReceiver.EXTRA_DEVICE_ID)?.let { putExtra(ProbeCommandReceiver.EXTRA_DEVICE_ID, it) }
        getString(ProbeCommandReceiver.EXTRA_FOOD_DATE)?.let { putExtra(ProbeCommandReceiver.EXTRA_FOOD_DATE, it) }
        getString(ProbeCommandReceiver.EXTRA_FROM_DATE)?.let { putExtra(ProbeCommandReceiver.EXTRA_FROM_DATE, it) }
        getString(ProbeCommandReceiver.EXTRA_TO_DATE)?.let { putExtra(ProbeCommandReceiver.EXTRA_TO_DATE, it) }
        putExtra(ProbeCommandReceiver.EXTRA_TRIGGER_AT_EPOCH_MS, getLong(ProbeCommandReceiver.EXTRA_TRIGGER_AT_EPOCH_MS, -1L))
        putExtra(ProbeCommandReceiver.EXTRA_ATTEMPT, getInt(ProbeCommandReceiver.EXTRA_ATTEMPT, 1))
        putExtra(ProbeCommandReceiver.EXTRA_SLEEP_DAYS, getInt(ProbeCommandReceiver.EXTRA_SLEEP_DAYS, -1))
        putExtra(ProbeCommandReceiver.EXTRA_NIGHTLY_RECHARGE_DAYS, getInt(ProbeCommandReceiver.EXTRA_NIGHTLY_RECHARGE_DAYS, -1))
        putExtra(ProbeCommandReceiver.EXTRA_HR_DAYS, getInt(ProbeCommandReceiver.EXTRA_HR_DAYS, -1))
        putExtra(ProbeCommandReceiver.EXTRA_PPI_DAYS, getInt(ProbeCommandReceiver.EXTRA_PPI_DAYS, -1))
        putExtra(ProbeCommandReceiver.EXTRA_MORNING_READ_GENERATION, getLong(ProbeCommandReceiver.EXTRA_MORNING_READ_GENERATION, -1L))
        getString(ProbeCommandReceiver.EXTRA_MORNING_TARGET_DATE)?.let { putExtra(ProbeCommandReceiver.EXTRA_MORNING_TARGET_DATE, it) }
        getString(ProbeCommandReceiver.EXTRA_MORNING_RETRY_STAGE)?.let { putExtra(ProbeCommandReceiver.EXTRA_MORNING_RETRY_STAGE, it) }
        putExtra(ProbeCommandReceiver.EXTRA_MORNING_RETRY_ATTEMPT, getInt(ProbeCommandReceiver.EXTRA_MORNING_RETRY_ATTEMPT, 1))
    }
