package com.takeabreak.controller

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object Prefs {
    private const val PREFS_NAME = "takeabreak_prefs"
    private const val KEY_PIN_HASH = "pin_hash"
    private const val KEY_LOCKED_PACKAGES = "locked_packages"

    // Screen time
    private const val KEY_USAGE_DATE = "usage_date"
    private const val KEY_USAGE_JSON = "usage_json"
    private const val KEY_LIMITS_JSON = "limits_json"
    private const val KEY_DAY_UNLOCKS = "day_unlocks"
    private const val KEY_EXTENSION_USED = "extension_used"
    private const val KEY_EXTENSIONS_JSON = "extensions_json"

    // Text Message Alerts
    private const val KEY_SMS_PHONE = "sms_phone_number"
    private const val KEY_ALERT_NEW_APP = "alert_new_app_installed"
    private const val KEY_ALERT_TAMPER = "alert_monitoring_tamper"
    private const val KEY_ALERT_DAY_UNLOCK = "alert_pin_day_unlock"
    private const val KEY_ALERT_DAILY_STATUS = "alert_daily_status"
    private const val KEY_DAILY_STATUS_HOUR = "daily_status_hour"
    private const val KEY_DAILY_STATUS_MINUTE = "daily_status_minute"
    private const val KEY_LAST_DAILY_STATUS_DATE = "last_daily_status_date"
    private const val KEY_DEBUG_NOTE = "debug_note"
    private const val KEY_SETUP_COMPLETE = "setup_complete"
    private const val KEY_SETUP_IN_PROGRESS = "setup_in_progress"
    private const val KEY_SETUP_STEP = "setup_step"

    const val DEFAULT_DAILY_STATUS_HOUR = 20
    const val DEFAULT_DAILY_STATUS_MINUTE = 0

    private const val MAX_LIMIT_MINUTES = (23 * 60) + 59

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isPinSet(context: Context): Boolean = prefs(context).contains(KEY_PIN_HASH)

    fun isSetupComplete(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SETUP_COMPLETE, false)

    fun setSetupComplete(context: Context, complete: Boolean) {
        prefs(context).edit {
            putBoolean(KEY_SETUP_COMPLETE, complete)
            if (complete) {
                putBoolean(KEY_SETUP_IN_PROGRESS, false)
                remove(KEY_SETUP_STEP)
            }
        }
    }

    fun isSetupInProgress(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SETUP_IN_PROGRESS, false)

    fun setSetupInProgress(context: Context, inProgress: Boolean) {
        prefs(context).edit { putBoolean(KEY_SETUP_IN_PROGRESS, inProgress) }
    }

    fun getSetupStep(context: Context): Int =
        prefs(context).getInt(KEY_SETUP_STEP, 1)

    fun setSetupStep(context: Context, step: Int) {
        prefs(context).edit { putInt(KEY_SETUP_STEP, step) }
    }

    fun setPin(context: Context, pin: String) {
        prefs(context).edit { putString(KEY_PIN_HASH, hashPin(pin)) }
    }

    fun checkPin(context: Context, pin: String): Boolean {
        val stored = prefs(context).getString(KEY_PIN_HASH, null) ?: return false
        return hashPin(pin) == stored
    }

    private fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(pin.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun getLockedPackages(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_LOCKED_PACKAGES, emptySet()) ?: emptySet()

    fun setLockedPackages(context: Context, packages: Set<String>) {
        prefs(context).edit { putStringSet(KEY_LOCKED_PACKAGES, packages) }
    }

    // ---- Screen time helpers ----

    private fun todayKey(): String = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)

    private fun ensureToday(context: Context) {
        val p = prefs(context)
        val today = todayKey()
        if (p.getString(KEY_USAGE_DATE, "") != today) {
            p.edit {
                putString(KEY_USAGE_DATE, today)
                putString(KEY_USAGE_JSON, "{}")
                putStringSet(KEY_DAY_UNLOCKS, emptySet())
                putStringSet(KEY_EXTENSION_USED, emptySet())
                putString(KEY_EXTENSIONS_JSON, "{}")
            }
        }
    }

    private fun loadJsonLong(context: Context, key: String, packageName: String): Long {
        ensureToday(context)
        val json = prefs(context).getString(key, "{}") ?: "{}"
        return try {
            JSONObject(json).optLong(packageName, 0L)
        } catch (_: Exception) {
            0L
        }
    }

    private fun loadJsonMap(context: Context, key: String): MutableMap<String, Long> {
        ensureToday(context)
        val json = prefs(context).getString(key, "{}") ?: "{}"
        val map = mutableMapOf<String, Long>()
        try {
            val obj = JSONObject(json)
            obj.keys().forEach { k ->
                map[k] = obj.getLong(k)
            }
        } catch (_: Exception) {
        }
        return map
    }

    private fun saveJsonMap(context: Context, key: String, map: Map<String, Long>) {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        prefs(context).edit { putString(key, obj.toString()) }
    }

    fun getLimitMinutes(context: Context, packageName: String): Int {
        val json = prefs(context).getString(KEY_LIMITS_JSON, "{}") ?: "{}"
        return try {
            JSONObject(json).optInt(packageName, 0)
        } catch (_: Exception) {
            0
        }
    }

    fun setLimitMinutes(context: Context, packageName: String, minutes: Int) {
        val json = prefs(context).getString(KEY_LIMITS_JSON, "{}") ?: "{}"
        val obj = try {
            JSONObject(json)
        } catch (_: Exception) {
            JSONObject()
        }
        if (minutes <= 0) {
            obj.remove(packageName)
        } else {
            obj.put(packageName, minutes.coerceIn(1, MAX_LIMIT_MINUTES))
        }
        prefs(context).edit { putString(KEY_LIMITS_JSON, obj.toString()) }
    }

    fun getAllLimits(context: Context): Map<String, Int> {
        val json = prefs(context).getString(KEY_LIMITS_JSON, "{}") ?: "{}"
        val map = mutableMapOf<String, Int>()
        try {
            val obj = JSONObject(json)
            obj.keys().forEach { k -> map[k] = obj.getInt(k) }
        } catch (_: Exception) {
        }
        return map
    }

    fun getUsageMs(context: Context, packageName: String): Long =
        loadJsonLong(context, KEY_USAGE_JSON, packageName)

    fun addUsageMs(context: Context, packageName: String, ms: Long) {
        if (ms <= 0L) return
        val map = loadJsonMap(context, KEY_USAGE_JSON)
        map[packageName] = (map[packageName] ?: 0L) + ms
        saveJsonMap(context, KEY_USAGE_JSON, map)
    }

    /** Batches multiple package usage increments into one JSON parse and SharedPreferences write. */
    fun addUsageBatch(context: Context, deltas: Map<String, Long>): Boolean {
        if (deltas.isEmpty()) return false
        val map = loadJsonMap(context, KEY_USAGE_JSON)
        var changed = false
        for ((pkg, ms) in deltas) {
            if (ms > 0L) {
                map[pkg] = (map[pkg] ?: 0L) + ms
                changed = true
            }
        }
        if (changed) saveJsonMap(context, KEY_USAGE_JSON, map)
        return changed
    }

    fun getAllUsageMs(context: Context): Map<String, Long> =
        loadJsonMap(context, KEY_USAGE_JSON)

    fun isDayUnlocked(context: Context, packageName: String): Boolean {
        ensureToday(context)
        return prefs(context).getStringSet(KEY_DAY_UNLOCKS, emptySet())?.contains(packageName) == true
    }

    fun setDayUnlocked(context: Context, packageName: String) {
        ensureToday(context)
        val set = prefs(context).getStringSet(KEY_DAY_UNLOCKS, emptySet())?.toMutableSet() ?: mutableSetOf()
        set.add(packageName)
        prefs(context).edit { putStringSet(KEY_DAY_UNLOCKS, set) }
    }

    fun isExtensionUsed(context: Context, packageName: String): Boolean {
        ensureToday(context)
        return prefs(context).getStringSet(KEY_EXTENSION_USED, emptySet())?.contains(packageName) == true
    }

    fun markExtensionUsed(context: Context, packageName: String) {
        ensureToday(context)
        val set = prefs(context).getStringSet(KEY_EXTENSION_USED, emptySet())?.toMutableSet() ?: mutableSetOf()
        set.add(packageName)
        prefs(context).edit { putStringSet(KEY_EXTENSION_USED, set) }
    }

    fun getExtensionUntil(context: Context, packageName: String): Long =
        loadJsonLong(context, KEY_EXTENSIONS_JSON, packageName)

    fun setExtensionUntil(context: Context, packageName: String, until: Long) {
        val map = loadJsonMap(context, KEY_EXTENSIONS_JSON)
        map[packageName] = until
        saveJsonMap(context, KEY_EXTENSIONS_JSON, map)
    }

    fun isWithinExtension(context: Context, packageName: String): Boolean {
        return System.currentTimeMillis() < getExtensionUntil(context, packageName)
    }

    /**
     * @param pendingMs usage the service has counted but not saved yet, so the limit
     * is enforced to the second instead of only when usage is flushed.
     */
    fun isOverLimit(context: Context, packageName: String, pendingMs: Long = 0L): Boolean {
        val limitMin = getLimitMinutes(context, packageName)
        if (limitMin <= 0) return false
        if (isDayUnlocked(context, packageName)) return false
        if (isWithinExtension(context, packageName)) return false
        val usageMin = (getUsageMs(context, packageName) + pendingMs) / 60_000L
        return usageMin >= limitMin
    }

    fun clearTodayUsage(context: Context) {
        ensureToday(context)
        prefs(context).edit { putString(KEY_USAGE_JSON, "{}") }
    }

    fun clearAllLimits(context: Context) {
        prefs(context).edit { putString(KEY_LIMITS_JSON, "{}") }
    }

    fun clearLockedPackages(context: Context) {
        prefs(context).edit { putStringSet(KEY_LOCKED_PACKAGES, emptySet()) }
    }

    /** Day-unlocks and one-time extensions, so a limit can be tested again before midnight. */
    fun clearTodayOverrides(context: Context) {
        ensureToday(context)
        prefs(context).edit {
            putStringSet(KEY_DAY_UNLOCKS, emptySet())
            putStringSet(KEY_EXTENSION_USED, emptySet())
            putString(KEY_EXTENSIONS_JSON, "{}")
        }
    }

    fun addUsageToLimitedApps(context: Context, extraMs: Long) {
        if (extraMs <= 0L) return
        val limits = getAllLimits(context)
        if (limits.isEmpty()) return
        addUsageBatch(context, limits.keys.associateWith { extraMs })
    }

    fun setDebugNote(context: Context, note: String) {
        prefs(context).edit { putString(KEY_DEBUG_NOTE, note) }
    }

    fun getDebugNote(context: Context): String =
        prefs(context).getString(KEY_DEBUG_NOTE, "") ?: ""

    // ---- Text Message Alerts settings ----

    /** Strips formatting characters (spaces, dashes, parentheses, dots). Returns "" if blank. */
    fun normalizePhoneNumber(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val hasPlus = trimmed.startsWith("+")
        val digits = trimmed.filter { it.isDigit() }
        if (digits.isEmpty()) return ""
        return if (hasPlus) "+$digits" else digits
    }

    fun isValidPhoneNumber(normalized: String): Boolean {
        if (normalized.isEmpty()) return false
        val digits = normalized.removePrefix("+")
        return (digits.length in 3..18) && digits.all { it.isDigit() }
    }

    fun getSmsPhoneNumber(context: Context): String =
        prefs(context).getString(KEY_SMS_PHONE, "") ?: ""

    fun setSmsPhoneNumber(context: Context, number: String) {
        prefs(context).edit { putString(KEY_SMS_PHONE, normalizePhoneNumber(number)) }
    }

    fun isAlertNewAppEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ALERT_NEW_APP, true)

    fun setAlertNewAppEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ALERT_NEW_APP, enabled) }
    }

    fun isAlertTamperEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ALERT_TAMPER, true)

    fun setAlertTamperEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ALERT_TAMPER, enabled) }
    }

    fun isAlertDayUnlockEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ALERT_DAY_UNLOCK, true)

    fun setAlertDayUnlockEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ALERT_DAY_UNLOCK, enabled) }
    }

    fun isDailyStatusEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ALERT_DAILY_STATUS, true)

    fun setDailyStatusEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ALERT_DAILY_STATUS, enabled) }
    }

    fun getDailyStatusHour(context: Context): Int =
        prefs(context).getInt(KEY_DAILY_STATUS_HOUR, DEFAULT_DAILY_STATUS_HOUR).coerceIn(0, 23)

    fun getDailyStatusMinute(context: Context): Int =
        prefs(context).getInt(KEY_DAILY_STATUS_MINUTE, DEFAULT_DAILY_STATUS_MINUTE).coerceIn(0, 59)

    fun setDailyStatusTime(context: Context, hour: Int, minute: Int) {
        prefs(context).edit {
            putInt(KEY_DAILY_STATUS_HOUR, hour.coerceIn(0, 23))
            putInt(KEY_DAILY_STATUS_MINUTE, minute.coerceIn(0, 59))
        }
    }

    fun getLastDailyStatusDate(context: Context): String =
        prefs(context).getString(KEY_LAST_DAILY_STATUS_DATE, "") ?: ""

    fun setLastDailyStatusDate(context: Context, dateKey: String) {
        prefs(context).edit { putString(KEY_LAST_DAILY_STATUS_DATE, dateKey) }
    }

    fun currentDateKey(): String = todayKey()
}
