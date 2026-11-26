package com.takeabreak.watcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.edit

/**
 * Guided setup wizard for Take a Break Watcher:
 *   1B. Grant SMS Permission (skips straight to 3B if SMS permission is already granted)
 *   2B. Enable Restricted Settings (shown if SMS permission was not granted)
 *   3B. Setup Complete (marks Watcher set up, sends setup-complete pong to Take a Break,
 *       and offers "Return to Take a Break")
 */
class PermissionsSetupActivity : Activity() {
    private var isFirstSetup = false
    private var currentStep = STEP_SMS
    private var activeDialog: Dialog? = null

    private var step2SmsBtn: Button? = null
    private var step2NextBtn: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isFirstSetup = intent.getBooleanExtra(EXTRA_FIRST_SETUP, false)

        currentStep = savedInstanceState?.getInt(STATE_STEP)
            ?: if (hasSmsPermission(this)) STEP_COMPLETE else STEP_SMS

        if (currentStep == STEP_COMPLETE) {
            markSetupReadyAndNotify()
        }
        renderStep()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_STEP, currentStep)
    }

    override fun onResume() {
        super.onResume()
        when (currentStep) {
            STEP_SMS -> {
                if (hasSmsPermission(this)) {
                    goToStep(STEP_COMPLETE)
                }
            }
            STEP_RESTRICTED_SETTINGS -> refreshStep2Ui()
            STEP_COMPLETE -> {
                if (!hasSmsPermission(this)) {
                    goToStep(STEP_SMS)
                } else {
                    markSetupReadyAndNotify()
                }
            }
        }
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            dismissActiveDialog()
        }
        super.onStop()
    }

    override fun onDestroy() {
        dismissActiveDialog()
        super.onDestroy()
    }

    @Deprecated("Use the activity result API when this screen is rewritten.")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != SMS_REQUEST) return
        val granted = (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) ||
            hasSmsPermission(this)
        if (granted) {
            toast("SMS permission granted")
            SmsAlerts.processQueue(this)
            if (currentStep == STEP_SMS) {
                goToStep(STEP_COMPLETE)
            } else {
                refreshStep2Ui()
            }
        } else if (currentStep == STEP_SMS) {
            goToStep(STEP_RESTRICTED_SETTINGS)
        } else {
            showRestrictedSettingsDialog()
            refreshStep2Ui()
        }
    }

    private fun goToStep(step: Int) {
        currentStep = step
        if (step == STEP_COMPLETE) {
            markSetupReadyAndNotify()
        }
        renderStep()
    }

    private fun markSetupReadyAndNotify() {
        setSetupComplete(this, complete = true)
        SmsAlerts.processQueue(this)
        PeerLink.notifySetupComplete(this)
    }

    private fun renderStep() {
        step2SmsBtn = null
        step2NextBtn = null
        when (currentStep) {
            STEP_SMS -> renderStep1BSms()
            STEP_RESTRICTED_SETTINGS -> renderStep2BRestrictedSettings()
            STEP_COMPLETE -> renderStep3BComplete()
        }
    }

    // ---- Screen 1B: Setup ----

    private fun renderStep1BSms() {
        val column = baseColumn()
        if (!isFirstSetup) {
            column.addView(backToWatcherButton())
        }
        column.addView(headingView("Set Up Text Message Alerts"))
        column.addView(
            bodyView(
                "Grant SMS permission so Take a Break Watcher can alert by text message if Take a Break is ever disabled or deleted.",
            ),
        )
        column.addView(
            primaryButton("Grant SMS Permission") {
                showSmsExplanationDialog()
            },
        )
        setScrollContent(column)
    }

    private fun showSmsExplanationDialog() {
        showManagedDialog(
            AlertDialog.Builder(this)
                .setTitle("Grant SMS Permission")
                .setMessage(
                    "The option to grant SMS permissions may not be allowed if the app was downloaded from an unofficial source. Please try tapping the permission anyway. Dismiss any pop-up concerning restricted settings. You will then be able to enable restricted settings if required by your Android version.",
                )
                .setPositiveButton("I Understand") { _, _ ->
                    requestPermissions(arrayOf(Manifest.permission.SEND_SMS), SMS_REQUEST)
                }
                .setCancelable(true)
                .create(),
        )
    }

    // ---- Screen 2B: Enable Restricted Settings ----

    private fun renderStep2BRestrictedSettings() {
        val column = baseColumn()
        if (!isFirstSetup) {
            column.addView(backToWatcherButton())
        }
        column.addView(headingView("Continuing Permissions Setup"))
        column.addView(
            bodyView(
                "You are viewing this screen because SMS permission was not enabled. If SMS permission was blocked, enable restricted settings first, then try granting SMS permission again.",
            ),
        )

        val restrictedBtn = primaryButton("Restricted Settings") {
            showRestrictedSettingsDialog()
        }
        val smsBtn = primaryButton("Grant SMS Permission") {
            requestPermissions(arrayOf(Manifest.permission.SEND_SMS), SMS_REQUEST)
        }
        val nextBtn = primaryButton("Next") {
            goToStep(STEP_COMPLETE)
        }

        step2SmsBtn = smsBtn
        step2NextBtn = nextBtn
        column.addView(restrictedBtn)
        column.addView(smsBtn)
        column.addView(nextBtn)

        setScrollContent(column)
        refreshStep2Ui()
    }

    private fun refreshStep2Ui() {
        val granted = hasSmsPermission(this)
        step2SmsBtn?.apply {
            isEnabled = !granted
            text = if (granted) "SMS Permission Granted" else "Grant SMS Permission"
        }
        step2NextBtn?.isEnabled = granted
    }

    // ---- Screen 3B: Setup Complete ----

    private fun renderStep3BComplete() {
        val column = baseColumn()
        column.addView(headingView("Watcher is Set Up."))
        column.addView(
            bodyView(
                "Take a Break Watcher has SMS permission and is ready to monitor Take a Break.",
            ),
        )
        column.addView(
            primaryButton("Return to Take a Break") {
                returnToTakeABreak()
            },
        )
        if (!isFirstSetup) {
            column.addView(
                primaryButton("Back to Watcher") {
                    finish()
                },
            )
        }
        setScrollContent(column)
    }

    private fun returnToTakeABreak() {
        markSetupReadyAndNotify()
        val launch = packageManager.getLaunchIntentForPackage(PeerLink.PARTNER)
        if (launch != null) {
            try {
                startActivity(launch)
                finish()
                return
            } catch (_: Exception) {
            }
        }
        if (isFirstSetup) {
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }

    // ---- Helpers ----

    private fun openAppsSettings() {
        val allAppsIntent = Intent(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS)
        val appsIntent = Intent(Settings.ACTION_APPLICATION_SETTINGS)
        try {
            startActivity(allAppsIntent)
            toast("Tap Take a Break Watcher, then tap ⋮ menu (or scroll down) and enable 'Allow restricted settings'")
        } catch (_: Exception) {
            try {
                startActivity(appsIntent)
                toast("Open All apps → Take a Break Watcher, then tap ⋮ menu and enable 'Allow restricted settings'")
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }

    private fun showRestrictedSettingsDialog() {
        showManagedDialog(
            AlertDialog.Builder(this)
                .setTitle("Allow Restricted Settings")
                .setMessage(
                    "On Android 13+ and GrapheneOS, Android hides \"Allow restricted settings\" if App Info is opened directly. You must open it from Settings → Apps → All apps:\n\n" +
                        "1. Tap Open Apps Settings below (or go to Settings → Apps → See all apps) and select Take a Break Watcher.\n\n" +
                        "2. Tap the ⋮ menu in the top-right corner (or scroll down on Android 15+ / GrapheneOS).\n\n" +
                        "3. Tap Allow restricted settings.\n\n" +
                        "4. Return here and tap Grant SMS Permission again (or enable SMS under Permissions → SMS).",
                )
                .setPositiveButton("Open Apps Settings") { _, _ -> openAppsSettings() }
                .setNegativeButton("Close", null)
                .create(),
        )
    }

    private fun showManagedDialog(dialog: Dialog) {
        dismissActiveDialog()
        activeDialog = dialog
        dialog.setOnDismissListener {
            if (activeDialog === dialog) activeDialog = null
        }
        dialog.show()
    }

    private fun dismissActiveDialog() {
        val d = activeDialog
        activeDialog = null
        if ((d != null) && d.isShowing) {
            try {
                d.dismiss()
            } catch (_: Exception) {
            }
        }
    }

    private fun backToWatcherButton(): Button = Button(this).apply {
        text = "Back"
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        setOnClickListener { finish() }
    }

    private fun baseColumn(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(40, 32, 40, 40)
    }

    private fun setScrollContent(column: LinearLayout) {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(column)
        }
        setContentView(scroll)
    }

    private fun headingView(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 22f
        setPadding(0, 8, 0, 12)
    }

    private fun bodyView(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 15f
        setPadding(0, 0, 0, 16)
    }

    private fun primaryButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = 16
        }
        setOnClickListener { onClick() }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        const val EXTRA_FIRST_SETUP = "first_setup"
        private const val SMS_REQUEST = 3002
        private const val PREFS = "watcher_setup"
        private const val KEY_COMPLETE = "setup_complete"

        private const val STEP_SMS = 1
        private const val STEP_RESTRICTED_SETTINGS = 2
        private const val STEP_COMPLETE = 3
        private const val STATE_STEP = "watcher_setup_step"

        fun isSetupComplete(context: Context): Boolean =
            context.applicationContext.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_COMPLETE, false)

        fun setSetupComplete(context: Context, complete: Boolean) {
            context.applicationContext.getSharedPreferences(PREFS, MODE_PRIVATE).edit {
                putBoolean(KEY_COMPLETE, complete)
            }
        }

        fun hasSmsPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
                PackageManager.PERMISSION_GRANTED
    }
}
