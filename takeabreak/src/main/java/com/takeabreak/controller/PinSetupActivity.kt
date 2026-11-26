package com.takeabreak.controller

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
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
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            gravity = Gravity.CENTER
        }

        val title = TextView(this).apply {
            text = getString(R.string.enter_current_pin)
            textSize = 20f
            setPadding(0, 0, 0, 32)
        }

        val pinInput = EditText(this).apply {
            hint = "Current PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }

        val nextBtn = Button(this).apply {
            text = "Continue"
            setOnClickListener {
                val pin = pinInput.text.toString()
                if (Prefs.checkPin(this@PinSetupActivity, pin)) {
                    showNewPinForm()
                } else {
                    Toast.makeText(this@PinSetupActivity, "Wrong PIN", Toast.LENGTH_SHORT).show()
                    pinInput.text.clear()
                    focusPin(pinInput)
                }
            }
        }

        layout.addView(menuBackButton())
        layout.addView(title)
        layout.addView(pinInput)
        layout.addView(nextBtn)
        setContentView(layout)
        focusPin(pinInput)
    }

    private fun showNewPinForm() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val title = TextView(this).apply {
            text = if (Prefs.isPinSet(this@PinSetupActivity)) "Change PIN" else "Set up PIN"
            textSize = 20f
            setPadding(0, 0, 0, 32)
        }

        val pinInput = EditText(this).apply {
            hint = "Enter new PIN (4+ digits)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }

        val confirmInput = EditText(this).apply {
            hint = "Confirm new PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }

        val saveBtn = Button(this).apply {
            text = "Save PIN"
            setOnClickListener {
                val pin = pinInput.text.toString()
                val confirm = confirmInput.text.toString()
                if (pin.length < 4) {
                    Toast.makeText(this@PinSetupActivity, "PIN must be at least 4 digits", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (pin != confirm) {
                    Toast.makeText(this@PinSetupActivity, "PINs do not match", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                Prefs.setPin(this@PinSetupActivity, pin)
                Toast.makeText(this@PinSetupActivity, "PIN saved", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        layout.addView(menuBackButton())
        layout.addView(title)
        layout.addView(pinInput)
        layout.addView(confirmInput)
        layout.addView(saveBtn)
        setContentView(layout)
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
