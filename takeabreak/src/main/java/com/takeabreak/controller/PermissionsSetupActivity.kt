package com.takeabreak.controller

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.util.Calendar

/**
 * Guided setup wizard for Take a Break.
 *
 * First-time setup ([EXTRA_FIRST_SETUP] = true) walks through Screens 1–8:
 *   1. Welcome
 *   2. Enable Accessibility Service
 *   3. Enable Restricted Settings (shown if Accessibility was not enabled on return from Screen 2)
 *   4. Enable SMS Permission & optional alert phone number
 *   5. Check / Set Up Take a Break Watcher (mandatory; bypasses to "already set up" if ready)
 *   6. Set a PIN
 *   7. Set Up App Blocking
 *   8. Set Up Time Limits
 *
 * Post-setup revisits from the home screen ([EXTRA_FIRST_SETUP] = false) walk through
 * Screens 2–5 only and disallow changing the alert phone number without the PIN.
 */
class PermissionsSetupActivity : Activity() {
    private var isFirstSetup = false
    private var currentStep = STEP_WELCOME
    private var awaitingAccessibilityReturn = false
    private var smsDeniedOnce = false

    // Screen 4 draft state (preserved across orientation changes)
    private var selectedCountryIndex = 0
    private var phoneDraft = ""

    // Screen 5 Watcher check state
    private var watcherProbePending = false
    private var watcherInitialProbeDone = false
    private var watcherAlreadySetUpOnArrival = false
    private var watcherSetupConfirmed = false

    // Screen 6 PIN draft state (preserved across orientation changes)
    private var pinDraft = ""
    private var pinConfirmDraft = ""

    // Screen 7 app blocking draft state
    private val selectedLockedPackages = mutableSetOf<String>()
    private var lockedPackagesLoaded = false

    // Active dialog management
    private var activeDialog: Dialog? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var watcherProbeTimeoutRunnable: Runnable? = null

    // View references updated dynamically
    private var step2AccBtn: Button? = null
    private var step2NextBtn: Button? = null
    private var step3AccBtn: Button? = null
    private var step3NextBtn: Button? = null
    private var step4SmsBtn: Button? = null
    private var step4RestrictedBtn: Button? = null
    private var step4NextBtn: Button? = null
    private var step4PhoneInput: EditText? = null
    private var step4DailyTimeLabel: TextView? = null
    private var step4ChangeTimeBtn: Button? = null
    private var step6PinInput: EditText? = null
    private var step6ConfirmInput: EditText? = null
    private var step7ActionBtn: Button? = null
    private var step8Container: LinearLayout? = null
    private var step8ActionBtn: Button? = null

    private val watcherSetupReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (currentStep != STEP_WATCHER) return
            val setupComplete = intent?.getBooleanExtra(PeerLink.EXTRA_SETUP_COMPLETE, false) == true
            handleWatcherProbeResult(setupComplete)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isFirstSetup = intent.getBooleanExtra(EXTRA_FIRST_SETUP, false)

        if (savedInstanceState != null) {
            currentStep = savedInstanceState.getInt(STATE_STEP, initialStep())
            awaitingAccessibilityReturn = savedInstanceState.getBoolean(STATE_AWAITING_ACC, false)
            smsDeniedOnce = savedInstanceState.getBoolean(STATE_SMS_DENIED, false)
            selectedCountryIndex = savedInstanceState.getInt(STATE_COUNTRY_INDEX, 0)
            phoneDraft = savedInstanceState.getString(STATE_PHONE_DRAFT, "")
            watcherInitialProbeDone = savedInstanceState.getBoolean(STATE_WATCHER_PROBE_DONE, false)
            watcherAlreadySetUpOnArrival = savedInstanceState.getBoolean(STATE_WATCHER_ALREADY_SETUP, false)
            watcherSetupConfirmed = savedInstanceState.getBoolean(STATE_WATCHER_CONFIRMED, false)
            pinDraft = savedInstanceState.getString(STATE_PIN_DRAFT, "")
            pinConfirmDraft = savedInstanceState.getString(STATE_PIN_CONFIRM_DRAFT, "")
            val savedLocked = savedInstanceState.getStringArrayList(STATE_LOCKED_PKGS)
            if (savedLocked != null) {
                selectedLockedPackages.clear()
                selectedLockedPackages.addAll(savedLocked)
                lockedPackagesLoaded = true
            }
        } else {
            currentStep = initialStep()
            if (isFirstSetup) {
                Prefs.setSetupInProgress(this, inProgress = true)
                preloadPhoneFromPrefs()
            }
        }

        renderStep()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            watcherSetupReceiver,
            IntentFilter(PeerLink.ACTION_WATCHER_SETUP_UPDATED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStop() {
        cancelWatcherProbeTimeout()
        try {
            unregisterReceiver(watcherSetupReceiver)
        } catch (_: Exception) {
        }
        if (!isChangingConfigurations) {
            dismissActiveDialog()
        }
        super.onStop()
    }

    override fun onDestroy() {
        cancelWatcherProbeTimeout()
        dismissActiveDialog()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        captureDraftInputs()
        outState.putInt(STATE_STEP, currentStep)
        outState.putBoolean(STATE_AWAITING_ACC, awaitingAccessibilityReturn)
        outState.putBoolean(STATE_SMS_DENIED, smsDeniedOnce)
        outState.putInt(STATE_COUNTRY_INDEX, selectedCountryIndex)
        outState.putString(STATE_PHONE_DRAFT, phoneDraft)
        outState.putBoolean(STATE_WATCHER_PROBE_DONE, watcherInitialProbeDone)
        outState.putBoolean(STATE_WATCHER_ALREADY_SETUP, watcherAlreadySetUpOnArrival)
        outState.putBoolean(STATE_WATCHER_CONFIRMED, watcherSetupConfirmed)
        outState.putString(STATE_PIN_DRAFT, pinDraft)
        outState.putString(STATE_PIN_CONFIRM_DRAFT, pinConfirmDraft)
        if (lockedPackagesLoaded) {
            outState.putStringArrayList(STATE_LOCKED_PKGS, ArrayList(selectedLockedPackages))
        }
    }

    override fun onResume() {
        super.onResume()
        noteControllerVisible()
        PeerLink.checkAccessibility(this, "setup")

        when (currentStep) {
            STEP_ACCESSIBILITY -> {
                if (awaitingAccessibilityReturn) {
                    awaitingAccessibilityReturn = false
                    if (isAccessibilityEnabled(this)) {
                        goToStep(STEP_SMS)
                    } else {
                        goToStep(STEP_RESTRICTED_SETTINGS)
                    }
                    return
                }
                refreshStep2Ui()
            }
            STEP_RESTRICTED_SETTINGS -> refreshStep3Ui()
            STEP_SMS -> refreshStep4Ui()
            STEP_WATCHER -> startWatcherCheck()
            STEP_TIME_LIMITS -> refreshStep8List()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentStep == STEP_TIME_LIMITS) {
            goToStep(STEP_APP_BLOCKING)
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    @Deprecated("Use the activity result API when the UI is rewritten.")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != SMS_REQUEST) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            toast("SMS permission granted")
            SmsAlerts.processQueue(this)
        } else {
            smsDeniedOnce = true
            showRestrictedSettingsDialog(forSms = true)
        }
        if (currentStep == STEP_SMS) {
            refreshStep4Ui()
        }
    }

    private fun initialStep(): Int {
        if (!isFirstSetup) return STEP_ACCESSIBILITY
        if (Prefs.isPinSet(this) && Prefs.isSetupInProgress(this)) {
            val saved = Prefs.getSetupStep(this)
            return if (saved == STEP_TIME_LIMITS) STEP_TIME_LIMITS else STEP_APP_BLOCKING
        }
        if (Prefs.isSetupInProgress(this)) {
            val saved = Prefs.getSetupStep(this)
            if (saved in STEP_WELCOME..STEP_PIN) {
                return saved
            }
        }
        return STEP_WELCOME
    }

    private fun preloadPhoneFromPrefs() {
        val existing = Prefs.getSmsPhoneNumber(this)
        if (existing.isEmpty()) return
        val matchedIndex = COUNTRY_CODES.indexOfFirst { entry ->
            entry.prefix.isNotEmpty() && existing.startsWith(entry.prefix)
        }
        if (matchedIndex >= 0) {
            selectedCountryIndex = matchedIndex
            val digits = existing.removePrefix(COUNTRY_CODES[matchedIndex].prefix).filter { it.isDigit() }
            phoneDraft = formatPhoneWithDashes(digits)
        } else {
            selectedCountryIndex = COUNTRY_CODES.lastIndex
            phoneDraft = existing
        }
    }

    private fun captureDraftInputs() {
        step4PhoneInput?.let { phoneDraft = it.text?.toString().orEmpty() }
        step6PinInput?.let { pinDraft = it.text?.toString().orEmpty() }
        step6ConfirmInput?.let { pinConfirmDraft = it.text?.toString().orEmpty() }
    }

    private fun goToStep(step: Int) {
        captureDraftInputs()
        cancelWatcherProbeTimeout()
        if (step == STEP_WATCHER && currentStep != STEP_WATCHER) {
            watcherProbePending = false
            watcherInitialProbeDone = false
            watcherAlreadySetUpOnArrival = false
            watcherSetupConfirmed = false
        }
        currentStep = step
        if (isFirstSetup) {
            Prefs.setSetupStep(this, step)
        }
        renderStep()
        if (step == STEP_WATCHER) {
            startWatcherCheck()
        }
    }

    private fun clearViewRefs() {
        step2AccBtn = null
        step2NextBtn = null
        step3AccBtn = null
        step3NextBtn = null
        step4SmsBtn = null
        step4RestrictedBtn = null
        step4NextBtn = null
        step4PhoneInput = null
        step4DailyTimeLabel = null
        step4ChangeTimeBtn = null
        step6PinInput = null
        step6ConfirmInput = null
        step7ActionBtn = null
        step8Container = null
        step8ActionBtn = null
    }

    private fun renderStep() {
        clearViewRefs()
        when (currentStep) {
            STEP_WELCOME -> renderStep1Welcome()
            STEP_ACCESSIBILITY -> renderStep2Accessibility()
            STEP_RESTRICTED_SETTINGS -> renderStep3RestrictedSettings()
            STEP_SMS -> renderStep4Sms()
            STEP_WATCHER -> renderStep5Watcher()
            STEP_PIN -> renderStep6Pin()
            STEP_APP_BLOCKING -> renderStep7AppBlocking()
            STEP_TIME_LIMITS -> renderStep8TimeLimits()
        }
    }

    // ---- Screen 1: Welcome ----

    private fun renderStep1Welcome() {
        val dp = { value: Float -> AeroGlassUi.dp(this, value) }
        val column = baseColumn().apply {
            gravity = Gravity.CENTER
        }
        column.addView(AeroGlassUi.createTitle(this, "Welcome to Take a Break", sizeSp = 32f, centered = true))
        column.addView(
            TakeABreakWindowView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(248f), dp(192f)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(8f)
                    bottomMargin = dp(16f)
                }
            },
        )
        val infoCard = AeroGlassUi.createGlassCard(this, cornerDp = 24f).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(16f)
            }
            addView(
                AeroGlassUi.createBodyText(
                    this@PermissionsSetupActivity,
                    "Take a Break is an accountability application for those who want to spend less time on their phones.",
                    centered = true,
                ).apply {
                    setPadding(0, dp(2f), 0, dp(2f))
                },
            )
        }
        column.addView(infoCard)
        column.addView(
            primaryButton("Begin Setup", AeroGlassUi.GlassTone.LIQUID_SKY) {
                goToStep(STEP_ACCESSIBILITY)
            },
        )
        setScrollContent(column)
    }

    // ---- Screen 2: Enable Accessibility Service ----

    private fun renderStep2Accessibility() {
        val column = baseColumn()
        if (!isFirstSetup) {
            column.addView(menuBackButton())
        }
        column.addView(headingView("Enable Accessibility Service"))
        column.addView(
            infoCard(
                "Accessibility Service allows Take a Break to monitor usage and manage time- or PIN-related restrictions. Take a Break does not connect to the internet or share your information beyond what you allow.",
            ),
        )

        val accBtn = primaryButton("Enable Accessibility Service") {
            showAccessibilityExplanationDialog()
        }
        val nextBtn = primaryButton("Next", AeroGlassUi.GlassTone.LIQUID_SKY) {
            goToStep(STEP_SMS)
        }

        step2AccBtn = accBtn
        step2NextBtn = nextBtn
        column.addView(accBtn)
        column.addView(nextBtn)

        setScrollContent(column)
        refreshStep2Ui()
    }

    private fun refreshStep2Ui() {
        val enabled = isAccessibilityEnabled(this)
        step2AccBtn?.apply {
            isEnabled = !enabled
            text = if (enabled) "Accessibility Service Enabled" else "Enable Accessibility Service"
        }
        step2NextBtn?.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    private fun showAccessibilityExplanationDialog() {
        showManagedDialog(
            AeroGlassUi.createGlassDialog(
                context = this,
                title = "Enable Accessibility Service",
                message = "The option to enable Accessibility Service may be greyed out if the app was downloaded from an unofficial source. Please try tapping the permission anyway. Dismiss any pop-up concerning restricted settings. You will then be able to enable restricted settings if required by your Android version.",
                positiveText = "I Understand",
                onPositive = { dlg ->
                    dlg.dismiss()
                    awaitingAccessibilityReturn = true
                    openAccessibilitySettings()
                },
                cancelable = true,
            ),
        )
    }

    // ---- Screen 3: Enable Restricted Settings ----

    private fun renderStep3RestrictedSettings() {
        val column = baseColumn()
        if (!isFirstSetup) {
            column.addView(menuBackButton())
        }
        column.addView(headingView("Continuing Permissions Setup"))
        column.addView(
            infoCard(
                "You are viewing this screen because Accessibility Service was not enabled. If Accessibility Service was blocked, enable restricted settings first, then try tapping Take a Break in Accessibility Settings again.",
            ),
        )

        val restrictedBtn = primaryButton("Restricted Settings", AeroGlassUi.GlassTone.LIQUID_FROST) {
            showRestrictedSettingsDialog(forSms = false)
        }
        val accBtn = primaryButton("Enable Accessibility Service") {
            openAccessibilitySettings()
        }
        val nextBtn = primaryButton("Next", AeroGlassUi.GlassTone.LIQUID_SKY) {
            goToStep(STEP_SMS)
        }

        step3AccBtn = accBtn
        step3NextBtn = nextBtn
        column.addView(restrictedBtn)
        column.addView(accBtn)
        column.addView(nextBtn)

        setScrollContent(column)
        refreshStep3Ui()
    }

    private fun refreshStep3Ui() {
        val enabled = isAccessibilityEnabled(this)
        step3AccBtn?.apply {
            isEnabled = !enabled
            text = if (enabled) "Accessibility Service Enabled" else "Enable Accessibility Service"
        }
        step3NextBtn?.isEnabled = enabled
    }

    // ---- Screen 4: Enable SMS Permission ----

    private fun renderStep4Sms() {
        val dp = { value: Float -> AeroGlassUi.dp(this, value) }
        val column = baseColumn()
        if (!isFirstSetup) {
            column.addView(menuBackButton())
        }
        column.addView(headingView("Set Up Text Message Alerts"))
        column.addView(
            infoCard("SMS permission may also require restricted settings to be enabled."),
        )

        val smsBtn = primaryButton("Grant SMS Permission") {
            requestPermissions(arrayOf(Manifest.permission.SEND_SMS), SMS_REQUEST)
        }
        val restrictedBtn = primaryButton("Restricted Settings", AeroGlassUi.GlassTone.LIQUID_FROST) {
            showRestrictedSettingsDialog(forSms = true)
        }

        step4SmsBtn = smsBtn
        step4RestrictedBtn = restrictedBtn
        column.addView(smsBtn)
        column.addView(restrictedBtn)

        if (isFirstSetup) {
            val phoneCard = AeroGlassUi.createGlassCard(this, cornerDp = 22f).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(12f)
                    bottomMargin = dp(10f)
                }
            }

            val phoneLabel = AeroGlassUi.createSectionTitle(this, "Enter Phone Number (Optional)")

            val phoneRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(4f)
                }
            }

            val countrySpinner = AeroGlassUi.createGlassSpinner(
                context = this,
                items = COUNTRY_CODES.map { it.label },
                selectedIndex = selectedCountryIndex,
            ) { position ->
                selectedCountryIndex = position
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginEnd = dp(8f)
                }
            }

            val phoneInput = AeroGlassUi.createGlassInput(
                this,
                "555-123-4567",
                InputType.TYPE_CLASS_PHONE,
            ).apply {
                setText(phoneDraft)
                setSelection(text?.length ?: 0)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                )
                addTextChangedListener(
                    PhoneDashTextWatcher(
                        editText = this,
                        useDashFormatting = { COUNTRY_CODES[selectedCountryIndex].prefix.isNotEmpty() },
                    ),
                )
            }
            step4PhoneInput = phoneInput

            phoneRow.addView(countrySpinner)
            phoneRow.addView(phoneInput)
            phoneCard.addView(phoneLabel)
            phoneCard.addView(phoneRow)
            column.addView(phoneCard)

            val togglesCard = AeroGlassUi.createGlassCard(this, cornerDp = 22f).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = dp(10f)
                }
            }

            val togglesHeader = AeroGlassUi.createSectionTitle(this, "Send an SMS when:")

            val newAppCb = AeroGlassUi.createGlassCheckBox(
                this,
                "A new app is installed",
                Prefs.isAlertNewAppEnabled(this),
            ) { checked ->
                Prefs.setAlertNewAppEnabled(this@PermissionsSetupActivity, checked)
            }

            val tamperCb = AeroGlassUi.createGlassCheckBox(
                this,
                "Take a Break or Watcher is deleted, disabled, reset, or accessibility is turned off",
                Prefs.isAlertTamperEnabled(this),
            ) { checked ->
                Prefs.setAlertTamperEnabled(this@PermissionsSetupActivity, checked)
                WatcherMessages.syncConfig(this@PermissionsSetupActivity)
            }

            val dayUnlockCb = AeroGlassUi.createGlassCheckBox(
                this,
                "PIN is entered to unlock a blocked app",
                Prefs.isAlertDayUnlockEnabled(this),
            ) { checked ->
                Prefs.setAlertDayUnlockEnabled(this@PermissionsSetupActivity, checked)
            }

            val dailyStatusCb = AeroGlassUi.createGlassCheckBox(
                this,
                "Once-a-day status check",
                Prefs.isDailyStatusEnabled(this),
            ) { checked ->
                Prefs.setDailyStatusEnabled(this@PermissionsSetupActivity, checked)
                DailyStatusWorker.syncSchedule(this@PermissionsSetupActivity)
                refreshStep4DailyTimeUi()
            }

            val timeRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(28f), dp(6f), 0, dp(4f))
            }

            val dailyTimeLabel = TextView(this).apply {
                textSize = 14.5f
                typeface = AeroGlassUi.minimalistFont(light = false)
                setTextColor(AeroGlassUi.TEXT_SECONDARY)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val changeTimeBtn = AeroGlassUi.createCompactGlassButton(
                this,
                "Change Time",
                AeroGlassUi.GlassTone.LIQUID_CLEAR,
            ) {
                showStep4TimePicker()
            }

            step4DailyTimeLabel = dailyTimeLabel
            step4ChangeTimeBtn = changeTimeBtn
            timeRow.addView(dailyTimeLabel)
            timeRow.addView(changeTimeBtn)

            togglesCard.addView(togglesHeader)
            togglesCard.addView(newAppCb)
            togglesCard.addView(AeroGlassUi.createDivider(this, verticalMarginDp = 3f))
            togglesCard.addView(tamperCb)
            togglesCard.addView(AeroGlassUi.createDivider(this, verticalMarginDp = 3f))
            togglesCard.addView(dayUnlockCb)
            togglesCard.addView(AeroGlassUi.createDivider(this, verticalMarginDp = 3f))
            togglesCard.addView(dailyStatusCb)
            togglesCard.addView(timeRow)
            column.addView(togglesCard)
        } else {
            column.addView(
                AeroGlassUi.createGlassNotice(
                    this,
                    "Alert phone number and SMS alert settings require your PIN. Open Text Message Alerts from the home screen to make changes.",
                    isWarning = false,
                ).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = dp(10f)
                        bottomMargin = dp(8f)
                    }
                },
            )
        }

        val nextBtn = primaryButton("Next", AeroGlassUi.GlassTone.LIQUID_SKY) {
            if (isFirstSetup && !saveOptionalPhoneNumber()) {
                return@primaryButton
            }
            goToStep(STEP_WATCHER)
        }
        step4NextBtn = nextBtn
        column.addView(nextBtn)

        setScrollContent(column)
        refreshStep4DailyTimeUi()
        refreshStep4Ui()
    }

    private fun refreshStep4DailyTimeUi() {
        val label = step4DailyTimeLabel ?: return
        val enabled = Prefs.isDailyStatusEnabled(this)
        val hour = Prefs.getDailyStatusHour(this)
        val minute = Prefs.getDailyStatusMinute(this)
        label.text = getString(R.string.daily_status_scheduled_time, formatTime(hour, minute))
        step4ChangeTimeBtn?.isEnabled = enabled
    }

    private fun showStep4TimePicker() {
        val hour = Prefs.getDailyStatusHour(this)
        val minute = Prefs.getDailyStatusMinute(this)
        val is24Hour = DateFormat.is24HourFormat(this)
        showManagedDialog(
            AeroGlassUi.createGlassTimePickerDialog(
                context = this,
                title = "Daily Status Time",
                initialHour = hour,
                initialMinute = minute,
                is24Hour = is24Hour,
            ) { selectedHour, selectedMinute ->
                Prefs.setDailyStatusTime(this, selectedHour, selectedMinute)
                DailyStatusWorker.syncSchedule(this)
                refreshStep4DailyTimeUi()
                toast("Daily status time set to ${formatTime(selectedHour, selectedMinute)}")
            },
        )
    }

    private fun formatTime(hour: Int, minute: Int): String {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
        }
        return DateFormat.getTimeFormat(this).format(cal.time)
    }

    private fun refreshStep4Ui() {
        val granted = hasSmsPermission()
        step4SmsBtn?.apply {
            isEnabled = !granted
            text = if (granted) "SMS Permission Granted" else "Grant SMS Permission"
        }
        step4RestrictedBtn?.visibility = if (!granted && smsDeniedOnce) View.VISIBLE else View.GONE
        step4NextBtn?.isEnabled = granted
    }

    /** Saves the optional phone number if entered; returns false if non-empty and invalid. */
    private fun saveOptionalPhoneNumber(): Boolean {
        val raw = step4PhoneInput?.text?.toString().orEmpty().trim()
        phoneDraft = raw
        if (raw.isEmpty()) {
            return true
        }
        val prefix = COUNTRY_CODES[selectedCountryIndex.coerceIn(0, COUNTRY_CODES.lastIndex)].prefix
        val combined = if (prefix.isNotEmpty()) {
            val digits = raw.filter { it.isDigit() }
            if (digits.isEmpty()) "" else "$prefix$digits"
        } else {
            raw
        }
        if (combined.isEmpty()) {
            return true
        }
        val normalized = Prefs.normalizePhoneNumber(combined)
        if (!Prefs.isValidPhoneNumber(normalized)) {
            toast("Enter a valid phone number, or leave it blank to set up later")
            return false
        }
        Prefs.setSmsPhoneNumber(this, normalized)
        WatcherMessages.syncConfig(this)
        DailyStatusWorker.syncSchedule(this)
        return true
    }

    // ---- Screen 5: Check for Watcher App ----

    private fun startWatcherCheck() {
        cancelWatcherProbeTimeout()
        val installed = PeerLink.isWatcherInstalled(this)
        if (!installed) {
            watcherProbePending = false
            watcherInitialProbeDone = true
            watcherAlreadySetUpOnArrival = false
            watcherSetupConfirmed = false
            renderStep5Watcher()
            return
        }

        watcherProbePending = !watcherInitialProbeDone
        renderStep5Watcher()
        PeerLink.probeWatcherSetup(this)

        val timeout = Runnable {
            if (currentStep == STEP_WATCHER && watcherProbePending) {
                watcherProbePending = false
                watcherInitialProbeDone = true
                renderStep5Watcher()
            }
        }
        watcherProbeTimeoutRunnable = timeout
        mainHandler.postDelayed(timeout, WATCHER_PROBE_TIMEOUT_MS)
    }

    private fun handleWatcherProbeResult(setupComplete: Boolean) {
        cancelWatcherProbeTimeout()
        val wasInitialCheck = !watcherInitialProbeDone
        watcherProbePending = false
        watcherInitialProbeDone = true
        watcherSetupConfirmed = setupComplete
        if (wasInitialCheck && setupComplete) {
            watcherAlreadySetUpOnArrival = true
        }
        renderStep5Watcher()
    }

    private fun cancelWatcherProbeTimeout() {
        watcherProbeTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        watcherProbeTimeoutRunnable = null
    }

    private fun renderStep5Watcher() {
        val dp = { value: Float -> AeroGlassUi.dp(this, value) }
        val column = baseColumn()
        if (!isFirstSetup) {
            column.addView(menuBackButton())
        }

        val installed = PeerLink.isWatcherInstalled(this)
        val disabled = PeerLink.isWatcherDisabled(this)

        if (watcherAlreadySetUpOnArrival && installed && watcherSetupConfirmed) {
            column.addView(headingView("Watcher Is Already Set Up"))
            column.addView(
                TakeABreakWindowView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(186f), dp(144f)).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        topMargin = dp(4f)
                        bottomMargin = dp(12f)
                    }
                },
            )
            column.addView(
                infoCard(
                    "Take a Break Watcher is installed and ready to send SMS alerts if Take a Break is ever disabled or deleted.",
                ),
            )
            column.addView(
                primaryButton(if (isFirstSetup) "Next" else "Done", AeroGlassUi.GlassTone.LIQUID_SKY) {
                    proceedPastStep5()
                },
            )
            setScrollContent(column)
            return
        }

        column.addView(headingView("Set Up Take a Break Watcher"))

        if (!installed) {
            val message = if (disabled) {
                "Take a Break Watcher is currently disabled. Please enable it in Android Settings so it can alert via SMS if Take a Break is ever disabled or deleted."
            } else {
                "Take a Break Watcher must be installed to alert via SMS if Take a Break is ever disabled or deleted."
            }
            column.addView(AeroGlassUi.createGlassNotice(this, message, isWarning = true))
        } else if (watcherProbePending) {
            column.addView(infoCard("Checking Take a Break Watcher status..."))
        } else if (watcherSetupConfirmed) {
            column.addView(
                infoCard("Take a Break Watcher setup is complete and SMS permissions are granted."),
            )
        } else {
            column.addView(
                infoCard(
                    "Take a Break Watcher is installed, but its SMS permissions setup is not complete yet.",
                ),
            )
            column.addView(
                primaryButton("Continue Setup on Watcher App") {
                    launchWatcherApp()
                },
            )
        }

        val continueBtn = primaryButton(
            if (isFirstSetup) "Continue" else "Done",
            AeroGlassUi.GlassTone.LIQUID_SKY,
        ) {
            proceedPastStep5()
        }.apply {
            isEnabled = installed && watcherSetupConfirmed
        }
        column.addView(continueBtn)

        setScrollContent(column)
    }

    private fun launchWatcherApp() {
        val launch = packageManager.getLaunchIntentForPackage(PeerLink.PARTNER)
        if (launch != null) {
            try {
                startActivity(launch)
            } catch (_: Exception) {
                toast("Could not open Take a Break Watcher")
            }
        } else {
            toast("Take a Break Watcher is not installed")
        }
    }

    private fun proceedPastStep5() {
        if (!isFirstSetup) {
            finish()
            return
        }
        if (Prefs.isPinSet(this)) {
            goToStep(STEP_APP_BLOCKING)
        } else {
            goToStep(STEP_PIN)
        }
    }

    // ---- Screen 6: Set a PIN ----

    private fun renderStep6Pin() {
        val dp = { value: Float -> AeroGlassUi.dp(this, value) }
        val column = baseColumn()
        column.addView(headingView("Thank You for Your Patience."))

        val formCard = AeroGlassUi.createGlassCard(this, cornerDp = 24f).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(12f)
            }
        }
        formCard.addView(AeroGlassUi.createSectionTitle(this, "Set a PIN: 4 digits or longer."))
        formCard.addView(
            AeroGlassUi.createBodyText(
                this,
                "We highly recommend that the user of this phone avoid knowing the PIN. Someone else close to the user should set the PIN to avoid the temptation to simply bypass the limits. Whoever sets the PIN should write it down in a secure place. If there is an emergency or a critical error, Take a Break can still be deleted.",
                secondary = true,
            ),
        )

        val pinInput = AeroGlassUi.createGlassInput(
            this,
            "Enter PIN (4+ digits)",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        ).apply {
            setText(pinDraft)
            setSelection(text?.length ?: 0)
        }
        val confirmInput = AeroGlassUi.createGlassInput(
            this,
            "Confirm PIN",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        ).apply {
            setText(pinConfirmDraft)
            setSelection(text?.length ?: 0)
        }
        step6PinInput = pinInput
        step6ConfirmInput = confirmInput

        val saveBtn = primaryButton("Save PIN", AeroGlassUi.GlassTone.LIQUID_SKY) {
            val pin = pinInput.text?.toString().orEmpty()
            val confirm = confirmInput.text?.toString().orEmpty()
            if (pin.length < 4) {
                toast("PIN must be at least 4 digits")
                return@primaryButton
            }
            if (pin != confirm) {
                toast("PINs do not match")
                return@primaryButton
            }
            Prefs.setPin(this, pin)
            pinDraft = ""
            pinConfirmDraft = ""
            toast("PIN saved")
            goToStep(STEP_APP_BLOCKING)
        }

        formCard.addView(pinInput)
        formCard.addView(confirmInput)
        formCard.addView(saveBtn)
        column.addView(formCard)
        setScrollContent(column)
    }

    // ---- Screen 7: Set Up App Blocking ----

    private fun renderStep7AppBlocking() {
        ProtectedPackages.invalidate()
        if (!lockedPackagesLoaded) {
            selectedLockedPackages.clear()
            selectedLockedPackages.addAll(
                Prefs.getLockedPackages(this).filterNot { ProtectedPackages.isProtected(this, it) },
            )
            lockedPackagesLoaded = true
        }

        val dp = { value: Float -> AeroGlassUi.dp(this, value) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        root.addView(headingView("Set Up App Blocking"))
        root.addView(
            AeroGlassUi.createGlassNotice(
                this,
                "Apps can be added or removed later (PIN must be entered).\n" +
                    "Block apps without a PIN entry. These apps will now only be usable while supervised.\n" +
                    "Suggestions: VPNs, Unrestricted Browsers, Shopping apps, Social Media.\n" +
                    "Apps like Phone and Settings cannot be blocked.",
                isWarning = false,
            ).apply {
                gravity = Gravity.START
            },
        )

        val listContainer = AeroGlassUi.createGlassCard(this, cornerDp = 22f).apply {
            setPadding(dp(14f), dp(8f), dp(14f), dp(10f))
        }

        val apps = AppList.controllableApps(this)
        apps.forEachIndexed { index, (pkg, label) ->
            if (index > 0) {
                listContainer.addView(AeroGlassUi.createDivider(this, verticalMarginDp = 2f))
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4f), dp(8f), dp(4f), dp(8f))
            }

            val cb = AeroGlassUi.createBareGlassCheckBox(this, selectedLockedPackages.contains(pkg)) { isChecked ->
                if (isChecked) {
                    selectedLockedPackages.add(pkg)
                } else {
                    selectedLockedPackages.remove(pkg)
                }
                refreshStep7ActionButton()
            }

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10f), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            textCol.addView(
                TextView(this).apply {
                    text = label
                    textSize = 15.5f
                    typeface = AeroGlassUi.minimalistFont(light = false)
                    setTextColor(AeroGlassUi.TEXT_BLACK)
                },
            )
            textCol.addView(
                TextView(this).apply {
                    text = pkg
                    textSize = 12.5f
                    typeface = AeroGlassUi.minimalistFont(light = false)
                    setTextColor(AeroGlassUi.TEXT_SECONDARY)
                },
            )

            row.setOnClickListener { cb.isChecked = !cb.isChecked }
            row.addView(cb)
            row.addView(textCol)
            listContainer.addView(row)
        }

        val scroll = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(listContainer)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ).apply {
                topMargin = dp(8f)
                bottomMargin = dp(8f)
            }
        }

        val actionBtn = primaryButton("Skip", AeroGlassUi.GlassTone.LIQUID_SKY) {
            val kept = selectedLockedPackages
                .asSequence()
                .filterNot { ProtectedPackages.isProtected(this, it) }
                .toSet()
            Prefs.setLockedPackages(this, kept)
            goToStep(STEP_TIME_LIMITS)
        }
        step7ActionBtn = actionBtn

        root.addView(scroll)
        root.addView(actionBtn)
        setContentView(AeroGlassUi.wrapFixedScreen(this, root))
        refreshStep7ActionButton()
    }

    private fun refreshStep7ActionButton() {
        step7ActionBtn?.text = if (selectedLockedPackages.isEmpty()) "Skip" else "Save"
    }

    // ---- Screen 8: Set Up Time Limits ----

    private fun renderStep8TimeLimits() {
        val dp = { value: Float -> AeroGlassUi.dp(this, value) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val backBtn = AeroGlassUi.createTopBarBackButton(this, "Back") {
            goToStep(STEP_APP_BLOCKING)
        }

        root.addView(backBtn)
        root.addView(headingView("Set Up Time Limits"))
        root.addView(
            AeroGlassUi.createGlassNotice(
                this,
                "Limits can be added or removed later (PIN must be entered).\n" +
                    "Set daily time limits for apps. Once an app reaches its daily limit, it will be blocked until midnight unless unlocked with your PIN.\n" +
                    "Suggestions: Social Media, Video Streaming, Games, News.\n" +
                    "Apps like Phone and Settings cannot be blocked.",
                isWarning = false,
            ).apply {
                gravity = Gravity.START
            },
        )

        val listContainer = AeroGlassUi.createGlassCard(this, cornerDp = 22f).apply {
            setPadding(dp(16f), dp(8f), dp(16f), dp(10f))
        }
        step8Container = listContainer

        val scroll = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(listContainer)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ).apply {
                topMargin = dp(8f)
                bottomMargin = dp(8f)
            }
        }

        val actionBtn = primaryButton("Skip", AeroGlassUi.GlassTone.LIQUID_SKY) {
            attemptFinishSetup()
        }
        step8ActionBtn = actionBtn

        root.addView(scroll)
        root.addView(actionBtn)
        setContentView(AeroGlassUi.wrapFixedScreen(this, root))
        refreshStep8List()
    }

    private fun refreshStep8List() {
        val dp = { value: Float -> AeroGlassUi.dp(this, value) }
        val container = step8Container ?: return
        container.removeAllViews()
        val allLimits = Prefs.getAllLimits(this)
        val apps = AppList.controllableApps(this)

        apps.forEachIndexed { index, (pkg, label) ->
            if (index > 0) {
                container.addView(AeroGlassUi.createDivider(this, verticalMarginDp = 2f))
            }

            val currentMin = allLimits[pkg] ?: 0
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4f), dp(10f), dp(4f), dp(10f))
                setOnClickListener { showSetLimitDialog(pkg, label) }
            }

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            textCol.addView(
                TextView(this).apply {
                    text = label
                    textSize = 15.5f
                    typeface = AeroGlassUi.minimalistFont(light = false)
                    setTextColor(AeroGlassUi.TEXT_BLACK)
                },
            )
            textCol.addView(
                TextView(this).apply {
                    text = if (currentMin > 0) "Limit: ${currentMin / 60}h ${currentMin % 60}m" else "No limit"
                    textSize = 13f
                    typeface = AeroGlassUi.minimalistFont(light = false)
                    setTextColor(if (currentMin > 0) AeroGlassUi.ACCENT_SKY else AeroGlassUi.TEXT_SECONDARY)
                },
            )

            val chevron = TextView(this).apply {
                text = "\u203A"
                textSize = 20f
                typeface = AeroGlassUi.minimalistFont(light = true)
                setTextColor(AeroGlassUi.TEXT_MUTED)
                setPadding(dp(12f), 0, dp(4f), 0)
            }

            row.addView(textCol)
            row.addView(chevron)
            container.addView(row)
        }

        step8ActionBtn?.text = if (allLimits.isEmpty()) "Skip" else "Save"
    }

    private fun showSetLimitDialog(pkg: String, label: String) {
        val dp = { value: Float -> AeroGlassUi.dp(this, value) }
        val current = Prefs.getLimitMinutes(this, pkg)
        val inputLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2f), dp(6f), dp(2f), dp(6f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        val hoursEt = AeroGlassUi.createGlassInput(
            this,
            "Hours",
            InputType.TYPE_CLASS_NUMBER,
        ).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6f)
            }
            if (current > 0) setText((current / 60).toString())
        }
        val minEt = AeroGlassUi.createGlassInput(
            this,
            "Minutes",
            InputType.TYPE_CLASS_NUMBER,
        ).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6f)
            }
            if (current > 0) setText((current % 60).toString())
        }
        inputLayout.addView(hoursEt)
        inputLayout.addView(minEt)

        showManagedDialog(
            AeroGlassUi.createGlassDialog(
                context = this,
                title = "Set limit for $label",
                customView = inputLayout,
                positiveText = "Set",
                onPositive = { dlg ->
                    val h = hoursEt.text.toString().toIntOrNull() ?: 0
                    val m = minEt.text.toString().toIntOrNull() ?: 0
                    val total = ((h * 60) + m).coerceIn(0, (23 * 60) + 59)
                    Prefs.setLimitMinutes(this, pkg, total)
                    toast(if (total > 0) "Limit set" else "Limit cleared")
                    dlg.dismiss()
                    refreshStep8List()
                },
                negativeText = "Cancel",
                onNegative = { it.dismiss() },
                neutralText = "Clear",
                onNeutral = { dlg ->
                    Prefs.setLimitMinutes(this, pkg, 0)
                    toast("Limit cleared")
                    dlg.dismiss()
                    refreshStep8List()
                },
                maxWidthDp = 380f,
            ),
        )
    }

    private fun attemptFinishSetup() {
        val noLocks = Prefs.getLockedPackages(this).isEmpty()
        val noLimits = Prefs.getAllLimits(this).isEmpty()
        if (noLocks && noLimits) {
            showManagedDialog(
                AeroGlassUi.createGlassDialog(
                    context = this,
                    title = "No Apps Blocked or Limited",
                    message = "You have not selected any apps to PIN-block or set time limits on. You can add them later from the home screen using your PIN. Do you want to continue anyway?",
                    positiveText = "Continue",
                    onPositive = { dlg ->
                        dlg.dismiss()
                        finishFirstSetup()
                    },
                    negativeText = "Go Back",
                    onNegative = { it.dismiss() },
                ),
            )
            return
        }
        finishFirstSetup()
    }

    private fun finishFirstSetup() {
        Prefs.setSetupComplete(this, complete = true)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }

    // ---- Shared Helpers ----

    private fun hasSmsPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        toast("Tap Take a Break, dismiss \"Restricted Settings\" if shown, and return to app")
    }

    private fun openAppsSettings() {
        val allAppsIntent = Intent(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS)
        val appsIntent = Intent(Settings.ACTION_APPLICATION_SETTINGS)
        try {
            startActivity(allAppsIntent)
            toast("Tap Take a Break, then tap ⋮ menu (or scroll down) and enable 'Allow restricted settings'")
        } catch (_: Exception) {
            try {
                startActivity(appsIntent)
                toast("Open All apps → Take a Break, then tap ⋮ menu and enable 'Allow restricted settings'")
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }

    private fun showRestrictedSettingsDialog(forSms: Boolean) {
        val nextStepText = if (forSms) {
            "4. Return to Take a Break and tap Grant SMS Permission again (or enable SMS under Permissions → SMS)."
        } else {
            "4. Return to Take a Break and tap Enable Accessibility Service again (the shortcut option is not needed)."
        }
        showManagedDialog(
            AeroGlassUi.createGlassDialog(
                context = this,
                title = "Allow Restricted Settings",
                message = "On Android 13+ and GrapheneOS, Android hides \"Allow restricted settings\" if App Info is opened directly. You must open it from Settings → Apps → All apps:\n\n" +
                    "1. Tap Open Apps Settings below (or go to Settings → Apps → See all apps) and select Take a Break.\n\n" +
                    "2. Tap the ⋮ menu in the top-right corner (or scroll down on Android 15+ / GrapheneOS).\n\n" +
                    "3. Tap Allow restricted settings.\n\n" +
                    nextStepText,
                positiveText = "Open Apps Settings",
                onPositive = { dlg ->
                    dlg.dismiss()
                    openAppsSettings()
                },
                negativeText = "Close",
                onNegative = { it.dismiss() },
            ),
        )
    }

    private fun showManagedDialog(dialog: Dialog) {
        dismissActiveDialog()
        activeDialog = dialog
        dialog.setOnDismissListener {
            if (activeDialog === dialog) activeDialog = null
        }
        dialog.show()
    }

    private fun dismissActiveDialog() {
        val d = activeDialog
        activeDialog = null
        if ((d != null) && d.isShowing) {
            try {
                d.dismiss()
            } catch (_: Exception) {
            }
        }
    }

    private fun baseColumn(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }

    private fun setScrollContent(column: LinearLayout) {
        setContentView(AeroGlassUi.wrapScrollableScreen(this, column))
    }

    private fun headingView(text: String): TextView = AeroGlassUi.createTitle(this, text)

    private fun infoCard(text: String): LinearLayout {
        val dp = { value: Float -> AeroGlassUi.dp(this, value) }
        return AeroGlassUi.createGlassCard(this, cornerDp = 22f).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(12f)
            }
            addView(
                AeroGlassUi.createBodyText(this@PermissionsSetupActivity, text).apply {
                    setPadding(0, 0, 0, 0)
                },
            )
        }
    }

    private fun primaryButton(
        label: String,
        tone: AeroGlassUi.GlassTone = AeroGlassUi.GlassTone.LIQUID_CLEAR,
        onClick: () -> Unit,
    ): Button = AeroGlassUi.createGlassButton(this, label, tone, onClick)

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private class PhoneDashTextWatcher(
        private val editText: EditText,
        private val useDashFormatting: () -> Boolean,
    ) : TextWatcher {
        private var formatting = false

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

        override fun afterTextChanged(s: Editable?) {
            if (formatting || s == null || !useDashFormatting()) return
            formatting = true
            try {
                val raw = s.toString()
                val cursor = editText.selectionStart.coerceIn(0, raw.length)
                val digitsBeforeCursor = raw.substring(0, cursor).count { it.isDigit() }
                val digits = raw.filter { it.isDigit() }
                val formatted = formatPhoneWithDashes(digits)
                if (formatted != raw) {
                    s.replace(0, s.length, formatted)
                    var newCursor = 0
                    var seenDigits = 0
                    while ((newCursor < formatted.length) && (seenDigits < digitsBeforeCursor)) {
                        if (formatted[newCursor].isDigit()) {
                            seenDigits++
                        }
                        newCursor++
                    }
                    editText.setSelection(newCursor.coerceIn(0, formatted.length))
                }
            } finally {
                formatting = false
            }
        }
    }

    private data class CountryCode(val label: String, val prefix: String)

    companion object {
        const val EXTRA_FIRST_SETUP = "first_setup"

        private const val SMS_REQUEST = 3001
        private const val WATCHER_PROBE_TIMEOUT_MS = 800L

        private const val STEP_WELCOME = 1
        private const val STEP_ACCESSIBILITY = 2
        private const val STEP_RESTRICTED_SETTINGS = 3
        private const val STEP_SMS = 4
        private const val STEP_WATCHER = 5
        private const val STEP_PIN = 6
        private const val STEP_APP_BLOCKING = 7
        private const val STEP_TIME_LIMITS = 8

        private const val STATE_STEP = "setup_step"
        private const val STATE_AWAITING_ACC = "awaiting_acc"
        private const val STATE_SMS_DENIED = "sms_denied"
        private const val STATE_COUNTRY_INDEX = "country_index"
        private const val STATE_PHONE_DRAFT = "phone_draft"
        private const val STATE_WATCHER_PROBE_DONE = "watcher_probe_done"
        private const val STATE_WATCHER_ALREADY_SETUP = "watcher_already_setup"
        private const val STATE_WATCHER_CONFIRMED = "watcher_confirmed"
        private const val STATE_PIN_DRAFT = "pin_draft"
        private const val STATE_PIN_CONFIRM_DRAFT = "pin_confirm_draft"
        private const val STATE_LOCKED_PKGS = "locked_pkgs"

        private val COUNTRY_CODES = listOf(
            CountryCode("+1 (US/CA)", "+1"),
            CountryCode("+44 (UK)", "+44"),
            CountryCode("+61 (AU)", "+61"),
            CountryCode("+64 (NZ)", "+64"),
            CountryCode("+33 (FR)", "+33"),
            CountryCode("+49 (DE)", "+49"),
            CountryCode("+34 (ES)", "+34"),
            CountryCode("+39 (IT)", "+39"),
            CountryCode("+52 (MX)", "+52"),
            CountryCode("+55 (BR)", "+55"),
            CountryCode("+91 (IN)", "+91"),
            CountryCode("+81 (JP)", "+81"),
            CountryCode("+82 (KR)", "+82"),
            CountryCode("+86 (CN)", "+86"),
            CountryCode("Other", ""),
        )

        private fun formatPhoneWithDashes(digits: String): String {
            if (digits.length <= 3) return digits
            if (digits.length <= 6) return "${digits.substring(0, 3)}-${digits.substring(3)}"
            if (digits.length <= 10) {
                return "${digits.substring(0, 3)}-${digits.substring(3, 6)}-${digits.substring(6)}"
            }
            return "${digits.substring(0, 3)}-${digits.substring(3, 6)}-${digits.substring(6, 10)}-${digits.substring(10)}"
        }
    }
}
