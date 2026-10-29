package com.danielealbano.androidremotecontrolmcp.services.localmodel

import com.danielealbano.androidremotecontrolmcp.data.model.ModelProvider

/** Names shared by the embedded server, the provider list and the gateway. */
object LocalModels {
    /** The provider entry that stands for the embedded server in 设置 → 服务商. */
    const val PROVIDER_ID = "local-llama"
    const val PROVIDER_NAME = "本机模型"

    fun isLocal(provider: ModelProvider): Boolean = provider.id == PROVIDER_ID

    fun isLocal(providerId: String): Boolean = providerId == PROVIDER_ID
}

/** Where the embedded server stands. */
sealed interface LocalServerStatus {
    data object Stopped : LocalServerStatus

    data class Starting(
        val modelId: String,
    ) : LocalServerStatus

    data class Running(
        val modelId: String,
    ) : LocalServerStatus

    data class Failed(
        val message: String,
    ) : LocalServerStatus
}

/**
 * What the chat side needs from the embedded server: wake it before a request, and tell whether a
 * model is installed without starting anything (the start-up presence check must not load 6 GB).
 */
interface LocalModelGate {
    /** Makes sure [modelId] is loaded and serving; null when ready, otherwise the reason in words. */
    suspend fun wake(modelId: String): String?

    fun installed(modelId: String): Boolean

    /** The added model [modelId]: its context window and whether it can look at pictures. */
    fun spec(modelId: String): LocalModelSpec?
}
