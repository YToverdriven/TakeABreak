package com.takeabreak.controller

import android.app.Application

class TakeABreakApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PeerLink.ensureScheduled(this)
        DailyStatusWorker.syncSchedule(this)
        SmsAlerts.processQueue(this)
    }
}
