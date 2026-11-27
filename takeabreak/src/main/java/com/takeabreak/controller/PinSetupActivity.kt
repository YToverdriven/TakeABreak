package com.takeabreak.controller

import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast

class PinSetupActivity : PinLockedActivity() {
    private var isFirstSetup = false

    override fun requiresPinLock(): Boolean = Prefs.isPinSet(this)

    override fun onResume() {
        super.onResume()
        noteControllerVisible()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isLockedOut) return
        isFirstSetup = !Prefs.isPinSet(this)
        if (!isFirstSetup) {
            showOldPinGate()
        } else {
            showNewPinForm()
        }
    }

    private fun showOldPinGate() {
        val dp = { value: Float -> AeroGlassUi.dp(this, value) }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val title = AeroGlassUi.createTitle(this, getString(R.string.enter_current_pin))
        val subtitle = AeroGlassUi.createBodyText(
            this,
            "Confirm your existing PIN before choosing a new one.",
            secondary = true,
        )

        val card = AeroGlassUi.createGlassCard(this, cornerDp = 24f).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(6f)
            }
        }

        val pinInput = AeroGlassUi.createGlassInput(
            this,
            "Current PIN",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        )

        val nextBtn = AeroGlassUi.createGlassButton(
            this,
            "Continue",
            AeroGlassUi.GlassTone.LIQUID_SKY,
        ) {
            val pin = pinInput.text.toString()
            if (Prefs.checkPin(this@PinSetupActivity, pin)) {
                showNewPinForm()
            } else {
                Toast.makeText(this@PinSetupActivity, "Wrong PIN", Toast.LENGTH_SHORT).show()
                pinInput.text.clear()
                focusPin(pinInput)
            }
        }

        card.addView(pinInput)
        card.addView(nextBtn)

        column.addView(menuBackButton())
        column.addView(title)
        column.addView(subtitle)
        column.addView(card)

        setContentView(AeroGlassUi.wrapScrollableScreen(this, column))
        focusPin(pinInput)
    }

    private fun showNewPinForm() {
        val dp = { value: Float -> AeroGlassUi.dp(this, value) }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val title = AeroGlassUi.createTitle(
            this,
            if (Prefs.isPinSet(this)) "Change PIN" else "Set up PIN",
        )
        val subtitle = AeroGlassUi.createBodyText(
            this,
            "Choose a PIN with at least 4 digits.",
            secondary = true,
        )

        val card = AeroGlassUi.createGlassCard(this, cornerDp = 24f).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(6f)
            }
        }

        val pinInput = AeroGlassUi.createGlassInput(
            this,
            "Enter new PIN (4+ digits)",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        )

        val confirmInput = AeroGlassUi.createGlassInput(
            this,
            "Confirm new PIN",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        )

        val saveBtn = AeroGlassUi.createGlassButton(
            this,
            "Save PIN",
            AeroGlassUi.GlassTone.LIQUID_SKY,
        ) {
            val pin = pinInput.text.toString()
            val confirm = confirmInput.text.toString()
            if (pin.length < 4) {
                Toast.makeText(this@PinSetupActivity, "PIN must be at least 4 digits", Toast.LENGTH_SHORT).show()
                return@createGlassButton
            }
            if (pin != confirm) {
                Toast.makeText(this@PinSetupActivity, "PINs do not match", Toast.LENGTH_SHORT).show()
                return@createGlassButton
            }
            Prefs.setPin(this@PinSetupActivity, pin)
            Toast.makeText(this@PinSetupActivity, "PIN saved", Toast.LENGTH_SHORT).show()
            finish()
        }

        card.addView(pinInput)
        card.addView(confirmInput)
        card.addView(saveBtn)

        column.addView(menuBackButton())
        column.addView(title)
        column.addView(subtitle)
        column.addView(card)

        setContentView(AeroGlassUi.wrapScrollableScreen(this, column))
        focusPin(pinInput)
    }

    /** Open the numeric keyboard on the PIN field. The user should not have to tap it first. */
    private fun focusPin(field: EditText) {
        field.requestFocus()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        field.post {
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }
}
