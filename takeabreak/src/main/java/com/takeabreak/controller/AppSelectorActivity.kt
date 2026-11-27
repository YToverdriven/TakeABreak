package com.takeabreak.controller

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class AppSelectorActivity : PinLockedActivity() {
    private val selected = mutableSetOf<String>()
    private lateinit var container: LinearLayout

    override fun onResume() {
        super.onResume()
        noteControllerVisible()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isLockedOut) return

        ProtectedPackages.invalidate()
        selected.addAll(
            Prefs.getLockedPackages(this).filterNot { ProtectedPackages.isProtected(this, it) },
        )

        val dp = { value: Float -> AeroGlassUi.dp(this, value) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val title = AeroGlassUi.createTitle(this, getString(R.string.select_apps_title))
        val hint = AeroGlassUi.createGlassNotice(
            this,
            "Phone, Settings, system cameras, Play services, and the default SMS app are not listed. The Play Store can be locked.",
            isWarning = false,
        ).apply {
            gravity = Gravity.START
        }

        container = AeroGlassUi.createGlassCard(this, cornerDp = 22f).apply {
            setPadding(dp(14f), dp(8f), dp(14f), dp(10f))
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
                bottomMargin = dp(10f)
            }
        }

        val saveBtn = AeroGlassUi.createGlassButton(
            this,
            "Save locked apps",
            AeroGlassUi.GlassTone.LIQUID_SKY,
        ) {
            val kept = selected
                .asSequence()
                .filterNot { ProtectedPackages.isProtected(this@AppSelectorActivity, it) }
                .toSet()
            Prefs.setLockedPackages(this@AppSelectorActivity, kept)
            finish()
        }

        root.addView(menuBackButton())
        root.addView(title)
        root.addView(hint)
        root.addView(scroll)
        root.addView(saveBtn)

        setContentView(AeroGlassUi.wrapFixedScreen(this, root))
        loadApps()
    }

    private fun loadApps() {
        val dp = { value: Float -> AeroGlassUi.dp(this, value) }
        val apps = AppList.controllableApps(this)

        apps.forEachIndexed { index, (pkg, label) ->
            if (index > 0) {
                container.addView(AeroGlassUi.createDivider(this, verticalMarginDp = 2f))
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4f), dp(8f), dp(4f), dp(8f))
            }

            val cb = AeroGlassUi.createBareGlassCheckBox(this, selected.contains(pkg)) { isChecked ->
                if (isChecked) selected.add(pkg) else selected.remove(pkg)
            }

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10f), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val nameTv = TextView(this).apply {
                text = label
                textSize = 15.5f
                typeface = AeroGlassUi.minimalistFont(light = false)
                setTextColor(AeroGlassUi.TEXT_BLACK)
            }

            val pkgTv = TextView(this).apply {
                text = pkg
                textSize = 12.5f
                typeface = AeroGlassUi.minimalistFont(light = false)
                setTextColor(AeroGlassUi.TEXT_SECONDARY)
            }

            textCol.addView(nameTv)
            textCol.addView(pkgTv)

            row.setOnClickListener { cb.isChecked = !cb.isChecked }
            row.addView(cb)
            row.addView(textCol)
            container.addView(row)
        }
    }
}
