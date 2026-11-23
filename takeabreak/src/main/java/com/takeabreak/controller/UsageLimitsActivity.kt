package com.takeabreak.controller

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class UsageLimitsActivity : Activity() {
    private lateinit var container: LinearLayout

    override fun onResume() {
        super.onResume()
        noteControllerVisible()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val title = TextView(this).apply {
            text = getString(R.string.usage_limits_title)
            textSize = 20f
            setPadding(0, 0, 0, 8)
        }

        val hint = TextView(this).apply {
            text = "Tap an app to set a daily limit (resets at midnight). Max 23h 59m. Phone, Settings, system cameras, Play services, and the default SMS app are not listed. The Play Store can have a limit."
            textSize = 14f
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
                1f,
            )
        }

        root.addView(menuBackButton("Save"))
        root.addView(title)
        root.addView(hint)
        root.addView(scroll)
        setContentView(root)

        loadApps()
    }

    private fun loadApps() {
        container.removeAllViews()
        val pm = packageManager
        val excluded = AppList.excludedLauncherAndImePackages(this)
        val allLimits = Prefs.getAllLimits(this)

        ProtectedPackages.invalidate()
        val apps = pm.getInstalledApplications(0)
            .asSequence()
            .filter { AppList.canControl(this, it, excluded) }
            .map { app -> app.packageName to pm.getApplicationLabel(app).toString() }
            .sortedBy { (_, label) -> label.lowercase() }
            .toList()

        for ((pkg, label) in apps) {
            val currentMin = allLimits[pkg] ?: 0

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 12, 0, 12)
                setOnClickListener { showSetLimitDialog(pkg, label) }
            }

            val nameTv = TextView(this).apply {
                text = label
                textSize = 16f
            }

            val limitTv = TextView(this).apply {
                text = if (currentMin > 0) "Limit: ${currentMin / 60}h ${currentMin % 60}m" else "No limit"
                textSize = 13f
                setTextColor(0xFF666666.toInt())
            }

            row.addView(nameTv)
            row.addView(limitTv)
            container.addView(row)
        }
    }

    private fun showSetLimitDialog(pkg: String, label: String) {
        val current = Prefs.getLimitMinutes(this, pkg)
        val inputLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(32, 16, 32, 16)
            gravity = Gravity.CENTER_VERTICAL
        }
        val hoursEt = EditText(this).apply {
            hint = "Hours"
            inputType = InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (current > 0) setText((current / 60).toString())
        }
        val minEt = EditText(this).apply {
            hint = "Minutes"
            inputType = InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (current > 0) setText((current % 60).toString())
        }
        inputLayout.addView(hoursEt)
        inputLayout.addView(TextView(this).apply { text = "  " })
        inputLayout.addView(minEt)

        AlertDialog.Builder(this)
            .setTitle("Set limit for $label")
            .setView(inputLayout)
            .setPositiveButton("Set") { _, _ ->
                val h = hoursEt.text.toString().toIntOrNull() ?: 0
                val m = minEt.text.toString().toIntOrNull() ?: 0
                val total = ((h * 60) + m).coerceIn(0, (23 * 60) + 59)
                Prefs.setLimitMinutes(this, pkg, total)
                Toast.makeText(this, if (total > 0) "Limit set" else "Limit cleared", Toast.LENGTH_SHORT).show()
                loadApps() // refresh list so limit shows under the app
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Clear") { _, _ ->
                Prefs.setLimitMinutes(this, pkg, 0)
                Toast.makeText(this, "Limit cleared", Toast.LENGTH_SHORT).show()
                loadApps()
            }
            .show()
    }
}
