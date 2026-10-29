package com.danielealbano.androidremotecontrolmcp.services.agent

import com.danielealbano.androidremotecontrolmcp.services.skill.ScreenBlockers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * When an app puts up a captcha, the task does not try to get past it: the app is closed, left
 * alone for a while and opened again from its first screen, and the task carries on from its plan.
 * A captcha that is still there after the last wait is the user's to do.
 */
internal class Cooldown(
    private val tools: AgentTools,
    private val events: AgentEvents,
    private val pause: suspend (Long) -> Unit,
    private val waits: List<Long> = WAITS_MS,
) {
    private var used = 0

    /** Closes [app], waits, and opens it again; false when that was already tried enough, or did not work. */
    suspend fun recover(app: String): Boolean {
        val wait = waits.getOrNull(used)
        if (wait == null || app.isEmpty()) return false
        used++
        val name = tools.appName(app)
        val minutes = (wait / MINUTE_MS).coerceAtLeast(1)
        events.step(AgentStep("「$name」弹出人机验证：先关掉它，冷却 $minutes 分钟后重开（第 $used/${waits.size} 次）"))
        tools.call(PRESS_HOME, JsonObject(emptyMap()))
        tools.call(CLOSE_APP, buildJsonObject { put(PACKAGE, app) })
        pause(wait)
        ColdStarts.note(app)
        val reopened =
            tools.call(
                OPEN_APP,
                buildJsonObject {
                    put(PACKAGE, app)
                    put("fresh", true)
                },
            )
        events.step(AgentStep("重新打开「$name」", ok = !reopened.isError))
        return !reopened.isError
    }

    companion object {
        /** A minute first, then three: long enough for an app's check on a burst of use to settle. */
        val WAITS_MS = listOf(60_000L, 180_000L)
        private const val MINUTE_MS = 60_000L
        private const val PRESS_HOME = "press_home"
        private const val CLOSE_APP = "close_app"
        private const val OPEN_APP = "open_app"
        private const val PACKAGE = "package_id"
    }
}

/**
 * What the task makes of a screen: a person is needed, the app was not allowed, or (for a captcha)
 * the app was cooled down and opened again, and the task goes on.
 */
internal class Blockers(
    private val guards: Guards,
    private val cooldown: Cooldown,
    private val mapper: Mapper,
    private val eyes: Eyes,
) {
    private var fresh: String? = null

    /** Why the task cannot go on from [screen], or null when it can (again). */
    suspend fun screen(screen: ScreenText): Halt? {
        val halt = guards.onScreen(screen)
        val cooled = halt?.reason == ScreenBlockers.CAPTCHA_REASON && cooldown.recover(screen.packageName)
        if (cooled) {
            mapper.restarted(AFTER_COOLDOWN)
            fresh = eyes.lookAfterAction()
        }
        return if (cooled) null else halt
    }

    /**
     * The screen the app came back on after a cooldown, once: the model is shown that one, not the
     * captcha it was read off before, or it would take the captcha for still being there.
     */
    fun freshScreen(): String? = fresh.also { fresh = null }

    private companion object {
        const val AFTER_COOLDOWN =
            "The app showed a captcha. It was closed, left alone for a while and opened again from its first " +
                "screen. Do not try to get past a captcha yourself. Carry on with the plan from the app's first " +
                "screen: everything you noted is kept, so do not look at it again."
    }
}

/**
 * How often apps were started from their first screen lately, across tasks. Starting an app cold
 * again and again is what an app's checks on automation look at most; past a few in a while the
 * app is only brought back to the front.
 */
internal object ColdStarts {
    private const val WINDOW_MS = 10 * 60_000L
    private const val MOST = 3
    private val times = mutableMapOf<String, ArrayDeque<Long>>()

    /** True when [app] may be started cold at [now]; such a start is counted. */
    @Synchronized
    fun allow(
        app: String,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val recent = times.getOrPut(app) { ArrayDeque() }
        while (recent.isNotEmpty() && now - recent.first() > WINDOW_MS) recent.removeFirst()
        val ok = recent.size < MOST
        if (ok) recent.addLast(now)
        return ok
    }

    /** A cold start made anyway (after a cooldown) still counts. */
    @Synchronized
    fun note(
        app: String,
        now: Long = System.currentTimeMillis(),
    ) {
        times.getOrPut(app) { ArrayDeque() }.addLast(now)
    }

    @Synchronized
    fun clear() = times.clear()
}

/** Within one task, an app is started from its first screen the first time only; after that it is brought back. */
internal class Launches {
    private val opened = mutableSetOf<String>()

    fun fresh(app: String): Boolean = opened.add(app) && ColdStarts.allow(app)
}

/**
 * Going back and forth between screens (opening apps, going back, going home) without getting
 * anywhere: the first time it is pointed out; when it goes on, the task stops before the app
 * takes it for a robot.
 */
internal class Thrash {
    private val recent = ArrayDeque<Boolean>()
    private var warned = false

    /** A warning is due before the model's next turn; handed out once. */
    var warning = false
        private set

    /** After a call to [tool]: why the task must stop now, or null. */
    fun after(tool: String): Halt? {
        recent.addLast(tool in AROUND)
        if (recent.size > WINDOW) recent.removeFirst()
        if (recent.count { it } < TOO_MANY) return null
        recent.clear()
        if (!warned) {
            warned = true
            warning = true
        }
        return if (warning) null else Halt(RunStatus.FAILED, STOP)
    }

    fun takeWarning(): Boolean = warning.also { warning = false }

    companion object {
        private const val WINDOW = 10
        private const val TOO_MANY = 6
        private val AROUND = setOf("open_app", "press_back", "press_home", "close_app")
        const val STOP = "在几个页面之间来回打转，先停下来了，免得被应用当成机器人弹出人机验证"
        const val WARN =
            "You keep going back and forth between screens (opening the app, going back, going home) without " +
                "getting further. Apps answer that with a captcha. Stop and think: read the screen once, change " +
                "the plan, or hand over. Doing it again will end the task."
    }
}
