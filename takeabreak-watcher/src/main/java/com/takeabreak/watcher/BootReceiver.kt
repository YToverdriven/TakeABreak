package com.takeabreak.watcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Runs the link check and SMS outbox check after the phone has finished booting. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        PeerLink.ensureScheduled(context)
        PeerLink.check(context, "boot")
        SmsAlerts.processQueue(context)
    }
}
