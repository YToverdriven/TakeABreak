package com.takeabreak.controller

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Durable SMS outbox with airplane-mode / low-service retry queue and strict anti-spam guards.
 *
 * Anti-spam & battery guarantees:
 *  - No phone number configured -> never queues or sends.
 *  - Deduplication: identical alert text is suppressed for 5 minutes (unless manually tested).
 *  - Hard rate limits: at most 5 SMS per 10 minutes and 20 SMS per 24 hours.
 *  - Hard queue cap: at most 10 queued alerts; items expire after 48 hours or 8 failed attempts.
 *  - Delivery confirmation: items stay IN_FLIGHT until SmsManager reports RESULT_OK or an explicit
 *    error via [SmsSentReceiver], preventing overlapping sends.
 *  - Zero polling loops: retries are scheduled via WorkManager with exponential backoff
 *    (2m -> 5m -> 15m -> 30m -> 60m) ONLY while the outbox is non-empty.
 */
object SmsAlerts {
    const val ACTION_SMS_SENT = "com.takeabreak.controller.SMS_SENT"
    const val ACTION_QUEUE_UPDATED = "com.takeabreak.controller.SMS_QUEUE_UPDATED"
    const val EXTRA_ID = "sms_id"
    const val EXTRA_PART_INDEX = "sms_part_index"
    const val EXTRA_PART_COUNT = "sms_part_count"

    private const val TAG = "TakeABreak"
    private const val PREFS = "sms_outbox"
    private const val KEY_QUEUE = "queue"
    private const val KEY_NEXT_ID = "next_id"
    private const val KEY_RECENT_SENDS = "recent_sends"
    private const val KEY_RECENT_TEXTS = "recent_texts"
    private const val RETRY_WORK = "sms_retry_queue"

    private const val MAX_QUEUE_SIZE = 10
    private const val MAX_ATTEMPTS = 8
    private const val MAX_AGE_MS = 48 * 60 * 60_000L
    private const val IN_FLIGHT_TIMEOUT_MS = 5 * 60_000L
    private const val DEDUPE_MS = 5 * 60_000L
    private const val DELAYED_STAMP_THRESHOLD_MS = 2 * 60_000L

    private const val RATE_SHORT_WINDOW_MS = 10 * 60_000L
    private const val RATE_SHORT_MAX = 5
    private const val RATE_DAY_WINDOW_MS = 24 * 60 * 60_000L
    private const val RATE_DAY_MAX = 20

    private val BACKOFF_MS = longArrayOf(
        2 * 60_000L,
        5 * 60_000L,
        15 * 60_000L,
        30 * 60_000L,
        60 * 60_000L,
    )

    private data class QueuedSms(
        val id: Long,
        val text: String,
        val createdAt: Long,
        var attempts: Int,
        var nextRetryAt: Long,
        var inFlightSince: Long,
        var partsTotal: Int,
        var partsDone: Int,
        var anyPartFailed: Boolean,
        var lastError: String
    )

    /**
     * Queues [message] for delivery and immediately attempts to send if ready.
     * Returns null if sent/queued cleanly, or a short reason if rejected (no number, no permission,
     * duplicate, or rate-limited).
     */
    @Synchronized
    fun enqueue(context: Context, message: String, bypassDedupe: Boolean = false): String? {
        val app = context.applicationContext
        val text = message.trim()
        if (text.isEmpty()) return "empty message"

        val destination = Prefs.getSmsPhoneNumber(app)
        if (!Prefs.isValidPhoneNumber(destination)) {
            Log.i(TAG, "SMS skipped (no phone number configured): $text")
            return "no phone number configured"
        }

        if (ContextCompat.checkSelfPermission(app, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "SMS not queued (permission missing): $text")
            return "SMS permission is not granted"
        }

        val now = System.currentTimeMillis()
        val queue = loadQueue(app)

        if (!bypassDedupe && isDuplicate(app, queue, text, now)) {
            Log.i(TAG, "SMS suppressed (duplicate within 5 min): $text")
            return "duplicate alert suppressed"
        }

        val rateReason = checkRateLimit(app, now)
        if (rateReason != null) {
            Log.w(TAG, "SMS suppressed ($rateReason): $text")
            PeerLink.note(app, "SMS suppressed ($rateReason)")
            return rateReason
        }

        val id = nextId(app)
        queue.add(
            QueuedSms(
                id = id,
                text = text,
                createdAt = now,
                attempts = 0,
                nextRetryAt = now,
                inFlightSince = 0L,
                partsTotal = 1,
                partsDone = 0,
                anyPartFailed = false,
                lastError = ""
            )
        )
        while (queue.size > MAX_QUEUE_SIZE) {
            val droppedId = queue.removeAt(0).id
            Log.w(TAG, "SMS outbox full; dropped oldest id=$droppedId")
        }
        recordTextSeen(app, text, now)
        saveQueue(app, queue)
        notifyQueueChanged(app)

        processQueue(app)
        return null
    }

    /** Attempts to send the next due queued SMS, or schedules a WorkManager retry if offline. */
    @Synchronized
    fun processQueue(context: Context) {
        val app = context.applicationContext
        val queue = loadQueue(app)
        if (queue.isEmpty()) {
            cancelRetryWork(app)
            return
        }

        val destination = Prefs.getSmsPhoneNumber(app)
        if (!Prefs.isValidPhoneNumber(destination)) {
            Log.i(TAG, "Clearing ${queue.size} queued SMS because phone number was cleared")
            queue.clear()
            saveQueue(app, queue)
            cancelRetryWork(app)
            notifyQueueChanged(app)
            return
        }

        if (ContextCompat.checkSelfPermission(app, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "SMS retry paused: permission not granted (${queue.size} queued)")
            scheduleRetry(app, 60 * 60_000L)
            return
        }

        val now = System.currentTimeMillis()
        var changed = false

        // Drop expired or max-attempted items; handle stale in-flight timeouts conservatively.
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (((now - item.createdAt) > MAX_AGE_MS) || (item.attempts >= MAX_ATTEMPTS)) {
                Log.w(TAG, "Dropping queued SMS id=${item.id} (attempts=${item.attempts}, age=${(now - item.createdAt) / 60_000L}m)")
                PeerLink.note(app, "SMS dropped after ${item.attempts} attempts: ${item.text}")
                iterator.remove()
                changed = true
                continue
            }
            if (item.inFlightSince > 0L && now - item.inFlightSince > IN_FLIGHT_TIMEOUT_MS) {
                // Sent callback never arrived. Drop conservatively instead of risking a duplicate
                // unless Airplane Mode is currently on.
                if (isAirplaneModeOn(app)) {
                    item.inFlightSince = 0L
                    item.attempts += 1
                    item.nextRetryAt = now + backoffFor(item.attempts)
                    item.lastError = "timed out in airplane mode"
                    changed = true
                } else {
                    Log.w(TAG, "SMS id=${item.id} had no delivery callback; assuming handled to avoid spam")
                    iterator.remove()
                    changed = true
                }
            }
        }

        if (queue.isEmpty()) {
            if (changed) {
                saveQueue(app, queue)
                notifyQueueChanged(app)
            }
            cancelRetryWork(app)
            return
        }

        // Only send one message at a time so sent-callbacks and rate limits stay deterministic.
        if (queue.any { it.inFlightSince > 0L }) {
            if (changed) {
                saveQueue(app, queue)
                notifyQueueChanged(app)
            }
            scheduleRetry(app, IN_FLIGHT_TIMEOUT_MS)
            return
        }

        if (isAirplaneModeOn(app)) {
            val next = queue.minOf { it.nextRetryAt }
            val delay = (next - now).coerceAtLeast(BACKOFF_MS[0])
            Log.i(TAG, "Airplane mode is on; ${queue.size} SMS waiting (next retry in ${delay / 60_000L}m)")
            if (changed) {
                saveQueue(app, queue)
                notifyQueueChanged(app)
            }
            scheduleRetry(app, delay)
            return
        }

        val rateReason = checkRateLimit(app, now)
        if (rateReason != null) {
            Log.w(TAG, "SMS send deferred ($rateReason); ${queue.size} queued")
            if (changed) {
                saveQueue(app, queue)
                notifyQueueChanged(app)
            }
            scheduleRetry(app, RATE_SHORT_WINDOW_MS)
            return
        }

        val due = queue.firstOrNull { it.nextRetryAt <= now }
        if (due == null) {
            val delay = (queue.minOf { it.nextRetryAt } - now).coerceAtLeast(15_000L)
            if (changed) {
                saveQueue(app, queue)
                notifyQueueChanged(app)
            }
            scheduleRetry(app, delay)
            return
        }

        // Hand off to SmsManager with explicit delivery confirmation intent.
        val wireText = formatForSend(due, now)
        val sms = smsManager(app)
        if (sms == null) {
            due.attempts += 1
            due.nextRetryAt = now + backoffFor(due.attempts)
            due.lastError = "SmsManager unavailable"
            saveQueue(app, queue)
            notifyQueueChanged(app)
            scheduleRetry(app, backoffFor(due.attempts))
            return
        }

        try {
            val parts = sms.divideMessage(wireText)
            due.inFlightSince = now
            due.partsTotal = parts.size.coerceAtLeast(1)
            due.partsDone = 0
            due.anyPartFailed = false
            saveQueue(app, queue)
            notifyQueueChanged(app)

            if (parts.size <= 1) {
                val sentPi = buildSentIntent(app, due.id, 0, 1)
                sms.sendTextMessage(destination, null, wireText, sentPi, null)
            } else {
                val sentIntents = ArrayList<PendingIntent>(parts.size)
                for (i in parts.indices) {
                    sentIntents.add(buildSentIntent(app, due.id, i, parts.size))
                }
                sms.sendMultipartTextMessage(destination, null, parts, sentIntents, null)
            }
            Log.i(TAG, "SMS id=${due.id} handed to modem for $destination (attempt ${due.attempts + 1})")
            // Safety-net retry in case the sent callback is lost across a reboot.
            scheduleRetry(app, IN_FLIGHT_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.e(TAG, "SMS send threw for id=${due.id}", e)
            due.inFlightSince = 0L
            due.attempts += 1
            due.nextRetryAt = now + backoffFor(due.attempts)
            due.lastError = e.message ?: "send exception"
            if (due.attempts >= MAX_ATTEMPTS) {
                queue.remove(due)
                PeerLink.note(app, "SMS dropped after send failure: ${due.lastError}")
            }
            saveQueue(app, queue)
            notifyQueueChanged(app)
            scheduleNextForQueue(app, queue, now)
        }
    }

    /** Called by [SmsSentReceiver] when Android finishes (or fails) handing off an SMS. */
    @Synchronized
    fun onSendResult(context: Context, id: Long, partCount: Int, resultCode: Int) {
        val app = context.applicationContext
        val queue = loadQueue(app)
        val item = queue.firstOrNull { it.id == id } ?: return
        val now = System.currentTimeMillis()

        item.partsDone += 1
        if (resultCode != Activity.RESULT_OK) {
            item.anyPartFailed = true
            item.lastError = describeResult(resultCode)
        }
        if (item.partsDone < item.partsTotal.coerceAtLeast(partCount)) {
            saveQueue(app, queue)
            return
        }

        if (!item.anyPartFailed) {
            queue.remove(item)
            recordSendSuccess(app, now)
            saveQueue(app, queue)
            notifyQueueChanged(app)
            PeerLink.note(app, "SMS sent: ${item.text}")
            if (queue.isNotEmpty()) {
                // Space out multiple queued alerts slightly so carriers don't flag rapid bursts.
                scheduleRetry(app, 5_000L)
            } else {
                cancelRetryWork(app)
            }
        } else {
            item.inFlightSince = 0L
            item.attempts += 1
            val delay = backoffFor(item.attempts)
            item.nextRetryAt = now + delay
            Log.w(TAG, "SMS id=$id failed (${item.lastError}); attempt ${item.attempts}/$MAX_ATTEMPTS, retry in ${delay / 60_000L}m")
            if (item.attempts >= MAX_ATTEMPTS) {
                queue.remove(item)
                PeerLink.note(app, "SMS failed (${item.lastError}) and dropped after $MAX_ATTEMPTS attempts")
            } else {
                PeerLink.note(app, "SMS queued for retry (${item.lastError}, attempt ${item.attempts}/$MAX_ATTEMPTS)")
            }
            saveQueue(app, queue)
            notifyQueueChanged(app)
            scheduleNextForQueue(app, queue, now)
        }
    }

    /** Called when Airplane Mode is switched OFF; resets retry timers and flushes after radio settle. */
    @Synchronized
    fun onAirplaneModeOff(context: Context) {
        val app = context.applicationContext
        val queue = loadQueue(app)
        if (queue.isEmpty()) return
        val now = System.currentTimeMillis()
        for (item in queue) {
            if (item.inFlightSince == 0L) {
                item.nextRetryAt = now
            }
        }
        saveQueue(app, queue)
        Log.i(TAG, "Airplane mode turned off; scheduling flush for ${queue.size} queued SMS in 15s")
        scheduleRetry(app, 15_000L)
    }

    @Synchronized
    fun clearQueue(context: Context) {
        val app = context.applicationContext
        saveQueue(app, emptyList())
        cancelRetryWork(app)
        notifyQueueChanged(app)
    }

    @Synchronized
    fun statusSummary(context: Context): String {
        val app = context.applicationContext
        val number = Prefs.getSmsPhoneNumber(app)
        val hasPerm = ContextCompat.checkSelfPermission(app, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED
        val queue = loadQueue(app)
        val numText = number.ifEmpty { "not set" }
        val permText = if (hasPerm) "granted" else "not granted"
        val airplane = if (isAirplaneModeOn(app)) " (airplane mode on)" else ""
        return "Number: $numText\nSMS permission: $permText\nQueued for retry: ${queue.size}$airplane"
    }

    // ------------------------------------------------------------------ internals

    private fun formatForSend(item: QueuedSms, now: Long): String {
        if (now - item.createdAt < DELAYED_STAMP_THRESHOLD_MS) return item.text
        val stamp = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(item.createdAt))
        return "${item.text} [at $stamp]"
    }

    private fun backoffFor(attempts: Int): Long {
        val idx = (attempts - 1).coerceIn(0, BACKOFF_MS.lastIndex)
        return BACKOFF_MS[idx]
    }

    private fun scheduleNextForQueue(context: Context, queue: List<QueuedSms>, now: Long) {
        if (queue.isEmpty()) {
            cancelRetryWork(context)
            return
        }
        val nextAt = queue.minOf { it.nextRetryAt }
        val delay = (nextAt - now).coerceAtLeast(15_000L)
        scheduleRetry(context, delay)
    }

    private fun scheduleRetry(context: Context, delayMs: Long) {
        val request = OneTimeWorkRequestBuilder<SmsRetryWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(5_000L), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            RETRY_WORK,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private fun cancelRetryWork(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(RETRY_WORK)
    }

    private fun buildSentIntent(
        context: Context,
        id: Long,
        partIndex: Int,
        partCount: Int
    ): PendingIntent {
        val intent = Intent(context, SmsSentReceiver::class.java)
            .setAction(ACTION_SMS_SENT)
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_PART_INDEX, partIndex)
            .putExtra(EXTRA_PART_COUNT, partCount)
        val requestCode = ((id * 31L + partIndex) and 0x7FFFFFFFL).toInt()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    private fun smsManager(context: Context): SmsManager? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
    } catch (_: Exception) {
        null
    }

    fun isAirplaneModeOn(context: Context): Boolean = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
    } catch (_: Exception) {
        false
    }

    private fun describeResult(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_RADIO_OFF -> "radio off / airplane mode"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "no cellular service"
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "Android SMS rate limit"
        SmsManager.RESULT_ERROR_NULL_PDU -> "null PDU"
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "network / carrier error"
        else -> "error $code"
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun nextId(context: Context): Long {
        val p = prefs(context)
        val id = p.getLong(KEY_NEXT_ID, 1L)
        p.edit { putLong(KEY_NEXT_ID, id + 1L) }
        return id
    }

    private fun isDuplicate(
        context: Context,
        queue: List<QueuedSms>,
        text: String,
        now: Long,
    ): Boolean {
        if (queue.any { (it.text == text) && ((now - it.createdAt) < DEDUPE_MS) }) return true
        val raw = prefs(context).getString(KEY_RECENT_TEXTS, "{}") ?: "{}"
        return try {
            val obj = JSONObject(raw)
            val last = obj.optLong(text, 0L)
            (last > 0L) && ((now - last) < DEDUPE_MS)
        } catch (_: Exception) {
            false
        }
    }

    private fun recordTextSeen(context: Context, text: String, now: Long) {
        val p = prefs(context)
        val obj = try {
            JSONObject(p.getString(KEY_RECENT_TEXTS, "{}") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        val cleaned = JSONObject()
        obj.keys().forEach { k ->
            val t = obj.optLong(k, 0L)
            if ((now - t) < DEDUPE_MS) cleaned.put(k, t)
        }
        cleaned.put(text, now)
        p.edit { putString(KEY_RECENT_TEXTS, cleaned.toString()) }
    }

    private fun checkRateLimit(context: Context, now: Long): String? {
        val times = loadSendTimes(context, now)
        val inShort = times.count { (now - it) < RATE_SHORT_WINDOW_MS }
        if (inShort >= RATE_SHORT_MAX) return "rate limit ($RATE_SHORT_MAX per 10 min)"
        if (times.size >= RATE_DAY_MAX) return "daily limit ($RATE_DAY_MAX per 24h)"
        return null
    }

    private fun recordSendSuccess(context: Context, now: Long) {
        val times = loadSendTimes(context, now).toMutableList()
        times.add(now)
        val arr = JSONArray()
        for (t in times) arr.put(t)
        prefs(context).edit { putString(KEY_RECENT_SENDS, arr.toString()) }
    }

    private fun loadSendTimes(context: Context, now: Long): List<Long> {
        val raw = prefs(context).getString(KEY_RECENT_SENDS, "[]") ?: "[]"
        val out = mutableListOf<Long>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val t = arr.optLong(i, 0L)
                if (t > 0L && now - t < RATE_DAY_WINDOW_MS) out.add(t)
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun loadQueue(context: Context): MutableList<QueuedSms> {
        val raw = prefs(context).getString(KEY_QUEUE, "[]") ?: "[]"
        val out = mutableListOf<QueuedSms>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val text = o.optString("text", "")
                if (text.isEmpty()) continue
                out.add(
                    QueuedSms(
                        id = o.optLong("id", 0L),
                        text = text,
                        createdAt = o.optLong("createdAt", 0L),
                        attempts = o.optInt("attempts", 0),
                        nextRetryAt = o.optLong("nextRetryAt", 0L),
                        inFlightSince = o.optLong("inFlightSince", 0L),
                        partsTotal = o.optInt("partsTotal", 1),
                        partsDone = o.optInt("partsDone", 0),
                        anyPartFailed = o.optBoolean("anyPartFailed", false),
                        lastError = o.optString("lastError", "")
                    )
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun saveQueue(context: Context, queue: List<QueuedSms>) {
        val arr = JSONArray()
        for (item in queue) {
            arr.put(
                JSONObject()
                    .put("id", item.id)
                    .put("text", item.text)
                    .put("createdAt", item.createdAt)
                    .put("attempts", item.attempts)
                    .put("nextRetryAt", item.nextRetryAt)
                    .put("inFlightSince", item.inFlightSince)
                    .put("partsTotal", item.partsTotal)
                    .put("partsDone", item.partsDone)
                    .put("anyPartFailed", item.anyPartFailed)
                    .put("lastError", item.lastError)
            )
        }
        prefs(context).edit { putString(KEY_QUEUE, arr.toString()) }
    }

    private fun notifyQueueChanged(context: Context) {
        Handler(Looper.getMainLooper()).post {
            context.sendBroadcast(Intent(ACTION_QUEUE_UPDATED).setPackage(context.packageName))
        }
    }
}
