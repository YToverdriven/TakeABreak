package com.takeabreak.controller

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * PIN entry with an on-screen pad. There is no text field, so the keyboard does not have to be
 * opened and the first tap can be a digit.
 */
object PinPrompt {
    fun show(activity: Activity, onSuccess: () -> Unit) {
        val entered = StringBuilder()
        val dots = TextView(activity).apply {
            text = " "
            textSize = 28f
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 16)
        }

        lateinit var dialog: AlertDialog

        fun refresh() {
            dots.text = if (entered.isEmpty()) " " else "\u25CF".repeat(entered.length)
        }

        fun submit() {
            val pin = entered.toString()
            entered.setLength(0)
            refresh()
            if (Prefs.checkPin(activity, pin)) {
                dialog.dismiss()
                onSuccess()
            } else {
                Toast.makeText(activity, "Wrong PIN", Toast.LENGTH_SHORT).show()
            }
        }

        fun key(caption: String, onClick: () -> Unit): Button = Button(activity).apply {
            text = caption
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        fun digit(d: String): Button = key(d) {
            if (entered.length < 12) {
                entered.append(d)
                refresh()
            }
        }

        fun row(vararg keys: View): LinearLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            keys.forEach { addView(it) }
        }

        val pad = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val width = (280 * resources.displayMetrics.density).toInt()
            minimumWidth = width
            setPadding(16, 8, 16, 8)
            addView(dots)
            addView(row(digit("1"), digit("2"), digit("3")))
            addView(row(digit("4"), digit("5"), digit("6")))
            addView(row(digit("7"), digit("8"), digit("9")))
            addView(
                row(
                    key("Del") {
                        if (entered.isNotEmpty()) {
                            entered.deleteCharAt(entered.length - 1)
                            refresh()
                        }
                    },
                    digit("0"),
                    key("OK") { submit() },
                ),
            )
        }

        dialog = AlertDialog.Builder(activity)
            .setTitle("Enter PIN")
            .setView(pad)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
    }
}
