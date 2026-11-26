package com.takeabreak.controller

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.inputmethod.InputMethodManager

/** Which installed apps the lock list and the limit list are allowed to offer. */
object AppList {
    /**
     * Returns `(packageName, displayLabel)` for every launchable app that Take a Break is allowed
     * to PIN-lock or time-limit, sorted alphabetically by label.
     *
     * Includes preinstalled/system apps with a launcher icon (such as Vanadium, Chrome, YouTube,
     * and the Play Store), while excluding Take a Break's own packages, [ProtectedPackages],
     * home launchers, and enabled keyboards.
     */
    fun controllableApps(context: Context): List<Pair<String, String>> {
        ProtectedPackages.invalidate()
        val pm = context.packageManager
        val launchable = launchablePackages(pm)
        val excluded = excludedLauncherAndImePackages(context)
        return pm.getInstalledApplications(0)
            .asSequence()
            .filter { canControl(context, it, launchable, excluded) }
            .map { app -> app.packageName to pm.getApplicationLabel(app).toString() }
            .sortedBy { (_, label) -> label.lowercase() }
            .toList()
    }

    fun canControl(
        context: Context,
        app: ApplicationInfo,
        launchable: Set<String>,
        extraExcluded: Set<String>,
    ): Boolean {
        val pkg = app.packageName
        if (pkg in extraExcluded) return false
        if (pkg.startsWith("com.takeabreak.")) return false
        if (ProtectedPackages.isProtected(context, pkg)) return false
        return (pkg == ProtectedPackages.PLAY_STORE) || (pkg in launchable)
    }

    /** Returns launcher and enabled keyboard packages, which shouldn't be locked, limited, or charted. */
    fun excludedLauncherAndImePackages(context: Context): Set<String> {
        val excluded = mutableSetOf<String>()
        val pm = context.packageManager
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            pm.resolveActivity(homeIntent, 0)
                ?.activityInfo
                ?.packageName
                ?.let { excluded.add(it) }
            excluded.addAll(queryActivityPackages(pm, homeIntent))
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
        try {
            val imm = context.getSystemService(InputMethodManager::class.java)
            imm?.enabledInputMethodList?.forEach { info ->
                val pkg = info.packageName
                if (!pkg.isNullOrEmpty()) excluded.add(pkg)
            }
        } catch (_: Exception) {
        }
        return excluded
    }

    private fun launchablePackages(pm: PackageManager): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return queryActivityPackages(pm, intent)
    }

    private fun queryActivityPackages(pm: PackageManager, intent: Intent): Set<String> {
        val all = queryPackagesWithFlags(pm, intent, PackageManager.MATCH_ALL)
        if (all.isNotEmpty()) return all
        return queryPackagesWithFlags(pm, intent, 0)
    }

    private fun queryPackagesWithFlags(
        pm: PackageManager,
        intent: Intent,
        flags: Int,
    ): Set<String> = try {
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, flags)
        }
        resolved.mapNotNullTo(HashSet()) { it.activityInfo?.packageName }
    } catch (_: Exception) {
        emptySet()
    }
}
