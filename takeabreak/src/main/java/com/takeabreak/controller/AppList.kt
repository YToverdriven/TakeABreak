package com.takeabreak.controller

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.provider.Settings

/** Which installed apps the lock list and the limit list are allowed to offer. */
object AppList {
    fun canControl(context: Context, app: ApplicationInfo, extraExcluded: Set<String>): Boolean {
        val pkg = app.packageName
        if (pkg in extraExcluded) return false
        if (pkg.startsWith("com.takeabreak.")) return false
        if (ProtectedPackages.isProtected(context, pkg)) return false
        // System app, but the one system app the user still wants to be able to block.
        return (pkg == ProtectedPackages.PLAY_STORE) || ((app.flags and ApplicationInfo.FLAG_SYSTEM) == 0)
    }

    /** Returns the default launcher and default keyboard packages, which shouldn't be limited or charted. */
    fun excludedLauncherAndImePackages(context: Context): Set<String> {
        val excluded = mutableSetOf<String>()
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            context.packageManager.resolveActivity(homeIntent, 0)
                ?.activityInfo
                ?.packageName
                ?.let { excluded.add(it) }
        } catch (_: Exception) {
        }
        try {
            val ime = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD,
            )
            val pkg = ime?.substringBefore("/")
            if (!pkg.isNullOrEmpty()) excluded.add(pkg)
        } catch (_: Exception) {
        }
        return excluded
    }
}
