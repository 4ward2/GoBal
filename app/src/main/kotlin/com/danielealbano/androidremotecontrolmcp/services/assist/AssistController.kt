package com.danielealbano.androidremotecontrolmcp.services.assist

import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.danielealbano.androidremotecontrolmcp.data.model.AssistConfig
import com.danielealbano.androidremotecontrolmcp.data.model.NotificationChangeEvent
import com.danielealbano.androidremotecontrolmcp.data.model.NotificationChangeType
import com.danielealbano.androidremotecontrolmcp.data.repository.AssistSettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService
import com.danielealbano.androidremotecontrolmcp.services.notifications.McpNotificationListenerService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Wires the assistive features into the accessibility service: splash-ad skipping and
 * verification-code autofill, each gated by its [AssistConfig] toggle.
 *
 * Codes come from two sources — the SMS inbox ([SmsOtpSource], needs `READ_SMS`) and any posted
 * notification (the messaging app's, or an app's own push), taken from the existing notification
 * listener's event flow. [OtpInbox] de-duplicates them.
 *
 * Lifecycle: [start] in `onServiceConnected`, [stop] in `onDestroy`. [onEvent] runs on the main
 * thread; the [scope] must use the main dispatcher.
 */
class AssistController(
    private val service: McpAccessibilityService,
    private val scope: CoroutineScope,
    private val settings: AssistSettingsRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    @Volatile
    private var config = AssistConfig()

    private val inbox = OtpInbox()
    private val splashSkipper = SplashAdSkipper(service)
    private val otpAutofiller = OtpAutofiller(service, inbox, scope)
    private val smsSource = SmsOtpSource(service, scope, ioDispatcher) { body -> offerCode(body, SOURCE_SMS) }
    private val jobs = mutableListOf<Job>()

    /** Starts observing settings, SMS and notifications. */
    fun start() {
        jobs += scope.launch { settings.assistConfig.collect(::applyConfig) }
        jobs +=
            scope.launch {
                McpNotificationListenerService.notificationChangeEvents.collect(::onNotification)
            }
    }

    /** Stops every source and job. Idempotent. */
    fun stop() {
        jobs.forEach(Job::cancel)
        jobs.clear()
        otpAutofiller.cancel()
        smsSource.stop()
    }

    /** Routes an accessibility event to the enabled features. */
    fun onEvent(event: AccessibilityEvent) {
        val current = config
        if (current.splashSkipEnabled) splashSkipper.onEvent(event)
        if (current.otpAutofillEnabled && event.eventType in OTP_RETRY_EVENTS) {
            // READ_SMS may have been granted from the settings screen since the last check.
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) smsSource.start()
            otpAutofiller.tryFill()
        }
    }

    private fun applyConfig(newConfig: AssistConfig) {
        config = newConfig
        if (newConfig.otpAutofillEnabled) smsSource.start() else smsSource.stop()
    }

    private fun onNotification(event: NotificationChangeEvent) {
        if (event.eventType != NotificationChangeType.POSTED) return
        val data = event.notification
        val text = listOfNotNull(data.title, data.bigText ?: data.text).joinToString(" ")
        offerCode(text, data.packageName)
    }

    private fun offerCode(
        text: String,
        source: String,
    ) {
        val code = OtpExtractor.extract(text).takeIf { config.otpAutofillEnabled } ?: return
        if (inbox.offer(code, source, System.currentTimeMillis())) {
            Log.i(TAG, "Verification code (${code.length} chars) captured from $source")
            scope.launch { otpAutofiller.tryFill(force = true) }
        }
    }

    companion object {
        private const val TAG = "MCP:AssistController"
        private const val SOURCE_SMS = "sms"

        /** Events after which a still-pending code is retried: the user may have just reached the code field. */
        private val OTP_RETRY_EVENTS =
            setOf(
                AccessibilityEvent.TYPE_VIEW_FOCUSED,
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            )
    }
}
