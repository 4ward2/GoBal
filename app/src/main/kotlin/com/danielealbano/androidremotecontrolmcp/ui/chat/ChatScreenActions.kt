package com.danielealbano.androidremotecontrolmcp.ui.chat

import com.danielealbano.androidremotecontrolmcp.data.model.AppTier
import com.danielealbano.androidremotecontrolmcp.services.assistant.SetupStep

/** What the chat screen can ask of its host. */
class ChatScreenActions(
    val onRequestMicrophonePermission: () -> Unit,
    val onRequestCameraPermission: () -> Unit = {},
    val onOpenMenu: () -> Unit,
    /** Starts a call with the active contact; true = with the camera on. */
    val onStartCall: (video: Boolean) -> Unit,
    /** Takes the user to where a setup step is done (a settings page of the app or of the system). */
    val onSetupStep: (SetupStep) -> Unit = {},
    /** Shows more or less of the app; from the simplest tier, the long press on the header asks for more. */
    val onTierChange: (AppTier) -> Unit = {},
)
