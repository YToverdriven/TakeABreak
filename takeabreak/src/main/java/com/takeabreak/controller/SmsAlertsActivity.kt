package com.takeabreak.controller

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.util.Calendar

/**
 * PIN-gated configuration page for SMS alerts: destination phone number, per-event toggles,
 * daily status check time (default 8:00 PM), SMS permission, and retry queue status.
 */
class SmsAlertsActivity : PinLockedActivity() {
    private lateinit var phoneInput: EditText
    private lateinit var statusView: TextView
    private lateinit var restrictedBtn: Button
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
        if (isLockedOut) return

        val dp = { value: Float -> AeroGlassUi.dp(this, value) }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val title = AeroGlassUi.createTitle(this, getString(R.string.sms_alerts_title))
        val intro = AeroGlassUi.createBodyText(
            this,
            "Alerts are sent by SMS to the number below. Leave blank to turn off all SMS alerts. If the phone is in airplane mode or has no service, failed alerts wait in a retry queue with backoff and rate limits.",
            secondary = true,
        )

        // Card 1: Phone Number & SMS Controls
        val phoneCard = AeroGlassUi.createGlassCard(this, cornerDp = 22f).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(12f)
            }
        }

        val phoneLabel = AeroGlassUi.createSectionTitle(this, "Alert phone number")

        phoneInput = AeroGlassUi.createGlassInput(
            this,
            "Phone number (blank = off)",
            InputType.TYPE_CLASS_PHONE,
        ).apply {
            setText(Prefs.getSmsPhoneNumber(this@SmsAlertsActivity))
        }

        val saveNumberBtn = AeroGlassUi.createGlassButton(
            this,
            "Save Phone Number",
            AeroGlassUi.GlassTone.LIQUID_SKY,
        ) {
            savePhoneNumber()
        }

        permBtn = AeroGlassUi.createGlassButton(
            this,
            "Grant SMS Permission",
            AeroGlassUi.GlassTone.LIQUID_CLEAR,
        ) {
            requestPermissions(arrayOf(Manifest.permission.SEND_SMS), SMS_REQUEST)
        }

        restrictedBtn = AeroGlassUi.createGlassButton(
            this,
            getString(R.string.restricted_settings_help_button),
            AeroGlassUi.GlassTone.LIQUID_FROST,
        ) {
            showSmsRestrictedHelp()
        }

        testBtn = AeroGlassUi.createGlassButton(
            this,
            "Send Test SMS",
            AeroGlassUi.GlassTone.LIQUID_FROST,
        ) {
            sendTestSms()
        }

        phoneCard.addView(phoneLabel)
        phoneCard.addView(phoneInput)
        phoneCard.addView(saveNumberBtn)
        phoneCard.addView(permBtn)
        phoneCard.addView(restrictedBtn)
        phoneCard.addView(testBtn)

        // Card 2: Event Toggles
        val togglesCard = AeroGlassUi.createGlassCard(this, cornerDp = 22f).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(12f)
            }
        }

        val togglesHeader = AeroGlassUi.createSectionTitle(this, "Send an SMS when:")

        val newAppCb = AeroGlassUi.createGlassCheckBox(
            this,
            "A new app is installed",
            Prefs.isAlertNewAppEnabled(this),
        ) { checked ->
            Prefs.setAlertNewAppEnabled(this@SmsAlertsActivity, checked)
        }

        val tamperCb = AeroGlassUi.createGlassCheckBox(
            this,
            "Take a Break or Watcher is deleted, disabled, reset, or accessibility is turned off",
            Prefs.isAlertTamperEnabled(this),
        ) { checked ->
            Prefs.setAlertTamperEnabled(this@SmsAlertsActivity, checked)
            WatcherMessages.syncConfig(this@SmsAlertsActivity)
        }

        val dayUnlockCb = AeroGlassUi.createGlassCheckBox(
            this,
            "PIN is entered to unlock a blocked app",
            Prefs.isAlertDayUnlockEnabled(this),
        ) { checked ->
            Prefs.setAlertDayUnlockEnabled(this@SmsAlertsActivity, checked)
        }

        val dailyStatusCb = AeroGlassUi.createGlassCheckBox(
            this,
            "Once-a-day status check",
            Prefs.isDailyStatusEnabled(this),
        ) { checked ->
            Prefs.setDailyStatusEnabled(this@SmsAlertsActivity, checked)
            DailyStatusWorker.syncSchedule(this@SmsAlertsActivity)
            refreshDailyTimeUi()
        }

        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28f), dp(6f), 0, dp(4f))
        }

        dailyTimeLabel = TextView(this).apply {
            textSize = 14.5f
            typeface = AeroGlassUi.minimalistFont(light = false)
            setTextColor(AeroGlassUi.TEXT_SECONDARY)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        changeTimeBtn = AeroGlassUi.createCompactGlassButton(
            this,
            "Change Time",
            AeroGlassUi.GlassTone.LIQUID_CLEAR,
        ) {
            showTimePicker()
        }

        timeRow.addView(dailyTimeLabel)
        timeRow.addView(changeTimeBtn)

        togglesCard.addView(togglesHeader)
        togglesCard.addView(newAppCb)
        togglesCard.addView(AeroGlassUi.createDivider(this, verticalMarginDp = 3f))
        togglesCard.addView(tamperCb)
        togglesCard.addView(AeroGlassUi.createDivider(this, verticalMarginDp = 3f))
        togglesCard.addView(dayUnlockCb)
        togglesCard.addView(AeroGlassUi.createDivider(this, verticalMarginDp = 3f))
        togglesCard.addView(dailyStatusCb)
        togglesCard.addView(timeRow)

        // Card 3: SMS Status
        val statusCard = AeroGlassUi.createGlassCard(this, cornerDp = 22f).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        val statusHeader = AeroGlassUi.createSectionTitle(this, "SMS status")

        statusView = TextView(this).apply {
            textSize = 14f
            typeface = AeroGlassUi.minimalistFont(light = false)
            setLineSpacing(AeroGlassUi.dpF(this@SmsAlertsActivity, 2.5f), 1f)
            setTextColor(AeroGlassUi.TEXT_SECONDARY)
        }

        statusCard.addView(statusHeader)
        statusCard.addView(statusView)

        column.addView(menuBackButton())
        column.addView(title)
        column.addView(intro)
        column.addView(phoneCard)
        column.addView(togglesCard)
        column.addView(statusCard)

        setContentView(AeroGlassUi.wrapScrollableScreen(this, column))

        refreshDailyTimeUi()
        refreshStatus()
    }

    @Deprecated("Use the activity result API when the UI is rewritten.")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != SMS_REQUEST) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            toast("SMS permission granted")
            SmsAlerts.processQueue(this)
        } else {
            showSmsRestrictedHelp()
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
            bypassDedupe = true,
        )
        if (error == null) {
            toast(
                if (SmsAlerts.isAirplaneModeOn(this)) {
                    "Airplane mode is on; test SMS queued for retry"
                } else {
                    "Test SMS queued to $number"
                },
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
        showManagedDialog(
            AeroGlassUi.createGlassTimePickerDialog(
                context = this,
                title = "Daily Status Time",
                initialHour = hour,
                initialMinute = minute,
                is24Hour = is24Hour,
            ) { selectedHour, selectedMinute ->
                Prefs.setDailyStatusTime(this, selectedHour, selectedMinute)
                DailyStatusWorker.syncSchedule(this)
                refreshDailyTimeUi()
                toast("Daily status time set to ${formatTime(selectedHour, selectedMinute)}")
            },
        )
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
        restrictedBtn.visibility = if (hasPerm) View.GONE else View.VISIBLE
        permBtn.text = if (hasPerm) "SMS Permission: Granted" else "Grant SMS Permission"
        permBtn.isEnabled = !hasPerm
        testBtn.isEnabled = hasNumber
        statusView.text = SmsAlerts.statusSummary(this)
    }

    private fun openAppsSettings() {
        val allAppsIntent = Intent(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS)
        val appsIntent = Intent(Settings.ACTION_APPLICATION_SETTINGS)
        try {
            startActivity(allAppsIntent)
            toast("Tap Take a Break, then tap ⋮ menu (or scroll down) and enable 'Allow restricted settings'")
        } catch (_: Exception) {
            try {
                startActivity(appsIntent)
                toast("Open All apps → Take a Break, then tap ⋮ menu and enable 'Allow restricted settings'")
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }

    private fun showSmsRestrictedHelp() {
        showManagedDialog(
            AeroGlassUi.createGlassDialog(
                context = this,
                title = "SMS Permission Blocked?",
                message = "On Android 13+ and GrapheneOS, Android hides \"Allow restricted settings\" if App Info is opened directly. You must open it from Settings → Apps → All apps:\n\n" +
                    "1. Tap Open Apps Settings below (or go to Settings → Apps → See all apps) and select Take a Break.\n\n" +
                    "2. Tap the ⋮ menu in the top-right corner (or scroll down) and enable Allow restricted settings.\n\n" +
                    "3. Return here and tap Grant SMS Permission (or enable SMS under Permissions → SMS).",
                positiveText = "Open Apps Settings",
                onPositive = { dlg ->
                    dlg.dismiss()
                    openAppsSettings()
                },
                negativeText = "Close",
                onNegative = { it.dismiss() },
            ),
        )
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val SMS_REQUEST = 2001
    }
}
