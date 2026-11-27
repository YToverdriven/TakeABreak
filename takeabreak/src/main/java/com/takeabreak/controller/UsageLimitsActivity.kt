package com.takeabreak.controller

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class UsageLimitsActivity : PinLockedActivity() {
    private lateinit var container: LinearLayout

    override fun onResume() {
        super.onResume()
        noteControllerVisible()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isLockedOut) return

        val dp = { value: Float -> AeroGlassUi.dp(this, value) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val title = AeroGlassUi.createTitle(this, getString(R.string.usage_limits_title))
        val hint = AeroGlassUi.createGlassNotice(
            this,
            "Tap an app to set a daily limit (resets at midnight). Max 23h 59m. Phone, Settings, system cameras, Play services, and the default SMS app are not listed. The Play Store can have a limit.",
            isWarning = false,
        ).apply {
            gravity = Gravity.START
        }

        container = AeroGlassUi.createGlassCard(this, cornerDp = 22f).apply {
            setPadding(dp(16f), dp(8f), dp(16f), dp(10f))
        }

        val scroll = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(container)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ).apply {
                topMargin = dp(8f)
            }
        }

        root.addView(menuBackButton("Save"))
        root.addView(title)
        root.addView(hint)
        root.addView(scroll)

        setContentView(AeroGlassUi.wrapFixedScreen(this, root))
        loadApps()
    }

    private fun loadApps() {
        val dp = { value: Float -> AeroGlassUi.dp(this, value) }
        container.removeAllViews()
        val allLimits = Prefs.getAllLimits(this)
        val apps = AppList.controllableApps(this)

        apps.forEachIndexed { index, (pkg, label) ->
            if (index > 0) {
                container.addView(AeroGlassUi.createDivider(this, verticalMarginDp = 2f))
            }

            val currentMin = allLimits[pkg] ?: 0
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4f), dp(11f), dp(4f), dp(11f))
                setOnClickListener { showSetLimitDialog(pkg, label) }
            }

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val nameTv = TextView(this).apply {
                text = label
                textSize = 15.5f
                typeface = AeroGlassUi.minimalistFont(light = false)
                setTextColor(AeroGlassUi.TEXT_BLACK)
            }

            val limitTv = TextView(this).apply {
                text = if (currentMin > 0) "Limit: ${currentMin / 60}h ${currentMin % 60}m" else "No limit"
                textSize = 13f
                typeface = AeroGlassUi.minimalistFont(light = false)
                setTextColor(if (currentMin > 0) AeroGlassUi.ACCENT_SKY else AeroGlassUi.TEXT_SECONDARY)
            }

            val chevron = TextView(this).apply {
                text = "\u203A"
                textSize = 20f
                typeface = AeroGlassUi.minimalistFont(light = true)
                setTextColor(AeroGlassUi.TEXT_MUTED)
                setPadding(dp(12f), 0, dp(4f), 0)
            }

            textCol.addView(nameTv)
            textCol.addView(limitTv)
            row.addView(textCol)
            row.addView(chevron)
            container.addView(row)
        }
    }

    private fun showSetLimitDialog(pkg: String, label: String) {
        val dp = { value: Float -> AeroGlassUi.dp(this, value) }
        val current = Prefs.getLimitMinutes(this, pkg)
        val inputLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2f), dp(6f), dp(2f), dp(6f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        val hoursEt = AeroGlassUi.createGlassInput(
            this,
            "Hours",
            InputType.TYPE_CLASS_NUMBER,
        ).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6f)
            }
            if (current > 0) setText((current / 60).toString())
        }
        val minEt = AeroGlassUi.createGlassInput(
            this,
            "Minutes",
            InputType.TYPE_CLASS_NUMBER,
        ).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6f)
            }
            if (current > 0) setText((current % 60).toString())
        }
        inputLayout.addView(hoursEt)
        inputLayout.addView(minEt)

        showManagedDialog(
            AeroGlassUi.createGlassDialog(
                context = this,
                title = "Set limit for $label",
                customView = inputLayout,
                positiveText = "Set",
                onPositive = { dlg ->
                    val h = hoursEt.text.toString().toIntOrNull() ?: 0
                    val m = minEt.text.toString().toIntOrNull() ?: 0
                    val total = ((h * 60) + m).coerceIn(0, (23 * 60) + 59)
                    Prefs.setLimitMinutes(this, pkg, total)
                    Toast.makeText(
                        this,
                        if (total > 0) "Limit set" else "Limit cleared",
                        Toast.LENGTH_SHORT,
                    ).show()
                    dlg.dismiss()
                    loadApps()
                },
                negativeText = "Cancel",
                onNegative = { it.dismiss() },
                neutralText = "Clear",
                onNeutral = { dlg ->
                    Prefs.setLimitMinutes(this, pkg, 0)
                    Toast.makeText(this, "Limit cleared", Toast.LENGTH_SHORT).show()
                    dlg.dismiss()
                    loadApps()
                },
                maxWidthDp = 380f,
            ),
        )
    }
}
