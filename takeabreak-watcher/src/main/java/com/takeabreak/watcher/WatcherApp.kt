package com.takeabreak.watcher

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

class WatcherApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PeerLink.ensureScheduled(this)
        SmsAlerts.processQueue(this)
        // While this process is alive, hear disable and uninstall immediately.
        // The manifest receiver still hears FULLY_REMOVED and DATA_CLEARED when it is not.
        ContextCompat.registerReceiver(
            this,
            PackageMonitorReceiver(),
            PackageMonitorReceiver.filter(),
            ContextCompat.RECEIVER_EXPORTED,
        )
        // Flush queued SMS promptly if airplane mode turns off while watcher is alive.
        val airplaneFilter = IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        val airplaneReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if ((intent.action == Intent.ACTION_AIRPLANE_MODE_CHANGED) &&
                    !intent.getBooleanExtra("state", false)
                ) {
                    SmsAlerts.onAirplaneModeOff(context)
                }
            }
        }
        ContextCompat.registerReceiver(
            this,
            airplaneReceiver,
            airplaneFilter,
            ContextCompat.RECEIVER_EXPORTED,
        )
    }
}
