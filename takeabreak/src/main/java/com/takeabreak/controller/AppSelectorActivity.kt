package com.takeabreak.controller

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class AppSelectorActivity : Activity() {
    private val selected = mutableSetOf<String>()
    private lateinit var container: LinearLayout

    override fun onResume() {
        super.onResume()
        noteControllerVisible()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ProtectedPackages.invalidate()
        selected.addAll(
            Prefs.getLockedPackages(this).filterNot { ProtectedPackages.isProtected(this, it) },
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val title = TextView(this).apply {
            text = getString(R.string.select_apps_title)
            textSize = 20f
            setPadding(0, 0, 0, 8)
        }

        val hint = TextView(this).apply {
            text = "Phone, Settings, system cameras, Play services, and the default SMS app are not listed. The Play Store can be locked."
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }

        val saveBtn = Button(this).apply {
            text = "Save locked apps"
            setOnClickListener {
                val kept = selected
                    .asSequence()
                    .filterNot { ProtectedPackages.isProtected(this@AppSelectorActivity, it) }
                    .toSet()
                Prefs.setLockedPackages(this@AppSelectorActivity, kept)
                finish()
            }
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
        root.addView(hint)
        root.addView(scroll)
        root.addView(saveBtn)

        setContentView(root)

        loadApps()
    }

    private fun loadApps() {
        val pm = packageManager
        val apps = pm.getInstalledApplications(0)
            .asSequence()
            .filter { AppList.canControl(this, it, emptySet()) }
            .map { app -> app.packageName to pm.getApplicationLabel(app).toString() }
            .sortedBy { (_, label) -> label.lowercase() }
            .toList()

        for ((pkg, label) in apps) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
                gravity = Gravity.CENTER_VERTICAL
            }

            val cb = CheckBox(this).apply {
                isChecked = selected.contains(pkg)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selected.add(pkg) else selected.remove(pkg)
                }
            }

            val tv = TextView(this).apply {
                text = getString(R.string.app_row_format, label, pkg)
                textSize = 14f
                setPadding(16, 0, 0, 0)
            }

            row.addView(cb)
            row.addView(tv)
            container.addView(row)
        }
    }
}
