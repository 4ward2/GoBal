package com.danielealbano.androidremotecontrolmcp.services.skill

import kotlin.random.Random

/**
 * The steps that work from what is drawn rather than from a known control: tapping text found by
 * OCR, and reading a whole list by scrolling through it. Both stop at captchas and login pages.
 */
internal class ScreenSteps(
    private val device: SkillDevice,
    private val timing: SkillTiming,
    private val clock: () -> Long,
    private val sleep: suspend (Long) -> Unit,
    private val random: Random,
) {
    /** Whether a control fitting [selector] shows up within the moment a passing popup is given. */
    suspend fun shows(selector: NodeSelector): Boolean {
        val deadline = clock() + timing.optionalMs
        while (true) {
            if (SelectorMatcher.find(device.read().windows, selector, HitPurpose.TAP) != null) return true
            if (clock() >= deadline) return false
            sleep(timing.pollMs)
        }
    }

    /** Taps where OCR reads the selector's text; the control need not exist in the accessibility tree. */
    suspend fun ocrTap(step: SkillStep.Tap) {
        val text = step.target.text.orEmpty()
        val deadline = clock() + timing.findTimeoutMs
        while (true) {
            val before = device.read()
            stopIfBlocked(before)
            val hit = device.ocrLocate(text)
            if (hit != null) {
                if (PaymentGuard.blocks(hit.read)) {
                    stop(SkillRunStatus.HANDED_OVER, "到了「${hit.read.take(LABEL_MAX)}」这一步，付款要你自己确认")
                }
                if (device.tap(hit.x, hit.y).isFailure) stop(SkillRunStatus.FAILED, "点不了「$text」")
                awaitChange(device, ScreenSignature.of(before.windows), clock() + timing.changeTimeoutMs, clock) {
                    sleep(timing.pollMs)
                }
                return
            }
            if (clock() >= deadline) stop(SkillRunStatus.FAILED, "截图里也没找到「$text」")
            sleep(timing.pollMs)
        }
    }

    /** Finds the step's picture on screen; taps it when the step says so, otherwise only checks it is there. */
    suspend fun image(step: SkillStep.Image) {
        val what = step.label.ifBlank { "这张图" }
        val deadline = clock() + step.timeoutMs
        while (true) {
            val before = device.read()
            stopIfBlocked(before)
            val hit = device.findImage(step)
            if (hit != null) {
                if (!step.tap) return
                if (device.tap(hit.x, hit.y).isFailure) stop(SkillRunStatus.FAILED, "点不了图片「$what」")
                awaitChange(device, ScreenSignature.of(before.windows), clock() + timing.changeTimeoutMs, clock) {
                    sleep(timing.pollMs)
                }
                return
            }
            if (clock() >= deadline) stop(SkillRunStatus.FAILED, "屏幕上没找到图片「$what」")
            sleep(timing.pollMs)
        }
    }

    /**
     * Scrolls the list down, reading its entries on each screen into [into] (no repeats), until an
     * end-of-list text shows, nothing new comes for a few screens, or a limit is reached.
     */
    suspend fun collect(
        step: SkillStep.Collect,
        into: MutableList<CollectedItem>,
    ) {
        val seen = into.mapTo(HashSet()) { it.line }
        var idle = 0
        var scrolls = 0
        while (true) {
            val snapshot = device.read()
            stopIfBlocked(snapshot)
            var fresh = 0
            for (item in ListItems.extract(snapshot.windows)) {
                if (into.size >= step.maxItems) break
                if (seen.add(item.line)) {
                    into += item
                    fresh++
                }
            }
            idle = if (fresh == 0) idle + 1 else 0
            val ended = step.endTexts.any { SelectorMatcher.screenShows(snapshot.windows, it) }
            val exhausted = idle >= IDLE_SCREENS || scrolls >= step.maxScrolls
            if (ended || exhausted || into.size >= step.maxItems) return
            if (device.scroll(SkillScrollDirection.DOWN).isFailure) stop(SkillRunStatus.FAILED, "滑动失败")
            scrolls++
            // A person reading a list does not scroll at a fixed beat; apps' risk checks notice one.
            sleep(step.pauseMs + random.nextLong(0, step.pauseMs / 2 + 1))
        }
    }

    private fun stop(
        status: SkillRunStatus,
        message: String,
    ): Nothing = throw Stop(status, message)

    private companion object {
        const val LABEL_MAX = 20

        /** Screens in a row with nothing new before the list counts as finished (a slow page load is one or two). */
        const val IDLE_SCREENS = 3
    }
}
