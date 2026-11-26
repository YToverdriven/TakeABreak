package com.takeabreak.watcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.provider.Settings
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Debug-only Watcher UI: visible logs (Messages from Take a Break, Link log) and Test SMS box.
 * Excluded from release builds.
 */
class DebugPanel(
    private val activity: Activity,
    private val onStatusChanged: () -> Unit,
) {
    private lateinit var messageBox: TextView
    private lateinit var linkLog: TextView
    private lateinit var smsHint: TextView
    private lateinit var smsInput: EditText
    private var activeDialog: AlertDialog? = null

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            onStatusChanged()
            refresh()
        }
    }

    fun attach(root: LinearLayout) {
        val label = TextView(activity).apply {
            text = "Messages from Take a Break"
            textSize = 16f
            setPadding(0, 20, 0, 8)
        }
        messageBox = TextView(activity).apply {
            textSize = 15f
            setPadding(24, 24, 24, 24)
            setBackgroundColor(0xFFF0F0F0.toInt())
            minLines = 6
        }
        val logLabel = TextView(activity).apply {
            text = "Link log"
            textSize = 16f
            setPadding(0, 16, 0, 8)
        }
        linkLog = TextView(activity).apply {
            textSize = 13f
            setPadding(16, 16, 16, 16)
            setBackgroundColor(0xFFF7F7F7.toInt())
        }
        val smsLabel = TextView(activity).apply {
            text = "Test SMS"
            textSize = 16f
            setPadding(0, 16, 0, 4)
        }
        smsHint = TextView(activity).apply {
            textSize = 13f
            setPadding(0, 0, 0, 8)
        }
        smsInput = EditText(activity).apply {
            hint = "Message"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            minLines = 2
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendTestSms()
                    true
                } else {
                    false
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        val send = Button(activity).apply {
            text = "Send SMS"
            setOnClickListener { sendTestSms() }
        }

        root.addView(label)
        root.addView(messageBox)
        root.addView(logLabel)
        root.addView(linkLog)
        root.addView(smsLabel)
        root.addView(smsHint)
        root.addView(smsInput)
        root.addView(send)
    }

    fun onStart() {
        val filter = IntentFilter().apply {
            addAction(AlertMessageReceiver.ACTION_UPDATED)
            addAction(PeerLink.ACTION_LOG_UPDATED)
            addAction(SmsAlerts.ACTION_QUEUE_UPDATED)
        }
        ContextCompat.registerReceiver(
            activity,
            refreshReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        refresh()
    }

    fun onStop() {
        try {
            activity.unregisterReceiver(refreshReceiver)
        } catch (_: Exception) {
        }
    }

    fun onDestroy() {
        dismissDialog()
    }

    fun refresh() {
        showMessages()
        showLog()
        showSmsHint()
    }

    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray) {
        if (requestCode != SMS_REQUEST) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            onStatusChanged()
            refresh()
            sendTestSms()
        } else {
            showSmsRestrictedDialog()
            onStatusChanged()
            refresh()
        }
    }

    private fun openAppsSettings() {
        val allAppsIntent = Intent(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS)
        val appsIntent = Intent(Settings.ACTION_APPLICATION_SETTINGS)
        try {
            activity.startActivity(allAppsIntent)
            toast("Tap Take a Break Watcher, then tap ⋮ menu (or scroll down) and enable 'Allow restricted settings'")
        } catch (_: Exception) {
            try {
                activity.startActivity(appsIntent)
                toast("Open All apps → Take a Break Watcher, then tap ⋮ menu and enable 'Allow restricted settings'")
            } catch (_: Exception) {
                activity.startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }

    private fun showSmsRestrictedDialog() {
        dismissDialog()
        val dialog = AlertDialog.Builder(activity)
            .setTitle("SMS Permission Blocked?")
            .setMessage(
                "On Android 13+ and GrapheneOS, Android hides \"Allow restricted settings\" if App Info is opened directly. Open it from Settings → Apps → All apps:\n\n" +
                    "1. Tap Open Apps Settings below (or go to Settings → Apps → See all apps) and select Take a Break Watcher.\n" +
                    "2. Tap the ⋮ menu in the top-right corner (or scroll down) and enable Allow restricted settings.\n" +
                    "3. Return here and grant SMS permission (or enable SMS under Permissions → SMS).",
            )
            .setPositiveButton("Open Apps Settings") { _, _ -> openAppsSettings() }
            .setNegativeButton("Close", null)
            .create()
        activeDialog = dialog
        dialog.setOnDismissListener {
            if (activeDialog === dialog) activeDialog = null
        }
        dialog.show()
    }

    private fun dismissDialog() {
        val d = activeDialog
        activeDialog = null
        if ((d != null) && d.isShowing) {
            try {
                d.dismiss()
            } catch (_: Exception) {
            }
        }
    }

    private fun sendTestSms() {
        val text = smsInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            toast("Enter a message first")
            return
        }
        val number = SmsAlerts.getPhoneNumber(activity)
        if (!SmsAlerts.isValidPhoneNumber(number)) {
            toast("No phone number set yet. Configure it in Take a Break -> Text Message Alerts.")
            return
        }
        if (!PermissionsSetupActivity.hasSmsPermission(activity)) {
            activity.requestPermissions(arrayOf(Manifest.permission.SEND_SMS), SMS_REQUEST)
            return
        }
        val error = SmsAlerts.enqueue(activity, text, bypassDedupe = true)
        if (error == null) {
            PeerLink.note(activity, "test SMS queued for $number")
            toast(
                if (SmsAlerts.isAirplaneModeOn(activity)) {
                    "Airplane mode is on; test SMS queued for retry"
                } else {
                    "Test SMS queued to $number"
                },
            )
        } else {
            PeerLink.note(activity, "test SMS not queued: $error")
            toast(error)
        }
        showLog()
        showSmsHint()
    }

    private fun showMessages() {
        if (::messageBox.isInitialized) {
            messageBox.text = ReceivedMessages.displayText(activity)
        }
    }

    private fun showLog() {
        if (::linkLog.isInitialized) {
            linkLog.text = PeerLink.logText(activity)
        }
    }

    private fun showSmsHint() {
        if (!::smsHint.isInitialized) return
        val number = SmsAlerts.getPhoneNumber(activity)
        val queued = SmsAlerts.queuedCount(activity)
        val permNote = if (PermissionsSetupActivity.hasSmsPermission(activity)) "" else " — SMS permission not granted"
        val queueNote = if (queued > 0) " ($queued queued for retry)" else ""
        smsHint.text = if (SmsAlerts.isValidPhoneNumber(number)) {
            "Sends to $number (synced from Take a Break)$permNote$queueNote"
        } else {
            "No phone number configured yet. Set it in Take a Break -> Text Message Alerts.$permNote"
        }
    }

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val SMS_REQUEST = 1001
    }
}
