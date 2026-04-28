package com.daveharris.healthmonitor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

object OvernightPpiScheduler {
    private const val PREFS_NAME = "overnight_ppi"
    private const val START_REQUEST_CODE = 4601
    private const val STOP_REQUEST_CODE = 4602

    fun scheduleNextStart(context: Context, timeText: String, deviceId: String?): Long =
        scheduleCommand(
            context = context,
            timeText = timeText,
            deviceId = deviceId,
            requestCode = START_REQUEST_CODE,
            command = "offline_start"
        )

    fun scheduleNextStartAfter(
        context: Context,
        timeText: String,
        deviceId: String?,
        earliest: LocalDateTime
    ): Long =
        scheduleCommand(
            context = context,
            timeText = timeText,
            deviceId = deviceId,
            requestCode = START_REQUEST_CODE,
            command = "offline_start",
            earliest = earliest
        )

    fun scheduleNextStop(context: Context, timeText: String, deviceId: String?): Long =
        scheduleCommand(
            context = context,
            timeText = timeText,
            deviceId = deviceId,
            requestCode = STOP_REQUEST_CODE,
            command = "offline_stop_fetch"
        )

    fun scheduleNextStopAfter(
        context: Context,
        timeText: String,
        deviceId: String?,
        earliest: LocalDateTime
    ): Long =
        scheduleCommand(
            context = context,
            timeText = timeText,
            deviceId = deviceId,
            requestCode = STOP_REQUEST_CODE,
            command = "offline_stop_fetch",
            earliest = earliest
        )

    fun cancelStart(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent(context, START_REQUEST_CODE, "offline_start", null))
    }

    fun cancelStop(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent(context, STOP_REQUEST_CODE, "offline_stop_fetch", null))
    }

    private fun scheduleCommand(
        context: Context,
        timeText: String,
        deviceId: String?,
        requestCode: Int,
        command: String,
        earliest: LocalDateTime = LocalDateTime.now().plusMinutes(1)
    ): Long {
        val startTime = LocalTime.parse(timeText)
        var next = LocalDateTime.of(LocalDate.from(earliest), startTime)
        if (next.isBefore(earliest)) {
            next = next.plusDays(1)
        }
        val triggerAtMs = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.setWindow(
            AlarmManager.RTC_WAKEUP,
            triggerAtMs,
            15 * 60 * 1000L,
            pendingIntent(context, requestCode, command, deviceId)
        )
        return triggerAtMs
    }

    fun cancel(context: Context) {
        cancelStart(context)
        cancelStop(context)
    }

    fun rescheduleEnabled(context: Context, completedCommand: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", null)
        val earliest = LocalDateTime.now().plusHours(12)
        val editor = prefs.edit()
        when (completedCommand) {
            "offline_start" -> if (prefs.getBoolean("start_enabled", false)) {
                val next = scheduleNextStartAfter(
                    context = context,
                    timeText = prefs.getString("start_time", "23:00") ?: "23:00",
                    deviceId = deviceId,
                    earliest = earliest
                )
                editor.putLong("next_start_epoch_ms", next)
            }
            "offline_stop_fetch" -> if (prefs.getBoolean("stop_enabled", false)) {
                val next = scheduleNextStopAfter(
                    context = context,
                    timeText = prefs.getString("stop_time", "10:30") ?: "10:30",
                    deviceId = deviceId,
                    earliest = earliest
                )
                editor.putLong("next_stop_epoch_ms", next)
            }
        }
        editor.apply()
    }

    private fun pendingIntent(
        context: Context,
        requestCode: Int,
        command: String,
        deviceId: String?
    ): PendingIntent {
        val intent = Intent(context, ProbeCommandReceiver::class.java).apply {
            putExtra(ProbeCommandReceiver.EXTRA_COMMAND, command)
            putExtra(ProbeCommandReceiver.EXTRA_DATA_TYPE, "PPI")
            deviceId?.let { putExtra(ProbeCommandReceiver.EXTRA_DEVICE_ID, it) }
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
