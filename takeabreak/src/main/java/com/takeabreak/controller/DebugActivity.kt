package com.takeabreak.controller

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

class DebugActivity : Activity() {
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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val title = TextView(this).apply {
            text = getString(R.string.debug_title)
            textSize = 22f
            setPadding(0, 0, 0, 8)
        }

        val intro = TextView(this).apply {
            text = "For testing. These do not change the PIN."
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }

        statusView = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(menuBackButton())
            addView(title)
            addView(intro)
            addView(statusView)
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
            addView(action("Reset all apps to no limit") {
                confirm(
                    "Clear every limit?",
                    "Every daily limit is removed. Time spent and PIN locks stay."
                ) {
                    Prefs.clearAllLimits(this@DebugActivity)
                    toast("Limits cleared")
                }
            })
            addView(action("Clear PIN-locked apps") {
                confirm(
                    "Clear PIN locks?",
                    "No app will ask for a PIN until you select apps again. The PIN itself stays."
                ) {
                    Prefs.clearLockedPackages(this@DebugActivity)
                    toast("PIN locks cleared")
                }
            })
            addView(action("Clear today's unlocks and extensions") {
                confirm(
                    "Clear unlocks and extensions?",
                    "Day-unlocks and the one-time 5-minute extensions are removed, so limits apply again today."
                ) {
                    Prefs.clearTodayOverrides(this@DebugActivity)
                    toast("Unlocks and extensions cleared")
                }
            })
            addView(action("Add 2 minutes to each limited app") {
                confirm(
                    "Add usage?",
                    "Each app that has a limit gets 2 more minutes of today's usage, so the time-up screen can be tested."
                ) {
                    val count = Prefs.getAllLimits(this@DebugActivity).size
                    Prefs.addUsageToLimitedApps(this@DebugActivity, 2 * 60_000L)
                    toast(if (count == 0) "No apps have a limit" else "Added 2 minutes to $count apps")
                }
            })
            addView(action("Clear queued SMS") {
                confirm(
                    "Clear SMS retry queue?",
                    "Any pending SMS alerts waiting to retry will be removed."
                ) {
                    SmsAlerts.clearQueue(this@DebugActivity)
                    toast("Queued SMS cleared")
                }
            })
            addView(action("Write status to logcat") {
                val text = statusText()
                Log.d("TakeABreak", "debug status\n$text")
                toast("Wrote status to logcat, tag TakeABreak")
            })
            addView(action("Refresh status") { refreshStatus() })
        }

        root.addView(ScrollView(this).apply { addView(column) })
        setContentView(root)
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        noteControllerVisible()
        refreshStatus()
    }

    private fun action(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun confirm(title: String, message: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Do it") { _, _ ->
                onYes()
                refreshStatus()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
