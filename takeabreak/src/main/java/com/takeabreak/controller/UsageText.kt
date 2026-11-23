package com.takeabreak.controller

object UsageText {
    /** Whole seconds. Sub-second usage still shows, so a new session does not look like zero. */
    fun format(ms: Long): String {
        if (ms <= 0L) return "0 sec"
        if (ms < 1000L) return "< 1 sec"
        val totalSec = ms / 1000L
        val hours = totalSec / 3600L
        val minutes = (totalSec % 3600L) / 60L
        val seconds = totalSec % 60L
        return when {
            (hours > 0L) && (seconds > 0L) -> "${hours}h ${minutes}m ${seconds}s"
            hours > 0L -> "${hours}h ${minutes}m"
            (minutes > 0L) && (seconds > 0L) -> "$minutes min $seconds sec"
            minutes > 0L -> "$minutes min"
            else -> "$seconds sec"
        }
    }
}
