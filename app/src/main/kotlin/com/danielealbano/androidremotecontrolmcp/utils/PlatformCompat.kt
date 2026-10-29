package com.danielealbano.androidremotecontrolmcp.utils

import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build

/**
 * Platform calls whose signature changed between the oldest Android this app runs on and the
 * newest: each function picks the spelling the phone has. Nothing here changes behaviour.
 */
object PlatformCompat {
    fun activitiesFor(
        packages: PackageManager,
        intent: Intent,
    ): List<ResolveInfo> =
        if (ApiLevel.atLeast(Build.VERSION_CODES.TIRAMISU)) {
            packages.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            packages.queryIntentActivities(intent, 0)
        }

    @Throws(PackageManager.NameNotFoundException::class)
    fun applicationInfo(
        packages: PackageManager,
        packageName: String,
    ): ApplicationInfo =
        if (ApiLevel.atLeast(Build.VERSION_CODES.TIRAMISU)) {
            packages.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            packages.getApplicationInfo(packageName, 0)
        }

    /**
     * A gesture aimed at [displayId]. Gestures on a display other than the phone's own screen
     * exist from Android 11; before that there is only the one screen to aim at.
     */
    fun gestureBuilder(displayId: Int): GestureDescription.Builder {
        val builder = GestureDescription.Builder()
        if (ApiLevel.atLeast(Build.VERSION_CODES.R)) builder.setDisplayId(displayId)
        return builder
    }
}
