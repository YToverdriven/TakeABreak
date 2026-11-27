package com.takeabreak.controller

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : Activity() {
    private var pinDialog: AlertDialog? = null
    private var uiBuilt = false

    private lateinit var lockedCountView: TextView
    private lateinit var limitsCountView: TextView
    private lateinit var accNoticeView: TextView
    private lateinit var phoneNoticeView: TextView

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

        configureSystemBars()
        buildMainUi()
    }

    override fun onResume() {
        super.onResume()
        if (!Prefs.isSetupComplete(this)) return
        noteControllerVisible()
        PeerLink.check(this, "opened")
        PeerLink.checkAccessibility(this, "opened")
        dropProtectedRules()
        if (!uiBuilt) {
            configureSystemBars()
            buildMainUi()
        }
        refreshMainUi()
    }

    private fun configureSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
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

    private fun buildMainUi() {
        uiBuilt = true
        val dp = { value: Float -> AeroGlassUi.dp(this, value) }

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24f), dp(22f), dp(24f), dp(18f))
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        // 1. Prominent modern minimalist heading at the top (non-bold, crisp black)
        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 36f
            typeface = AeroGlassUi.minimalistFont(light = true)
            letterSpacing = 0.025f
            setTextColor(AeroGlassUi.TEXT_BLACK)
            gravity = Gravity.CENTER
            setPadding(0, dp(6f), 0, dp(6f))
        }

        // 2. Seamless open-window graphic with animated blue-area shine centered beneath the title
        val windowGraphic = TakeABreakWindowView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(266f), dp(204f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(4f)
                bottomMargin = dp(12f)
            }
        }

        // 3. Pure crystal Liquid Glass status pill showing PIN-locked & Daily Limits counts
        val statusCard = AeroGlassUi.createGlassCard(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(10f)
            }
        }

        lockedCountView = TextView(this).apply {
            textSize = 26f
            typeface = AeroGlassUi.minimalistFont(light = true)
            setTextColor(AeroGlassUi.TEXT_BLACK)
            gravity = Gravity.CENTER
        }
        val lockedCol = buildStatColumn(lockedCountView, "PIN-Locked Apps")

        val divider = View(this).apply {
            setBackgroundColor(0x307C9FB5)
            layoutParams = LinearLayout.LayoutParams(dp(1f), dp(36f)).apply {
                marginStart = dp(8f)
                marginEnd = dp(8f)
            }
        }

        limitsCountView = TextView(this).apply {
            textSize = 26f
            typeface = AeroGlassUi.minimalistFont(light = true)
            setTextColor(AeroGlassUi.TEXT_BLACK)
            gravity = Gravity.CENTER
        }
        val limitsCol = buildStatColumn(limitsCountView, "Usage Limits")

        statusCard.addView(lockedCol)
        statusCard.addView(divider)
        statusCard.addView(limitsCol)

        // 4. Optional frosted glass notices
        accNoticeView = AeroGlassUi.createGlassNotice(
            this,
            getString(R.string.main_accessibility_off_note),
            isWarning = true,
        )
        phoneNoticeView = AeroGlassUi.createGlassNotice(
            this,
            getString(R.string.main_phone_not_set_note),
            isWarning = false,
        )

        // 5. Crystal-clear Liquid Glass menu buttons (clear in the middle, catching subtle green at bottom)
        val buttonColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(6f)
            }
        }

        // Pages that don't require PIN to view
        buttonColumn.addView(
            AeroGlassUi.createGlassButton(
                this,
                "Set Focus Mode (Coming Soon)",
                AeroGlassUi.GlassTone.LIQUID_FROST,
            ) {}.apply {
                isEnabled = false
            },
        )

        buttonColumn.addView(
            AeroGlassUi.createGlassButton(
                this,
                "Time Spent on Apps",
                AeroGlassUi.GlassTone.LIQUID_CLEAR,
            ) {
                startActivity(Intent(this@MainActivity, TimeSpentActivity::class.java))
            },
        )

        buttonColumn.addView(
            AeroGlassUi.createGlassButton(
                this,
                "White Noise (Coming Soon)",
                AeroGlassUi.GlassTone.LIQUID_FROST,
            ) {}.apply {
                isEnabled = false
            },
        )

        // Thin divider separating open pages from PIN-protected & settings controls
        buttonColumn.addView(
            AeroGlassUi.createDivider(this, verticalMarginDp = 10f).apply {
                (layoutParams as LinearLayout.LayoutParams).apply {
                    marginStart = dp(12f)
                    marginEnd = dp(12f)
                }
            },
        )

        buttonColumn.addView(
            AeroGlassUi.createGlassButton(
                this,
                "App Usage Limits (PIN)",
                AeroGlassUi.GlassTone.LIQUID_CLEAR,
            ) {
                requirePinThen {
                    startActivity(Intent(this@MainActivity, UsageLimitsActivity::class.java))
                }
            },
        )

        buttonColumn.addView(
            AeroGlassUi.createGlassButton(
                this,
                "Select Apps to Lock (PIN)",
                AeroGlassUi.GlassTone.LIQUID_CLEAR,
            ) {
                requirePinThen {
                    startActivity(Intent(this@MainActivity, AppSelectorActivity::class.java))
                }
            },
        )

        buttonColumn.addView(
            AeroGlassUi.createGlassButton(
                this,
                "Text Message Alerts (PIN)",
                AeroGlassUi.GlassTone.LIQUID_CLEAR,
            ) {
                requirePinThen {
                    startActivity(Intent(this@MainActivity, SmsAlertsActivity::class.java))
                }
            },
        )

        buttonColumn.addView(
            AeroGlassUi.createGlassButton(
                this,
                "Change PIN",
                AeroGlassUi.GlassTone.LIQUID_FROST,
            ) {
                requirePinThen {
                    startActivity(Intent(this@MainActivity, PinSetupActivity::class.java))
                }
            },
        )

        buttonColumn.addView(
            AeroGlassUi.createGlassButton(
                this,
                getString(R.string.setup_title),
                AeroGlassUi.GlassTone.LIQUID_FROST,
            ) {
                startActivity(Intent(this@MainActivity, PermissionsSetupActivity::class.java))
            },
        )

        DebugMenu.addButton(this, buttonColumn, ::requirePinThen)

        content.addView(title)
        content.addView(windowGraphic)
        content.addView(statusCard)
        content.addView(accNoticeView)
        content.addView(phoneNoticeView)
        content.addView(buttonColumn)

        val bottomSpacer = Space(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }

        // 6. Small modern minimalist version footer at the bottom
        val versionFooter = TextView(this).apply {
            text = getString(R.string.main_version_footer, appVersion())
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

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            background = AeroGlassUi.MainMenuBackgroundDrawable()
            addView(outer)
        }

        val basePadH = dp(24f)
        val basePadTop = dp(22f)
        val basePadBot = dp(18f)
        val maxContentWidth = dp(440f)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val availableW = resources.displayMetrics.widthPixels - bars.left - bars.right
            val extraSide = ((availableW - maxContentWidth) / 2).coerceAtLeast(0)
            outer.setPadding(
                basePadH + bars.left + extraSide,
                basePadTop + bars.top,
                basePadH + bars.right + extraSide,
                basePadBot + bars.bottom,
            )
            insets
        }

        setContentView(scroll)
    }

    private fun buildStatColumn(valueView: TextView, caption: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(valueView)
            addView(
                TextView(this@MainActivity).apply {
                    text = caption
                    textSize = 13f
                    typeface = AeroGlassUi.minimalistFont(light = false)
                    letterSpacing = 0.015f
                    setTextColor(AeroGlassUi.TEXT_BLACK)
                    gravity = Gravity.CENTER
                },
            )
        }

    private fun refreshMainUi() {
        if (!uiBuilt) return
        lockedCountView.text = Prefs.getLockedPackages(this).size.toString()
        limitsCountView.text = Prefs.getAllLimits(this).size.toString()

        val accEnabled = isAccessibilityEnabled(this)
        val hasPhoneNumber = Prefs.isValidPhoneNumber(Prefs.getSmsPhoneNumber(this))
        accNoticeView.visibility = if (accEnabled) View.GONE else View.VISIBLE
        phoneNoticeView.visibility = if (hasPhoneNumber) View.GONE else View.VISIBLE
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
        packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
    } catch (_: Exception) {
        "1.0"
    }
}
