package com.takeabreak.controller

import android.app.BroadcastOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/** Delivers alerts and synced SMS config to Take a Break Watcher. */
object WatcherMessages {
    const val ACTION = "com.takeabreak.watcher.ALERT_MESSAGE"
    const val ACTION_SYNC_CONFIG = "com.takeabreak.watcher.SYNC_CONFIG"
    const val EXTRA_TEXT = "text"
    const val EXTRA_PHONE = "phone"
    const val EXTRA_TAMPER_ENABLED = "tamper_enabled"
    private const val WATCHER_PACKAGE = "com.takeabreak.watcher"
    private const val WATCHER_RECEIVER = "com.takeabreak.watcher.AlertMessageReceiver"
    private const val PERMISSION = "com.takeabreak.permission.POST_MESSAGE"

    fun send(context: Context, message: String) {
        val text = message.trim()
        if (text.isEmpty()) return
        val intent = Intent(ACTION)
            .setComponent(ComponentName(WATCHER_PACKAGE, WATCHER_RECEIVER))
            .putExtra(EXTRA_TEXT, text)
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        sendSigned(context, intent, "Sent to watcher: $text")
    }

    /** Syncs the configured SMS number and tamper-alert toggle to Watcher over a signed broadcast. */
    fun syncConfig(context: Context) {
        val app = context.applicationContext
        val intent = Intent(ACTION_SYNC_CONFIG)
            .setComponent(ComponentName(WATCHER_PACKAGE, WATCHER_RECEIVER))
            .putExtra(EXTRA_PHONE, Prefs.getSmsPhoneNumber(app))
            .putExtra(EXTRA_TAMPER_ENABLED, Prefs.isAlertTamperEnabled(app))
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        sendSigned(app, intent, "Synced SMS config to watcher")
    }

    private fun sendSigned(context: Context, intent: Intent, logLine: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val options = BroadcastOptions.makeBasic().apply {
                    isShareIdentityEnabled = true
                }.toBundle()
                context.sendBroadcast(intent, PERMISSION, options)
            } else {
                context.sendBroadcast(intent, PERMISSION)
            }
            Log.i("TakeABreak", logLine)
        } catch (e: Exception) {
            Log.e("TakeABreak", "Failed to send to watcher", e)
        }
    }
}
