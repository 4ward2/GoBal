package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.data.model.ModelProvider
import com.danielealbano.androidremotecontrolmcp.data.model.ProviderKind
import com.danielealbano.androidremotecontrolmcp.data.repository.AssistSettingsRepository
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.chat.ModelGateway
import com.danielealbano.androidremotecontrolmcp.services.chat.reportedContexts
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** The add / edit dialog's fields. */
data class ProviderForm(
    val id: String = "",
    val name: String = "",
    val kind: ProviderKind = ProviderKind.OPENAI,
    val baseUrl: String = "",
    val apiKey: String = "",
    val models: String = "",
    val contextWindow: String = "",
    /** Each model's context window as the service reported it on the last fetch. */
    val contexts: Map<String, Int> = emptyMap(),
) {
    val isNew: Boolean get() = id.isBlank()

    val valid: Boolean get() = name.isNotBlank() && baseUrl.trim().startsWith("http")

    fun toProvider(): ModelProvider {
        val listed =
            models
                .split(',', '\n', '，')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        return ModelProvider(
            id = id.ifBlank { UUID.randomUUID().toString() },
            name = name.trim(),
            kind = kind,
            baseUrl = baseUrl.trim().trimEnd('/'),
            apiKey = apiKey.trim(),
            contextWindow = contextWindow.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0,
            models = listed,
            modelContexts = contexts.filterKeys { it in listed },
        )
    }

    companion object {
        fun of(provider: ModelProvider) =
            ProviderForm(
                provider.id,
                provider.name,
                provider.kind,
                provider.baseUrl,
                provider.apiKey,
                provider.models.joinToString(", "),
                provider.contextWindow
                    .takeIf { it > 0 }
                    ?.toString()
                    .orEmpty(),
                provider.modelContexts,
            )
    }
}

data class ProviderUiState(
    val providers: List<ModelProvider> = emptyList(),
    val form: ProviderForm? = null,
    val fetching: Boolean = false,
    val notice: String? = null,
)

/** 设置 → 服务商: the language-model services every contact can pick a model from. */
@HiltViewModel
class ProviderViewModel
    @Inject
    constructor(
        private val settings: AssistSettingsRepository,
        private val gateway: ModelGateway,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val _ui = MutableStateFlow(ProviderUiState())
        val ui: StateFlow<ProviderUiState> = _ui.asStateFlow()

        init {
            viewModelScope.launch(ioDispatcher) {
                settings.providers.collect { p -> _ui.update { it.copy(providers = p) } }
            }
        }

        fun startAdd() {
            _ui.update { it.copy(form = ProviderForm()) }
        }

        fun startEdit(provider: ModelProvider) {
            _ui.update { it.copy(form = ProviderForm.of(provider)) }
        }

        fun edit(change: (ProviderForm) -> ProviderForm) {
            _ui.update { state -> state.copy(form = state.form?.let(change)) }
        }

        fun cancel() {
            _ui.update { it.copy(form = null, fetching = false) }
        }

        fun save() {
            val form = _ui.value.form?.takeIf { it.valid } ?: return
            viewModelScope.launch(ioDispatcher) {
                settings.saveProvider(form.toProvider())
                _ui.update { it.copy(form = null) }
            }
        }

        fun delete(id: String) {
            viewModelScope.launch(ioDispatcher) { settings.deleteProvider(id) }
        }

        /** Asks the provider in the form for its model list and fills the models field with it. */
        fun fetchModels() {
            val form = _ui.value.form?.takeIf { it.valid } ?: return
            _ui.update { it.copy(fetching = true) }
            viewModelScope.launch(ioDispatcher) {
                val result = gateway.listModels(form.toProvider())
                result.fold(
                    onSuccess = { models ->
                        _ui.update { state ->
                            state.copy(
                                fetching = false,
                                form =
                                    state.form?.copy(
                                        models = models.joinToString(", ") { it.id },
                                        contexts = models.reportedContexts(),
                                    ),
                                notice = "已拉取 ${models.size} 个对话模型",
                            )
                        }
                    },
                    onFailure = { e -> _ui.update { it.copy(fetching = false, notice = e.message ?: "拉取失败") } },
                )
            }
        }

        fun noticeShown() {
            _ui.update { it.copy(notice = null) }
        }
    }
