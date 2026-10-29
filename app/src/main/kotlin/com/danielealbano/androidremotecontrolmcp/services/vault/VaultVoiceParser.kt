package com.danielealbano.androidremotecontrolmcp.services.vault

/**
 * What one utterance turns into, shown to the user before anything is saved.
 *
 * @property replaces the existing slot this overwrites, or null for a new slot.
 * @property warning a plain-language doubt about the value (wrong length, bad checksum, …).
 */
data class VoiceDraft(
    val slotId: String,
    val label: String,
    val category: VaultCategory,
    val sensitive: Boolean,
    val kind: VoiceValueKind,
    val value: String,
    val replaces: VaultSlot?,
    val warning: String?,
) {
    override fun toString(): String = "VoiceDraft(slotId=$slotId, label=$label, kind=$kind, value=<redacted>)"
}

sealed interface VoiceParseResult {
    data class Parsed(
        val draft: VoiceDraft,
    ) : VoiceParseResult

    data class Failed(
        val reason: String,
    ) : VoiceParseResult
}

/**
 * Turns one spoken sentence such as 「手机号 幺三八 一二三四 五六七八」, "my phone number is nine eight
 * four five…" or "मेरा फ़ोन नंबर नौ आठ…" into a vault entry with fixed rules only: [VoiceLexicon]
 * picks the slot, [SpokenNumbers] reads the digits, [VoiceValueCleaner] checks the value. Nothing
 * leaves the phone and no language model is involved.
 */
object VaultVoiceParser {
    private const val CUSTOM_ID_BASE = "item"

    private const val EMPTY = "没有听到内容，请再说一次"
    private const val FORBIDDEN = "密码、验证码和 PIN 不要用语音录入，旁边的人会听到。请点「手动添加」"
    private const val NO_LABEL = "没听出条目名称。请先说名称，再说内容，例如「学号 二零二一零一」"
    private const val NUMERAL_HINT = "数字没听明白。请一位一位地读，例如「一三八」而不是「一百三十八」"
    private const val CREDENTIAL_SLOT = "是密码类条目，请手动修改"

    /**
     * @param existing the slots already in the vault: a known slot or an identical label is updated
     *   in place instead of being added twice.
     */
    fun parse(
        transcript: String,
        existing: List<VaultSlot>,
    ): VoiceParseResult {
        val text = VoiceEdits.stripLeading(VoiceText.clean(transcript), VoiceLexicon.LEADING_FILLERS)
        return when {
            text.isEmpty() -> VoiceParseResult.Failed(EMPTY)
            isForbidden(text) -> VoiceParseResult.Failed(FORBIDDEN)
            else -> parseKnown(text, existing) ?: parseCustom(text, existing)
        }
    }

    private fun isForbidden(text: String): Boolean {
        val pinCode = VoiceLexicon.PIN_CODE_PHRASES.any { VoiceText.containsPhrase(text, it) }
        return VoiceLexicon.FORBIDDEN_PHRASES
            .filterNot { pinCode && (it == "pin" || it == "पिन") }
            .any { VoiceText.containsPhrase(text, it) }
    }

    private fun parseKnown(
        text: String,
        existing: List<VaultSlot>,
    ): VoiceParseResult? {
        val match =
            VoiceLexicon.KEYWORDS.firstNotNullOfOrNull { (keyword, spec) ->
                VoiceText.matchAt(text, 0, keyword.text).takeIf { it > 0 }?.let { Triple(keyword, spec, it) }
            }
        return match?.let { (keyword, spec, end) ->
            val current = existing.firstOrNull { it.id == spec.id }
            if (current?.category == VaultCategory.CREDENTIAL) {
                VoiceParseResult.Failed("「${current.label}」$CREDENTIAL_SLOT")
            } else {
                draftOf(
                    Target(
                        slotId = spec.id,
                        label = current?.label ?: spec.label(keyword.language),
                        category = spec.category,
                        sensitive = spec.sensitive || current?.sensitive == true,
                        kind = spec.kind,
                        replaces = current,
                    ),
                    spokenValue = VoiceLabels.valuePart(text.substring(end)),
                )
            }
        }
    }

    private fun parseCustom(
        text: String,
        existing: List<VaultSlot>,
    ): VoiceParseResult {
        val split = VoiceLabels.split(text) ?: return VoiceParseResult.Failed(NO_LABEL)
        val (rawLabel, spokenValue) = split
        val label = VoiceEdits.tidySpaces(rawLabel).replaceFirstChar { if (it in 'a'..'z') it.uppercaseChar() else it }
        val current = existing.firstOrNull { it.label.equals(label, ignoreCase = true) }
        val (kind, category) = VoiceLabels.inferKind(rawLabel)
        return if (current?.category == VaultCategory.CREDENTIAL) {
            VoiceParseResult.Failed("「$label」$CREDENTIAL_SLOT")
        } else {
            draftOf(
                Target(
                    slotId = current?.id ?: VaultIds.unique(CUSTOM_ID_BASE, existing.map { it.id }.toSet()),
                    label = label,
                    category = current?.category ?: category,
                    sensitive =
                        current?.sensitive == true ||
                            VoiceLexicon.SENSITIVE_PHRASES.any { VoiceText.containsPhrase(rawLabel, it) },
                    kind = kind,
                    replaces = current,
                ),
                spokenValue = spokenValue,
            )
        }
    }

    /** Everything about the slot a value is going to; the value itself comes separately. */
    private data class Target(
        val slotId: String,
        val label: String,
        val category: VaultCategory,
        val sensitive: Boolean,
        val kind: VoiceValueKind,
        val replaces: VaultSlot?,
    )

    private fun draftOf(
        target: Target,
        spokenValue: String,
    ): VoiceParseResult {
        val label = target.label
        val cleaned = if (spokenValue.isEmpty()) null else VoiceValueCleaner.clean(target.kind, spokenValue)
        return when {
            spokenValue.isEmpty() -> {
                VoiceParseResult.Failed("听到了「$label」，但没听到内容。请连着名称一起说")
            }

            label.length > VaultValidation.MAX_LABEL -> {
                VoiceParseResult.Failed("条目名称太长，请说短一点")
            }

            cleaned == null -> {
                VoiceParseResult.Failed(NUMERAL_HINT)
            }

            cleaned.value.isEmpty() -> {
                VoiceParseResult.Failed("没听清「$label」的内容，请再说一次")
            }

            cleaned.value.length > VaultValidation.MAX_VALUE -> {
                VoiceParseResult.Failed("内容太长了")
            }

            else -> {
                VoiceParseResult.Parsed(
                    VoiceDraft(
                        slotId = target.slotId,
                        label = label,
                        category = target.category,
                        sensitive = target.sensitive,
                        kind = target.kind,
                        value = cleaned.value,
                        replaces = target.replaces,
                        warning = cleaned.warning,
                    ),
                )
            }
        }
    }
}

/** Slot-id helpers shared by the voice parser and the manual editor. */
object VaultIds {
    fun unique(
        base: String,
        taken: Set<String>,
    ): String {
        if (base !in taken) return base
        var n = 2
        while ("${base}_$n" in taken) n++
        return "${base}_$n"
    }
}
