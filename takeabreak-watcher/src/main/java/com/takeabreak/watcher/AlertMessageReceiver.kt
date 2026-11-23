package com.takeabreak.watcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/** Stores alerts and synced SMS config sent by the Take a Break controller. */
class AlertMessageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if ((action != ACTION) && (action != ACTION_SYNC_CONFIG)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val sender = sentFromPackage
            if (!sender.isNullOrEmpty() && (sender != CONTROLLER_PACKAGE)) {
                Log.w("Watcher", "Ignored message from $sender")
                return
            }
        }
        if (action == ACTION_SYNC_CONFIG) {
            val phone = intent.getStringExtra(EXTRA_PHONE).orEmpty()
            val tamperEnabled = intent.getBooleanExtra(EXTRA_TAMPER_ENABLED, true)
            SmsAlerts.applySyncedConfig(context, phone, tamperEnabled)
            context.sendBroadcast(Intent(ACTION_UPDATED).setPackage(context.packageName))
            return
        }
        val text = intent.getStringExtra(EXTRA_TEXT)?.trim().orEmpty()
        if (text.isEmpty()) return
        if (text == PeerLink.ALERT_ACCESSIBILITY_OFF) {
            PeerLink.noteControllerReportedAccessibilityOff(context)
        }
        Log.i("Watcher", "Stored message: $text")
        ReceivedMessages.add(context, text)
        context.sendBroadcast(Intent(ACTION_UPDATED).setPackage(context.packageName))
    }

    companion object {
        const val ACTION = "com.takeabreak.watcher.ALERT_MESSAGE"
        const val ACTION_SYNC_CONFIG = "com.takeabreak.watcher.SYNC_CONFIG"
        const val ACTION_UPDATED = "com.takeabreak.watcher.MESSAGES_UPDATED"
        const val EXTRA_TEXT = "text"
        const val EXTRA_PHONE = "phone"
        const val EXTRA_TAMPER_ENABLED = "tamper_enabled"
        const val CONTROLLER_PACKAGE = "com.takeabreak.controller"
    }
}
