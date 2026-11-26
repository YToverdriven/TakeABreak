package com.takeabreak.watcher

import android.app.BroadcastOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Watches Take a Break, and checks that this app can still reach it.
 *
 * Uninstall and storage-cleared arrive as system broadcasts. Disable and force-stop are
 * looked up at boot and about once an hour. The hourly job does not stay running.
 * The ping is logged here. It is not an SMS, and it is not added to the message box.
 */
object PeerLink {
    const val PARTNER = "com.takeabreak.controller"
    const val PARTNER_LABEL = "Take a Break app"
    private const val PARTNER_RECEIVER = "com.takeabreak.controller.PeerMessageReceiver"
    private const val SELF = "com.takeabreak.watcher"

    const val ACTION_PING = "com.takeabreak.peer.PING"
    const val ACTION_PONG = "com.takeabreak.peer.PONG"
    const val ACTION_LOG_UPDATED = "com.takeabreak.peer.LOG_UPDATED"
    const val EXTRA_FROM = "from"
    const val EXTRA_REASON = "reason"
    const val EXTRA_SETUP_COMPLETE = "setup_complete"
    const val ALERT_ACCESSIBILITY_OFF = "ALERT: Take a Break accessibility service was turned off!"
    private const val PERMISSION = "com.takeabreak.permission.POST_MESSAGE"

    private const val TAG = "Watcher"
    private const val PREFS = "peer_link"
    private const val KEY_STATE = "state"
    private const val KEY_ACCESSIBILITY = "accessibility_state"
    private const val KEY_PING_AT = "ping_at"
    private const val KEY_PONG_AT = "pong_at"
    private const val KEY_LOG = "log"
    private const val WORK = "peer_check"
    private const val MAX_LOG = 12

    private enum class State { MISSING, DISABLED, STOPPED, OK }

    @Volatile
    private var checkedThisProcess = false

    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<PeerCheckWorker>(1, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Boot, or the first time this process opens the screen. Hourly always runs. */
    @Synchronized
    fun check(context: Context, reason: String) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val state = inspect(app)
        val previous = prefs.getString(KEY_STATE, null)
        if (reason != "hourly") {
            if (checkedThisProcess && (previous == state.name)) return
            checkedThisProcess = true
        }
        SmsAlerts.processQueue(app)
        val accEnabled = if ((state == State.OK) || (state == State.STOPPED)) {
            isControllerAccessibilityEnabled(app)
        } else {
            null
        }
        val accNote = when (accEnabled) {
            true -> "; accessibility is enabled"
            false -> "; accessibility is off"
            null -> ""
        }
        log(app, "peer check ($reason): $PARTNER_LABEL is ${describe(state)}$accNote")
        val transition = messageFor(previous, state)
        if (transition != null) {
            raise(app, state, transition)
        } else {
            prefs.edit { putString(KEY_STATE, state.name) }
        }

        if ((state == State.MISSING) || (state == State.DISABLED)) {
            // Uninstall/disable already triggers its own alert; avoid a duplicate accessibility alert.
            prefs.edit { putString(KEY_ACCESSIBILITY, "DISABLED") }
        } else {
            accEnabled?.let { checkControllerAccessibility(app, it) }
        }

        val pingAt = prefs.getLong(KEY_PING_AT, 0L)
        val pongAt = prefs.getLong(KEY_PONG_AT, 0L)
        if (pingAt > 0L) {
            if (pongAt >= pingAt) log(app, "peer check ($reason): last ping was answered")
            else log(app, "peer check ($reason): no reply to the last ping")
        }

        if ((state == State.MISSING) || (state == State.DISABLED)) {
            log(app, "peer check ($reason): ping not sent")
            return
        }
        send(app, ACTION_PING, reason)
    }

    /**
     * Called when the controller already reported accessibility-off to this watcher, so the
     * watcher's next periodic check does not send a duplicate SMS for the same turn-off event.
     */
    @Synchronized
    fun noteControllerReportedAccessibilityOff(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_ACCESSIBILITY, "DISABLED")
        }
    }

    private fun checkControllerAccessibility(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getString(KEY_ACCESSIBILITY, null)
        val current = if (enabled) "ENABLED" else "DISABLED"
        if (previous == current) return
        prefs.edit { putString(KEY_ACCESSIBILITY, current) }
        if (!enabled && (previous == "ENABLED")) {
            Log.w(TAG, "Controller accessibility service was turned off")
            deliver(context, ALERT_ACCESSIBILITY_OFF)
        }
    }

    private fun isControllerAccessibilityEnabled(context: Context): Boolean? = try {
        val cr = context.contentResolver
        if (Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1) == 0) {
            false
        } else {
            val enabled = Settings.Secure.getString(
                cr,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
            enabled?.split(":")?.any {
                it.equals("$PARTNER/$PARTNER.AppLockAccessibilityService", ignoreCase = true) ||
                    it.equals("$PARTNER/.AppLockAccessibilityService", ignoreCase = true)
            } ?: false
        }
    } catch (_: Exception) {
        null
    }

    @Synchronized
    fun notePartnerAlive(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_STATE, null) != State.OK.name) {
            prefs.edit { putString(KEY_STATE, State.OK.name) }
        }
    }

    fun isWatcherSetupReady(context: Context): Boolean {
        val app = context.applicationContext
        return PermissionsSetupActivity.isSetupComplete(app) &&
            PermissionsSetupActivity.hasSmsPermission(app)
    }

    /** Proactively sends a setup-complete pong to Take a Break once Watcher setup finishes. */
    @Synchronized
    fun notifySetupComplete(context: Context) {
        val app = context.applicationContext
        val state = inspect(app)
        if ((state == State.MISSING) || (state == State.DISABLED)) return
        send(app, ACTION_PONG, "setup_complete")
    }

    @Synchronized
    fun onPing(context: Context, from: String, reason: String) {
        val app = context.applicationContext
        if (from != PARTNER) {
            log(app, "peer check: ignored ping from $from")
            return
        }
        notePartnerAlive(app)
        log(app, "peer check: ping received from $PARTNER_LABEL reason=$reason")
        send(app, ACTION_PONG, reason)
    }

    @Synchronized
    fun onPong(context: Context, from: String, reason: String) {
        val app = context.applicationContext
        if (from != PARTNER) {
            log(app, "peer check: ignored pong from $from")
            return
        }
        notePartnerAlive(app)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putLong(KEY_PONG_AT, System.currentTimeMillis())
        }
        log(app, "peer check: pong received from $PARTNER_LABEL reason=$reason")
    }

    @Synchronized
    fun onPackageBroadcast(context: Context, intent: Intent) {
        val pkg = intent.data?.schemeSpecificPart ?: return
        if (pkg == context.packageName) return
        val app = context.applicationContext
        val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED -> {
                if (pkg != PARTNER) return
                val state = inspect(app)
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                    putString(KEY_STATE, state.name)
                }
                log(app, "peer check (package added): $PARTNER_LABEL is ${describe(state)}")
            }
            Intent.ACTION_PACKAGE_REMOVED,
            Intent.ACTION_PACKAGE_FULLY_REMOVED,
            -> {
                if (replacing) return
                if (!once(app, "gone:$pkg")) return
                if (pkg == PARTNER) {
                    Log.w(TAG, "Main app was removed")
                    app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                        putString(KEY_STATE, State.MISSING.name)
                        putString(KEY_ACCESSIBILITY, "DISABLED")
                    }
                    deliver(app, "ALERT: $PARTNER_LABEL was uninstalled!")
                }
            }
            Intent.ACTION_PACKAGE_DATA_CLEARED -> {
                if (pkg != PARTNER) return
                if (!once(app, "cleared:$pkg")) return
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                    putString(KEY_STATE, State.STOPPED.name)
                }
                Log.w(TAG, "Main app storage was cleared")
                deliver(app, "ALERT: $PARTNER_LABEL storage was cleared!")
            }
            Intent.ACTION_PACKAGE_CHANGED -> {
                if (pkg != PARTNER) return
                val state = inspect(app)
                val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val previous = prefs.getString(KEY_STATE, null)
                log(app, "peer check (package change): $PARTNER_LABEL is ${describe(state)}")
                val transition = messageFor(previous, state)
                if (transition != null) {
                    raise(app, state, transition)
                } else {
                    prefs.edit { putString(KEY_STATE, state.name) }
                }
            }
        }
    }

    fun note(context: Context, line: String) {
        log(context.applicationContext, line)
    }

    fun logText(context: Context): String {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LOG, null)
        if (raw.isNullOrBlank()) return "(no peer check yet)"
        return raw
    }

    private fun raise(context: Context, state: State, message: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getString(KEY_STATE, null)
        prefs.edit { putString(KEY_STATE, state.name) }
        if (previous == state.name) {
            log(context, "$PARTNER_LABEL is already recorded as ${describe(state)}")
            return
        }
        deliver(context, message)
    }

    private fun deliver(context: Context, message: String) {
        log(context, message)
        if (!SmsAlerts.isTamperAlertEnabled(context)) {
            log(context, "SMS skipped (monitoring tamper alerts toggled off)")
            return
        }
        val error = SmsAlerts.enqueue(context, message)
        if (error != null) log(context, "SMS not queued: $error")
    }

    private fun messageFor(previous: String?, state: State): String? {
        if (previous == state.name) return null
        return when (state) {
            State.DISABLED -> "ALERT: $PARTNER_LABEL was disabled!"
            State.MISSING -> if (previous != null) "ALERT: $PARTNER_LABEL was uninstalled!" else null
            State.STOPPED -> if (previous == State.OK.name) {
                "ALERT: $PARTNER_LABEL was stopped or its storage was cleared!"
            } else {
                null
            }
            State.OK -> null
        }
    }

    private fun inspect(context: Context): State {
        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(PARTNER, 0)
            val disabled = when (pm.getApplicationEnabledSetting(PARTNER)) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
                -> true
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> false
                else -> !info.enabled
            }
            if (disabled) State.DISABLED
            else if ((info.flags and ApplicationInfo.FLAG_STOPPED) != 0) State.STOPPED
            else State.OK
        } catch (_: PackageManager.NameNotFoundException) {
            State.MISSING
        } catch (_: IllegalArgumentException) {
            State.MISSING
        }
    }

    private fun describe(state: State): String = when (state) {
        State.MISSING -> "not installed"
        State.DISABLED -> "disabled"
        State.STOPPED -> "stopped (never opened, force-stopped, or storage cleared)"
        State.OK -> "installed and enabled"
    }

    private fun send(context: Context, action: String, reason: String) {
        val intent = Intent(action)
            .setComponent(ComponentName(PARTNER, PARTNER_RECEIVER))
            .putExtra(EXTRA_FROM, SELF)
            .putExtra(EXTRA_REASON, reason)
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        if (action == ACTION_PONG) {
            intent.putExtra(EXTRA_SETUP_COMPLETE, isWatcherSetupReady(context))
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val options = BroadcastOptions.makeBasic().apply {
                    isShareIdentityEnabled = true
                }.toBundle()
                context.sendBroadcast(intent, PERMISSION, options)
            } else {
                context.sendBroadcast(intent, PERMISSION)
            }
            if (action == ACTION_PING) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                    putLong(KEY_PING_AT, System.currentTimeMillis())
                }
                log(context, "peer check ($reason): ping sent to $PARTNER_LABEL")
            } else {
                log(context, "peer check ($reason): pong sent to $PARTNER_LABEL")
            }
        } catch (e: Exception) {
            Log.e(TAG, "peer send failed", e)
            log(context, "peer check ($reason): send failed: ${e.message}")
        }
    }

    private fun once(context: Context, key: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val prefKey = "once_$key"
        val last = prefs.getLong(prefKey, 0L)
        if ((now - last) < 20_000L) return false
        prefs.edit {
            for ((k, v) in prefs.all) {
                if (k.startsWith("once_") && (v is Long) && ((now - v) >= 60_000L)) {
                    remove(k)
                }
            }
            putLong(prefKey, now)
        }
        return true
    }

    private fun log(context: Context, line: String) {
        Log.i(TAG, line)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getString(KEY_LOG, "").orEmpty()
        val stamped = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
            .format(Date()) + "  " + line
        val lines = (listOf(stamped) + previous.lines().filter { it.isNotBlank() }).take(MAX_LOG)
        prefs.edit { putString(KEY_LOG, lines.joinToString("\n")) }
        val app = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            app.sendBroadcast(Intent(ACTION_LOG_UPDATED).setPackage(app.packageName))
        }
    }
}
