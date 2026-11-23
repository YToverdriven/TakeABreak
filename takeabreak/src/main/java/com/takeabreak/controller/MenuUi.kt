package com.takeabreak.controller

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Button

fun Activity.menuBackButton(label: String = "Back"): Button = Button(this).apply {
    text = label
    setOnClickListener { finish() }
}

/** Tells the accessibility service this app is in front, so it saves usage before a screen reads it. */
fun Activity.noteControllerVisible() {
    sendBroadcast(
        Intent(AppLockAccessibilityService.ACTION_OUR_SCREEN_VISIBLE).setPackage(packageName),
    )
}

fun isAccessibilityEnabled(context: Context): Boolean {
    val cr = context.contentResolver
    if (Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1) == 0) {
        return false
    }
    val enabled = Settings.Secure.getString(
        cr,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val component = android.content.ComponentName(
        context,
        AppLockAccessibilityService::class.java
    )
    val flat = component.flattenToString()
    val shortFlat = component.flattenToShortString()
    return enabled.split(":").any {
        it.equals(flat, ignoreCase = true) ||
            it.equals(shortFlat, ignoreCase = true) ||
            it.contains("AppLockAccessibilityService")
    }
}
