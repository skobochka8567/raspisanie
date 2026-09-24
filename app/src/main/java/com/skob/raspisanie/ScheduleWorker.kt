package com.skob.raspisanie

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class ScheduleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            val config = ScheduleEngine.loadConfig(applicationContext)
            val message = ScheduleEngine.buildMessage(config)
            NotificationHelper.show(applicationContext, message)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
