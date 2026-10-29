package com.danielealbano.androidremotecontrolmcp.services.vault

/** The input field a vault value is about to be written into. */
data class FillTarget(
    val packageName: String?,
    val editable: Boolean,
    val enabled: Boolean,
    val password: Boolean,
)

sealed interface FillDecision {
    data object Allow : FillDecision

    /** Ask the user on the phone before filling. */
    data class NeedsApproval(
        val rememberable: Boolean,
    ) : FillDecision

    data class Deny(
        val reason: String,
    ) : FillDecision
}

/**
 * Decides whether a slot may be written into a field. Pure; the caller supplies facts about
 * the live node. The model picks the field, this decides whether that pick is acceptable —
 * a prompt-injected page cannot talk the agent into pasting a password into a chat box.
 */
object VaultPolicy {
    fun decide(
        slot: VaultSlot,
        target: FillTarget,
        selfPackage: String,
        approvedForPackage: Boolean,
    ): FillDecision {
        val pkg = target.packageName
        return when {
            !target.editable -> {
                FillDecision.Deny("目标不是输入框")
            }

            !target.enabled -> {
                FillDecision.Deny("目标输入框不可用")
            }

            pkg.isNullOrBlank() -> {
                FillDecision.Deny("无法确定目标应用")
            }

            // Anything typed into this app's own chat goes straight to the model.
            pkg == selfPackage -> {
                FillDecision.Deny("不能填进助手自己的界面")
            }

            slot.allowedPackages.isNotEmpty() && pkg !in slot.allowedPackages -> {
                FillDecision.Deny("该槽位只允许填入：${slot.allowedPackages.joinToString()}")
            }

            slot.category == VaultCategory.CREDENTIAL && !target.password -> {
                FillDecision.Deny("密码类只允许填入密码框")
            }

            slot.sensitive -> {
                FillDecision.NeedsApproval(rememberable = false)
            }

            approvedForPackage -> {
                FillDecision.Allow
            }

            else -> {
                FillDecision.NeedsApproval(rememberable = true)
            }
        }
    }
}
