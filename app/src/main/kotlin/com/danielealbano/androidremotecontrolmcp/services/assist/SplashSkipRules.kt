package com.danielealbano.androidremotecontrolmcp.services.assist

/**
 * Plain snapshot of the accessibility-node properties the splash-ad rules look at.
 *
 * Kept free of Android types so the matching rules are unit-testable on the JVM.
 */
data class SplashNodeFacts(
    val text: String?,
    val contentDescription: String?,
    val viewIdResourceName: String?,
    val width: Int,
    val height: Int,
    val screenWidth: Int,
    val screenHeight: Int,
)

/**
 * Decides whether a node is the "skip" control of a launch (splash) advertisement.
 *
 * The label rules only accept SHORT labels made of the skip word plus an optional countdown
 * ("跳过", "跳过 3", "3s | 跳过", "Skip Ad 5"), so ordinary sentences that merely mention
 * "跳过" (e.g. "跳过此步骤将无法…") are never clicked.
 */
object SplashSkipRules {
    private const val MAX_LABEL_LENGTH = 12
    private const val MAX_WIDTH_FRACTION = 0.5
    private const val MAX_HEIGHT_FRACTION = 0.2

    private const val SKIP_WORD = "(?:跳过|跳過|skip)\\s?(?:广告|廣告|ad|ads)?"
    private const val COUNTDOWN = "[0-9\\s:：|/sS秒后>›»·]*"

    /** Skip word with an optional countdown on either side, optionally prefixed by "广告". */
    private val SKIP_LABEL = Regex("^(?:广告|廣告|ad)?$COUNTDOWN$SKIP_WORD$COUNTDOWN$", RegexOption.IGNORE_CASE)

    /**
     * Resource ids of skip buttons in the common ad SDKs (e.g. `tt_splash_skip_btn`, `ksad_skip_view`,
     * `gdt_skip`). "jump" is deliberately NOT matched: SDKs use it for the ad's click-through button.
     */
    private val SKIP_VIEW_ID = Regex("skip|tiaoguo", RegexOption.IGNORE_CASE)

    private val COUNTDOWN_ONLY = Regex("^[0-9\\s:：|/sS秒]*$")

    private val WHITESPACE = Regex("\\s+")

    /** True when [facts] looks like the skip control of a splash ad. */
    fun isSkipControl(facts: SplashNodeFacts): Boolean {
        val label = normalizedLabel(facts)
        return isSmallEnough(facts) && (hasSkipLabel(label) || hasSkipViewId(facts.viewIdResourceName, label))
    }

    private fun hasSkipLabel(label: String?): Boolean {
        val shortEnough = label != null && label.length <= MAX_LABEL_LENGTH
        return shortEnough && SKIP_LABEL.matches(label.orEmpty())
    }

    private fun hasSkipViewId(
        viewIdResourceName: String?,
        label: String?,
    ): Boolean {
        val viewId = viewIdResourceName?.substringAfter(":id/") ?: return false
        val labelIsNeutral = label == null || COUNTDOWN_ONLY.matches(label)
        return labelIsNeutral && SKIP_VIEW_ID.containsMatchIn(viewId)
    }

    private fun isSmallEnough(facts: SplashNodeFacts): Boolean {
        val hasSize = facts.width > 0 && facts.height > 0
        val screenKnown = facts.screenWidth > 0 && facts.screenHeight > 0
        val fitsLimits =
            facts.width <= facts.screenWidth * MAX_WIDTH_FRACTION &&
                facts.height <= facts.screenHeight * MAX_HEIGHT_FRACTION
        return hasSize && (!screenKnown || fitsLimits)
    }

    private fun normalizedLabel(facts: SplashNodeFacts): String? {
        val raw = facts.text?.takeIf { it.isNotBlank() } ?: facts.contentDescription?.takeIf { it.isNotBlank() }
        return raw?.replace(WHITESPACE, " ")?.trim()
    }
}
