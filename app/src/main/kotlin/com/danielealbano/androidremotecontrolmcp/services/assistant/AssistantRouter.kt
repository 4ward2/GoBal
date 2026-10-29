package com.danielealbano.androidremotecontrolmcp.services.assistant

import com.danielealbano.androidremotecontrolmcp.services.chat.PhoneMcpChatLogic
import com.danielealbano.androidremotecontrolmcp.services.provider.ProviderDraft
import com.danielealbano.androidremotecontrolmcp.services.provider.ProviderIntentParser
import com.danielealbano.androidremotecontrolmcp.services.vault.SpokenNumbers
import com.danielealbano.androidremotecontrolmcp.services.vault.VaultSlot
import com.danielealbano.androidremotecontrolmcp.services.vault.VaultVoiceParser
import com.danielealbano.androidremotecontrolmcp.services.vault.VoiceDraft
import com.danielealbano.androidremotecontrolmcp.services.vault.VoiceEdits
import com.danielealbano.androidremotecontrolmcp.services.vault.VoiceLexicon
import com.danielealbano.androidremotecontrolmcp.services.vault.VoiceParseResult
import com.danielealbano.androidremotecontrolmcp.services.vault.VoiceText
import com.danielealbano.androidremotecontrolmcp.services.vault.VoiceValueKind

/** Where one sentence from the single voice / text input goes. */
sealed interface AssistantRoute {
    /**
     * Setting up a model service, or anything holding an API key: confirmed on the phone, never
     * written to chat history or sent to a model.
     */
    data class SetUpProvider(
        val draft: ProviderDraft,
    ) : AssistantRoute

    /** Personal data to keep: confirm on the phone, then store. Never reaches a language model. */
    data class SaveToVault(
        val draft: VoiceDraft,
    ) : AssistantRoute

    /**
     * Meant for the vault but not storable (a password said aloud, a misheard number). Answered
     * on the phone with [reason]; the sentence is not sent anywhere, since it may hold private data.
     */
    data class VaultNotSaved(
        val reason: String,
    ) : AssistantRoute

    /** Operate the phone: handed to the on-device agent. */
    data object PhoneControl : AssistantRoute

    /** Anything else: the language model answers. */
    data object Chat : AssistantRoute
}

/**
 * Local-first intent routing with fixed rules, the pattern of Home Assistant's "prefer handling
 * commands locally": the rules decide what they can, and only the rest goes to a language model.
 *
 * Order matters for privacy: API keys and service set-up come first, then vault intents, so a key,
 * a phone number or an ID given to the assistant is caught here and never becomes a chat message.
 */
object AssistantRouter {
    /** "记住…" style verbs that make the intent explicit. Generic ones ("保存", "add") are left out. */
    private val SAVE_VERBS =
        listOf(
            "帮我记住",
            "帮我记下",
            "帮我记一下",
            "帮我记",
            "请记住",
            "记住",
            "记下",
            "记一下",
            "帮我存一下",
            "存一下",
            "存到保险箱",
            "存进保险箱",
            "保存到保险箱",
            "录入",
            "please remember",
            "remember that",
            "remember",
            "note down",
            "save",
            "store",
            "याद रखो",
            "याद रखना",
            "yaad rakho",
            "सेव करो",
        ).sortedByDescending { it.length }

    /** "把手机号改成…" → "手机号 …". */
    private val ZH_UPDATE = Regex("^(?:把|将)?(.+?)(?:改成|换成|改为|换为|更新为|更新成|变成)(.+)$")

    /** "change my phone number to …" → "phone number …". */
    private val EN_UPDATE = Regex("^(?:please )?(?:change|update|set) (?:my )?(.+?) to (.+)$")

    private val QUESTION_WORDS =
        listOf("多少", "什么", "是啥", "哪", "吗", "what", "which", "how", "क्या", "कितना", "कौन", "kya")
    private val QUESTION_MARKS = listOf("?", "？")

    /** Text-valued slots (names, addresses) are only taken without a verb when said as "X 是 Y". */
    private val TEXT_MARKERS = listOf("是", "叫", " is ", " है", "नाम", "my name", "i am", "i'm")

    private const val PERSONAL_DIGITS = 4

    private const val SECRET_REFUSED = "密码、验证码不要说出来，旁边的人会听到。这句话没有发给 AI。请在保险箱里手动输入。"

    fun route(
        text: String,
        slots: List<VaultSlot>,
    ): AssistantRoute {
        val clean = VoiceText.clean(text)
        // First, on the raw text: cleaning folds case, and keys are case-sensitive.
        val provider = ProviderIntentParser.parse(text)
        return when {
            provider != null -> AssistantRoute.SetUpProvider(provider)
            clean.isEmpty() -> AssistantRoute.Chat
            saysASecret(clean) -> AssistantRoute.VaultNotSaved(SECRET_REFUSED)
            isQuestion(clean) -> otherwise(text)
            else -> vaultRoute(clean, slots) ?: otherwise(text)
        }
    }

    private fun otherwise(text: String): AssistantRoute =
        if (PhoneMcpChatLogic.isPhoneControlIntent(text)) AssistantRoute.PhoneControl else AssistantRoute.Chat

    private fun vaultRoute(
        clean: String,
        slots: List<VaultSlot>,
    ): AssistantRoute? {
        val afterVerb = SAVE_VERBS.firstNotNullOfOrNull { verb -> VoiceText.matchAt(clean, 0, verb).takeIf { it > 0 } }
        val rewritten = updateAsStatement(clean, slots)
        return when {
            afterVerb != null -> explicit(clean.substring(afterVerb), slots)
            rewritten != null -> explicit(rewritten, slots)
            else -> implicit(clean, slots)
        }
    }

    /** The user asked to store something: store it, or explain why not, but keep it local. */
    private fun explicit(
        rest: String,
        slots: List<VaultSlot>,
    ): AssistantRoute? =
        when (val parsed = VaultVoiceParser.parse(rest, slots)) {
            is VoiceParseResult.Parsed -> {
                AssistantRoute.SaveToVault(parsed.draft)
            }

            is VoiceParseResult.Failed -> {
                if (looksPersonal(rest)) AssistantRoute.VaultNotSaved(parsed.reason) else null
            }
        }

    /**
     * No verb: "我的手机号是 138…" is still clearly a value to keep. Only known slots qualify, numbers
     * need a few digits, and names / addresses need "是 / 叫 / is"; otherwise it is ordinary talk.
     */
    private fun implicit(
        clean: String,
        slots: List<VaultSlot>,
    ): AssistantRoute? {
        val draft = (VaultVoiceParser.parse(clean, slots) as? VoiceParseResult.Parsed)?.draft
        val known = draft != null && VoiceLexicon.SLOTS.any { it.id == draft.slotId }
        val confident =
            when {
                draft == null || !known -> false
                draft.kind == VoiceValueKind.TEXT -> TEXT_MARKERS.any { " $clean ".contains(it) }
                draft.kind == VoiceValueKind.EMAIL -> draft.warning == null
                else -> draft.value.count { it.isDigit() } >= PERSONAL_DIGITS
            }
        return if (confident) AssistantRoute.SaveToVault(requireNotNull(draft)) else null
    }

    /**
     * "把手机号改成…" becomes "手机号 …" only when the part being changed is exactly a slot name, so
     * "把手机音量改成最大" stays a phone command.
     */
    private fun updateAsStatement(
        clean: String,
        slots: List<VaultSlot>,
    ): String? {
        val match = ZH_UPDATE.matchEntire(clean) ?: EN_UPDATE.matchEntire(clean) ?: return null
        val (name, value) = match.destructured
        val bare = VoiceText.foldAll(VoiceEdits.stripLeading(name, VoiceLexicon.LEADING_FILLERS))
        val names = VoiceLexicon.KEYWORDS.map { it.first.text } + slots.map { it.label }
        return if (names.any { VoiceText.foldAll(it.lowercase()) == bare }) "${name.trim()} ${value.trim()}" else null
    }

    /** Starts with a slot name, or holds a run of digits: something private may be in the sentence. */
    private fun looksPersonal(text: String): Boolean {
        val bare = VoiceEdits.stripLeading(text, VoiceLexicon.LEADING_FILLERS)
        val namesASlot = VoiceLexicon.KEYWORDS.any { (keyword, _) -> VoiceText.matchAt(bare, 0, keyword.text) > 0 }
        return namesASlot || digitCount(text) >= PERSONAL_DIGITS
    }

    /** "我的银行卡密码是 123456": never forwarded to a language model. */
    private fun saysASecret(text: String): Boolean =
        VoiceLexicon.FORBIDDEN_PHRASES.any { VoiceText.containsPhrase(text, it) } && digitCount(text) >= PERSONAL_DIGITS

    private fun digitCount(text: String): Int = (SpokenNumbers.convert(text) ?: text).count { it in '0'..'9' }

    private fun isQuestion(clean: String): Boolean =
        QUESTION_MARKS.any { clean.contains(it) } || QUESTION_WORDS.any { VoiceText.containsPhrase(clean, it) }
}
