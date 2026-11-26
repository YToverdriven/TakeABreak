package com.takeabreak.controller

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.os.Bundle

/**
 * Base class for PIN-gated screens in Take a Break.
 *
 * Leaving the app without closing it (Home, Recents, switching apps, turning the screen off, etc.)
 * finishes this screen so returning to Take a Break lands back on [MainActivity] and requires the
 * PIN again. In-process configuration changes (such as rotating the phone) keep the screen open.
 */
abstract class PinLockedActivity : Activity() {
    private var sessionId: Int = NO_SESSION
    private var activeDialog: Dialog? = null

    /**
     * Subclasses may return false when the screen is not currently PIN-gated
     * (for example, first-time PIN setup before any PIN exists).
     */
    protected open fun requiresPinLock(): Boolean = true

    /** True when this activity was restored without a valid unlocked session and is finishing. */
    protected var isLockedOut: Boolean = false
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!requiresPinLock()) return

        if (savedInstanceState == null) {
            sessionId = nextSessionId++
            liveSessions.add(sessionId)
        } else {
            val restored = savedInstanceState.getInt(STATE_SESSION_ID, NO_SESSION)
            if ((restored != NO_SESSION) && (restored in liveSessions)) {
                sessionId = restored
            } else {
                isLockedOut = true
                returnToMain()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (sessionId != NO_SESSION) {
            outState.putInt(STATE_SESSION_ID, sessionId)
        }
    }

    override fun onStop() {
        if (requiresPinLock() && !isChangingConfigurations) {
            dismissActiveDialog()
            releaseSession()
            if (!isFinishing) {
                finish()
            }
        }
        super.onStop()
    }

    override fun onDestroy() {
        dismissActiveDialog()
        if (!isChangingConfigurations) {
            releaseSession()
        }
        super.onDestroy()
    }

    protected fun showManagedDialog(dialog: Dialog) {
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
        if (d != null && d.isShowing) {
            try {
                d.dismiss()
            } catch (_: Exception) {
            }
        }
    }

    private fun releaseSession() {
        if (sessionId != NO_SESSION) {
            liveSessions.remove(sessionId)
            sessionId = NO_SESSION
        }
    }

    private fun returnToMain() {
        if (isTaskRoot) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
        finish()
    }

    companion object {
        private const val STATE_SESSION_ID = "pin_locked_session_id"
        private const val NO_SESSION = 0
        private var nextSessionId = 1
        private val liveSessions = HashSet<Int>()
    }
}
