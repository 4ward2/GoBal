package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.services.vault.VaultCategory
import com.danielealbano.androidremotecontrolmcp.services.vault.VaultEntry
import com.danielealbano.androidremotecontrolmcp.services.vault.VaultFillRecord
import com.danielealbano.androidremotecontrolmcp.services.vault.VaultIds
import com.danielealbano.androidremotecontrolmcp.services.vault.VaultRepository
import com.danielealbano.androidremotecontrolmcp.services.vault.VaultSlot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

/** Form state of the add / edit dialog. [value] is only populated while the dialog is open. */
data class VaultForm(
    val isNew: Boolean,
    val id: String = "",
    val label: String = "",
    val category: VaultCategory = VaultCategory.CONTACT,
    val value: String = "",
    val sensitive: Boolean = false,
    val allowedPackages: String = "",
    val error: String? = null,
) {
    override fun toString(): String = "VaultForm(isNew=$isNew, id=$id, label=$label, value=<redacted>)"
}

@HiltViewModel
class VaultViewModel
    @Inject
    constructor(
        private val repository: VaultRepository,
    ) : ViewModel() {
        val slots: StateFlow<List<VaultSlot>> = repository.slots
        val fillLog: StateFlow<List<VaultFillRecord>> = repository.fillLog

        private val _form = MutableStateFlow<VaultForm?>(null)
        val form: StateFlow<VaultForm?> = _form.asStateFlow()

        private val _loadError = MutableStateFlow<String?>(null)
        val loadError: StateFlow<String?> = _loadError.asStateFlow()

        init {
            viewModelScope.launch {
                try {
                    repository.load()
                } catch (e: IOException) {
                    _loadError.value = e.message
                }
            }
        }

        /**
         * Manual entry is the fallback for what must not be said aloud, so the form starts as a
         * password with a ready slot name; the user can still switch category.
         */
        fun startNew() {
            val taken = slots.value.map { it.id }.toSet()
            _form.value =
                VaultForm(
                    isNew = true,
                    id = VaultIds.unique(MANUAL_ID_BASE, taken),
                    category = VaultCategory.CREDENTIAL,
                    sensitive = true,
                )
        }

        /** Opens the editor without the stored value: nothing on screen can reveal it. */
        fun startEdit(id: String) {
            val slot = slots.value.firstOrNull { it.id == id } ?: return
            _form.value =
                VaultForm(
                    isNew = false,
                    id = slot.id,
                    label = slot.label,
                    category = slot.category,
                    value = "",
                    sensitive = slot.sensitive,
                    allowedPackages = slot.allowedPackages.joinToString(", "),
                )
        }

        fun updateForm(transform: (VaultForm) -> VaultForm) {
            _form.value = _form.value?.let { transform(it).copy(error = null) }
        }

        fun dismissForm() {
            _form.value = null
        }

        fun save() {
            val current = _form.value ?: return
            if (current.isNew && slots.value.any { it.id == current.id.trim() }) {
                _form.value = current.copy(error = "槽位名已存在")
                return
            }
            val entry =
                VaultEntry(
                    id = current.id.trim(),
                    label = current.label.trim(),
                    category = current.category,
                    value = current.value,
                    sensitive = current.sensitive || current.category == VaultCategory.CREDENTIAL,
                    allowedPackages =
                        current.allowedPackages
                            .split(',', '，', ' ', '\n')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .distinct(),
                )
            viewModelScope.launch {
                // Editing with an empty value keeps the stored one; it is never shown on screen.
                val keep = if (!current.isNew && entry.value.isEmpty()) repository.reveal(entry.id)?.value else null
                repository
                    .upsert(if (keep != null) entry.copy(value = keep) else entry)
                    .onSuccess { _form.value = null }
                    .onFailure { e -> _form.value = current.copy(error = e.message ?: "保存失败") }
            }
        }

        fun delete(id: String) {
            viewModelScope.launch { repository.delete(id) }
        }

        /** Wipes the whole vault; offered when the vault files can no longer be decrypted. */
        fun resetVault() {
            viewModelScope.launch {
                repository.reset()
                _loadError.value = null
            }
        }

        private companion object {
            const val MANUAL_ID_BASE = "password"
        }
    }
