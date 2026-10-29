package com.danielealbano.androidremotecontrolmcp.services.skill

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A named, replayable UI flow ("点{{店名}}外卖"). Steps address controls by what they say
 * (text, description, resource id), never by coordinates or node ids, so a skill survives
 * layout shifts, other screen sizes and the shadow display.
 *
 * `{{name}}` placeholders may appear in [name], in selector text, typed text, verify text and
 * launch URIs; [params] declares them with the value used when the caller gives none.
 */
@Serializable
data class Skill(
    val name: String,
    val description: String = "",
    val params: List<SkillParam> = emptyList(),
    val steps: List<SkillStep>,
    val source: SkillSource = SkillSource.RECORDED,
    @SerialName("created_at") val createdAt: Long = 0L,
    @SerialName("updated_at") val updatedAt: Long = 0L,
    @SerialName("run_count") val runCount: Int = 0,
    @SerialName("last_run_ok") val lastRunOk: Boolean? = null,
    /**
     * The steps only get to the right screen; the request is answered by reading it (a lookup:
     * "what is on offer today"). After a replay the model is asked to read and answer.
     */
    @SerialName("then_read") val thenRead: Boolean = false,
)

@Serializable
data class SkillParam(
    val name: String,
    @SerialName("default") val defaultValue: String = "",
)

@Serializable
enum class SkillSource {
    /** The user did it once while recording. */
    @SerialName("recorded")
    RECORDED,

    /** An agent saved a flow it had just completed. */
    @SerialName("agent")
    AGENT,
}

@Serializable
enum class TextMatch {
    @SerialName("exact")
    EXACT,

    @SerialName("contains")
    CONTAINS,
}

/**
 * Which control a step acts on. Every given field must match. [text] is compared against both
 * the node's text and its content description (React Native apps such as 美团 put card labels in
 * the description).
 */
@Serializable
data class NodeSelector(
    val text: String? = null,
    val match: TextMatch = TextMatch.EXACT,
    @SerialName("resource_id") val resourceId: String? = null,
    @SerialName("class_name") val className: String? = null,
    @SerialName("package") val packageName: String? = null,
) {
    val isEmpty: Boolean get() = text.isNullOrBlank() && resourceId.isNullOrBlank() && className.isNullOrBlank()
}

@Serializable
sealed interface SkillStep {
    /** Start an app (optionally a deep link) on the current target display. */
    @Serializable
    @SerialName("launch")
    data class Launch(
        @SerialName("package") val packageName: String,
        val uri: String? = null,
    ) : SkillStep

    /**
     * Tap a control. With [scroll], the list is scrolled down to look for it when not on screen.
     * With [ocr], a control missing from the accessibility tree (美团's distance panel) is found by
     * reading a screenshot with the phone's OCR and tapped where its text is; only [NodeSelector.text] is used then.
     * An [optional] tap dismisses something that is not always there (an update prompt, a tip): it is made
     * when its control shows up within a moment and skipped, without failing the run, when it does not.
     */
    @Serializable
    @SerialName("tap")
    data class Tap(
        val target: NodeSelector,
        val scroll: Boolean = true,
        val ocr: Boolean = false,
        val optional: Boolean = false,
    ) : SkillStep

    /**
     * Scroll the main list to the end and keep every item seen on the way: the list the app
     * only shows a screen at a time. Stops at an end-of-list text, after [maxScrolls], at
     * [maxItems], or when scrolling brings nothing new.
     */
    @Serializable
    @SerialName("collect")
    data class Collect(
        @SerialName("max_items") val maxItems: Int = DEFAULT_COLLECT_ITEMS,
        @SerialName("max_scrolls") val maxScrolls: Int = DEFAULT_COLLECT_SCROLLS,
        @SerialName("pause_ms") val pauseMs: Long = DEFAULT_COLLECT_PAUSE_MS,
        @SerialName("end_texts") val endTexts: List<String> = DEFAULT_END_TEXTS,
    ) : SkillStep

    /** Set the text of an input field; a null [target] means the first input field on screen. */
    @Serializable
    @SerialName("type")
    data class Type(
        val target: NodeSelector? = null,
        val text: String,
    ) : SkillStep

    /**
     * Checkpoint: the screen must show [text] (accessibility tree first, then OCR of a screenshot
     * when [ocr] is on), otherwise the run stops before doing anything else.
     */
    @Serializable
    @SerialName("verify")
    data class Verify(
        val text: String,
        val ocr: Boolean = true,
        @SerialName("timeout_ms") val timeoutMs: Long = DEFAULT_VERIFY_TIMEOUT_MS,
    ) : SkillStep

    /** Wait until a control appears. */
    @Serializable
    @SerialName("wait")
    data class Wait(
        val target: NodeSelector,
        @SerialName("timeout_ms") val timeoutMs: Long = DEFAULT_WAIT_TIMEOUT_MS,
    ) : SkillStep

    @Serializable
    @SerialName("scroll")
    data class Scroll(
        val direction: SkillScrollDirection = SkillScrollDirection.DOWN,
    ) : SkillStep

    /**
     * A picture the user picked on a screenshot (an icon, a product photo): found again by template
     * matching ([ImageMatcher]) and tapped when [tap], otherwise only required to be on screen.
     * [gray] is base64 of [templateWidth]×[templateHeight] grey levels; [width]×[height] is the
     * picked region's size on screen, which sets the scale the screen is compared at.
     */
    @Serializable
    @SerialName("image")
    data class Image(
        val gray: String,
        @SerialName("template_width") val templateWidth: Int,
        @SerialName("template_height") val templateHeight: Int,
        val width: Int,
        val height: Int,
        val tap: Boolean = true,
        val label: String = "",
        @SerialName("timeout_ms") val timeoutMs: Long = DEFAULT_WAIT_TIMEOUT_MS,
    ) : SkillStep

    @Serializable
    @SerialName("back")
    data object Back : SkillStep

    /** Stop here and let the user take over (login, captcha, payment). */
    @Serializable
    @SerialName("hand_over")
    data class HandOver(
        val reason: String = "",
    ) : SkillStep

    companion object {
        const val DEFAULT_VERIFY_TIMEOUT_MS = 6_000L
        const val DEFAULT_WAIT_TIMEOUT_MS = 8_000L
        const val DEFAULT_COLLECT_ITEMS = 200
        const val DEFAULT_COLLECT_SCROLLS = 60
        const val DEFAULT_COLLECT_PAUSE_MS = 1_500L
        val DEFAULT_END_TEXTS = listOf("没有更多", "到底了", "已经到底", "敬请期待", "No more")
    }
}

@Serializable
enum class SkillScrollDirection {
    @SerialName("up")
    UP,

    @SerialName("down")
    DOWN,
}
