package com.takeabreak.controller

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Retries queued SMS alerts with backoff when returning from airplane mode or low service. */
class SmsRetryWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        SmsAlerts.processQueue(applicationContext)
        return Result.success()
    }
}
