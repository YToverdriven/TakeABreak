package com.takeabreak.controller

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Liquid Glass PIN entry pop-up with an on-screen pad. There is no text field, so the keyboard
 * does not have to be opened and the first tap can be a digit.
 */
object PinPrompt {
    fun show(activity: Activity, onSuccess: () -> Unit): AlertDialog {
        val dp = { value: Float -> AeroGlassUi.dp(activity, value) }
        val entered = StringBuilder()

        val dotsText = TextView(activity).apply {
            text = "Enter PIN"
            textSize = 17f
            typeface = AeroGlassUi.minimalistFont(light = true)
            letterSpacing = 0.04f
            setTextColor(AeroGlassUi.TEXT_HINT)
            gravity = Gravity.CENTER
        }

        val dotsCard = AeroGlassUi.createGlassCard(activity, cornerDp = 18f).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14f), dp(11f), dp(14f), dp(12f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(10f)
            }
            addView(dotsText)
        }

        lateinit var dialog: AlertDialog

        fun refresh() {
            if (entered.isEmpty()) {
                dotsText.text = "Enter PIN"
                dotsText.textSize = 17f
                dotsText.letterSpacing = 0.04f
                dotsText.setTextColor(AeroGlassUi.TEXT_HINT)
            } else {
                dotsText.text = "\u25CF".repeat(entered.length)
                dotsText.textSize = 23f
                dotsText.letterSpacing = 0.22f
                dotsText.setTextColor(AeroGlassUi.TEXT_BLACK)
            }
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

        fun digit(d: String) = AeroGlassUi.createKeypadButton(
            context = activity,
            caption = d,
            tone = AeroGlassUi.GlassTone.LIQUID_CLEAR,
            keyHeightDp = 50f,
            textSizeSp = 19f,
        ) {
            if (entered.length < 12) {
                entered.append(d)
                refresh()
            }
        }

        fun actionKey(caption: String, tone: AeroGlassUi.GlassTone, onClick: () -> Unit) =
            AeroGlassUi.createKeypadButton(
                context = activity,
                caption = caption,
                tone = tone,
                keyHeightDp = 50f,
                textSizeSp = 15.5f,
                onClick = onClick,
            )

        fun row(vararg keys: View): LinearLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            keys.forEach { addView(it) }
        }

        val pad = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            addView(dotsCard)
            addView(row(digit("1"), digit("2"), digit("3")))
            addView(row(digit("4"), digit("5"), digit("6")))
            addView(row(digit("7"), digit("8"), digit("9")))
            addView(
                row(
                    actionKey("Del", AeroGlassUi.GlassTone.LIQUID_FROST) {
                        if (entered.isNotEmpty()) {
                            entered.deleteCharAt(entered.length - 1)
                            refresh()
                        }
                    },
                    digit("0"),
                    actionKey("OK", AeroGlassUi.GlassTone.LIQUID_SKY) { submit() },
                ),
            )
        }

        dialog = AeroGlassUi.createGlassDialog(
            context = activity,
            title = "Enter PIN",
            customView = pad,
            negativeText = "Cancel",
            onNegative = { it.dismiss() },
            maxWidthDp = 360f,
        )
        dialog.show()
        return dialog
    }
}
