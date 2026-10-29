package com.danielealbano.androidremotecontrolmcp.services.remote.paseo

import com.danielealbano.androidremotecontrolmcp.services.remote.AgentCatalog
import com.danielealbano.androidremotecontrolmcp.services.remote.ProviderChoices
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteMode
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteModel
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteProvider
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** The questions about what a daemon can start: its providers, and each one's models and modes. Pure. */
object PaseoCatalogRequests {
    fun providers(requestId: String): String = ask("list_available_providers_request", requestId, "")

    fun models(
        requestId: String,
        provider: String,
    ): String = ask("list_provider_models_request", requestId, provider)

    fun modes(
        requestId: String,
        provider: String,
    ): String = ask("list_provider_modes_request", requestId, provider)

    private fun ask(
        type: String,
        requestId: String,
        provider: String,
    ): String =
        buildJsonObject {
            put("type", "session")
            putJsonObject("message") {
                put("type", type)
                put("requestId", requestId)
                if (provider.isNotEmpty()) put("provider", provider)
            }
        }.toString()
}

/** Reads the daemon's answers to [PaseoCatalogRequests]. Pure. */
internal object PaseoCatalogNodes {
    val ANSWERS =
        setOf("list_available_providers_response", "list_provider_models_response", "list_provider_modes_response")

    /** The answer as a reply; a provider that is switched off or broken comes back as the reply's error. */
    fun reply(payload: JsonObject): PaseoFrame.Reply =
        PaseoFrame.Reply(
            requestId = payload.text("requestId"),
            error = payload.text("error"),
            providers = payload.list("providers").mapNotNull { (it as? JsonObject)?.let(::provider) },
            models = payload.list("models").mapNotNull { (it as? JsonObject)?.let(::model) },
            modes = payload.list("modes").mapNotNull { (it as? JsonObject)?.let(::mode) },
        )

    private fun provider(node: JsonObject): RemoteProvider {
        val available = node.flag("available") == true
        return RemoteProvider(node.text("provider"), available, node.text("error"))
    }

    private fun model(node: JsonObject): RemoteModel {
        val window = (node["contextWindowMaxTokens"] as? JsonPrimitive)?.intOrNull ?: 0
        val label = node.text("label").ifEmpty { node.text("id") }
        return RemoteModel(node.text("id"), label, node.text("description"), node.flag("isDefault") == true, window)
    }

    private fun mode(node: JsonObject) =
        RemoteMode(node.text("id"), node.text("label").ifEmpty { node.text("id") }, node.text("description"))
}

/** What a Paseo daemon can start, asked of the daemon each time: nothing here is a list shipped with the app. */
internal class PaseoCatalog(
    private val ask: suspend ((String) -> String) -> Result<PaseoFrame.Reply>,
) : AgentCatalog {
    override suspend fun providers(): Result<List<RemoteProvider>> {
        val listed = ask(PaseoCatalogRequests::providers)
        return listed.map { it.providers }
    }

    override suspend fun choices(provider: String): Result<ProviderChoices> {
        val models = ask { PaseoCatalogRequests.models(it, provider) }
        val modes = ask { PaseoCatalogRequests.modes(it, provider) }
        // Models are what cannot be guessed; a provider without a list of modes still starts in its own default.
        return models.map { ProviderChoices(it.models, modes.getOrNull()?.modes.orEmpty()) }
    }
}
