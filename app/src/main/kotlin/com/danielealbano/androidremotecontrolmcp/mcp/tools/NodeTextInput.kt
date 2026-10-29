package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import com.danielealbano.androidremotecontrolmcp.utils.ApiLevel
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock

/** One change to the text of a field, worked out on the whole text at once. Pure. */
internal sealed interface NodeTextEdit {
    /** The field's text after the change. Throws when the change does not fit [current]. */
    fun applyTo(current: String): String

    /** What was done, for the tool's answer. */
    fun summary(nodeId: String): String

    data class Append(
        val text: String,
    ) : NodeTextEdit {
        override fun applyTo(current: String): String = current + text

        override fun summary(nodeId: String): String = "Typed ${text.length} characters at end of node '$nodeId'."
    }

    data class Insert(
        val text: String,
        val offset: Int,
    ) : NodeTextEdit {
        override fun applyTo(current: String): String {
            if (offset > current.length) {
                throw McpToolException.InvalidParams("offset ($offset) exceeds text length (${current.length})")
            }
            return current.substring(0, offset) + text + current.substring(offset)
        }

        override fun summary(nodeId: String): String {
            val count = text.length
            return "Typed $count characters at offset $offset in node '$nodeId'."
        }
    }

    data class Replace(
        val search: String,
        val newText: String,
    ) : NodeTextEdit {
        override fun applyTo(current: String): String {
            val at = current.indexOf(search)
            if (at < 0) throw McpToolException.ActionFailed("Search text not found in the field")
            return current.substring(0, at) + newText + current.substring(at + search.length)
        }

        override fun summary(nodeId: String): String = "Replaced the first occurrence in node '$nodeId'."
    }

    data object Clear : NodeTextEdit {
        override fun applyTo(current: String): String = ""

        override fun summary(nodeId: String): String = "Text cleared from node '$nodeId'."
    }
}

/**
 * The text tools on phones older than Android 13, which have no accessibility keyboard: the
 * field is focused, its text is read from the accessibility tree, and the new text is set in one
 * step. Ordinary text fields take that; fields that only listen to key presses (some web pages,
 * split one-digit code boxes) do not, and the tool says so instead of pretending.
 */
internal class NodeTextInput(
    private val treeParser: AccessibilityTreeParser,
    private val actionExecutor: ActionExecutor,
    private val accessibilityServiceProvider: AccessibilityServiceProvider,
    private val nodeCache: AccessibilityNodeCache,
) {
    suspend fun edit(
        nodeId: String,
        change: NodeTextEdit,
    ): CallToolResult {
        val content =
            typeOperationMutex.withLock {
                val windows = getFreshWindows(treeParser, accessibilityServiceProvider, nodeCache).windows
                actionExecutor.clickNode(nodeId, windows).onFailure { e -> mapNodeActionException(e, nodeId) }
                val field = awaitFocusedField(nodeId)
                // A search over a password field would be a guessing oracle for its content.
                if (field.isPassword && change is NodeTextEdit.Replace) {
                    throw McpToolException.ActionFailed(
                        "type_replace_text is not available in password fields; use type_clear_text",
                    )
                }
                setText(field, change.applyTo(currentText(field)), nodeId)
            }
        return McpToolUtils.untrustedTextResult("${change.summary(nodeId)}\n$ONE_STEP_NOTE\nField content: $content")
    }

    private suspend fun awaitFocusedField(nodeId: String): AccessibilityNodeInfo {
        val deadline = System.currentTimeMillis() + FOCUS_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            findFocusedEditableNode(accessibilityServiceProvider)?.let { return it }
            delay(FOCUS_POLL_MS)
        }
        throw McpToolException.ActionFailed(
            "No editable field took the focus after tapping node '$nodeId'. It may not be a text field.",
        )
    }

    /** What the field holds: nothing when it shows its hint, and nothing readable when it is a password. */
    private fun currentText(field: AccessibilityNodeInfo): String {
        val showsHint = ApiLevel.atLeast(Build.VERSION_CODES.O) && field.isShowingHintText
        return if (showsHint || field.isPassword) "" else field.text?.toString().orEmpty()
    }

    private fun setText(
        field: AccessibilityNodeInfo,
        text: String,
        nodeId: String,
    ): String {
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            throw McpToolException.ActionFailed(
                "Node '$nodeId' does not accept text set directly. On this Android version that is the only " +
                    "way to type; fields that only listen to key presses cannot be filled.",
            )
        }
        field.refresh()
        return if (field.isPassword) PASSWORD_HIDDEN else currentText(field)
    }

    companion object {
        /** Whether this phone has to type this way. */
        fun needed(): Boolean = !ApiLevel.atLeast(Build.VERSION_CODES.TIRAMISU)

        private const val ONE_STEP_NOTE =
            "(The text was set in one step: this phone's Android has no key-by-key typing for assistants.)"
        private const val FOCUS_WAIT_MS = 1_500L
        private const val FOCUS_POLL_MS = 50L
    }
}
