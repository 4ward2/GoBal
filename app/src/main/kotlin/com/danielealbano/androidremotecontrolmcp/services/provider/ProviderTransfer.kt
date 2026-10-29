package com.danielealbano.androidremotecontrolmcp.services.provider

import com.danielealbano.androidremotecontrolmcp.data.model.ModelChoice
import com.danielealbano.androidremotecontrolmcp.data.model.ModelProvider
import com.danielealbano.androidremotecontrolmcp.data.repository.AssistSettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.localmodel.LocalModels
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject

/** The saved providers of one phone, keys included, for carrying to another phone of the same owner. */
@Serializable
data class ProviderBundle(
    val format: Int = FORMAT,
    val providers: List<ModelProvider> = emptyList(),
    /** The model the assistant talks through, when it is one of [providers]. */
    val assistant: ModelChoice = ModelChoice(),
) {
    companion object {
        const val FORMAT = 1
    }
}

/** Reading and writing a [ProviderBundle], and what of it may be carried. Pure. */
object ProviderBundles {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    fun encode(bundle: ProviderBundle): String = json.encodeToString(ProviderBundle.serializer(), bundle)

    /** Null when [text] is not a bundle this version understands. */
    fun decode(text: String): ProviderBundle? =
        runCatching { json.decodeFromString(ProviderBundle.serializer(), text) }
            .getOrNull()
            ?.takeIf { it.format == ProviderBundle.FORMAT }

    /**
     * What leaves the phone: online providers only. The on-device model is a file of this phone,
     * and the assistant's choice travels only when it points at a provider that travels too.
     */
    fun portable(
        providers: List<ModelProvider>,
        assistant: ModelChoice,
    ): ProviderBundle {
        val carried = providers.filterNot { LocalModels.isLocal(it) }
        val choice = assistant.takeIf { chosen -> carried.any { it.id == chosen.providerId } } ?: ModelChoice()
        return ProviderBundle(providers = carried, assistant = choice)
    }
}

/** Carries the saved providers out of, and into, this phone's settings. */
class ProviderTransfer
    @Inject
    constructor(
        private val settings: AssistSettingsRepository,
    ) {
        suspend fun export(): String {
            val bundle = ProviderBundles.portable(settings.providers.first(), settings.assistantModel.first())
            return ProviderBundles.encode(bundle)
        }

        /**
         * Saves the bundle's providers (one with the same id is replaced) and, when the assistant
         * here has no usable model yet, gives it the one it had on the other phone.
         *
         * @return how many providers were saved, or null when [text] is not a bundle.
         */
        suspend fun import(text: String): Int? {
            val bundle = ProviderBundles.decode(text) ?: return null
            val carried = ProviderBundles.portable(bundle.providers, bundle.assistant)
            carried.providers.forEach { settings.saveProvider(it) }
            val current = settings.assistantModel.first()
            val known =
                settings.providers
                    .first()
                    .map { it.id }
                    .toSet()
            val unset = current.providerId !in known
            if (unset && carried.assistant.providerId in known) settings.updateAssistantModel(carried.assistant)
            return carried.providers.size
        }
    }
