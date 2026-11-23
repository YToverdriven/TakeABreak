package com.takeabreak.controller

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager

/**
 * Apps that must never be PIN-locked or time-limited.
 *
 * The Play Store is intentionally not in this set, so it can still be blocked.
 * The default Phone and SMS apps are protected even when a third-party app holds that role.
 * Every system camera app is protected, including when the phone has more than one.
 * A sideloaded camera stays blockable.
 */
object ProtectedPackages {
    const val PLAY_STORE = "com.android.vending"

    private val PLAY_SERVICES = setOf(
        "com.google.android.gms",
        "com.google.android.gsf",
    )

    private val SETTINGS = setOf(
        "com.android.settings",
        "com.android.settings.intelligence"
    )

    /** System packages that place a call or show the in-call screen, across common builds. */
    private val PHONE_PACKAGES = setOf(
        "com.android.dialer",
        "com.google.android.dialer",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.incallui",
        "com.samsung.android.dialer",
        "com.samsung.android.incallui"
    )

    /** Known system camera package names. Included only when that package is a system app. */
    private val CAMERA_PACKAGES = setOf(
        "com.android.camera",
        "com.android.camera2",
        "com.google.android.GoogleCamera",
        "com.google.android.apps.camera",
        "com.sec.android.app.camera",
        "com.motorola.camera",
        "com.motorola.camera2",
        "com.oneplus.camera",
        "com.oplus.camera",
        "com.oppo.camera",
        "com.huawei.camera",
        "org.codeaurora.snapcam",
        "app.grapheneos.camera"
    )

    private val CAMERA_ACTIONS = listOf(
        MediaStore.ACTION_IMAGE_CAPTURE,
        MediaStore.ACTION_VIDEO_CAPTURE,
        MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA,
        "android.media.action.STILL_IMAGE_CAMERA_SECURE",
        "android.media.action.VIDEO_CAMERA"
    )

    @Volatile private var cache: Set<String> = emptySet()
    @Volatile private var cachedAt = 0L

    fun invalidate() {
        cachedAt = 0L
    }

    fun isProtected(context: Context, pkg: String): Boolean =
        (pkg != PLAY_STORE) && (pkg in current(context))

    fun current(context: Context): Set<String> {
        val now = SystemClock.elapsedRealtime()
        val hit = cache
        if (hit.isNotEmpty() && ((now - cachedAt) < 60_000L)) return hit
        val built = build(context.applicationContext)
        cache = built
        cachedAt = now
        return built
    }

    /** "App name (package)" lines for the debug screen. */
    fun describe(context: Context): List<String> {
        val pm = context.packageManager
        return current(context)
            .asSequence()
            .sorted()
            .map { pkg ->
                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (_: Exception) {
                    pkg
                }
                "$label ($pkg)"
            }
            .toList()
    }

    /** Every camera app on this phone. System ones are never blocked. */
    fun cameraApps(context: Context): List<CameraApp> {
        val pm = context.packageManager
        return discoverCameras(pm, installedApps(pm))
            .asSequence()
            .map { (pkg, system) -> CameraApp(pkg, system) }
            .sortedBy { (packageName, _) -> packageName }
            .toList()
    }

    /** "App name (package) - never blocked|can be locked" lines for the debug screen. */
    fun describeCameras(context: Context): List<String> {
        val pm = context.packageManager
        return cameraApps(context).map { (packageName, system) ->
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            } catch (_: Exception) {
                packageName
            }
            val note = if (system) "never blocked" else "can be locked"
            "$label ($packageName) - $note"
        }
    }

    data class CameraApp(val packageName: String, val system: Boolean)

    private fun build(context: Context): Set<String> {
        val pm = context.packageManager
        val installed = installedApps(pm)
        val out = mutableSetOf<String>()

        for (pkg in PLAY_SERVICES + SETTINGS + PHONE_PACKAGES) {
            if (isInstalled(pm, pkg)) out.add(pkg)
        }
        for ((pkg, system) in discoverCameras(pm, installed)) {
            if (system) out.add(pkg)
        }

        defaultDialer(context)?.let { out.add(it) }
        defaultSms(context)?.let { out.add(it) }
        resolvedSettings(pm)?.let { if (isSystem(pm, it)) out.add(it) }

        for (pkg in handlers(pm, Intent(Intent.ACTION_DIAL))) {
            if (isSystem(pm, pkg)) out.add(pkg)
        }

        // In-call UI package names vary by manufacturer. Keep any system package whose
        // name marks it as the call screen, so a phone call cannot be covered.
        for (app in installed) {
            if ((app.flags and ApplicationInfo.FLAG_SYSTEM) == 0) continue
            val pkg = app.packageName.lowercase()
            if (pkg.contains("incallui") || pkg.endsWith(".dialer")) out.add(app.packageName)
        }

        out.remove(PLAY_STORE)
        out.remove("")
        return out
    }

    private fun defaultDialer(context: Context): String? = try {
        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        telecom?.defaultDialerPackage
    } catch (_: Exception) {
        null
    }

    private fun defaultSms(context: Context): String? = try {
        Telephony.Sms.getDefaultSmsPackage(context)
    } catch (_: Exception) {
        null
    }

    private fun resolvedSettings(pm: PackageManager): String? = try {
        pm.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)?.activityInfo?.packageName
    } catch (_: Exception) {
        null
    }

    /**
     * All camera apps, not only the default one.
     * A package counts when it is a known camera package, it handles a camera intent,
     * or it can be launched and its package name contains "camera".
     */
    private fun discoverCameras(
        pm: PackageManager,
        installed: List<ApplicationInfo>,
    ): Map<String, Boolean> {
        val found = linkedMapOf<String, Boolean>()
        fun consider(pkg: String?) {
            if (pkg.isNullOrEmpty() || (pkg == PLAY_STORE)) return
            if (pkg.startsWith("com.takeabreak.")) return
            if (pkg in found) return
            found[pkg] = isSystem(pm, pkg)
        }
        for (pkg in CAMERA_PACKAGES) {
            if (isInstalled(pm, pkg)) consider(pkg)
        }
        for (action in CAMERA_ACTIONS) {
            for (pkg in handlers(pm, Intent(action))) consider(pkg)
        }
        for (app in installed) {
            val pkg = app.packageName
            val lower = pkg.lowercase()
            if (!lower.contains("camera")) continue
            if (lower.contains("cameraextension")) continue
            if (!launches(pm, pkg)) continue
            consider(pkg)
        }
        return found
    }

    private fun launches(pm: PackageManager, pkg: String): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
        return handlers(pm, intent).any { it == pkg }
    }

    /** MATCH_ALL so a second camera or dialer is included, not only the default app. */
    private fun handlers(pm: PackageManager, intent: Intent): List<String> {
        val all = queryHandlers(pm, intent, PackageManager.MATCH_ALL)
        if (all.isNotEmpty()) return all
        return queryHandlers(pm, intent, 0)
    }

    private fun queryHandlers(pm: PackageManager, intent: Intent, flags: Int): List<String> = try {
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, flags)
        }
        resolved.mapNotNull { it.activityInfo?.packageName }.distinct()
    } catch (_: Exception) {
        emptyList()
    }

    private fun installedApps(pm: PackageManager): List<ApplicationInfo> = try {
        pm.getInstalledApplications(0)
    } catch (_: Exception) {
        emptyList()
    }

    private fun isInstalled(pm: PackageManager, pkg: String): Boolean = try {
        pm.getApplicationInfo(pkg, 0)
        true
    } catch (_: Exception) {
        false
    }

    private fun isSystem(pm: PackageManager, pkg: String): Boolean = try {
        val flags = pm.getApplicationInfo(pkg, 0).flags
        flags and ApplicationInfo.FLAG_SYSTEM != 0
    } catch (_: Exception) {
        false
    }
}
