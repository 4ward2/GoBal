package com.danielealbano.androidremotecontrolmcp.services.vault

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** A value waiting for the user's "对，保存". [error] is set when storing it failed. */
data class PendingVaultSave(
    val draft: VoiceDraft,
    val saving: Boolean = false,
    val error: String? = null,
)

fun VoiceDraft.toEntry(): VaultEntry =
    VaultEntry(
        id = slotId,
        label = label,
        category = category,
        value = value,
        sensitive = sensitive,
        allowedPackages = replaces?.allowedPackages.orEmpty(),
    )

/**
 * Where a sentence routed to the vault waits for confirmation, whichever input it came from: the
 * chat microphone, typed text, or the power-button wake. Holding it here keeps the value out of
 * intents, chat history and anything sent to a language model.
 */
@Singleton
class VaultSaveRequests
    @Inject
    constructor(
        private val repository: VaultRepository,
    ) {
        private val _pending = MutableStateFlow<PendingVaultSave?>(null)
        val pending: StateFlow<PendingVaultSave?> = _pending.asStateFlow()

        /** Replaces whatever was waiting: the latest sentence is the one the user is looking at. */
        fun offer(draft: VoiceDraft) {
            _pending.value = PendingVaultSave(draft)
        }

        fun dismiss() {
            val current = _pending.value
            if (current != null && !current.saving) _pending.compareAndSet(current, null)
        }

        /**
         * Stores the waiting value. Safe to call twice: only the first call saves.
         *
         * @return the label that was saved, or null when nothing was saved.
         */
        suspend fun confirm(): String? {
            val current = _pending.value?.takeUnless { it.saving }
            val claimed = current != null && _pending.compareAndSet(current, current.copy(saving = true, error = null))
            if (!claimed || current == null) return null
            return repository.upsert(current.draft.toEntry()).fold(
                onSuccess = {
                    _pending.value = null
                    current.draft.label
                },
                onFailure = { e ->
                    _pending.value = current.copy(saving = false, error = e.message ?: "保存失败")
                    null
                },
            )
        }
    }
