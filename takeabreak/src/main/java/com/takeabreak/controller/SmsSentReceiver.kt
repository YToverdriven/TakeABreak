package com.takeabreak.controller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Receives delivery-handoff status from SmsManager so the outbox only retries failed sends. */
class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SmsAlerts.ACTION_SMS_SENT) return
        val id = intent.getLongExtra(SmsAlerts.EXTRA_ID, 0L)
        if (id <= 0L) return
        val partCount = intent.getIntExtra(SmsAlerts.EXTRA_PART_COUNT, 1)
        SmsAlerts.onSendResult(context, id, partCount, resultCode)
    }
}
