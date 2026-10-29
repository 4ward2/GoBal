package com.danielealbano.androidremotecontrolmcp.services.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * What the agent needs from a `get_screen_state` result beyond handing it to the model: which
 * app is in front and what each control says, to name steps and to stop before a payment. Pure.
 */
class ScreenText(
    /** The package of the app window in front, "" when the result named none. */
    val packageName: String,
    /** node_id to the words the control shows (its text, else its description). */
    val labels: Map<String, String>,
    /** node_id to the ids of the controls directly inside it. */
    private val children: Map<String, List<String>> = emptyMap(),
    /** Every node on the screen, the ones that say nothing included. */
    private val ids: Set<String> = labels.keys,
    /** The cursor of the next page when the screen did not fit one result, "" otherwise. */
    val nextCursor: String = "",
) {
    /** True when the control [id] is on this screen. */
    fun has(id: String): Boolean = id in ids

    /** Every control on this screen, in reading order, the ones that say nothing included. */
    fun controls(): Set<String> = ids

    /**
     * Everything written on the control [id] and inside it. A button is often a bare container
     * whose words sit on a child: judging a tap by the container's own (empty) label would miss
     * "提交订单" entirely.
     */
    fun wordsWithin(id: String): String {
        val seen = LinkedHashSet<String>()
        val queue = ArrayDeque(listOf(id))
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (seen.add(next)) queue += children[next].orEmpty()
        }
        return seen.mapNotNull { labels[it] }.joinToString(" ")
    }

    /** Every word on the screen, for spotting a captcha or a login page. */
    val allText: String get() = labels.values.joinToString("\n")

    /** This screen with a further page of the same screen added. */
    operator fun plus(page: ScreenText): ScreenText {
        val merged = children.toMutableMap()
        page.children.forEach { (parent, inside) -> merged[parent] = (merged[parent].orEmpty() + inside).distinct() }
        val app = packageName.ifEmpty { page.packageName }
        return ScreenText(app, labels + page.labels, merged, ids + page.ids, page.nextCursor)
    }

    companion object {
        val EMPTY = ScreenText("", emptyMap())

        /**
         * Reads the compact screen text (see `CompactTreeFormatter`): one section per window, each
         * with a table of controls and their hierarchy. Every window counts: a payment sheet, a
         * dialog and a captcha are windows of their own. Unknown lines are skipped.
         */
        fun parse(result: String): ScreenText {
            val reader = ScreenReader()
            result.lines().forEach(reader::line)
            return reader.screen()
        }
    }
}

/** Reads a screen result line by line; which part of a window's section a line is in decides what it means. */
private class ScreenReader {
    private enum class Part { OTHER, ROWS, TREE }

    private var part = Part.OTHER
    private val labels = LinkedHashMap<String, String>()
    private val ids = LinkedHashSet<String>()
    private val children = LinkedHashMap<String, MutableList<String>>()
    private val open = ArrayList<String>()
    private var focusedApp = ""
    private var firstApp = ""
    private var cursor = ""

    fun line(line: String) {
        when {
            line.startsWith(WINDOW_START) -> window(line)
            line.startsWith(HEADER_START) -> part = Part.ROWS
            line == HIERARCHY_HEADER -> tree()
            line.startsWith(NOTE_START) || line.startsWith(PAGE_START) -> note(line)
            part == Part.ROWS -> row(line.split('\t'))
            part == Part.TREE && line.isNotBlank() -> branch(line)
        }
    }

    fun screen(): ScreenText {
        val said = labels.filterValues { it.isNotEmpty() }
        return ScreenText(focusedApp.ifEmpty { firstApp }, said, children, ids, cursor)
    }

    private fun window(header: String) {
        part = Part.OTHER
        // Read by position: the title in between is the app's own text and could say anything.
        val fields = header.split(' ')
        val isApp = fields.getOrNull(TYPE_FIELD) == "type:$APPLICATION"
        val app = fields.getOrNull(PKG_FIELD)?.removePrefix(PKG).orEmpty()
        if (isApp && firstApp.isEmpty()) firstApp = app
        if (isApp && fields.getOrNull(fields.size - FOCUSED_FROM_END) == "focused:true") focusedApp = app
    }

    private fun tree() {
        part = Part.TREE
        open.clear()
    }

    private fun note(line: String) {
        part = Part.OTHER
        val after = line.substringAfter(CURSOR_MARK, "")
        if (after.isNotEmpty()) cursor = after.substringBefore('"')
    }

    private fun row(cells: List<String>) {
        if (cells.size <= COLUMN_DESC) return
        ids += cells[0]
        labels[cells[0]] = cells[COLUMN_TEXT].clean().ifEmpty { cells[COLUMN_DESC].clean() }
    }

    /** The hierarchy lists node ids, two spaces deeper per level: turned into parent to children. */
    private fun branch(line: String) {
        val id = line.trim()
        val depth = (line.length - line.trimStart().length) / INDENT
        while (open.size > depth) open.removeAt(open.lastIndex)
        open.lastOrNull()?.let { parent -> children.getOrPut(parent) { mutableListOf() } += id }
        open += id
        ids += id
    }

    /** The table writes "-" for an empty cell. */
    private fun String.clean(): String = trim().takeUnless { it == "-" }.orEmpty()

    private companion object {
        const val WINDOW_START = "--- window:"
        const val HEADER_START = "node_id\t"
        const val HIERARCHY_HEADER = "hierarchy:"
        const val NOTE_START = "note:"
        const val PAGE_START = "page:"
        const val CURSOR_MARK = "with cursor \""
        const val APPLICATION = "APPLICATION"
        const val PKG = "pkg:"
        const val TYPE_FIELD = 2
        const val PKG_FIELD = 3
        const val FOCUSED_FROM_END = 2
        const val INDENT = 2
        const val COLUMN_TEXT = 2
        const val COLUMN_DESC = 3
    }
}

/** Short Chinese names for the agent's steps, shown as progress and kept in the task history. Pure. */
object StepLabels {
    private const val MAX_WORDS = 16

    /**
     * What calling [tool] with [args] does, in a few words. [screen] names the control a node id
     * refers to; [appName] turns a package into the app's name.
     */
    fun of(
        tool: String,
        args: JsonObject,
        screen: ScreenText,
        appName: (String) -> String,
    ): String {
        val node = screen.labels[args.text("node_id")].orEmpty().short()
        val typed = args.text("text").ifEmpty { args.text("new_text") }.short()
        return when (tool) {
            "open_app", "shadow_open_app" -> "打开「${appName(args.text("package_id"))}」"
            "click_node", "tap_node" -> if (node.isEmpty()) "点一下" else "点「$node」"
            "long_click_node" -> if (node.isEmpty()) "长按" else "长按「$node」"
            "tap_text" -> "点「$typed」"
            in TYPING -> "输入「$typed」"
            else -> FIXED[tool] ?: tool
        }
    }

    private val TYPING = setOf("type_append_text", "type_insert_text", "type_replace_text")

    /** Steps that read the same whatever their arguments. */
    private val FIXED =
        mapOf(
            "type_clear_text" to "清空输入框",
            "scroll" to "滑动屏幕",
            "swipe" to "滑动屏幕",
            "scroll_to_node" to "滑动屏幕",
            "press_back" to "返回",
            "press_home" to "回到桌面",
            "get_screen_state" to "看屏幕",
            "find_nodes" to "看屏幕",
            "get_node_details" to "看屏幕",
            "wait_for_idle" to "等页面加载",
            "wait_for_node" to "等页面加载",
            "list_apps" to "找应用",
            "open_uri" to "打开链接",
            "vault_fill" to "代填资料",
            "system_autofill" to "代填资料",
            "vault_list_slots" to "查看可代填的资料",
            "skill_run" to "重放技能",
            "dismiss_keyboard" to "收起键盘",
        )

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun String.short(): String {
        val line = lineSequence().firstOrNull().orEmpty().trim()
        return if (line.length <= MAX_WORDS) line else line.take(MAX_WORDS) + "…"
    }
}
