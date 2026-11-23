package com.takeabreak.controller

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val title = TextView(this).apply {
            text = getString(R.string.time_spent_title)
            textSize = 20f
            setPadding(0, 0, 0, 8)
        }

        val subtitle = TextView(this).apply {
            text = "Today only. Resets at midnight. Shows minutes and seconds of foreground time. Time on the block screen is not counted."
            textSize = 13f
            setPadding(0, 0, 0, 16)
        }

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val scroll = ScrollView(this).apply {
            addView(container)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        root.addView(menuBackButton())
        root.addView(title)
        root.addView(subtitle)
        root.addView(scroll)
        setContentView(root)

        loadUsage()
    }

    private fun loadUsage() {
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
            val empty = TextView(this).apply {
                text = getString(R.string.time_spent_empty)
                textSize = 16f
                setPadding(0, 32, 0, 0)
            }
            container.addView(empty)
            return
        }

        val maxMs = entries.maxOfOrNull { it.second } ?: 1L

        for ((label, ms, minutes) in entries) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 8, 0, 8)
            }

            val header = TextView(this).apply {
                text = "$label — ${UsageText.format(ms)}"
                textSize = 15f
            }

            val barContainer = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    36,
                )
                setPadding(0, 4, 0, 0)
            }

            val fraction = ms.toFloat() / maxMs.coerceAtLeast(1L).toFloat()
            val bar = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    fraction,
                )
                setBackgroundColor(colorForMinutes(minutes))
            }

            // spacer so the bar doesn't stretch full width always
            val spacer = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1f - fraction,
                )
            }

            barContainer.addView(bar)
            barContainer.addView(spacer)

            row.addView(header)
            row.addView(barContainer)
            container.addView(row)
        }
    }

    private fun colorForMinutes(minutes: Int): Int {
        val hours = minutes / 60.0
        return when {
            hours < 1.0 -> Color.rgb(255, 180, 180)
            hours < 2.0 -> Color.rgb(255, 120, 120)
            hours < 3.0 -> Color.rgb(230, 70, 70)
            hours < 4.0 -> Color.rgb(200, 40, 50)
            else -> Color.rgb(220, 20, 60)
        }
    }
}
