package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import com.danielealbano.androidremotecontrolmcp.data.repository.AssistSettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatBackend
import com.danielealbano.androidremotecontrolmcp.services.chat.ContextBudget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * Keeps [NativeChatUiState.contextUsage] current for the ring by the composer's "+". The system
 * prompt and the window are re-read only when the contact (or its memory files) changes; every
 * keystroke and message then costs a string-length pass and nothing more.
 */
class ContextUsageTracker(
    private val backend: ChatBackend,
    private val settings: AssistSettingsRepository,
    private val ui: MutableStateFlow<NativeChatUiState>,
) {
    private var systemTokens = 0
    private var window = ContextBudget.DEFAULT_WINDOW

    /** Re-reads the system prompt and window (I/O), then recomputes the ring. */
    suspend fun refresh() {
        val choice = backend.store.modelChoice()
        val provider = settings.providers.first().firstOrNull { it.id == choice.providerId }
        window = ContextBudget.window(provider, choice.model)
        systemTokens = ContextBudget.tokens(backend.store.systemPrompt(sending = false))
        update()
    }

    /** Cheap: string lengths only, so it can follow every keystroke. */
    fun update() {
        val state = ui.value
        val sent = state.messages.filterNot { it.excluded }.map { it.content }
        val used = systemTokens + ContextBudget.tokens("", sent, state.draft)
        ui.update { it.copy(contextUsage = ContextBudget.ratio(used, window)) }
    }
}
