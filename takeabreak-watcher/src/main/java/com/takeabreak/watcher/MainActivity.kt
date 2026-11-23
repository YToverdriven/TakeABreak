package com.takeabreak.watcher

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private lateinit var messageBox: TextView
    private lateinit var linkLog: TextView
    private lateinit var smsHint: TextView
    private lateinit var smsInput: EditText

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            showMessages()
            showLog()
            showSmsHint()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PeerLink.check(this, "opened")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        val status = TextView(this).apply {
            text = getString(R.string.watcher_status_summary, versionName())
            textSize = 16f
            setPadding(0, 0, 0, 24)
        }
        val label = TextView(this).apply {
            text = "Messages from Take a Break"
            textSize = 16f
            setPadding(0, 0, 0, 8)
        }
        messageBox = TextView(this).apply {
            textSize = 15f
            setPadding(24, 24, 24, 24)
            setBackgroundColor(0xFFF0F0F0.toInt())
            minLines = 6
        }
        val scroll = ScrollView(this).apply {
            addView(messageBox)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        val logLabel = TextView(this).apply {
            text = "Link log"
            textSize = 16f
            setPadding(0, 16, 0, 8)
        }
        linkLog = TextView(this).apply {
            textSize = 13f
            setPadding(16, 16, 16, 16)
            setBackgroundColor(0xFFF7F7F7.toInt())
        }
        val logScroll = ScrollView(this).apply {
            addView(linkLog)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (140 * resources.displayMetrics.density).toInt(),
            )
        }
        val smsLabel = TextView(this).apply {
            text = "Test SMS"
            textSize = 16f
            setPadding(0, 16, 0, 4)
        }
        smsHint = TextView(this).apply {
            textSize = 13f
            setPadding(0, 0, 0, 8)
        }
        smsInput = EditText(this).apply {
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
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val send = Button(this).apply {
            text = "Send SMS"
            setOnClickListener { sendTestSms() }
        }

        root.addView(status)
        root.addView(label)
        root.addView(scroll)
        root.addView(logLabel)
        root.addView(logScroll)
        root.addView(smsLabel)
        root.addView(smsHint)
        root.addView(smsInput)
        root.addView(send)
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(AlertMessageReceiver.ACTION_UPDATED)
            addAction(PeerLink.ACTION_LOG_UPDATED)
            addAction(SmsAlerts.ACTION_QUEUE_UPDATED)
        }
        ContextCompat.registerReceiver(
            this,
            refreshReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        showMessages()
        showLog()
        showSmsHint()
    }

    override fun onStop() {
        try {
            unregisterReceiver(refreshReceiver)
        } catch (_: Exception) {
        }
        super.onStop()
    }

    @Deprecated("Use the activity result API when this screen is rewritten.")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != SMS_REQUEST) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            sendTestSms()
        } else {
            toast("SMS permission is needed to send the test")
        }
    }

    private fun sendTestSms() {
        val text = smsInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            toast("Enter a message first")
            return
        }
        val number = SmsAlerts.getPhoneNumber(this)
        if (!SmsAlerts.isValidPhoneNumber(number)) {
            toast("No phone number set yet. Configure it in Take a Break -> Text Message Alerts.")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.SEND_SMS), SMS_REQUEST)
            return
        }
        val error = SmsAlerts.enqueue(this, text, bypassDedupe = true)
        if (error == null) {
            PeerLink.note(this, "test SMS queued for $number")
            toast(
                if (SmsAlerts.isAirplaneModeOn(this)) {
                    "Airplane mode is on; test SMS queued for retry"
                } else {
                    "Test SMS queued to $number"
                }
            )
        } else {
            PeerLink.note(this, "test SMS not queued: $error")
            toast(error)
        }
        showLog()
        showSmsHint()
    }

    private fun showMessages() {
        if (::messageBox.isInitialized) {
            messageBox.text = ReceivedMessages.displayText(this)
        }
    }

    private fun showLog() {
        if (::linkLog.isInitialized) {
            linkLog.text = PeerLink.logText(this)
        }
    }

    private fun showSmsHint() {
        if (!::smsHint.isInitialized) return
        val number = SmsAlerts.getPhoneNumber(this)
        val queued = SmsAlerts.queuedCount(this)
        val queueNote = if (queued > 0) " ($queued queued for retry)" else ""
        smsHint.text = if (SmsAlerts.isValidPhoneNumber(number)) {
            "Sends to $number (synced from Take a Break)$queueNote"
        } else {
            "No phone number configured yet. Set it in Take a Break -> Text Message Alerts."
        }
    }

    private fun versionName(): String = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0.11.0"
    } catch (_: Exception) {
        "0.11.0"
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val SMS_REQUEST = 1001
    }
}
