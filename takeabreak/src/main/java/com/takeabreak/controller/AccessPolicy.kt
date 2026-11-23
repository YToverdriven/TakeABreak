package com.takeabreak.controller

import android.content.Context

/** What should happen to an app that is currently in front of the user. */
enum class Decision {
    /** Nothing to enforce, let the app run. */
    ALLOW,

    /** App is PIN-locked and the PIN has not been entered this visit. */
    PIN_LOCK,

    /** App has a daily limit and it is used up (no day-unlock, no active extension). */
    TIME_UP
}

/**
 * The ONE place that decides whether an app is blocked.
 *
 * Rules are checked in a fixed order, and each rule only looks at its own state, so
 * features cannot undo each other:
 *
 *  0. Protected   - system cameras, Phone, Settings, Play services, and the default SMS
 *                   app are always allowed. The Play Store is not protected.
 *  1. Time limit  - always wins. A PIN entered earlier does NOT excuse an app that is
 *                   over its limit, and screen pinning cannot excuse it either, because
 *                   blocking is done with an overlay window, not by launching a screen.
 *  2. PIN lock    - needed once per visit to the app.
 *
 * "Visit" = from the moment the app becomes visible on screen (full-screen or in split-screen)
 * until it is no longer visible (user goes Home, switches away, closes its split pane, or the
 * screen turns off).
 */
object AccessPolicy {

    /**
     * @param pinPassed true if the PIN was entered for [pkg] during its current visible visit
     * @param pendingMs usage counted by the service for [pkg] but not saved yet
     */
    fun decide(context: Context, pkg: String, pinPassed: Boolean, pendingMs: Long): Decision {
        if (ProtectedPackages.isProtected(context, pkg)) return Decision.ALLOW
        if (Prefs.isOverLimit(context, pkg, pendingMs)) return Decision.TIME_UP
        if (Prefs.getLockedPackages(context).contains(pkg) && !pinPassed) return Decision.PIN_LOCK
        return Decision.ALLOW
    }
}
