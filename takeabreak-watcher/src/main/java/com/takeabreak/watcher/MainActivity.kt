package com.takeabreak.watcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Space
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

        val dp = { value: Float -> AeroGlassUi.dp(this, value) }

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        val title = TextView(this).apply {
            text = getString(R.string.watcher_title)
            textSize = 30f
            typeface = AeroGlassUi.minimalistFont(light = true)
            letterSpacing = 0.025f
            setTextColor(AeroGlassUi.TEXT_BLACK)
            gravity = Gravity.CENTER
            setPadding(0, dp(6f), 0, dp(4f))
        }

        val windowGraphic = TakeABreakWindowView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(244f), dp(188f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(4f)
                bottomMargin = dp(12f)
            }
        }

        val statusCard = AeroGlassUi.createGlassCard(this, cornerDp = 24f).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(10f)
            }
            addView(
                AeroGlassUi.createBodyText(
                    this@MainActivity,
                    getString(R.string.watcher_monitoring_desc),
                    centered = true,
                ).apply {
                    setPadding(0, dp(2f), 0, dp(2f))
                },
            )
        }

        smsWarning = AeroGlassUi.createGlassNotice(
            this,
            getString(R.string.watcher_sms_off_note),
            isWarning = true,
        )
        phoneWarning = AeroGlassUi.createGlassNotice(
            this,
            getString(R.string.watcher_phone_not_set_note),
            isWarning = false,
        )

        val setupBtn = AeroGlassUi.createGlassButton(
            this,
            getString(R.string.watcher_setup_title),
            AeroGlassUi.GlassTone.LIQUID_CLEAR,
        ) {
            startActivity(Intent(this@MainActivity, PermissionsSetupActivity::class.java))
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(6f)
                bottomMargin = dp(6f)
            }
        }

        content.addView(title)
        content.addView(windowGraphic)
        content.addView(statusCard)
        content.addView(smsWarning)
        content.addView(phoneWarning)
        content.addView(setupBtn)
        debugPanel.attach(content)

        val bottomSpacer = Space(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }

        val versionFooter = TextView(this).apply {
            text = getString(R.string.watcher_version_footer, versionName())
            textSize = 12f
            typeface = AeroGlassUi.minimalistFont(light = true)
            letterSpacing = 0.03f
            setTextColor(AeroGlassUi.TEXT_BLACK)
            gravity = Gravity.CENTER
            setPadding(0, dp(20f), 0, dp(4f))
        }

        outer.addView(content)
        outer.addView(bottomSpacer)
        outer.addView(versionFooter)

        setContentView(
            AeroGlassUi.wrapScrollableScreen(
                activity = this,
                content = outer,
                padHDp = 24f,
                padTopDp = 22f,
                padBotDp = 18f,
                maxContentWidthDp = 440f,
            ),
        )
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
        packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
    } catch (_: Exception) {
        "1.0"
    }
}
