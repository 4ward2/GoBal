package com.danielealbano.androidremotecontrolmcp.services.skill

import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData

/**
 * Screens a replay must hand to the user instead of working around: a risk-control check
 * (美团 Yoda slider, "依次点击" puzzles) or a login page. A replay never solves either; it stops
 * and says so, rather than timing out on "control not found" or swiping across a slider.
 */
object ScreenBlockers {
    private val CAPTCHA =
        listOf("滑块验证", "向右滑动", "拖动滑块", "完成拼图", "安全验证", "人机验证", "请完成验证", "请依次点击", "最短线连接")
    private val LOGIN = listOf("欢迎登录", "获取短信验证码")

    const val CAPTCHA_REASON = "App 弹出了人机验证，需要你自己完成，完成后再说一遍"
    const val LOGIN_REASON = "App 要求登录，需要你自己登录，登录后再说一遍"

    /** [reason] for a screen given as its words alone (the in-app agent reads the screen as text). */
    fun reasonInText(text: String): String? {
        val shown = SelectorMatcher.squeeze(text)
        return when {
            CAPTCHA.any { shown.contains(SelectorMatcher.squeeze(it)) } -> CAPTCHA_REASON
            LOGIN.any { shown.contains(SelectorMatcher.squeeze(it)) } -> LOGIN_REASON
            else -> null
        }
    }

    /** Why the screen needs the user, or null when nothing blocks the replay. */
    fun reason(windows: List<WindowData>): String? =
        when {
            CAPTCHA.any { SelectorMatcher.screenShows(windows, it) } -> CAPTCHA_REASON
            LOGIN.any { SelectorMatcher.screenShows(windows, it) } -> LOGIN_REASON
            else -> null
        }
}
