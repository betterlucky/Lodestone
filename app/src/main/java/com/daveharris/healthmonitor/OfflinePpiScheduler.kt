package com.daveharris.healthmonitor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object OfflinePpiScheduler {
    private const val REQUEST_CODE = 4801
    private const val RETRY_DELAY_MS = 5 * 60 * 1000L
    const val MAX_START_ATTEMPTS = 4

    fun scheduleStart(
        context: Context,
        triggerAtEpochMs: Long,
        deviceId: String?,
        attempt: Int = 1
    ): Long {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.setWindow(
            AlarmManager.RTC_WAKEUP,
            triggerAtEpochMs,
            2 * 60 * 1000L,
            pendingIntent(context, deviceId, attempt)
        )
        return triggerAtEpochMs
    }

    fun scheduleRetry(context: Context, deviceId: String?, previousAttempt: Int): Long? {
        val nextAttempt = previousAttempt + 1
        if (nextAttempt > MAX_START_ATTEMPTS) return null
        return scheduleStart(
            context = context,
            triggerAtEpochMs = System.currentTimeMillis() + RETRY_DELAY_MS,
            deviceId = deviceId,
            attempt = nextAttempt
        )
    }

    fun cancelStart(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent(context, null, 1))
    }

    private fun pendingIntent(context: Context, deviceId: String?, attempt: Int): PendingIntent {
        val intent = Intent(context, ProbeCommandReceiver::class.java).apply {
            putExtra(ProbeCommandReceiver.EXTRA_COMMAND, "offline_ppi_start")
            putExtra(ProbeCommandReceiver.EXTRA_ATTEMPT, attempt)
            deviceId?.let { putExtra(ProbeCommandReceiver.EXTRA_DEVICE_ID, it) }
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
