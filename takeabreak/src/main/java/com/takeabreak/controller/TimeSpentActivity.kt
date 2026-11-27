package com.takeabreak.controller

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat

class TimeSpentActivity : Activity() {
    private lateinit var container: LinearLayout

    private val usageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (::container.isInitialized) loadUsage()
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            usageReceiver,
            IntentFilter(AppLockAccessibilityService.ACTION_USAGE_UPDATED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStop() {
        try {
            unregisterReceiver(usageReceiver)
        } catch (_: Exception) {
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        noteControllerVisible()
        if (::container.isInitialized) loadUsage()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dp = { value: Float -> AeroGlassUi.dp(this, value) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val title = AeroGlassUi.createTitle(this, getString(R.string.time_spent_title))
        val subtitle = AeroGlassUi.createGlassNotice(
            this,
            "Today only. Resets at midnight. Shows minutes and seconds of foreground time. Time on the block screen is not counted.",
            isWarning = false,
        ).apply {
            gravity = Gravity.START
        }

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
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

        root.addView(menuBackButton())
        root.addView(title)
        root.addView(subtitle)
        root.addView(scroll)

        setContentView(AeroGlassUi.wrapFixedScreen(this, root))
        loadUsage()
    }

    private fun loadUsage() {
        val dp = { value: Float -> AeroGlassUi.dp(this, value) }
        container.removeAllViews()
        val usageMap = Prefs.getAllUsageMs(this)
        val pm = packageManager
        val excluded = AppList.excludedLauncherAndImePackages(this)
        val entries = usageMap.entries
            .asSequence()
            .filter { (pkg, ms) -> (pkg !in excluded) && !pkg.startsWith("com.takeabreak") && (ms > 0L) }
            .map { (pkg, ms) ->
                val label = try {
                    val info = pm.getApplicationInfo(pkg, 0)
                    pm.getApplicationLabel(info).toString()
                } catch (_: Exception) {
                    pkg
                }
                Triple(label, ms, (ms / 60_000L).toInt())
            }
            .sortedByDescending { it.second }
            .toList()

        if (entries.isEmpty()) {
            val emptyCard = AeroGlassUi.createGlassCard(this, cornerDp = 22f).apply {
                setPadding(dp(20f), dp(24f), dp(20f), dp(24f))
                addView(
                    AeroGlassUi.createBodyText(
                        this@TimeSpentActivity,
                        getString(R.string.time_spent_empty),
                        centered = true,
                        secondary = true,
                    ).apply {
                        setPadding(0, 0, 0, 0)
                    },
                )
            }
            container.addView(emptyCard)
            return
        }

        val listCard = AeroGlassUi.createGlassCard(this, cornerDp = 22f).apply {
            setPadding(dp(16f), dp(12f), dp(16f), dp(14f))
        }

        val maxMs = entries.maxOfOrNull { it.second } ?: 1L

        entries.forEachIndexed { index, (label, ms, minutes) ->
            if (index > 0) {
                listCard.addView(AeroGlassUi.createDivider(this, verticalMarginDp = 6f))
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(2f), dp(4f), dp(2f), dp(4f))
            }

            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(6f))
            }

            val nameTv = TextView(this).apply {
                text = label
                textSize = 15f
                typeface = AeroGlassUi.minimalistFont(light = false)
                setTextColor(AeroGlassUi.TEXT_BLACK)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val durationTv = TextView(this).apply {
                text = UsageText.format(ms)
                textSize = 14f
                typeface = AeroGlassUi.minimalistFont(light = false)
                setTextColor(AeroGlassUi.TEXT_SECONDARY)
            }

            headerRow.addView(nameTv)
            headerRow.addView(durationTv)

            val barTrack = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = AeroGlassUi.createGlassBarTrackDrawable(this@TimeSpentActivity)
                setPadding(dp(3f), dp(3f), dp(3f), dp(4f))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(20f),
                )
            }

            val fraction = (ms.toFloat() / maxMs.coerceAtLeast(1L).toFloat()).coerceIn(0.04f, 1f)
            val bar = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    fraction,
                )
                background = AeroGlassUi.createGlassBarFillDrawable(
                    this@TimeSpentActivity,
                    colorForMinutes(minutes),
                )
            }

            val spacer = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (1f - fraction).coerceAtLeast(0f),
                )
            }

            barTrack.addView(bar)
            barTrack.addView(spacer)

            row.addView(headerRow)
            row.addView(barTrack)
            listCard.addView(row)
        }

        container.addView(listCard)
    }

    private fun colorForMinutes(minutes: Int): Int {
        val hours = minutes / 60.0
        return when {
            hours < 1.0 -> Color.rgb(112, 188, 226)
            hours < 2.0 -> Color.rgb(245, 168, 122)
            hours < 3.0 -> Color.rgb(236, 118, 112)
            hours < 4.0 -> Color.rgb(214, 72, 78)
            else -> Color.rgb(198, 40, 62)
        }
    }
}
