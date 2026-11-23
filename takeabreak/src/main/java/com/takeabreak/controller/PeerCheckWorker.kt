package com.takeabreak.controller

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** One short job, about once an hour. It does not stay running. */
class PeerCheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        PeerLink.check(applicationContext, "hourly")
        return Result.success()
    }
}
