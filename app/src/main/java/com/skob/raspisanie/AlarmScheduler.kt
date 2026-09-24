package com.skob.raspisanie

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.LocalDateTime
import java.time.ZoneId

object AlarmScheduler {

    fun scheduleNext(context: Context) {
        val config = ScheduleEngine.loadConfig(context)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        var next = LocalDateTime.now()
            .withHour(config.notifyHour)
            .withMinute(config.notifyMinute)
            .withSecond(0)
            .withNano(0)
        if (next.isBefore(LocalDateTime.now())) {
            next = next.plusDays(1)
        }
        val triggerAt = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val intent = Intent(context, ScheduleAlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

        if (canExact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }
}
