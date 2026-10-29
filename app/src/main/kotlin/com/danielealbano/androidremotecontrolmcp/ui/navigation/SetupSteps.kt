package com.danielealbano.androidremotecontrolmcp.ui.navigation

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.danielealbano.androidremotecontrolmcp.services.assistant.SetupStep

/** Where each setup step is done. */
object SetupSteps {
    /** The page of the app's own settings a step is done on; null for the one done in the system's settings. */
    fun pageOf(step: SetupStep): SettingsRoute? =
        when (step) {
            SetupStep.MODEL -> SettingsRoute.Providers
            SetupStep.SPEECH -> SettingsRoute.Chat
            SetupStep.ACCESSIBILITY -> null
        }

    /** Takes the user there: [openPage] for a page of the app, the system's accessibility settings otherwise. */
    fun open(
        step: SetupStep,
        context: Context,
        openPage: (SettingsRoute) -> Unit,
    ) {
        val page = pageOf(step)
        if (page != null) {
            openPage(page)
        } else {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }
}
