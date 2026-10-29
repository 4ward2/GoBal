package com.danielealbano.androidremotecontrolmcp.services.skill

import com.danielealbano.androidremotecontrolmcp.services.accessibility.MultiWindowResult
import kotlinx.coroutines.delay
import kotlin.random.Random

/** What a replay needs from the phone; the Android implementation is [AndroidSkillDevice]. */
interface SkillDevice {
    /**
     * A fresh read of the target display. Returns no windows while the screen is in transition;
     * throws [IllegalStateException] when accessibility is not available at all.
     */
    fun read(): MultiWindowResult

    suspend fun click(
        nodeId: String,
        snapshot: MultiWindowResult,
    ): Result<Unit>

    suspend fun tap(
        x: Float,
        y: Float,
    ): Result<Unit>

    suspend fun setText(
        nodeId: String,
        text: String,
        snapshot: MultiWindowResult,
    ): Result<Unit>

    suspend fun scroll(direction: SkillScrollDirection): Result<Unit>

    suspend fun back(): Result<Unit>

    suspend fun launch(
        packageName: String,
        uri: String?,
    ): Result<Unit>

    /** Text read from a screenshot of the target display, or null when OCR is unavailable. */
    suspend fun ocrScreen(): String?

    /** Where OCR reads [text] on the target display (screen pixels), or null when it is not there. */
    suspend fun ocrLocate(text: String): OcrHit?

    /** Where the step's picture is on the target display (its centre, screen pixels), or null. */
    suspend fun findImage(step: SkillStep.Image): OcrHit?
}

enum class SkillRunStatus {
    /** Every step ran. */
    DONE,

    /** Stopped on purpose for the user: a hand-over step or a payment button. */
    HANDED_OVER,

    /** A step could not be done; nothing after it ran. */
    FAILED,
}

data class SkillRunResult(
    val status: SkillRunStatus,
    val skillName: String,
    val completedSteps: Int,
    val totalSteps: Int,
    /** 1-based step where the run stopped, for HANDED_OVER and FAILED. */
    val stoppedAt: Int?,
    val message: String,
    val log: List<String>,
    /** Entries read by collect steps, in the order they were seen. */
    val collected: List<CollectedItem> = emptyList(),
    /** Where the collected entries were saved on the phone, when there were any. */
    val savedTo: String? = null,
    /** The steps that went through, parameters filled in (a task that repairs a broken skill starts from these). */
    val executed: List<SkillStep> = emptyList(),
    /** The skill only navigates: what the user asked is answered by reading the screen it ends on. */
    val thenRead: Boolean = false,
)

/** Timings of a replay; the pauses between steps keep the rhythm of a person, which apps' risk checks expect. */
data class SkillTiming(
    val pollMs: Long = 400,
    val findTimeoutMs: Long = 8_000,
    val scrollAfterMs: Long = 2_500,
    val maxScrolls: Int = 5,
    val launchTimeoutMs: Long = 10_000,
    val ocrAfterMs: Long = 2_000,
    val pauseMinMs: Long = 700,
    val pauseMaxMs: Long = 1_400,
    /** How long a tap may take to change the screen before the next step looks at it. */
    val changeTimeoutMs: Long = 3_000,
    /** How long a popup that is not always there is given to show up before its tap is skipped. */
    val optionalMs: Long = 1_500,
)

/**
 * Replays a skill step by step. Every control is looked up again on the live screen by what it
 * says, so list order and layout may change between runs; a checkpoint ([SkillStep.Verify]) that
 * fails stops the run before anything is ordered. A tap on a payment / submit-order control is
 * never made ([PaymentGuard]).
 */
class SkillRunner(
    private val device: SkillDevice,
    private val timing: SkillTiming = SkillTiming(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val random: Random = Random.Default,
) {
    private val screenSteps = ScreenSteps(device, timing, clock, sleep, random)

    suspend fun run(
        skill: Skill,
        given: Map<String, String>,
    ): SkillRunResult =
        SkillParams.resolve(skill, given).fold(
            onSuccess = { values ->
                val result = replay(skill.name, skill.steps.map { SkillParams.fill(it, values) })
                result.copy(thenRead = skill.thenRead)
            },
            onFailure = { e ->
                val message = e.message.orEmpty()
                SkillRunResult(SkillRunStatus.FAILED, skill.name, 0, skill.steps.size, null, message, emptyList())
            },
        )

    private suspend fun replay(
        name: String,
        steps: List<SkillStep>,
    ): SkillRunResult {
        val log = mutableListOf<String>()
        val collected = mutableListOf<CollectedItem>()
        val executed = mutableListOf<SkillStep>()
        var app: String? = null
        steps.forEachIndexed { i, step ->
            if (i > 0) sleep(pause())
            if (step is SkillStep.Launch) app = step.packageName
            val stop = attempt(step, app, collected)
            if (stop != null) {
                log += "${i + 1}. ${SkillText.describe(step)} — ${stop.message}"
                // What a collect step read before a captcha or failure is kept, not thrown away.
                return SkillRunResult(
                    status = stop.status,
                    skillName = name,
                    completedSteps = i,
                    totalSteps = steps.size,
                    stoppedAt = i + 1,
                    message = stop.message.orEmpty(),
                    log = log,
                    collected = collected,
                    savedTo = null,
                    executed = executed,
                )
            }
            val count = if (step is SkillStep.Collect) "（共 ${collected.size} 条）" else ""
            log += "${i + 1}. ${SkillText.describe(step)} ✓$count"
            executed += step
        }
        val message = if (collected.isEmpty()) "完成" else "完成，收集到 ${collected.size} 条"
        return SkillRunResult(
            status = SkillRunStatus.DONE,
            skillName = name,
            completedSteps = steps.size,
            totalSteps = steps.size,
            stoppedAt = null,
            message = message,
            log = log,
            collected = collected,
            savedTo = null,
            executed = executed,
        )
    }

    /** Runs one step; null when it went through, otherwise why the replay stops there. */
    private suspend fun attempt(
        step: SkillStep,
        app: String?,
        collected: MutableList<CollectedItem>,
    ): Stop? =
        try {
            execute(step, app, collected)
            null
        } catch (stop: Stop) {
            stop
        } catch (e: IllegalStateException) {
            Stop(SkillRunStatus.FAILED, e.message ?: "无障碍服务不可用")
        }

    private suspend fun execute(
        step: SkillStep,
        app: String?,
        collected: MutableList<CollectedItem>,
    ) {
        when (step) {
            is SkillStep.Launch -> launch(step)
            is SkillStep.Tap -> if (step.ocr) screenSteps.ocrTap(step) else tap(step, app)
            is SkillStep.Collect -> screenSteps.collect(step, collected)
            is SkillStep.Image -> screenSteps.image(step)
            is SkillStep.Type -> type(step, app)
            is SkillStep.Verify -> verify(step)
            is SkillStep.Wait -> waitFor(step.target.withApp(app), HitPurpose.ANY, step.timeoutMs, scroll = false)
            is SkillStep.Scroll -> device.scroll(step.direction).orFail("滑动失败")
            SkillStep.Back -> device.back().orFail("返回失败")
            is SkillStep.HandOver -> throw Stop(SkillRunStatus.HANDED_OVER, step.reason.ifBlank { "这一步交给你来做" })
        }
    }

    private suspend fun launch(step: SkillStep.Launch) {
        device.launch(step.packageName, step.uri).orFail("打不开应用 ${step.packageName}")
        val deadline = clock() + timing.launchTimeoutMs
        while (!SelectorMatcher.showsPackage(device.read().windows, step.packageName)) {
            if (clock() >= deadline) fail("应用 ${step.packageName} 没有出现在屏幕上")
            sleep(timing.pollMs)
        }
    }

    private suspend fun tap(
        step: SkillStep.Tap,
        app: String?,
    ) {
        if (step.optional && !screenSteps.shows(step.target.withApp(app))) return
        val (hit, snapshot) = waitFor(step.target.withApp(app), HitPurpose.TAP, timing.findTimeoutMs, step.scroll)
        if (PaymentGuard.blocks(hit.label) || PaymentGuard.blocks(SelectorMatcher.labelOf(hit.clickTarget))) {
            throw Stop(SkillRunStatus.HANDED_OVER, "到了「${hit.label.take(LABEL_MAX)}」这一步，付款要你自己确认")
        }
        if (device.click(hit.clickTarget.id, snapshot).isFailure) {
            // Views that ignore accessibility clicks (WebView, touch-only custom views) still take a real touch.
            val b = hit.node.bounds
            device.tap((b.left + b.right) / 2f, (b.top + b.bottom) / 2f).orFail("点不了「${hit.label.take(LABEL_MAX)}」")
        }
        // The next step (often a checkpoint) must look at the new screen, not the one just tapped.
        val before = ScreenSignature.of(snapshot.windows)
        awaitChange(device, before, clock() + timing.changeTimeoutMs, clock) { sleep(timing.pollMs) }
    }

    private suspend fun type(
        step: SkillStep.Type,
        app: String?,
    ) {
        val deadline = clock() + timing.findTimeoutMs
        while (true) {
            val snapshot = device.read()
            val hit =
                step.target?.let { SelectorMatcher.find(snapshot.windows, it.withApp(app), HitPurpose.INPUT) }
                    ?: SelectorMatcher.firstInput(snapshot.windows, app).takeIf { step.target == null }
            if (hit != null) {
                device.setText(hit.node.id, step.text, snapshot).orFail("输入框写不进去")
                return
            }
            stopIfBlocked(snapshot)
            if (clock() >= deadline) fail("找不到输入框")
            sleep(timing.pollMs)
        }
    }

    private suspend fun verify(step: SkillStep.Verify) {
        val start = clock()
        val deadline = start + step.timeoutMs
        var ocrTried = false
        while (true) {
            val snapshot = device.read()
            if (SelectorMatcher.screenShows(snapshot.windows, step.text)) return
            stopIfBlocked(snapshot)
            val now = clock()
            val ocrDue = now - start >= timing.ocrAfterMs || now >= deadline
            if (step.ocr && !ocrTried && ocrDue) {
                ocrTried = true
                val seen = device.ocrScreen()
                if (seen != null && SelectorMatcher.squeeze(seen).contains(SelectorMatcher.squeeze(step.text))) return
            }
            if (now >= deadline) fail("核对没通过：屏幕上没看到「${step.text}」，已停下，后面的步骤都没做")
            sleep(timing.pollMs)
        }
    }

    private suspend fun waitFor(
        selector: NodeSelector,
        purpose: HitPurpose,
        timeoutMs: Long,
        scroll: Boolean,
    ): Pair<NodeHit, MultiWindowResult> {
        var scrolls = 0
        var sinceMove = clock()
        var deadline = clock() + timeoutMs
        while (true) {
            val snapshot = device.read()
            SelectorMatcher.find(snapshot.windows, selector, purpose)?.let { return it to snapshot }
            // Checked before any scroll: a swipe across a slider captcha would count as a failed attempt.
            stopIfBlocked(snapshot)
            val now = clock()
            if (scroll && scrolls < timing.maxScrolls && now - sinceMove >= timing.scrollAfterMs) {
                device.scroll(SkillScrollDirection.DOWN)
                scrolls++
                sinceMove = clock()
                deadline = maxOf(deadline, sinceMove + timing.scrollAfterMs)
            } else if (now >= deadline) {
                fail("找不到「${SkillText.describe(selector)}」")
            }
            sleep(timing.pollMs)
        }
    }

    private fun pause(): Long =
        if (timing.pauseMaxMs <= timing.pauseMinMs) {
            timing.pauseMinMs
        } else {
            random.nextLong(timing.pauseMinMs, timing.pauseMaxMs + 1)
        }

    private companion object {
        const val LABEL_MAX = 20
    }
}

/** Why a replay stopped at a step: thrown by the step, turned into a [SkillRunResult] by the runner. */
internal class Stop(
    val status: SkillRunStatus,
    message: String,
) : Exception(message)

private fun NodeSelector.withApp(app: String?): NodeSelector =
    when {
        packageName == null && app != null -> copy(packageName = app)
        else -> this
    }

/** Polls until the screen differs from [before] or [deadline] passes; a tap that changes nothing is not an error. */
internal suspend fun awaitChange(
    device: SkillDevice,
    before: Int,
    deadline: Long,
    clock: () -> Long,
    poll: suspend () -> Unit,
) {
    while (clock() < deadline) {
        poll()
        if (ScreenSignature.of(device.read().windows) != before) return
    }
}

/** A captcha or login page ends the replay as a hand-over, never as a failure to be retried blindly. */
internal fun stopIfBlocked(snapshot: MultiWindowResult) {
    ScreenBlockers.reason(snapshot.windows)?.let { throw Stop(SkillRunStatus.HANDED_OVER, it) }
}

private fun <T> Result<T>.orFail(message: String): T = getOrElse { fail(message) }

private fun fail(message: String): Nothing = throw Stop(SkillRunStatus.FAILED, message)
