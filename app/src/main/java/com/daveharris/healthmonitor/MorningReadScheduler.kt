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
    private const val INTERVAL_MS = 10 * 60 * 1000L

    fun scheduleNextCheck(context: Context, targetDate: String, deviceId: String?): Long {
        val triggerAtMs = System.currentTimeMillis() + INTERVAL_MS
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.setWindow(
            AlarmManager.RTC_WAKEUP,
            triggerAtMs,
            2 * 60 * 1000L,
            pendingIntent(context, deviceId)
        )
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(NEXT_CHECK_EPOCH_MS, triggerAtMs)
            .putString(TARGET_DATE, targetDate)
            .apply()
        return triggerAtMs
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent(context, null))
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(NEXT_CHECK_EPOCH_MS)
            .remove(TARGET_DATE)
            .apply()
    }

    private fun pendingIntent(context: Context, deviceId: String?): PendingIntent {
        val intent = Intent(context, ProbeCommandReceiver::class.java).apply {
            putExtra(ProbeCommandReceiver.EXTRA_COMMAND, "morning_read_check")
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
