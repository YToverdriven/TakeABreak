package com.takeabreak.watcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * Hears Take a Break being uninstalled, disabled, or cleared.
 *
 * PACKAGE_FULLY_REMOVED and PACKAGE_DATA_CLEARED reach the manifest receiver
 * even when this app is in the background. PACKAGE_CHANGED (disabled) does not,
 * so the application also registers this receiver while the process is alive,
 * and the hourly check covers the time in between.
 */
class PackageMonitorReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_PACKAGE_REMOVED,
            Intent.ACTION_PACKAGE_FULLY_REMOVED,
            Intent.ACTION_PACKAGE_DATA_CLEARED,
            Intent.ACTION_PACKAGE_CHANGED,
            -> PeerLink.onPackageBroadcast(context, intent)
        }
    }

    companion object {
        fun filter(): IntentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
            addAction(Intent.ACTION_PACKAGE_DATA_CLEARED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
    }
}
