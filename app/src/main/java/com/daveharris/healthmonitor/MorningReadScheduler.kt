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
    private const val INTERVAL_MS = 10 * 60 * 1000L

    fun scheduleNextCheck(context: Context, targetDate: String, deviceId: String?): Long {
        val triggerAtMs = System.currentTimeMillis() + INTERVAL_MS
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val generation = prefs.getLong(GENERATION, 0L) + 1L
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.setWindow(
            AlarmManager.RTC_WAKEUP,
            triggerAtMs,
            2 * 60 * 1000L,
            pendingIntent(context, deviceId, generation)
        )
        prefs.edit()
            .putLong(NEXT_CHECK_EPOCH_MS, triggerAtMs)
            .putString(TARGET_DATE, targetDate)
            .putLong(GENERATION, generation)
            .apply()
        return triggerAtMs
    }

    fun cancel(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent(context, null, prefs.getLong(GENERATION, 0L)))
        prefs.edit()
            .remove(NEXT_CHECK_EPOCH_MS)
            .remove(TARGET_DATE)
            .putLong(GENERATION, prefs.getLong(GENERATION, 0L) + 1L)
            .apply()
    }

    fun isCurrentCheck(context: Context, targetDate: String, generation: Long): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return generation > 0L &&
            prefs.getLong(GENERATION, 0L) == generation &&
            prefs.getString(TARGET_DATE, null) == targetDate
    }

    private fun pendingIntent(context: Context, deviceId: String?, generation: Long): PendingIntent {
        val intent = Intent(context, ProbeCommandReceiver::class.java).apply {
            putExtra(ProbeCommandReceiver.EXTRA_COMMAND, "morning_read_check")
            deviceId?.let { putExtra(ProbeCommandReceiver.EXTRA_DEVICE_ID, it) }
            putExtra(ProbeCommandReceiver.EXTRA_MORNING_READ_GENERATION, generation)
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
