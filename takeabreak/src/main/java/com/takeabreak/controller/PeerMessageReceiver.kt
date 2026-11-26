package com.takeabreak.controller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/** The other app asks "are you there?" and this app answers. Signature permission only. */
class PeerMessageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val sender = sentFromPackage
            if (!sender.isNullOrEmpty() && (sender != PeerLink.PARTNER)) {
                Log.w("TakeABreak", "Ignored peer message from $sender")
                return
            }
        }
        val from = intent.getStringExtra(PeerLink.EXTRA_FROM) ?: return
        val reason = intent.getStringExtra(PeerLink.EXTRA_REASON).orEmpty()
        when (intent.action) {
            PeerLink.ACTION_PING -> PeerLink.onPing(context, from, reason)
            PeerLink.ACTION_PONG -> {
                val setupComplete = intent.getBooleanExtra(PeerLink.EXTRA_SETUP_COMPLETE, false)
                PeerLink.onPong(context, from, reason, setupComplete)
            }
        }
    }
}
