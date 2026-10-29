package com.danielealbano.androidremotecontrolmcp.services.agent

import com.danielealbano.androidremotecontrolmcp.services.chat.ChatApiResult
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatMessage
import com.danielealbano.androidremotecontrolmcp.services.skill.ScreenBlockers
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Shows what the agent's guards make of a real screen. A check for a person at the phone, not a
 * feature: the guards are only as good as their reading of real screens (pop-ups in windows of
 * their own, words written on a child of the button), and that cannot be seen from a unit test.
 */
class GuardProbe(
    private val tools: AgentTools,
) {
    private val everyApp =
        object : AppGate {
            override fun settled(packageName: String): Boolean = true

            override suspend fun ask(packageName: String): Boolean = true
        }

    private val quiet =
        object : AgentEvents {
            override fun step(step: AgentStep) = Unit

            override fun waitingForApproval(waiting: Boolean) = Unit

            override fun archived(skillName: String) = Unit

            override fun takeNotes(): List<String> = emptyList()
        }

    /**
     * Reads the screen as it is and reports, line by line, what a task would be stopped at:
     * a captcha or login, and every control a tap on which counts as paying. Controls showing
     * [text] are each listed with their verdict. Nothing on the screen is pressed or filled.
     */
    suspend fun judge(text: String): List<String> {
        val eyes = Eyes(tools) { }
        val notReady = tools.notReady()
        val unread = if (notReady == null && eyes.look() == null) "the screen could not be read" else null
        val trouble = notReady?.let { "not ready: $it" } ?: unread
        return if (trouble == null) verdicts(eyes.screen, text) else listOf(trouble)
    }

    private suspend fun verdicts(
        screen: ScreenText,
        text: String,
    ): List<String> {
        val guards = Guards(tools, everyApp, quiet)
        val verdicts = screen.controls().associateWith { guards.before(tap(it), screen)?.reason }
        val stopped = verdicts.filterValues { it != null }
        val asked = if (text.isEmpty()) emptyMap() else verdicts.filterKeys { text in screen.wordsWithin(it) }
        val person = ScreenBlockers.reasonInText(screen.allText)
        val head =
            listOf(
                "app in front: ${screen.packageName.ifEmpty { "(none)" }}",
                "controls read: ${screen.controls().size}",
                "needs a person: ${person ?: "no"}",
                "controls a tap on which is stopped as paying: ${stopped.size}",
            )
        val named = if (text.isEmpty()) emptyList() else listOf("controls showing \"$text\": ${asked.size}")
        return head + stopped.entries.take(LISTED).map(::line) + named + asked.entries.take(LISTED).map(::line)
    }

    private fun line(verdict: Map.Entry<String, String?>): String =
        "  ${verdict.key} -> " + (verdict.value?.let { "STOPPED: $it" } ?: "allowed")

    /**
     * Runs the real task loop on the test app [onlyApp], with a script in the model's place:
     * it looks at the screen, then taps the control showing [text] ([up] levels above the one
     * that carries the words). Every other app is refused, so the script can press nothing else.
     */
    suspend fun loop(
        script: ProbeScript,
        onlyApp: String,
    ): AgentOutcome {
        val gate =
            object : AppGate {
                override fun settled(packageName: String): Boolean = packageName == onlyApp

                override suspend fun ask(packageName: String): Boolean = false
            }
        return PhoneAgent(tools, script, gate, quiet).run(PROBE_GOAL)
    }

    private fun tap(node: String) = AgentMove.Call(TAP, buildJsonObject { put("node_id", node) })

    companion object {
        /** Not the name of anything a user would save as a skill. */
        const val PROBE_GOAL = "guard probe"
        private const val TAP = "click_node"
        private const val LISTED = 12
    }
}

/**
 * The stand-in for the model in [GuardProbe.loop]: reads the screen, taps once, and then says
 * the tap went through. A task that ends with that sentence was not stopped.
 */
class ProbeScript(
    private val text: String,
    private val up: Int,
) : AgentBrain {
    private var tapped = false

    override suspend fun reply(messages: List<ChatMessage>): ChatApiResult {
        val screen = ScreenText.parse(messages.last().text)
        // The control with the fewest words around [text] is the one that carries it; each step
        // up is the next tighter container.
        val showing = screen.controls().filter { text in screen.wordsWithin(it) }
        val target = showing.sortedBy { screen.wordsWithin(it).length }.getOrNull(up)
        val move =
            when {
                tapped -> finished(NOT_STOPPED)
                screen.controls().isEmpty() -> call(Eyes.GET_SCREEN, "")
                target == null -> finished("no control showing \"$text\" on the screen")
                else -> call("click_node", target).also { tapped = true }
            }
        return ChatApiResult(ok = true, content = move)
    }

    private fun call(
        tool: String,
        node: String,
    ): String =
        buildJsonObject {
            put("action", tool)
            putJsonObject("args") { if (node.isNotEmpty()) put("node_id", node) }
        }.toString()

    private fun finished(why: String): String =
        buildJsonObject {
            put("action", "fail")
            putJsonObject("args") { put("reason", why) }
        }.toString()

    companion object {
        const val NOT_STOPPED = "NOT STOPPED: the tap went through"
    }
}
