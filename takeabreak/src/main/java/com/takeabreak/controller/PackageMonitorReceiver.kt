package com.takeabreak.controller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * Hears package installs, uninstalls, storage clears, and enable changes.
 *
 * Android 8 and later does not deliver PACKAGE_ADDED, PACKAGE_REMOVED, or
 * PACKAGE_CHANGED to a manifest receiver. The accessibility service registers
 * this receiver while it is running, so those still arrive. PACKAGE_FULLY_REMOVED
 * and PACKAGE_DATA_CLEARED are delivered to the manifest receiver even when the
 * service is off, which is what records an uninstall in the background.
 */
class PackageMonitorReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REMOVED,
            Intent.ACTION_PACKAGE_FULLY_REMOVED,
            Intent.ACTION_PACKAGE_DATA_CLEARED,
            Intent.ACTION_PACKAGE_CHANGED,
            -> PeerLink.onPackageBroadcast(context, intent)
        }
    }

    companion object {
        fun filter(): IntentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
            addAction(Intent.ACTION_PACKAGE_DATA_CLEARED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
    }
}
