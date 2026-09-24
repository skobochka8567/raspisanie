package com.skob.raspisanie

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class ScheduleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val request = OneTimeWorkRequestBuilder<ScheduleWorker>().build()
        WorkManager.getInstance(context).enqueue(request)
        // Планируем следующее срабатывание на завтра
        AlarmScheduler.scheduleNext(context)
    }
}
