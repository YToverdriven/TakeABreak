package com.takeabreak.controller

import android.app.BroadcastOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.edit
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Watches Take a Break Watcher, records uninstalls of other apps, and checks the link.
 *
 * Uninstall and storage-cleared arrive as system broadcasts, so nothing has to poll for them.
 * Disable and force-stop are not announced to an app that is not running. Those are looked up
 * at boot and about once an hour. The hourly job is one short piece of work Android can batch.
 * A loop, or a service that stays up only to keep checking, would drain the battery. This does not.
 *
 * The ping is a signed broadcast between the two apps. It is logged. It is not an SMS, and it
 * is not written into the watcher's message box.
 */
object PeerLink {
    const val PARTNER = "com.takeabreak.watcher"
    const val PARTNER_LABEL = "Take a Break Watcher"
    private const val PARTNER_RECEIVER = "com.takeabreak.watcher.PeerMessageReceiver"
    private const val SELF = "com.takeabreak.controller"

    const val ACTION_PING = "com.takeabreak.peer.PING"
    const val ACTION_PONG = "com.takeabreak.peer.PONG"
    const val ACTION_LOG_UPDATED = "com.takeabreak.peer.LOG_UPDATED"
    const val EXTRA_FROM = "from"
    const val EXTRA_REASON = "reason"
    const val ALERT_ACCESSIBILITY_OFF = "ALERT: Take a Break accessibility service was turned off!"
    private const val PERMISSION = "com.takeabreak.permission.POST_MESSAGE"

    private const val TAG = "TakeABreak"
    private const val PREFS = "peer_link"
    private const val KEY_STATE = "state"
    private const val KEY_ACCESSIBILITY = "accessibility_state"
    private const val KEY_PING_AT = "ping_at"
    private const val KEY_PONG_AT = "pong_at"
    private const val KEY_LOG = "log"
    private const val WORK = "peer_check"
    private const val MAX_LOG = 12

    private enum class State { MISSING, DISABLED, STOPPED, OK }
    private enum class AlertType { NEW_APP, OTHER_UNINSTALL, TAMPER, DAY_UNLOCK, DAILY_STATUS }

    @Volatile
    private var checkedThisProcess = false

    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<PeerCheckWorker>(1, TimeUnit.HOURS).build()
        // KEEP leaves an existing hourly schedule alone. UPDATE would restart the hour
        // every time the process starts, and the check would keep getting pushed back.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Boot, the accessibility service, or the first time this process opens a screen. */
    @Synchronized
    fun check(context: Context, reason: String) {
        if (reason != "hourly") {
            if (checkedThisProcess) return
            checkedThisProcess = true
        }
        val app = context.applicationContext
        checkAccessibility(app, reason)
        SmsAlerts.processQueue(app)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val state = inspect(app)
        val previous = prefs.getString(KEY_STATE, null)
        log(app, "peer check ($reason): $PARTNER_LABEL is ${describe(state)}")
        val transition = messageFor(previous, state)
        if (transition != null) {
            raise(app, state, transition)
        } else {
            prefs.edit { putString(KEY_STATE, state.name) }
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
        WatcherMessages.syncConfig(app)
        send(app, ACTION_PING, reason)
    }

    /** Records that the accessibility service is now connected and active. */
    @Synchronized
    fun onAccessibilityConnected(context: Context) {
        recordAccessibility(context.applicationContext, enabled = true, reason = "service connected")
    }

    /**
     * Checks whether Take a Break's accessibility service is enabled.
     * Fires an alert only when it transitions from enabled to off (never before it was
     * first enabled, and never during an update/restart while still enabled in Settings).
     */
    @Synchronized
    fun checkAccessibility(context: Context, reason: String) {
        val app = context.applicationContext
        recordAccessibility(app, isAccessibilityEnabled(app), reason)
    }

    private fun recordAccessibility(context: Context, enabled: Boolean, reason: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getString(KEY_ACCESSIBILITY, null)
        val current = if (enabled) "ENABLED" else "DISABLED"
        if (previous == current) return
        prefs.edit { putString(KEY_ACCESSIBILITY, current) }
        if (enabled) {
            log(context, "accessibility ($reason): enabled")
        } else if (previous == "ENABLED") {
            Log.w(TAG, "Accessibility service was turned off ($reason)")
            deliver(context, AlertType.TAMPER, ALERT_ACCESSIBILITY_OFF)
        } else {
            log(context, "accessibility ($reason): not enabled yet")
        }
    }

    /** Called when PIN is entered on the time-up screen to unlock an app for the rest of today. */
    @Synchronized
    fun reportDayUnlock(context: Context, appLabel: String) {
        val app = context.applicationContext
        deliver(
            app,
            AlertType.DAY_UNLOCK,
            "ALERT: PIN entered to unlock $appLabel for the rest of the day."
        )
    }

    /** Called by [DailyStatusWorker] for the scheduled once-a-day status SMS. */
    @Synchronized
    fun reportDailyStatus(context: Context, message: String) {
        deliver(context.applicationContext, AlertType.DAILY_STATUS, message)
    }

    fun partnerStatusShort(context: Context): String = when (inspect(context.applicationContext)) {
        State.OK -> "OK"
        State.STOPPED -> "stopped"
        State.DISABLED -> "disabled"
        State.MISSING -> "not installed"
    }

    @Synchronized
    fun onPing(context: Context, from: String, reason: String) {
        val app = context.applicationContext
        if (from != PARTNER) {
            log(app, "peer check: ignored ping from $from")
            return
        }
        log(app, "peer check: ping received from $PARTNER_LABEL reason=$reason")
        WatcherMessages.syncConfig(app)
        send(app, ACTION_PONG, reason)
    }

    @Synchronized
    fun onPong(context: Context, from: String, reason: String) {
        val app = context.applicationContext
        if (from != PARTNER) {
            log(app, "peer check: ignored pong from $from")
            return
        }
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
                if (replacing) {
                    Log.i(TAG, "App updated: $pkg")
                    return
                }
                if (!once(app, "added:$pkg")) return
                Log.i(TAG, "New app installed: $pkg")
                deliver(app, AlertType.NEW_APP, "ALERT: New app installed: $pkg")
            }
            Intent.ACTION_PACKAGE_REMOVED,
            Intent.ACTION_PACKAGE_FULLY_REMOVED,
            -> {
                if (replacing) {
                    Log.i(TAG, "Package removed for an update, ignored: $pkg")
                    return
                }
                if (!once(app, "gone:$pkg")) return
                if (pkg == PARTNER) {
                    Log.w(TAG, "Watcher was removed")
                    raise(app, State.MISSING, "ALERT: $PARTNER_LABEL was uninstalled!")
                } else {
                    Log.i(TAG, "App uninstalled: $pkg")
                    deliver(app, AlertType.OTHER_UNINSTALL, "ALERT: App uninstalled: $pkg")
                }
            }
            Intent.ACTION_PACKAGE_DATA_CLEARED -> {
                if (pkg != PARTNER) return
                if (!once(app, "cleared:$pkg")) return
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                    putString(KEY_STATE, State.STOPPED.name)
                }
                Log.w(TAG, "Watcher storage was cleared")
                deliver(app, AlertType.TAMPER, "ALERT: $PARTNER_LABEL storage was cleared!")
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
        deliver(context, AlertType.TAMPER, message)
    }

    private fun deliver(context: Context, type: AlertType, message: String) {
        log(context, message)
        WatcherMessages.send(context, message)
        val smsAllowed = when (type) {
            AlertType.NEW_APP -> Prefs.isAlertNewAppEnabled(context)
            AlertType.TAMPER -> Prefs.isAlertTamperEnabled(context)
            AlertType.DAY_UNLOCK -> Prefs.isAlertDayUnlockEnabled(context)
            AlertType.DAILY_STATUS -> Prefs.isDailyStatusEnabled(context)
            AlertType.OTHER_UNINSTALL -> false
        }
        if (!smsAllowed) return
        SmsAlerts.enqueue(context, message)?.let { error ->
            log(context, "SMS not queued: $error")
        }
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

    /** Collapses the pair of uninstall broadcasts, and a manifest receiver plus a live one. */
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
