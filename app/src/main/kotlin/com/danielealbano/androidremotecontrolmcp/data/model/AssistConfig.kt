package com.danielealbano.androidremotecontrolmcp.data.model

/**
 * Toggles for the on-device assistive features that run inside the accessibility service.
 *
 * Both default to ON: the app targets users who find tiny countdown buttons and six-box code
 * fields hard to operate, so the helpers should work without first digging through settings.
 *
 * @property splashSkipEnabled Automatically press the "跳过/Skip" button of launch (splash) ads.
 * @property otpAutofillEnabled Fill incoming SMS / notification verification codes into the focused code field.
 */
data class AssistConfig(
    val splashSkipEnabled: Boolean = true,
    val otpAutofillEnabled: Boolean = true,
)
