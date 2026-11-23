package com.takeabreak.controller

import android.Manifest
import android.app.Activity
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.text.format.DateFormat
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.util.Calendar

/**
 * PIN-gated configuration page for SMS alerts: destination phone number, per-event toggles,
 * daily status check time (default 8:00 PM), SMS permission, and retry queue status.
 */
class SmsAlertsActivity : Activity() {
    private lateinit var phoneInput: EditText
    private lateinit var statusView: TextView
    private lateinit var permBtn: Button
    private lateinit var testBtn: Button
    private lateinit var dailyTimeLabel: TextView
    private lateinit var changeTimeBtn: Button

    private val queueReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshStatus()
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            queueReceiver,
            IntentFilter(SmsAlerts.ACTION_QUEUE_UPDATED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStop() {
        try {
            unregisterReceiver(queueReceiver)
        } catch (_: Exception) {
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        noteControllerVisible()
        refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 32)
        }

        val title = TextView(this).apply {
            text = getString(R.string.sms_alerts_title)
            textSize = 22f
            setPadding(0, 8, 0, 8)
        }

        val intro = TextView(this).apply {
            text = "Alerts are sent by SMS to the number below. Leave blank to turn off all SMS alerts. If the phone is in airplane mode or has no service, failed alerts wait in a retry queue with backoff and rate limits."
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }

        val phoneLabel = TextView(this).apply {
            text = "Alert phone number"
            textSize = 16f
            setPadding(0, 8, 0, 4)
        }

        phoneInput = EditText(this).apply {
            hint = "Phone number (blank = off)"
            inputType = InputType.TYPE_CLASS_PHONE
            setText(Prefs.getSmsPhoneNumber(this@SmsAlertsActivity))
        }

        val saveNumberBtn = Button(this).apply {
            text = "Save Phone Number"
            setOnClickListener { savePhoneNumber() }
        }

        permBtn = Button(this).apply {
            text = "Grant SMS Permission"
            setOnClickListener {
                requestPermissions(arrayOf(Manifest.permission.SEND_SMS), SMS_REQUEST)
            }
        }

        testBtn = Button(this).apply {
            text = "Send Test SMS"
            setOnClickListener { sendTestSms() }
        }

        val togglesHeader = TextView(this).apply {
            text = "Send an SMS when:"
            textSize = 16f
            setPadding(0, 24, 0, 8)
        }

        val newAppCb = CheckBox(this).apply {
            text = "A new app is installed"
            isChecked = Prefs.isAlertNewAppEnabled(this@SmsAlertsActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setAlertNewAppEnabled(this@SmsAlertsActivity, checked)
            }
        }

        val tamperCb = CheckBox(this).apply {
            text = "Take a Break or Watcher is deleted, disabled, reset, or accessibility is turned off"
            isChecked = Prefs.isAlertTamperEnabled(this@SmsAlertsActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setAlertTamperEnabled(this@SmsAlertsActivity, checked)
                WatcherMessages.syncConfig(this@SmsAlertsActivity)
            }
        }

        val dayUnlockCb = CheckBox(this).apply {
            text = "PIN is entered to unlock an app for the rest of the day"
            isChecked = Prefs.isAlertDayUnlockEnabled(this@SmsAlertsActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setAlertDayUnlockEnabled(this@SmsAlertsActivity, checked)
            }
        }

        val dailyStatusCb = CheckBox(this).apply {
            text = "Once-a-day status check"
            isChecked = Prefs.isDailyStatusEnabled(this@SmsAlertsActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setDailyStatusEnabled(this@SmsAlertsActivity, checked)
                DailyStatusWorker.syncSchedule(this@SmsAlertsActivity)
                refreshDailyTimeUi()
            }
        }

        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(36, 4, 0, 12)
        }

        dailyTimeLabel = TextView(this).apply {
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        changeTimeBtn = Button(this).apply {
            text = "Change Time"
            setOnClickListener { showTimePicker() }
        }

        timeRow.addView(dailyTimeLabel)
        timeRow.addView(changeTimeBtn)

        val statusHeader = TextView(this).apply {
            text = "SMS status"
            textSize = 16f
            setPadding(0, 20, 0, 4)
        }

        statusView = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }

        column.addView(menuBackButton())
        column.addView(title)
        column.addView(intro)
        column.addView(phoneLabel)
        column.addView(phoneInput)
        column.addView(saveNumberBtn)
        column.addView(permBtn)
        column.addView(testBtn)
        column.addView(togglesHeader)
        column.addView(newAppCb)
        column.addView(tamperCb)
        column.addView(dayUnlockCb)
        column.addView(dailyStatusCb)
        column.addView(timeRow)
        column.addView(statusHeader)
        column.addView(statusView)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(column)
        }
        setContentView(scroll)

        refreshDailyTimeUi()
        refreshStatus()
    }

    @Deprecated("Use the activity result API when the UI is rewritten.")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != SMS_REQUEST) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            toast("SMS permission granted")
            SmsAlerts.processQueue(this)
        } else {
            toast("SMS permission was not granted")
        }
        refreshStatus()
    }

    private fun savePhoneNumber() {
        val raw = phoneInput.text?.toString().orEmpty().trim()
        if (raw.isEmpty()) {
            Prefs.setSmsPhoneNumber(this, "")
            SmsAlerts.clearQueue(this)
            WatcherMessages.syncConfig(this)
            DailyStatusWorker.syncSchedule(this)
            toast("Phone number cleared; SMS alerts disabled")
            refreshStatus()
            return
        }
        val normalized = Prefs.normalizePhoneNumber(raw)
        if (!Prefs.isValidPhoneNumber(normalized)) {
            toast("Enter a valid phone number (digits, optional leading +)")
            return
        }
        Prefs.setSmsPhoneNumber(this, normalized)
        phoneInput.setText(normalized)
        WatcherMessages.syncConfig(this)
        DailyStatusWorker.syncSchedule(this)
        toast("Phone number saved and synced to Watcher")
        refreshStatus()
    }

    private fun sendTestSms() {
        val number = Prefs.getSmsPhoneNumber(this)
        if (!Prefs.isValidPhoneNumber(number)) {
            toast("Save a phone number first")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.SEND_SMS), SMS_REQUEST)
            return
        }
        val error = SmsAlerts.enqueue(
            this,
            "Take a Break test message: SMS alerts are working.",
            bypassDedupe = true
        )
        if (error == null) {
            toast(
                if (SmsAlerts.isAirplaneModeOn(this)) {
                    "Airplane mode is on; test SMS queued for retry"
                } else {
                    "Test SMS queued to $number"
                }
            )
        } else {
            toast("Could not send: $error")
        }
        refreshStatus()
    }

    private fun showTimePicker() {
        val hour = Prefs.getDailyStatusHour(this)
        val minute = Prefs.getDailyStatusMinute(this)
        val is24Hour = DateFormat.is24HourFormat(this)
        TimePickerDialog(
            this,
            { _, selectedHour, selectedMinute ->
                Prefs.setDailyStatusTime(this, selectedHour, selectedMinute)
                DailyStatusWorker.syncSchedule(this)
                refreshDailyTimeUi()
                toast("Daily status time set to ${formatTime(selectedHour, selectedMinute)}")
            },
            hour,
            minute,
            is24Hour
        ).show()
    }

    private fun refreshDailyTimeUi() {
        if (!::dailyTimeLabel.isInitialized) return
        val enabled = Prefs.isDailyStatusEnabled(this)
        val hour = Prefs.getDailyStatusHour(this)
        val minute = Prefs.getDailyStatusMinute(this)
        dailyTimeLabel.text = getString(R.string.daily_status_scheduled_time, formatTime(hour, minute))
        changeTimeBtn.isEnabled = enabled
    }

    private fun formatTime(hour: Int, minute: Int): String {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
        }
        return DateFormat.getTimeFormat(this).format(cal.time)
    }

    private fun refreshStatus() {
        if (!::statusView.isInitialized) return
        val hasPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED
        val hasNumber = Prefs.isValidPhoneNumber(Prefs.getSmsPhoneNumber(this))
        permBtn.text = if (hasPerm) "SMS Permission: Granted" else "Grant SMS Permission"
        permBtn.isEnabled = !hasPerm
        testBtn.isEnabled = hasNumber
        statusView.text = SmsAlerts.statusSummary(this)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val SMS_REQUEST = 2001
    }
}
