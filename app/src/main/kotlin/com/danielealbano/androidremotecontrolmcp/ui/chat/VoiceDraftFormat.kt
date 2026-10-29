package com.danielealbano.androidremotecontrolmcp.ui.chat

import com.danielealbano.androidremotecontrolmcp.services.vault.VoiceDraft
import com.danielealbano.androidremotecontrolmcp.services.vault.VoiceLanguage
import com.danielealbano.androidremotecontrolmcp.services.vault.VoiceValueKind

/** Display helpers for a recognized value; kept pure so they can be tested. */
internal object VoiceDraftFormat {
    private const val CN_MOBILE_LENGTH = 11
    private const val GROUP = 4
    private const val MOBILE_HEAD = 3
    private const val MOBILE_BODY_END = 7

    /** "13812345678" → "138 1234 5678"; other numbers in groups of four; text unchanged. */
    fun forReading(draft: VoiceDraft): String {
        val v = draft.value
        val numeric = draft.kind != VoiceValueKind.TEXT && draft.kind != VoiceValueKind.EMAIL
        return when {
            !numeric -> v
            v.length == CN_MOBILE_LENGTH && v.all { it.isDigit() } -> groupMobile(v)
            else -> v.chunked(GROUP).joinToString(" ")
        }
    }

    /** 3-4-4, the way Chinese mobile numbers are printed and read aloud. */
    private fun groupMobile(v: String): String {
        val head = v.take(MOBILE_HEAD)
        val body = v.substring(MOBILE_HEAD, MOBILE_BODY_END)
        return "$head $body ${v.substring(MOBILE_BODY_END)}"
    }

    /** Screen readers read "1 3 8 …" instead of "thirteen billion …". */
    fun forScreenReader(draft: VoiceDraft): String =
        if (draft.kind == VoiceValueKind.TEXT || draft.kind == VoiceValueKind.EMAIL) {
            draft.value
        } else {
            draft.value.toList().joinToString(" ")
        }

    /** What to say to the assistant to keep something, in the recognizer's language. */
    fun examples(language: VoiceLanguage): List<String> =
        when (language) {
            VoiceLanguage.ZH -> {
                listOf(
                    "「记住我的手机号 幺三八 一二三四 五六七八」",
                    "「我的学号是 二零二一 零一零五」",
                    "「把地址改成 北京市海淀区中关村大街27号」",
                )
            }

            VoiceLanguage.EN, VoiceLanguage.EN_IN -> {
                listOf(
                    "“Remember my phone number is nine eight four five…”",
                    "“My roll number is two zero two one…”",
                    "“Change my email to john at the rate gmail dot com”",
                )
            }

            VoiceLanguage.HI -> {
                listOf(
                    "“मेरा फ़ोन नंबर नौ आठ चार पांच… है”",
                    "“याद रखो आधार नंबर दो तीन चार…”",
                    "“मेरा पता बारह एमजी रोड है”",
                )
            }
        }
}
