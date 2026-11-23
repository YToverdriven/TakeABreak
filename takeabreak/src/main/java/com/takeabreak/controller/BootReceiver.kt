package com.takeabreak.controller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Runs the link check, daily status schedule, and SMS outbox check after boot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        PeerLink.ensureScheduled(context)
        DailyStatusWorker.syncSchedule(context)
        PeerLink.check(context, "boot")
        SmsAlerts.processQueue(context)
    }
}
