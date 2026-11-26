package com.takeabreak.controller

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Enforcement engine (rewritten in 0.8.0, multi-window / split-screen aware since 0.10.1).
 *
 * How it works, in one paragraph:
 * The service tracks EVERY app window currently visible on screen ([visiblePkgs]) — one in
 * full-screen, two in split-screen. It learns that from window events AND from a 1-second check
 * of the real window list, so a missed event or an unfocused split-screen pane can never leave
 * a blocked app exposed. Every second, and on every change, it calls [enforce], which asks
 * [AccessPolicy] for a [Decision] on every visible controlled app and shows or hides the single
 * [BlockOverlay]. Nothing else is allowed to block or unblock anything.
 *
 * Why this fixes both the old problems and split-screen glitches:
 *  - No more launching LockActivity/TimeUpActivity or relaunching the app with CLEAR_TASK
 *    (that was racy and cannot work while an app is screen-pinned). The overlay is a window.
 *  - Limits are checked live every second across all visible apps, not only the focused pane.
 *  - Screen pinning needs no special handling: the overlay sits on top of a pinned app too.
 *  - In split-screen, opening a launcher picker or an unblocked app in the second pane does not
 *    end the blocked app's visit or hide the overlay while the blocked app is still on screen.
 *  - Leaving to Home/Recents (when no app windows remain visible) ends all visits.
 *  - Screen off or lock screen ends all visits, and visible apps are re-checked on unlock.
 */
class AppLockAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TakeABreak"
        private const val TICK_MS = 1_000L
        private const val POST_EVENT_PROBE_MS = 250L
        private const val CACHE_MS = 60_000L

        /** Our own screen is open. Save usage immediately so the chart is not one visit behind. */
        const val ACTION_OUR_SCREEN_VISIBLE = "com.takeabreak.controller.OUR_SCREEN_VISIBLE"

        /** Debug "clear time spent" must also drop time counted in memory but not saved yet. */
        const val ACTION_DROP_PENDING_USAGE = "com.takeabreak.controller.DROP_PENDING_USAGE"

        /** Time-spent screen reloads when this arrives, including while it is already open. */
        const val ACTION_USAGE_UPDATED = "com.takeabreak.controller.USAGE_UPDATED"

        /** System windows that appear ON TOP of an app without the user really leaving it. */
        private val TRANSIENT_SYSTEM_PACKAGES = setOf(
            "com.android.systemui",
            "android",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.intentresolver",
        )
    }

    private data class WindowSnapshot(
        val apps: LinkedHashSet<String>,
        val focusedApp: String?,
        val hasHome: Boolean,
        val hasOurActivity: Boolean
    )

    private val handler = Handler(Looper.getMainLooper())
    private var receiverRegistered = false
    private val packageMonitor = PackageMonitorReceiver()

    // ---- Visit state (all in memory; a service restart safely starts fresh visits) ----
    /** All real app packages currently visible on screen (1 full-screen, 2 in split-screen). */
    private val visiblePkgs = LinkedHashSet<String>()

    /** Most recently focused/active app among [visiblePkgs], used only to break ties. */
    private var focusedPkg: String? = null

    /** Last time usage was added up for [visiblePkgs] (elapsedRealtime). */
    private var lastAccumAt = 0L

    /** Usage counted per visible app but not yet saved to Prefs. */
    private val pendingMs = HashMap<String, Long>()

    /** Apps whose PIN was entered during their current continuous visible visit. */
    private val pinPassedPkgs = HashSet<String>()

    /** App set seen by the previous periodic window-list check (ignores one-off glitches). */
    private var lastProbeApps: Set<String>? = null

    private var homePackages: Set<String> = emptySet()
    private var imePackages: Set<String> = emptySet()
    private var cacheAt = 0L
    private var lastUsable = true
    private var lastLogged = ""

    private val powerManager by lazy { getSystemService(POWER_SERVICE) as PowerManager }
    private val keyguardManager by lazy { getSystemService(KEYGUARD_SERVICE) as KeyguardManager }
    private val activityManager by lazy { getSystemService(ACTIVITY_SERVICE) as ActivityManager }
    private val inputMethodManager by lazy { getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager }

    private val overlay by lazy {
        BlockOverlay(
            service = this,
            onGoHome = { performGlobalAction(GLOBAL_ACTION_HOME) },
            onPinAccepted = { pkg, decision -> handlePinAccepted(pkg, decision) },
        ) { pkg -> handleExtend(pkg) }
    }

    private val tick = object : Runnable {
        override fun run() {
            try {
                onTick()
            } catch (e: Exception) {
                Log.e(TAG, "tick failed", e)
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val postEventProbe = Runnable {
        try {
            probeNow()
        } catch (e: Exception) {
            Log.e(TAG, "post-event probe failed", e)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> endAllVisits()
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT,
                -> handler.postDelayed({ probeNow() }, 300L)
                Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                    val isOn = intent.getBooleanExtra("state", false)
                    if (!isOn) SmsAlerts.onAirplaneModeOff(this@AppLockAccessibilityService)
                }
            }
        }
    }

    private val localReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_DROP_PENDING_USAGE -> {
                    pendingMs.clear()
                    lastAccumAt = if (visiblePkgs.isEmpty()) 0L else SystemClock.elapsedRealtime()
                    Log.d(TAG, "dropped pending usage")
                }
                ACTION_OUR_SCREEN_VISIBLE -> handleOurScreenVisible()
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        PeerLink.onAccessibilityConnected(this)
        PeerLink.check(this, "service")
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            }
            ContextCompat.registerReceiver(
                this,
                screenReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED,
            )
            // PACKAGE_ADDED, PACKAGE_REMOVED, and PACKAGE_CHANGED are not delivered to a
            // manifest receiver on Android 8+. FULLY_REMOVED and DATA_CLEARED are, so an
            // uninstall is still recorded when this service is off.
            ContextCompat.registerReceiver(
                this,
                packageMonitor,
                PackageMonitorReceiver.filter(),
                ContextCompat.RECEIVER_EXPORTED,
            )
            ContextCompat.registerReceiver(
                this,
                localReceiver,
                IntentFilter().apply {
                    addAction(ACTION_DROP_PENDING_USAGE)
                    addAction(ACTION_OUR_SCREEN_VISIBLE)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, TICK_MS)
        // Pick up whatever app(s) are already on screen when the service starts.
        handler.postDelayed({ probeNow() }, 500L)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        PeerLink.checkAccessibility(this, "service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        PeerLink.checkAccessibility(this, "service destroyed")
        handler.removeCallbacksAndMessages(null)
        if (receiverRegistered) {
            try {
                unregisterReceiver(screenReceiver)
            } catch (_: Exception) {
            }
            try {
                unregisterReceiver(packageMonitor)
            } catch (_: Exception) {
            }
            receiverRegistered = false
        }
        closeAllUsage()
        overlay.hide()
        try {
            unregisterReceiver(localReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ detecting visible apps

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if ((event == null) || (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)) return
        val pkg = event.packageName?.toString() ?: return
        Log.d(TAG, "event pkg=$pkg class=${event.className} visible=$visiblePkgs")

        if (pkg == packageName) {
            // Only a real screen of ours (MainActivity, TimeSpentActivity, ...) counts as
            // opening Take a Break. The block overlay's window events use this same package,
            // and their class is often null, a button, or a layout — not an Activity.
            val cls = event.className?.toString().orEmpty()
            if (cls.endsWith("Activity")) handleOurScreenVisible()
            return
        }
        observeEvent(pkg)
    }

    /** A window of [pkg] changed state (from an accessibility event). */
    private fun observeEvent(pkg: String) {
        if (!checkUsable()) return
        refreshCaches()
        if (pkg in TRANSIENT_SYSTEM_PACKAGES || pkg in imePackages) return

        val snapshot = probeWindows()
        if (pkg in homePackages) {
            // In split-screen, the launcher/recents picker opens in the second pane while the
            // first app is STILL visible in the other pane. Only end visits if no real apps
            // remain visible on screen.
            if (snapshot != null && snapshot.apps.isNotEmpty()) {
                syncVisibleApps(snapshot.apps, snapshot.focusedApp)
                schedulePostEventProbe()
            } else if (visiblePkgs.isNotEmpty() || overlay.isShowing) {
                endAllVisits()
            }
            return
        }

        if (snapshot != null) {
            // Include every currently visible app window (both split-screen panes) PLUS the
            // newly announced package in case the window list is a few milliseconds behind.
            // A short follow-up probe cleans up any app that was animating out.
            val merged = LinkedHashSet<String>(snapshot.apps)
            merged.add(pkg)
            syncVisibleApps(merged, newFocused = pkg)
            schedulePostEventProbe()
        } else {
            // Fallback when Android's window list is unavailable.
            syncVisibleApps(linkedSetOf(pkg), newFocused = pkg)
        }
    }

    /**
     * Our own Activity came to the front. Save usage right away so charts are current, and
     * close out visits for any apps no longer visible (keeping any app still open in a
     * split-screen pane enforced).
     */
    private fun handleOurScreenVisible() {
        closeAllUsage()
        val snapshot = probeWindows()
        if (snapshot != null && snapshot.apps.isNotEmpty()) {
            syncVisibleApps(snapshot.apps, snapshot.focusedApp)
        } else if (visiblePkgs.isNotEmpty() || overlay.isShowing) {
            endAllVisits()
        }
    }

    /**
     * Asks Android for the real window list and returns ALL visible app windows (including
     * unfocused panes in split-screen). Works even while our overlay is covering the screen.
     */
    private fun probeWindows(): WindowSnapshot? {
        try {
            val list = windows ?: return null
            if (list.isEmpty()) return null
            refreshCaches()

            val apps = LinkedHashSet<String>()
            var focusedApp: String? = null
            var highestLayerApp: String? = null
            var highestLayer = Int.MIN_VALUE
            var hasHome = false
            var hasOurActivity = false
            val rect = Rect()

            for (w in list) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                w.getBoundsInScreen(rect)
                if (rect.isEmpty) continue

                val root = w.root ?: continue
                val pkg = try {
                    root.packageName?.toString()
                } finally {
                    @Suppress("DEPRECATION")
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        try {
                            root.recycle()
                        } catch (_: Exception) {
                        }
                    }
                }
                if (pkg.isNullOrEmpty()) continue

                when (pkg) {
                    packageName -> {
                        if (!overlay.isShowing) hasOurActivity = true
                    }
                    in homePackages -> hasHome = true
                    in TRANSIENT_SYSTEM_PACKAGES, in imePackages -> Unit
                    else -> {
                        apps.add(pkg)
                        if (w.isActive || w.isFocused) {
                            focusedApp = pkg
                        }
                        if (w.layer >= highestLayer) {
                            highestLayer = w.layer
                            highestLayerApp = pkg
                        }
                    }
                }
            }

            // If no app window, launcher, or Take a Break activity was found (e.g., transient
            // animation frame or temporarily unavailable roots), treat the probe as inconclusive
            // so we do not flash the block screen off.
            if (apps.isEmpty() && !hasHome && !hasOurActivity) return null

            return WindowSnapshot(
                apps = apps,
                focusedApp = focusedApp ?: highestLayerApp,
                hasHome = hasHome,
                hasOurActivity = hasOurActivity
            )
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Applies a window-list snapshot. [trusted] = act immediately (screen on / unlock / service
     * start / post-event settle). Otherwise the same visible app set must be seen twice in a
     * row on the 1-second tick so a brief animation frame cannot cause a wrong lock or flash.
     */
    private fun handleProbe(snapshot: WindowSnapshot, trusted: Boolean) {
        val nextApps: Set<String> = snapshot.apps
        val stable = trusted || nextApps == lastProbeApps
        if (nextApps != lastProbeApps || nextApps != visiblePkgs) {
            Log.d(TAG, "probe apps=$nextApps focused=${snapshot.focusedApp} visible=$visiblePkgs trusted=$trusted stable=$stable")
        }
        lastProbeApps = HashSet(nextApps)
        if (!stable) return

        if (nextApps.isEmpty()) {
            if (visiblePkgs.isNotEmpty() || overlay.isShowing) endAllVisits()
        } else if (nextApps != visiblePkgs || (snapshot.focusedApp != null && snapshot.focusedApp != focusedPkg)) {
            syncVisibleApps(nextApps, snapshot.focusedApp)
        }
    }

    private fun probeNow() {
        if (!checkUsable()) return
        val snapshot = probeWindows() ?: return
        handleProbe(snapshot, trusted = true)
    }

    private fun schedulePostEventProbe() {
        handler.removeCallbacks(postEventProbe)
        handler.postDelayed(postEventProbe, POST_EVENT_PROBE_MS)
    }

    /** Updates [visiblePkgs] to [newApps], flushing usage and ending visits for departed apps. */
    private fun syncVisibleApps(newApps: Set<String>, newFocused: String?) {
        val now = SystemClock.elapsedRealtime()
        accumulate(now)

        val departed = visiblePkgs.filter { it !in newApps }
        for (pkg in departed) {
            flushUsageFor(pkg)
            pinPassedPkgs.remove(pkg)
        }

        visiblePkgs.clear()
        visiblePkgs.addAll(newApps)

        focusedPkg = when {
            newFocused != null && newFocused in visiblePkgs -> newFocused
            focusedPkg in visiblePkgs -> focusedPkg
            else -> visiblePkgs.lastOrNull()
        }

        if (visiblePkgs.isEmpty()) {
            lastAccumAt = 0L
        } else if (lastAccumAt == 0L) {
            lastAccumAt = now
        }

        enforce()
    }

    // ------------------------------------------------------------------ the heartbeat

    private fun onTick() {
        if (!checkUsable()) return
        probeWindows()?.let { handleProbe(it, trusted = false) }
        enforce() // also adds up usage and checks time limits live across all visible apps
        // Save about once a second. The chart reads prefs, so a 5-second wait looked like lag.
        flushAllPendingUsage()
    }

    // ------------------------------------------------------------------ the one enforcement point

    private fun enforce() {
        accumulate(SystemClock.elapsedRealtime())
        if (visiblePkgs.isEmpty()) {
            overlay.hide()
            return
        }

        val blocked = mutableListOf<Pair<String, Decision>>()
        for (pkg in visiblePkgs) {
            val decision = AccessPolicy.decide(
                context = this,
                pkg = pkg,
                pinPassed = pkg in pinPassedPkgs,
                pendingMs = pendingMs[pkg] ?: 0L,
            )
            if (decision != Decision.ALLOW) {
                blocked.add(pkg to decision)
            }
        }

        if (blocked.isEmpty()) {
            val logKey = "$visiblePkgs/ALLOW/${overlay.isShowing}"
            if (logKey != lastLogged) {
                lastLogged = logKey
                val line = "enforce visible=$visiblePkgs decision=ALLOW overlayShowing=${overlay.isShowing} pinned=${isPinned()}"
                Log.d(TAG, line)
                Prefs.setDebugNote(this, line)
            }
            overlay.hide()
            return
        }

        // Time-up always wins over PIN-lock if both exist across split-screen panes.
        val topDecision = if (blocked.any { (_, decision) -> decision == Decision.TIME_UP }) {
            Decision.TIME_UP
        } else {
            Decision.PIN_LOCK
        }
        val candidates = blocked.filter { (_, decision) -> decision == topDecision }

        // Keep showing the same app if it is still visible and needs blocking at this priority,
        // so the overlay never jumps or glitches between two split-screen apps.
        val currentShown = overlay.shownPkg
        val chosenPkg = when {
            overlay.isShowing && currentShown != null && candidates.any { it.first == currentShown } -> currentShown
            focusedPkg != null && candidates.any { it.first == focusedPkg } -> focusedPkg!!
            else -> candidates.last().first
        }

        val logKey = "$visiblePkgs/$chosenPkg/$topDecision/${overlay.isShowing}"
        if (logKey != lastLogged) {
            lastLogged = logKey
            val line = "enforce visible=$visiblePkgs chosen=$chosenPkg decision=$topDecision overlayShowing=${overlay.isShowing} pinned=${isPinned()} pendingMs=${pendingMs[chosenPkg] ?: 0L}"
            Log.d(TAG, line)
            Prefs.setDebugNote(this, line)
        }

        if (!overlay.show(chosenPkg, topDecision, isPinned())) {
            // Could not draw the block screen: fail closed by sending the user Home.
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    // ------------------------------------------------------------------ button results from the overlay

    private fun handlePinAccepted(pkg: String, decision: Decision) {
        val label = appLabel(pkg)
        when (decision) {
            Decision.TIME_UP -> {
                val alreadyUnlocked = Prefs.isDayUnlocked(this, pkg)
                Prefs.setDayUnlocked(this, pkg)
                if (!alreadyUnlocked) {
                    PeerLink.reportPinUnlock(this, label, forRestOfDay = true)
                }
            }
            Decision.PIN_LOCK -> {
                if (pkg !in pinPassedPkgs) {
                    PeerLink.reportPinUnlock(this, label, forRestOfDay = false)
                }
            }
            Decision.ALLOW -> Unit
        }
        if (pkg in visiblePkgs) {
            pinPassedPkgs.add(pkg)
        }
        enforce()
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    private fun handleExtend(pkg: String) {
        if (Prefs.isExtensionUsed(this, pkg)) {
            Toast.makeText(this, "Extension already used today", Toast.LENGTH_SHORT).show()
            return
        }
        Prefs.markExtensionUsed(this, pkg)
        Prefs.setExtensionUntil(this, pkg, System.currentTimeMillis() + 5 * 60_000L)
        Toast.makeText(this, "5 more minutes granted", Toast.LENGTH_SHORT).show()
        enforce()
    }

    // ------------------------------------------------------------------ visit + usage bookkeeping

    /** The user is no longer viewing any tracked app (Home, screen off, lock screen, our app full-screen). */
    private fun endAllVisits() {
        Log.d(TAG, "endAllVisits visible=$visiblePkgs overlay=${overlay.isShowing}")
        handler.removeCallbacks(postEventProbe)
        closeAllUsage()
        visiblePkgs.clear()
        focusedPkg = null
        lastAccumAt = 0L
        pendingMs.clear()
        pinPassedPkgs.clear()
        lastProbeApps = null
        overlay.hide()
    }

    /** Adds the time since the last call to every visible app in [pendingMs], unless the block screen is up. */
    private fun accumulate(now: Long) {
        if (visiblePkgs.isNotEmpty() && lastAccumAt > 0L && !overlay.isShowing) {
            val delta = now - lastAccumAt
            if (delta > 0L) {
                for (pkg in visiblePkgs) {
                    pendingMs[pkg] = (pendingMs[pkg] ?: 0L) + delta
                }
            }
        }
        lastAccumAt = if (visiblePkgs.isEmpty()) 0L else now
    }

    private fun flushUsageFor(pkg: String): Boolean {
        val adding = pendingMs.remove(pkg) ?: 0L
        if (adding <= 0L) return false
        Prefs.addUsageMs(this, pkg, adding)
        return true
    }

    private fun flushAllPendingUsage() {
        if (pendingMs.isEmpty()) return
        val snapshot = HashMap(pendingMs)
        pendingMs.clear()
        if (Prefs.addUsageBatch(this, snapshot)) {
            sendBroadcast(Intent(ACTION_USAGE_UPDATED).setPackage(packageName))
        }
    }

    private fun closeAllUsage() {
        accumulate(SystemClock.elapsedRealtime())
        flushAllPendingUsage()
    }

    // ------------------------------------------------------------------ small helpers

    /**
     * Like [isUsable], but when the lock screen is up (for example Android asking for your
     * fingerprint/PIN after unpinning) it also ends all visits and removes the block screen,
     * so the block screen can never sit on top of the fingerprint/PIN prompt.
     * Coming back from the lock screen is picked up by the USER_PRESENT check.
     */
    private fun checkUsable(): Boolean {
        val usable = isUsable()
        if (usable != lastUsable) {
            lastUsable = usable
            Log.d(
                TAG,
                "usable=$usable interactive=${powerManager.isInteractive} keyguardLocked=${keyguardManager.isKeyguardLocked} visible=$visiblePkgs overlay=${overlay.isShowing}",
            )
        }
        if (usable) return true
        if (visiblePkgs.isNotEmpty() || overlay.isShowing) endAllVisits()
        return false
    }

    /** Screen on and not on the lock screen. */
    private fun isUsable(): Boolean =
        powerManager.isInteractive && !keyguardManager.isKeyguardLocked

    private fun isPinned(): Boolean = try {
        activityManager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    } catch (_: Exception) {
        false
    }

    /** Launcher and keyboard package lists change rarely, so refresh them once a minute. */
    private fun refreshCaches() {
        val now = SystemClock.elapsedRealtime()
        if ((cacheAt != 0L) && ((now - cacheAt) < CACHE_MS)) return
        cacheAt = now
        homePackages = try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            packageManager.queryIntentActivities(intent, 0)
                .asSequence()
                .map { it.activityInfo.packageName }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }
        imePackages = try {
            inputMethodManager.enabledInputMethodList
                .asSequence()
                .map { it.packageName }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }
}
