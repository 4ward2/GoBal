package com.danielealbano.androidremotecontrolmcp.services.assistant

import com.danielealbano.androidremotecontrolmcp.services.provider.ProviderSetupRequests
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillMatcher
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillRepository
import com.danielealbano.androidremotecontrolmcp.services.vault.VaultRepository
import com.danielealbano.androidremotecontrolmcp.services.vault.VaultSaveRequests
import java.io.IOException
import javax.inject.Inject

sealed interface LocalOutcome {
    /** Dealt with on the phone. [message] is shown to the user when nothing could be saved. */
    data class Handled(
        val message: String?,
    ) : LocalOutcome

    /** Not for the rules: hand it to the phone agent ([phoneControl]) or to the language model. */
    data class Forward(
        val phoneControl: Boolean,
    ) : LocalOutcome
}

/**
 * The single entry every input goes through first (chat microphone, typed text, power-button
 * wake). Vault sentences and model-service set-up end here: they are queued in [VaultSaveRequests]
 * or [ProviderSetupRequests] for confirmation and never written to chat history or sent to a model.
 */
class AssistantLocalFirst
    @Inject
    constructor(
        private val vault: VaultRepository,
        private val requests: VaultSaveRequests,
        private val providers: ProviderSetupRequests,
        private val skills: SkillRepository,
    ) {
        suspend fun handle(text: String): LocalOutcome {
            // An undecryptable vault still lets the sentence be routed; saving will report the error.
            try {
                vault.load()
            } catch (_: IOException) {
                // Slots stay empty: known slot names still route to the vault.
            }
            return when (val route = AssistantRouter.route(text, vault.slots.value)) {
                is AssistantRoute.SetUpProvider -> {
                    providers.offer(route.draft)
                    LocalOutcome.Handled(message = null)
                }

                is AssistantRoute.SaveToVault -> {
                    requests.offer(route.draft)
                    LocalOutcome.Handled(message = null)
                }

                is AssistantRoute.VaultNotSaved -> {
                    LocalOutcome.Handled(route.reason)
                }

                AssistantRoute.PhoneControl -> {
                    LocalOutcome.Forward(phoneControl = true)
                }

                AssistantRoute.Chat -> {
                    // A saved skill's name ("老样子", "点蜜雪冰城外卖") is a phone command even when it
                    // does not look like one; the agent then replays the skill instead of planning.
                    LocalOutcome.Forward(phoneControl = namesASkill(text))
                }
            }
        }

        private suspend fun namesASkill(text: String): Boolean {
            skills.load()
            return SkillMatcher.match(text, skills.skills.value) != null
        }
    }
