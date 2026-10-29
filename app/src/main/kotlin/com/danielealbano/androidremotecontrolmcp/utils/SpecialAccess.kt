package com.danielealbano.androidremotecontrolmcp.utils

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * The two kinds of access no permission dialog can give and that, until now, were handed out over
 * adb: drawing over other apps (the wake glow that does not block touches) and being the phone's
 * assistant (what a long press of the power button opens). The user grants both in system settings;
 * this only reads their state and opens the right page.
 */
object SpecialAccess {
    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun isAssistant(context: Context): Boolean =
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true

    fun openOverlaySettings(context: Context) {
        val own = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        open(context, listOf(own, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION), appDetails(context)))
    }

    fun openAssistantSettings(context: Context) {
        val pages =
            listOf(
                Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
                Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
                appDetails(context),
            )
        open(context, pages)
    }

    /** The first page this phone's settings app actually has: vendors drop some of them. */
    fun firstAvailable(
        pages: List<Intent>,
        resolvable: (Intent) -> Boolean,
    ): Intent? = pages.firstOrNull(resolvable)

    private fun appDetails(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    /** Opens the first of [pages] this phone's settings app has; does nothing when it has none. */
    fun open(
        context: Context,
        pages: List<Intent>,
    ) {
        val page = firstAvailable(pages) { it.resolveActivity(context.packageManager) != null } ?: return
        context.startActivity(page.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
