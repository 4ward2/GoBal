package com.danielealbano.androidremotecontrolmcp.services.remote.paseo

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * The computer's daemon configuration and provider status as seen from this phone.
 * Fields shown: relay on/off, endpoint listen address, and the list of providers.
 */
data class DaemonConfigView(
    val relayEnabled: Boolean = false,
)

/**
 * Health status of a provider, from a single diagnostic request.
 * [ok] is true if the provider is working; [message] explains the status.
 */
data class ProviderHealth(
    val provider: String = "",
    val ok: Boolean = false,
    val message: String = "",
    val version: String = "",
)

/** One agent program the daemon knows, and whether it is ready to start agents (providers snapshot). */
data class ProviderStatus(
    val id: String,
    val ready: Boolean,
)

/** Reads daemon config and provider diagnostic responses. Pure. */
internal object PaseoDaemonAdminNodes {
    /**
     * Parses the response to `get_daemon_config_request`. The schema is per messages.ts:4924–4928.
     * Extracts relay.enabled, a truncated listen address (first 40 chars), and version from status.
     */
    fun configView(payload: JsonObject): DaemonConfigView {
        val config = payload.obj("config") ?: JsonObject(emptyMap())
        val relay = config.obj("relay")
        val relayEnabled = relay?.flag("enabled") ?: false

        return DaemonConfigView(relayEnabled = relayEnabled)
    }

    /**
     * Parses the response to `provider_diagnostic_request`. The schema is per messages.ts:6161–6169.
     * Returns diagnostic status: ok if diagnostic is empty (no error), message is the diagnostic string.
     */
    fun providerHealth(payload: JsonObject): ProviderHealth {
        val diagnostic = payload.text("diagnostic")
        val provider = payload.text("provider")
        val ok = diagnostic.isEmpty() || diagnostic.equals("ok", ignoreCase = true)

        return ProviderHealth(
            provider = provider,
            ok = ok,
            message = diagnostic.ifEmpty { "OK" }.take(PaseoDaemonAdmin.DIAGNOSTIC_MESSAGE_MAX_LENGTH),
            version = "",
        )
    }
}

/** The client side for daemon settings: config, provider availability, and diagnostics. */
class PaseoDaemonAdmin(
    private val rpc: PaseoRpc,
) {
    companion object {
        internal const val DIAGNOSTIC_MESSAGE_MAX_LENGTH = 120
    }

    /**
     * Asks the daemon for its current configuration: relay status, listen address.
     * Fails if the daemon is not connected or if permission is denied (daemon.read required).
     */
    suspend fun config(): Result<DaemonConfigView> =
        rpc.call("get_daemon_config_request").mapCatching { payload ->
            PaseoDaemonAdminNodes.configView(payload)
        }

    /**
     * Asks the daemon for its current list of providers.
     * The snapshot includes which providers are available.
     * Fails if the daemon is not connected or if permission is denied.
     */
    suspend fun providers(): Result<List<ProviderStatus>> =
        rpc.call("get_providers_snapshot_request").mapCatching { payload ->
            // get_providers_snapshot_response: entries is a list of {provider, status, enabled, label, …}.
            payload.list("entries").mapNotNull { entry ->
                (entry as? JsonObject)?.takeIf { it.flag("enabled") != false }?.let {
                    ProviderStatus(it.text("provider"), it.text("status") == "ready").takeIf { p -> p.id.isNotEmpty() }
                }
            }
        }

    /**
     * Refreshes the daemon's provider availability snapshot (triggers a re-probe of all providers).
     * Returns true if the refresh was triggered successfully.
     */
    suspend fun refreshProviders(): Result<Boolean> =
        rpc.call("refresh_providers_snapshot_request").mapCatching { payload ->
            payload.flag("acknowledged") ?: false
        }

    /**
     * Runs a diagnostic on a single provider and returns its health status.
     * [provider] is the provider ID (e.g., "claude", "codex").
     * Fails if the provider is not found or the diagnostic fails.
     */
    suspend fun diagnose(provider: String): Result<ProviderHealth> {
        val fields = mapOf("provider" to JsonPrimitive(provider))
        return rpc
            .call("provider_diagnostic_request", JsonObject(fields))
            .mapCatching { payload ->
                PaseoDaemonAdminNodes.providerHealth(payload)
            }
    }

    /**
     * Requests the daemon to restart its HTTP server. This is a destructive operation.
     * Fails if the daemon is not connected or if permission is denied (daemon.manage required).
     * The phone's connection to the daemon will drop during the restart and reconnect.
     */
    suspend fun restart(): Result<Unit> =
        rpc.call("restart_server_request").mapCatching {
            Unit
        }
}
