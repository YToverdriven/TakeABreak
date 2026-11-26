package com.takeabreak.controller

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private var pinDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PeerLink.check(this, "opened")

        if (!Prefs.isSetupComplete(this)) {
            if (Prefs.isPinSet(this) && !Prefs.isSetupInProgress(this)) {
                // Existing install upgraded from an earlier version; skip the first-time wizard.
                Prefs.setSetupComplete(this, complete = true)
            } else {
                startActivity(
                    Intent(this, PermissionsSetupActivity::class.java)
                        .putExtra(PermissionsSetupActivity.EXTRA_FIRST_SETUP, true),
                )
                finish()
                return
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!Prefs.isSetupComplete(this)) return
        noteControllerVisible()
        PeerLink.check(this, "opened")
        PeerLink.checkAccessibility(this, "opened")
        dropProtectedRules()
        showMainUi()
    }

    /** Older saves may name an app that is no longer allowed to be blocked. */
    private fun dropProtectedRules() {
        ProtectedPackages.invalidate()
        val locked = Prefs.getLockedPackages(this)
        val keptLocks = locked
            .asSequence()
            .filterNot { ProtectedPackages.isProtected(this, it) }
            .toSet()
        if (keptLocks.size != locked.size) Prefs.setLockedPackages(this, keptLocks)
        for (pkg in Prefs.getAllLimits(this).keys) {
            if (ProtectedPackages.isProtected(this, pkg)) Prefs.setLimitMinutes(this, pkg, 0)
        }
    }

    private fun showMainUi() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val title = TextView(this).apply {
            text = getString(R.string.main_title, appVersion())
            textSize = 24f
            setPadding(0, 0, 0, 32)
        }

        val status = TextView(this).apply {
            text = "PIN-locked apps: ${Prefs.getLockedPackages(this@MainActivity).size}\n" +
                "Apps with usage limits: ${Prefs.getAllLimits(this@MainActivity).size}"
            textSize = 16f
            setPadding(0, 0, 0, 20)
        }

        val accEnabled = isAccessibilityEnabled(this)
        val hasPhoneNumber = Prefs.isValidPhoneNumber(Prefs.getSmsPhoneNumber(this))

        val pinBtn = Button(this).apply {
            text = "Change PIN"
            setOnClickListener {
                requirePinThen {
                    startActivity(Intent(this@MainActivity, PinSetupActivity::class.java))
                }
            }
        }

        val appsBtn = Button(this).apply {
            text = "Select apps to lock (PIN)"
            setOnClickListener {
                requirePinThen {
                    startActivity(Intent(this@MainActivity, AppSelectorActivity::class.java))
                }
            }
        }

        val limitsBtn = Button(this).apply {
            text = "App Usage Limits"
            setOnClickListener {
                requirePinThen {
                    startActivity(Intent(this@MainActivity, UsageLimitsActivity::class.java))
                }
            }
        }

        val spentBtn = Button(this).apply {
            text = "Time Spent on Apps"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, TimeSpentActivity::class.java))
            }
        }

        val smsAlertsBtn = Button(this).apply {
            text = "Text Message Alerts"
            setOnClickListener {
                requirePinThen {
                    startActivity(Intent(this@MainActivity, SmsAlertsActivity::class.java))
                }
            }
        }

        val setupBtn = Button(this).apply {
            text = getString(R.string.setup_title)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, PermissionsSetupActivity::class.java))
            }
        }

        layout.addView(title)
        layout.addView(status)
        if (!accEnabled) {
            val accNote = TextView(this).apply {
                text = getString(R.string.main_accessibility_off_note)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 12)
            }
            layout.addView(accNote)
        }
        if (!hasPhoneNumber) {
            val phoneNote = TextView(this).apply {
                text = getString(R.string.main_phone_not_set_note)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 16)
            }
            layout.addView(phoneNote)
        }
        layout.addView(pinBtn)
        layout.addView(appsBtn)
        layout.addView(limitsBtn)
        layout.addView(spentBtn)
        layout.addView(smsAlertsBtn)
        layout.addView(setupBtn)
        DebugMenu.addButton(this, layout, ::requirePinThen)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(layout)
        }
        setContentView(scroll)
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            dismissPinDialog()
        }
        super.onStop()
    }

    override fun onDestroy() {
        dismissPinDialog()
        super.onDestroy()
    }

    private fun requirePinThen(action: () -> Unit) {
        dismissPinDialog()
        val dialog = PinPrompt.show(this, action)
        pinDialog = dialog
        dialog.setOnDismissListener {
            if (pinDialog === dialog) pinDialog = null
        }
    }

    private fun dismissPinDialog() {
        val d = pinDialog
        pinDialog = null
        if ((d != null) && d.isShowing) {
            try {
                d.dismiss()
            } catch (_: Exception) {
            }
        }
    }

    private fun appVersion(): String = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0.12.1"
    } catch (_: Exception) {
        "0.12.1"
    }
}
