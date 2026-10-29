package com.danielealbano.androidremotecontrolmcp.services.provider

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.ModelProvider
import com.danielealbano.androidremotecontrolmcp.data.repository.AssistSettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.character.CharacterRepository
import com.danielealbano.androidremotecontrolmcp.services.chat.ModelGateway
import com.danielealbano.androidremotecontrolmcp.services.chat.ModelInfo
import com.danielealbano.androidremotecontrolmcp.services.chat.reportedContexts
import com.danielealbano.androidremotecontrolmcp.services.localmodel.LocalModels
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** What a provider's model list becomes after asking the provider. Pure. */
object ModelListMerge {
    /**
     * [stored] with the models the service lists now, or null when nothing changes. A service
     * that lists nothing (or could not be asked) changes nothing: an empty list would leave every
     * contact of that provider without a model. [inUse] models stay listed even when the service
     * no longer names them: the contact that uses one must not lose its choice silently.
     */
    fun merged(
        stored: ModelProvider,
        listed: List<ModelInfo>,
        inUse: Set<String>,
    ): ModelProvider? {
        val now = listed.map { it.id }
        val kept = stored.models.filter { it in inUse && it !in now }
        val models = now + kept
        val contexts = stored.modelContexts.filterKeys { it in kept } + listed.reportedContexts()
        val same = models.toSet() == stored.models.toSet() && contexts == stored.modelContexts
        return if (now.isEmpty() || same) null else stored.copy(models = models, modelContexts = contexts)
    }
}

/**
 * Keeps each provider's model list as the provider itself states it, without anyone pressing a
 * button: model names change by the month, and a list typed in once goes stale. Asked when the
 * app starts and when a model is about to be chosen, at most once in a while per provider.
 */
@Singleton
class ProviderModelSync
    @Inject
    constructor(
        private val settings: AssistSettingsRepository,
        private val gateway: ModelGateway,
        private val characters: CharacterRepository,
    ) {
        private val asking = Mutex()
        private val askedAt = HashMap<String, Long>()

        /** Asks every provider that is due. Safe to call often: each provider is asked once in a while. */
        suspend fun refresh() =
            asking.withLock {
                val now = System.currentTimeMillis()
                val inUse = modelsInUse()
                val due = settings.providers.first().filter { it.asked() && now - (askedAt[it.id] ?: 0) > EVERY_MS }
                due.forEach { provider ->
                    askedAt[provider.id] = now
                    val listed = gateway.listModels(provider).getOrNull().orEmpty()
                    val merged = ModelListMerge.merged(provider, listed, inUse[provider.id].orEmpty())
                    // The provider's name and two counts: nothing of its address or key.
                    val change = merged?.let { "${provider.models.size} -> ${it.models.size}" }
                    val outcome = change ?: "unchanged"
                    Log.i(TAG, "models of ${provider.name}: listed ${listed.size}, $outcome")
                    merged?.let { settings.saveProvider(it) }
                }
            }

        /** The models the assistant and the characters are set to, by provider id. */
        private suspend fun modelsInUse(): Map<String, Set<String>> {
            characters.load()
            val choices = characters.characters.value.map { it.model } + settings.assistantModel.first()
            return choices.groupBy({ it.providerId }, { it.model }).mapValues { it.value.toSet() }
        }

        /** The on-device model is no service to ask, and a provider without an address cannot be asked. */
        private fun ModelProvider.asked(): Boolean = !LocalModels.isLocal(this) && baseUrl.isNotBlank()

        private companion object {
            const val TAG = "MCP:Models"
            const val EVERY_MS = 6 * 60 * 60 * 1000L
        }
    }
