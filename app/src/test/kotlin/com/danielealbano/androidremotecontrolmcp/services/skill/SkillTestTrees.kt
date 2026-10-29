package com.danielealbano.androidremotecontrolmcp.services.skill

import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.MultiWindowResult
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData

/** Small builders for accessibility snapshots used by the skill tests. */
internal object SkillTestTrees {
    private var counter = 0

    /** A node described by what the test cares about; the rest takes test defaults. */
    data class Node(
        val text: String? = null,
        val desc: String? = null,
        val id: String = "n${counter++}",
        val resourceId: String? = null,
        val className: String = "android.widget.TextView",
        val clickable: Boolean = false,
        val editable: Boolean = false,
        val visible: Boolean = true,
        val top: Int = 100,
        val children: List<Node> = emptyList(),
        val height: Int = 80,
        val left: Int = 0,
        val right: Int = 500,
    ) {
        fun build(): AccessibilityNodeData =
            AccessibilityNodeData(
                id = id,
                className = className,
                text = text,
                contentDescription = desc,
                resourceId = resourceId,
                bounds = BoundsData(left, top, right, top + height),
                clickable = clickable,
                editable = editable,
                enabled = true,
                visible = visible,
                children = children.map { it.build() },
            )
    }

    fun window(
        pkg: String,
        vararg nodes: Node,
        type: String = "APPLICATION",
    ) = WindowData(
        windowId = pkg.hashCode(),
        windowType = type,
        packageName = pkg,
        tree =
            Node(
                id = "root_$pkg",
                className = "android.widget.FrameLayout",
                children = nodes.toList(),
                top = 0,
                height = SCREEN_HEIGHT,
            ).build(),
    )

    fun screen(vararg windows: WindowData) = MultiWindowResult(windows.toList())

    const val SCREEN_HEIGHT = 2000
}
