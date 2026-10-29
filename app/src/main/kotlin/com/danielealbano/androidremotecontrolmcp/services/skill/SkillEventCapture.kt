package com.danielealbano.androidremotecontrolmcp.services.skill

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Converts accessibility events into [RecordedEvent]s while [SkillRecorder] is recording. Password
 * fields are never recorded, and nothing from this app, the launcher, system UI or the keyboard
 * becomes a step.
 */
class SkillEventCapture(
    private val context: Context,
    private val recorder: SkillRecorder,
) {
    private var ignored: Set<String> = emptySet()

    /** Call when a recording starts: the launcher and keyboard in use are looked up once. */
    fun refreshIgnoredPackages() {
        ignored =
            setOfNotNull(
                context.packageName,
                SYSTEM_UI,
                homePackage(),
                Settings.Secure
                    .getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                    ?.let { ComponentName.unflattenFromString(it)?.packageName },
            )
    }

    fun onEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString()
        if (!recorder.isListening || pkg == null) return
        if (pkg in ignored) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
            ) {
                recorder.onEvent(RecordedEvent.WindowShown(pkg, ignored = true))
            }
        } else {
            record(event, pkg)
        }
    }

    private fun record(
        event: AccessibilityEvent,
        pkg: String,
    ) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                recorder.onEvent(RecordedEvent.WindowShown(pkg, ignored = false))
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                recorder.onEvent(RecordedEvent.Clicked(pkg, clickSelector(event)))
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                textChange(event, pkg)?.let(recorder::onEvent)
            }
        }
    }

    private fun clickSelector(event: AccessibilityEvent): NodeSelector? {
        val node = event.source
        // The node that carries the label: the tapped control itself, or its first labelled child.
        val labelled = node?.let { n -> ownLabel(n)?.let { n to it } ?: descendantLabel(n) }
        val label = labelled?.second ?: event.text.joinToString(" ").takeIf { it.isNotBlank() }
        val id = node?.viewIdResourceName?.takeIf { it.isNotBlank() }
        if (label == null && id == null) return null
        val (text, match) = label?.let(::stableLabel) ?: (null to TextMatch.EXACT)
        return NodeSelector(
            text = text,
            match = match,
            // With the text, the class of the node showing it tells a list item (美团 shop titles are
            // ViewGroup) from the same words in a search bar or a banner (TextView). No id: the
            // id belongs to the tapped node, which may not be the labelled one.
            resourceId = id.takeIf { labelled?.first === node || label == null },
            className = labelled?.first?.className?.toString(),
        )
    }

    private fun textChange(
        event: AccessibilityEvent,
        pkg: String,
    ): RecordedEvent? {
        val node = event.source
        if (event.isPassword || node?.isPassword == true) return null
        val text = node?.text?.toString() ?: event.text.joinToString("")
        val field = node?.viewIdResourceName?.takeIf { it.isNotBlank() }?.let { NodeSelector(resourceId = it) }
        return RecordedEvent.TextChanged(pkg, field, text)
    }

    private fun homePackage(): String? =
        context.packageManager
            .resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY,
            )?.activityInfo
            ?.packageName

    companion object {
        private const val SYSTEM_UI = "com.android.systemui"
        private const val MAX_DESCENDANTS = 30

        /** Events only needed while recording; the service subscribes to them just for that time. */
        const val RECORDING_EVENT_TYPES =
            AccessibilityEvent.TYPE_VIEW_CLICKED or AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED

        private fun ownLabel(node: AccessibilityNodeInfo): String? =
            node.text?.toString()?.takeIf { it.isNotBlank() }
                ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }

        /** A tapped card usually has no text of its own; its first labelled child names it. */
        private fun descendantLabel(root: AccessibilityNodeInfo): Pair<AccessibilityNodeInfo, String>? {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            for (i in 0 until root.childCount) root.getChild(i)?.let(queue::addLast)
            var seen = 0
            while (queue.isNotEmpty() && seen < MAX_DESCENDANTS) {
                val n = queue.removeFirst()
                seen++
                ownLabel(n)?.let { return n to it }
                for (i in 0 until n.childCount) n.getChild(i)?.let(queue::addLast)
            }
            return null
        }

        /** See [SelectorFactory.stableLabel]; recording and task archiving store labels the same way. */
        internal fun stableLabel(label: String): Pair<String, TextMatch> = SelectorFactory.stableLabel(label)
    }
}
