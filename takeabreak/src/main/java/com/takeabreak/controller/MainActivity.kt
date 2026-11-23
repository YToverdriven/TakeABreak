package com.takeabreak.controller

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PeerLink.check(this, "opened")
        if (!Prefs.isPinSet(this)) {
            startActivity(Intent(this, PinSetupActivity::class.java))
            finish()
            return
        }
    }

    override fun onResume() {
        super.onResume()
        if (!Prefs.isPinSet(this)) return
        noteControllerVisible()
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
            setPadding(0, 0, 0, 24)
        }

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

        val accEnabled = isAccessibilityEnabled(this)
        val accBtn = Button(this).apply {
            text = if (accEnabled) "Accessibility Service: Enabled" else "Enable Accessibility Service"
            isEnabled = !accEnabled
            setOnClickListener {
                openAccessibilitySettings()
            }
        }

        val adminBtn = Button(this).apply {
            text = "Enable Device Admin (optional)"
            setOnClickListener {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(
                        DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                        ComponentName(this@MainActivity, DeviceAdminReceiver::class.java),
                    )
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Allows stronger control over the device for locking features.",
                    )
                }
                startActivity(intent)
            }
        }

        val debugBtn = Button(this).apply {
            text = "Debug"
            setOnClickListener {
                requirePinThen {
                    startActivity(Intent(this@MainActivity, DebugActivity::class.java))
                }
            }
        }

        layout.addView(title)
        layout.addView(status)
        layout.addView(pinBtn)
        layout.addView(appsBtn)
        layout.addView(limitsBtn)
        layout.addView(spentBtn)
        layout.addView(smsAlertsBtn)
        if (!accEnabled) {
            val setupNote = TextView(this).apply {
                text = "Accessibility service is off. Blocking and time tracking will not run until it is enabled."
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, 16, 0, 8)
            }
            layout.addView(setupNote)
        }
        layout.addView(accBtn)
        if (!accEnabled) {
            val restrictedBtn = Button(this).apply {
                text = "Allow Restricted Settings (Setup Help)"
                setOnClickListener { showRestrictedSettingsHelp() }
            }
            layout.addView(restrictedBtn)
        }
        layout.addView(adminBtn)
        layout.addView(debugBtn)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(layout)
        }
        setContentView(scroll)
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        Toast.makeText(
            this,
            "Find and enable 'Take a Break' (shortcut is not needed)",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun openAppInfo() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        )
        startActivity(intent)
    }

    private fun showRestrictedSettingsHelp() {
        AlertDialog.Builder(this)
            .setTitle("Allow Restricted Settings")
            .setMessage(
                "On Android 13+ and GrapheneOS, sideloaded apps have Accessibility grayed out as a restricted setting.\n\n" +
                "1. Tap Accessibility Settings below and tap Take a Break once. If Android says 'Restricted setting', dismiss that message (some phones require this step first).\n\n" +
                "2. Tap Open App Info below, open the ⋮ menu in the top-right corner (or scroll down on GrapheneOS), and tap Allow restricted settings.\n\n" +
                "3. Return to Accessibility Settings and turn on Use Take a Break. You do not need to enable the shortcut."
            )
            .setPositiveButton("Open App Info") { _, _ -> openAppInfo() }
            .setNeutralButton("Accessibility Settings") { _, _ -> openAccessibilitySettings() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun requirePinThen(action: () -> Unit) {
        PinPrompt.show(this, action)
    }

    private fun appVersion(): String = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0.11.0"
    } catch (_: Exception) {
        "0.11.0"
    }
}
