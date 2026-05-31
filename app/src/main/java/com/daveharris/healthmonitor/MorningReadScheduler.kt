package com.daveharris.healthmonitor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object MorningReadScheduler {
    private const val REQUEST_CODE = 4701
    private const val PREFS_NAME = "morning_read"
    private const val NEXT_CHECK_EPOCH_MS = "next_check_epoch_ms"
    private const val TARGET_DATE = "target_date"
    private const val GENERATION = "generation"
    private const val RETRY_STAGE = "retry_stage"
    private const val RETRY_ATTEMPT = "retry_attempt"
    private const val PPI_INTERVAL_MS = 10 * 60 * 1000L
    private const val SLEEP_REPORT_INTERVAL_MS = 60 * 60 * 1000L
    private const val PPI_MAX_ATTEMPTS = 3
    private const val SLEEP_REPORT_MAX_ATTEMPTS = 6

    fun schedulePpiRetry(context: Context, targetDate: String, deviceId: String?, attempt: Int = 1): Long =
        scheduleRetry(context, targetDate, deviceId, MorningRetryStage.PPI, attempt)

    fun scheduleSleepReportRetry(context: Context, targetDate: String, deviceId: String?, attempt: Int = 1): Long =
        scheduleRetry(context, targetDate, deviceId, MorningRetryStage.SLEEP_REPORT, attempt)

    fun scheduleNextAttempt(
        context: Context,
        targetDate: String,
        deviceId: String?,
        stage: MorningRetryStage,
        currentAttempt: Int
    ): Long? {
        val nextAttempt = currentAttempt + 1
        return if (nextAttempt <= maxAttempts(stage)) {
            scheduleRetry(context, targetDate, deviceId, stage, nextAttempt)
        } else {
            null
        }
    }

    fun maxAttempts(stage: MorningRetryStage): Int =
        when (stage) {
            MorningRetryStage.PPI -> PPI_MAX_ATTEMPTS
            MorningRetryStage.SLEEP_REPORT -> SLEEP_REPORT_MAX_ATTEMPTS
        }

    fun fromWireName(value: String?): MorningRetryStage? =
        MorningRetryStage.entries.firstOrNull { it.name == value }

    fun scheduledTargetDate(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(TARGET_DATE, null)

    private fun scheduleRetry(
        context: Context,
        targetDate: String,
        deviceId: String?,
        stage: MorningRetryStage,
        attempt: Int
    ): Long {
        val clampedAttempt = attempt.coerceIn(1, maxAttempts(stage))
        val triggerAtMs = System.currentTimeMillis() + intervalMs(stage)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val generation = prefs.getLong(GENERATION, 0L) + 1L
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.setWindow(
            AlarmManager.RTC_WAKEUP,
            triggerAtMs,
            windowMs(stage),
            pendingIntent(context, targetDate, deviceId, generation, stage, clampedAttempt)
        )
        prefs.edit()
            .putLong(NEXT_CHECK_EPOCH_MS, triggerAtMs)
            .putString(TARGET_DATE, targetDate)
            .putLong(GENERATION, generation)
            .putString(RETRY_STAGE, stage.name)
            .putInt(RETRY_ATTEMPT, clampedAttempt)
            .apply()
        return triggerAtMs
    }

    fun cancel(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(
            pendingIntent(
                context = context,
                targetDate = prefs.getString(TARGET_DATE, null),
                deviceId = null,
                generation = prefs.getLong(GENERATION, 0L),
                stage = MorningRetryStage.PPI,
                attempt = 1
            )
        )
        prefs.edit()
            .remove(NEXT_CHECK_EPOCH_MS)
            .remove(TARGET_DATE)
            .remove(RETRY_STAGE)
            .remove(RETRY_ATTEMPT)
            .putLong(GENERATION, prefs.getLong(GENERATION, 0L) + 1L)
            .apply()
    }

    fun isCurrentCheck(context: Context, targetDate: String, generation: Long): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return generation > 0L &&
            prefs.getLong(GENERATION, 0L) == generation &&
            prefs.getString(TARGET_DATE, null) == targetDate
    }

    fun isCurrentCheck(
        context: Context,
        targetDate: String,
        generation: Long,
        stage: MorningRetryStage
    ): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return isCurrentCheck(context, targetDate, generation) &&
            prefs.getString(RETRY_STAGE, null) == stage.name
    }

    private fun intervalMs(stage: MorningRetryStage): Long =
        when (stage) {
            MorningRetryStage.PPI -> PPI_INTERVAL_MS
            MorningRetryStage.SLEEP_REPORT -> SLEEP_REPORT_INTERVAL_MS
        }

    private fun windowMs(stage: MorningRetryStage): Long =
        when (stage) {
            MorningRetryStage.PPI -> 2 * 60 * 1000L
            MorningRetryStage.SLEEP_REPORT -> 10 * 60 * 1000L
        }

    private fun pendingIntent(
        context: Context,
        targetDate: String?,
        deviceId: String?,
        generation: Long,
        stage: MorningRetryStage,
        attempt: Int
    ): PendingIntent {
        val intent = Intent(context, ProbeCommandReceiver::class.java).apply {
            putExtra(ProbeCommandReceiver.EXTRA_COMMAND, "morning_read_check")
            deviceId?.let { putExtra(ProbeCommandReceiver.EXTRA_DEVICE_ID, it) }
            targetDate?.let { putExtra(ProbeCommandReceiver.EXTRA_MORNING_TARGET_DATE, it) }
            putExtra(ProbeCommandReceiver.EXTRA_MORNING_READ_GENERATION, generation)
            putExtra(ProbeCommandReceiver.EXTRA_MORNING_RETRY_STAGE, stage.name)
            putExtra(ProbeCommandReceiver.EXTRA_MORNING_RETRY_ATTEMPT, attempt)
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

enum class MorningRetryStage {
    PPI,
    SLEEP_REPORT
}
