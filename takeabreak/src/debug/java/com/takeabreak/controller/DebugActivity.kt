package com.takeabreak.controller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

class DebugActivity : PinLockedActivity() {
    private lateinit var statusView: TextView

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshStatus()
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(PeerLink.ACTION_LOG_UPDATED)
            addAction(SmsAlerts.ACTION_QUEUE_UPDATED)
        }
        ContextCompat.registerReceiver(
            this,
            logReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStop() {
        try {
            unregisterReceiver(logReceiver)
        } catch (_: Exception) {
        }
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isLockedOut) return

        val dp = { value: Float -> AeroGlassUi.dp(this, value) }

        val title = AeroGlassUi.createTitle(this, getString(R.string.debug_title))
        val intro = AeroGlassUi.createBodyText(
            this,
            "For testing. These do not change the PIN.",
            secondary = true,
        )

        val statusCard = AeroGlassUi.createGlassCard(this, cornerDp = 22f).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(12f)
            }
        }

        statusView = TextView(this).apply {
            textSize = 13.5f
            typeface = AeroGlassUi.minimalistFont(light = false)
            setLineSpacing(AeroGlassUi.dpF(this@DebugActivity, 2.5f), 1f)
            setTextColor(AeroGlassUi.TEXT_BLACK)
            gravity = Gravity.START
        }
        statusCard.addView(statusView)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(menuBackButton())
            addView(title)
            addView(intro)
            addView(statusCard)
            addView(
                action("Clear all time spent today") {
                    confirm(
                        "Clear time spent?",
                        "Today's foreground times are deleted. Limits and PIN locks stay.",
                    ) {
                        dropPendingUsage()
                        Prefs.clearTodayUsage(this@DebugActivity)
                        toast("Time spent cleared")
                    }
                },
            )
            addView(
                action("Reset all apps to no limit") {
                    confirm(
                        "Clear every limit?",
                        "Every daily limit is removed. Time spent and PIN locks stay.",
                    ) {
                        Prefs.clearAllLimits(this@DebugActivity)
                        toast("Limits cleared")
                    }
                },
            )
            addView(
                action("Clear PIN-locked apps") {
                    confirm(
                        "Clear PIN locks?",
                        "No app will ask for a PIN until you select apps again. The PIN itself stays.",
                    ) {
                        Prefs.clearLockedPackages(this@DebugActivity)
                        toast("PIN locks cleared")
                    }
                },
            )
            addView(
                action("Clear today's unlocks and extensions") {
                    confirm(
                        "Clear unlocks and extensions?",
                        "Day-unlocks and the one-time 5-minute extensions are removed, so limits apply again today.",
                    ) {
                        Prefs.clearTodayOverrides(this@DebugActivity)
                        toast("Unlocks and extensions cleared")
                    }
                },
            )
            addView(
                action("Add 2 minutes to each limited app") {
                    confirm(
                        "Add usage?",
                        "Each app that has a limit gets 2 more minutes of today's usage, so the time-up screen can be tested.",
                    ) {
                        val count = Prefs.getAllLimits(this@DebugActivity).size
                        Prefs.addUsageToLimitedApps(this@DebugActivity, 2 * 60_000L)
                        toast(if (count == 0) "No apps have a limit" else "Added 2 minutes to $count apps")
                    }
                },
            )
            addView(
                action("Clear queued SMS") {
                    confirm(
                        "Clear SMS retry queue?",
                        "Any pending SMS alerts waiting to retry will be removed.",
                    ) {
                        SmsAlerts.clearQueue(this@DebugActivity)
                        toast("Queued SMS cleared")
                    }
                },
            )
            addView(
                action("Write status to logcat", AeroGlassUi.GlassTone.LIQUID_FROST) {
                    val text = statusText()
                    Log.d("TakeABreak", "debug status\n$text")
                    toast("Wrote status to logcat, tag TakeABreak")
                },
            )
            addView(
                action("Refresh status", AeroGlassUi.GlassTone.LIQUID_FROST) {
                    refreshStatus()
                },
            )
        }

        setContentView(AeroGlassUi.wrapScrollableScreen(this, column))
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        noteControllerVisible()
        refreshStatus()
    }

    private fun action(
        label: String,
        tone: AeroGlassUi.GlassTone = AeroGlassUi.GlassTone.LIQUID_CLEAR,
        onClick: () -> Unit,
    ): Button = AeroGlassUi.createGlassButton(this, label, tone, onClick)

    private fun confirm(title: String, message: String, onYes: () -> Unit) {
        showManagedDialog(
            AeroGlassUi.createGlassDialog(
                context = this,
                title = title,
                message = message,
                positiveText = "Do it",
                onPositive = { dlg ->
                    dlg.dismiss()
                    onYes()
                    refreshStatus()
                },
                negativeText = "Cancel",
                onNegative = { it.dismiss() },
            ),
        )
    }

    private fun dropPendingUsage() {
        sendBroadcast(
            Intent(AppLockAccessibilityService.ACTION_DROP_PENDING_USAGE)
                .setPackage(packageName),
        )
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun refreshStatus() {
        if (!::statusView.isInitialized) return
        statusView.text = statusText()
        statusView.gravity = Gravity.START
    }

    private fun statusText(): String {
        ProtectedPackages.invalidate()
        val usage = Prefs.getAllUsageMs(this)
        val total = usage.values.sum()
        val protected = ProtectedPackages.describe(this)
        val cameras = ProtectedPackages.describeCameras(this)
        val note = Prefs.getDebugNote(this).ifBlank { "(none yet)" }
        return buildString {
            append("Accessibility service: ")
            append(if (isAccessibilityEnabled(this@DebugActivity)) "enabled" else "off")
            append("\nPIN-locked apps: ")
            append(Prefs.getLockedPackages(this@DebugActivity).size)
            append("\nApps with a limit: ")
            append(Prefs.getAllLimits(this@DebugActivity).size)
            append("\nCamera apps: ")
            append(cameras.size)
            append("\nTracked today: ")
            append(UsageText.format(total))
            append(" across ")
            append(usage.size)
            append(" apps")
            append("\n\nSMS alerts:\n")
            append(SmsAlerts.statusSummary(this@DebugActivity))
            append("\n\nLast service note:\n")
            append(note)
            append("\n\nOther app and link (newest first):\n")
            append(PeerLink.logText(this@DebugActivity))
            append("\n\nNever blocked on this phone:\n")
            if (protected.isEmpty()) {
                append("(none detected)")
            } else {
                append(protected.joinToString("\n"))
            }
            append("\n\nCamera apps on this phone:\n")
            if (cameras.isEmpty()) {
                append("(none detected)")
            } else {
                append(cameras.joinToString("\n"))
            }
        }
    }
}
