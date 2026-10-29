package com.danielealbano.androidremotecontrolmcp.services.assist

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService
import com.danielealbano.androidremotecontrolmcp.utils.ApiLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Types a pending verification code ([OtpInbox]) into the focused code field.
 *
 * Called whenever a new code arrives and on focus / window changes while a code is pending, so
 * it works in both orders: code first then the user opens the code screen, or the code screen is
 * open and the code arrives. The text is set through [AccessibilityNodeInfo.ACTION_SET_TEXT];
 * when the field rejects it (custom multi-box widgets), it is committed through the accessibility
 * input method instead. Rows of single-digit boxes get the code one character at a time through the
 * input method, pausing so the app can move focus to the next box. A filled code is consumed so
 * it is never typed twice.
 *
 * Main-thread only.
 */
class OtpAutofiller(
    private val service: McpAccessibilityService,
    private val inbox: OtpInbox,
    private val scope: CoroutineScope,
) {
    private var lastAttemptAt: Long? = null
    private var typingJob: Job? = null

    /**
     * Tries to fill the pending code. [force] bypasses the attempt rate limit (used right after a
     * code arrives). Returns true when a code was filled.
     */
    fun tryFill(force: Boolean = false): Boolean {
        val now = SystemClock.uptimeMillis()
        val pending = inbox.peek(System.currentTimeMillis())
        val rateLimited = !force && lastAttemptAt.let { it != null && now - it < MIN_ATTEMPT_INTERVAL_MS }
        if (pending == null || rateLimited || typingJob?.isActive == true) return false
        lastAttemptAt = now

        val field =
            service
                .findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?.takeIf { it.packageName?.toString() != service.packageName }
        val filled = field != null && fill(field, pending.code, logRejection = force)
        if (filled) {
            inbox.consume(pending.code)
            Log.i(TAG, "Filled a ${pending.code.length}-char code from ${pending.source} into ${field.packageName}")
            Toast.makeText(service, R.string.assist_otp_filled_toast, Toast.LENGTH_SHORT).show()
        }
        return filled
    }

    private fun fill(
        field: AccessibilityNodeInfo,
        code: String,
        logRejection: Boolean,
    ): Boolean {
        val screenText =
            field.window
                ?.root
                ?.let(::collectScreenText)
                .orEmpty()
        val facts = field.toOtpFacts()
        val mode = OtpFieldRules.fillMode(facts, code, screenText)
        val filled =
            when (mode) {
                OtpFillMode.NONE -> false
                OtpFillMode.WHOLE_CODE -> setText(field, code) || commitThroughInputMethod(code)
                OtpFillMode.PER_CHARACTER -> typeCharacters(code)
            }
        // Logged once per arriving code (not per retry); field text and the code itself are never logged.
        if (!filled && logRejection) {
            Log.d(TAG, "Code pending, focused field not filled: mode=$mode ${facts.copy(text = null)}")
        }
        return filled
    }

    /** Cancels an in-flight per-character typing run. */
    fun cancel() {
        typingJob?.cancel()
        typingJob = null
    }

    private fun typeCharacters(code: String): Boolean {
        if (!commitThroughInputMethod(code.take(1))) return false
        typingJob =
            scope.launch {
                for (char in code.drop(1)) {
                    delay(PER_CHARACTER_DELAY_MS)
                    if (!commitThroughInputMethod(char.toString())) {
                        Log.w(TAG, "Input connection lost while typing the verification code")
                        return@launch
                    }
                }
            }
        return true
    }

    /** Sets [code] as the field text; true only when the field really holds it afterwards. */
    private fun setText(
        field: AccessibilityNodeInfo,
        code: String,
    ): Boolean {
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, code) }
        val accepted = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) && field.refresh()
        return accepted && field.text?.toString()?.contains(code) == true
    }

    private fun commitThroughInputMethod(text: String): Boolean {
        if (!ApiLevel.atLeast(Build.VERSION_CODES.TIRAMISU)) return false
        val connection =
            McpAccessibilityService.inputMethodInstance
                ?.takeIf { it.getCurrentInputStarted() }
                ?.getCurrentInputConnection()
        connection?.commitText(text, 1, null)
        return connection != null
    }

    private fun collectScreenText(root: AccessibilityNodeInfo): String {
        val builder = StringBuilder()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES_PER_SCAN && builder.length < MAX_SCREEN_TEXT_CHARS) {
            val node = queue.removeFirst()
            visited++
            node.text?.let { builder.append(it).append('\n') }
            node.hintText?.let { builder.append(it).append('\n') }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return builder.toString()
    }

    private fun AccessibilityNodeInfo.toOtpFacts(): OtpFieldFacts =
        OtpFieldFacts(
            isEditable = isEditable,
            isPassword = isPassword,
            isShowingHint = isShowingHintText,
            text = text?.toString(),
            hint = hintText?.toString(),
            contentDescription = contentDescription?.toString(),
            viewIdResourceName = viewIdResourceName,
            isNumberInput = (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER,
            maxTextLength = maxTextLength,
        )

    companion object {
        private const val TAG = "MCP:OtpAutofiller"
        private const val MIN_ATTEMPT_INTERVAL_MS = 300L

        /** Time for the app to move focus to the next single-digit box. */
        private const val PER_CHARACTER_DELAY_MS = 120L
        private const val MAX_NODES_PER_SCAN = 400
        private const val MAX_SCREEN_TEXT_CHARS = 4_000
    }
}
