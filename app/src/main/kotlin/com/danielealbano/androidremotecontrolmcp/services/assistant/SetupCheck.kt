package com.danielealbano.androidremotecontrolmcp.services.assistant

import com.danielealbano.androidremotecontrolmcp.data.model.ModelChoice
import com.danielealbano.androidremotecontrolmcp.data.repository.AssistSettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatHttpServer
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteContact
import com.danielealbano.androidremotecontrolmcp.services.speech.LocalPorts
import com.danielealbano.androidremotecontrolmcp.services.speech.LocalSttModel
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** What a new user still has to set up before the assistant can do its three jobs. */
data class SetupState(
    /** A language model is connected: without one the assistant can neither talk nor act. */
    val model: Boolean = true,
    /** The accessibility service is on: without it the assistant cannot operate apps. */
    val accessibility: Boolean = true,
    /** Some speech recogniser is available: without one the microphone does nothing. */
    val speech: Boolean = true,
)

/** One thing left to set up, in the order a new user should do them. */
enum class SetupStep {
    MODEL,
    ACCESSIBILITY,
    SPEECH,
}

/** Which steps are still open. Pure. */
object SetupChecklist {
    fun missing(state: SetupState): List<SetupStep> =
        listOfNotNull(
            SetupStep.MODEL.takeUnless { state.model },
            SetupStep.ACCESSIBILITY.takeUnless { state.accessibility },
            // Voice is extra: it is only offered once the two things everything depends on are done.
            SetupStep.SPEECH.takeUnless { state.speech || !state.model || !state.accessibility },
        )

    /** Whether the contact's model choice leads anywhere, given the providers saved and the rooted helper. */
    fun modelReady(
        choice: ModelChoice,
        providerIds: Set<String>,
        helperConfigured: Boolean,
    ): Boolean =
        when {
            choice.onDevice -> helperConfigured
            RemoteContact.of(choice) != null -> true
            else -> choice.providerId in providerIds
        }
}

/** Looks at the phone to fill in a [SetupState]. */
class SetupProbe
    @Inject
    constructor(
        private val settings: AssistSettingsRepository,
        private val accessibility: AccessibilityServiceProvider,
        private val localStt: LocalSttModel,
    ) {
        /**
         * @param choice the model the current contact talks through.
         * @param helperConfigured the helper process of rooted phones has a model of its own.
         */
        suspend fun check(
            choice: ModelChoice,
            helperConfigured: Boolean,
        ): SetupState {
            val known = settings.providers.first()
            val providers = known.map { it.id }.toSet()
            return SetupState(
                model = SetupChecklist.modelReady(choice, providers, helperConfigured),
                accessibility = accessibility.isReady(),
                speech = localStt.installed() || LocalPorts.isOpen(ChatHttpServer.STT_PORT),
            )
        }
    }
