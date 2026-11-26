package com.takeabreak.watcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private lateinit var smsWarning: TextView
    private lateinit var phoneWarning: TextView
    private val debugPanel by lazy { DebugPanel(this, ::refreshWarnings) }

    private val configReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshWarnings()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PeerLink.check(this, "opened")

        if (!PermissionsSetupActivity.isSetupComplete(this)) {
            if (PermissionsSetupActivity.hasSmsPermission(this)) {
                // Existing install upgraded from an earlier version; skip the first-time wizard.
                PermissionsSetupActivity.setSetupComplete(this, complete = true)
            } else {
                startActivity(
                    Intent(this, PermissionsSetupActivity::class.java)
                        .putExtra(PermissionsSetupActivity.EXTRA_FIRST_SETUP, true),
                )
                finish()
                return
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        val status = TextView(this).apply {
            text = getString(R.string.watcher_status_summary, versionName())
            textSize = 16f
            setPadding(0, 0, 0, 12)
        }
        smsWarning = TextView(this).apply {
            text = getString(R.string.watcher_sms_off_note)
            textSize = 14f
            setPadding(0, 0, 0, 8)
        }
        phoneWarning = TextView(this).apply {
            text = getString(R.string.watcher_phone_not_set_note)
            textSize = 14f
            setPadding(0, 0, 0, 12)
        }
        val setupBtn = Button(this).apply {
            text = getString(R.string.watcher_setup_title)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, PermissionsSetupActivity::class.java))
            }
        }

        root.addView(status)
        root.addView(smsWarning)
        root.addView(phoneWarning)
        root.addView(setupBtn)
        debugPanel.attach(root)

        val outerScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        setContentView(outerScroll)
    }

    override fun onStart() {
        super.onStart()
        if (!PermissionsSetupActivity.isSetupComplete(this)) return
        ContextCompat.registerReceiver(
            this,
            configReceiver,
            IntentFilter(AlertMessageReceiver.ACTION_UPDATED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        refreshWarnings()
        debugPanel.onStart()
    }

    override fun onResume() {
        super.onResume()
        if (!PermissionsSetupActivity.isSetupComplete(this)) return
        PeerLink.check(this, "opened")
        refreshWarnings()
        debugPanel.refresh()
    }

    override fun onStop() {
        try {
            unregisterReceiver(configReceiver)
        } catch (_: Exception) {
        }
        debugPanel.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        debugPanel.onDestroy()
        super.onDestroy()
    }

    @Deprecated("Use the activity result API when the UI is rewritten.")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        debugPanel.onRequestPermissionsResult(requestCode, grantResults)
    }

    private fun refreshWarnings() {
        if (::smsWarning.isInitialized) {
            smsWarning.visibility =
                if (PermissionsSetupActivity.hasSmsPermission(this)) View.GONE else View.VISIBLE
        }
        if (::phoneWarning.isInitialized) {
            val hasNumber = SmsAlerts.isValidPhoneNumber(SmsAlerts.getPhoneNumber(this))
            phoneWarning.visibility = if (hasNumber) View.GONE else View.VISIBLE
        }
    }

    private fun versionName(): String = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0.12.1"
    } catch (_: Exception) {
        "0.12.1"
    }
}
