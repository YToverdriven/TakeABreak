package com.takeabreak.controller

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Sends one daily status SMS at the configured local time (default 8:00 PM) and reschedules
 * itself for the next day. Uses WorkManager (no exact alarms or foreground services) and records
 * the date sent so it can never send more than once per calendar day.
 */
class DailyStatusWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val app = applicationContext
        if (!Prefs.isDailyStatusEnabled(app) || !Prefs.isValidPhoneNumber(Prefs.getSmsPhoneNumber(app))) {
            cancel(app)
            return Result.success()
        }

        val today = Prefs.currentDateKey()
        if (Prefs.getLastDailyStatusDate(app) != today) {
            Prefs.setLastDailyStatusDate(app, today)
            val acc = if (isAccessibilityEnabled(app)) "ON" else "OFF"
            val watcher = PeerLink.partnerStatusShort(app)
            val locked = Prefs.getLockedPackages(app).size
            val limited = Prefs.getAllLimits(app).size
            val msg = "Take a Break daily status: Accessibility $acc, Watcher $watcher, $locked locked, $limited limited."
            PeerLink.reportDailyStatus(app, msg)
        } else {
            Log.i(TAG, "Daily status already sent for $today; skipping duplicate")
        }

        syncSchedule(app)
        return Result.success()
    }

    companion object {
        private const val TAG = "TakeABreak"
        private const val WORK_NAME = "daily_status_check"

        fun syncSchedule(context: Context) {
            val app = context.applicationContext
            if (!Prefs.isDailyStatusEnabled(app) || !Prefs.isValidPhoneNumber(Prefs.getSmsPhoneNumber(app))) {
                cancel(app)
                return
            }

            val hour = Prefs.getDailyStatusHour(app)
            val minute = Prefs.getDailyStatusMinute(app)
            val delayMs = computeNextDelayMs(app, hour, minute)

            val request = OneTimeWorkRequestBuilder<DailyStatusWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(app).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
            Log.i(TAG, "Scheduled daily status check for %02d:%02d (in %d min)".format(hour, minute, delayMs / 60_000L))
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        }

        private fun computeNextDelayMs(context: Context, hour: Int, minute: Int): Long {
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val alreadySentToday = Prefs.getLastDailyStatusDate(context) == Prefs.currentDateKey()
            if (alreadySentToday || (target.timeInMillis <= now.timeInMillis)) {
                target.add(Calendar.DAY_OF_YEAR, 1)
            }
            return (target.timeInMillis - now.timeInMillis).coerceAtLeast(60_000L)
        }
    }
}
