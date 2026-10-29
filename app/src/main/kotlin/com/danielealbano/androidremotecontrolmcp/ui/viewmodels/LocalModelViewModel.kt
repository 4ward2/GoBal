package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.data.model.ModelChoice
import com.danielealbano.androidremotecontrolmcp.data.repository.AssistSettingsRepository
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.character.CharacterRepository
import com.danielealbano.androidremotecontrolmcp.services.chat.ConversationStore
import com.danielealbano.androidremotecontrolmcp.services.localmodel.LocalModelFiles
import com.danielealbano.androidremotecontrolmcp.services.localmodel.LocalModelServer
import com.danielealbano.androidremotecontrolmcp.services.localmodel.LocalModelSpec
import com.danielealbano.androidremotecontrolmcp.services.localmodel.LocalModels
import com.danielealbano.androidremotecontrolmcp.services.localmodel.LocalServerStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/** One model in the list: whether it is on the phone, and how far its download is. */
data class LocalModelRow(
    val spec: LocalModelSpec,
    val installed: Boolean,
    val progress: Float?,
)

data class LocalModelUi(
    val rows: List<LocalModelRow> = emptyList(),
    val status: LocalServerStatus = LocalServerStatus.Stopped,
    val supported: Boolean = true,
    /** The model the contact being talked to uses, if it is one of these. */
    val activeModel: String? = null,
    val activeName: String = "",
    val notice: String? = null,
) {
    fun nameOf(id: String): String = rows.firstOrNull { it.spec.id == id }?.spec?.name ?: id
}

/** 设置 → 服务商 → 本机模型: add, download, start / stop, and hand a model to the current contact. */
@HiltViewModel
class LocalModelViewModel
    @Inject
    constructor(
        private val files: LocalModelFiles,
        private val server: LocalModelServer,
        private val store: ConversationStore,
        private val characters: CharacterRepository,
        private val settings: AssistSettingsRepository,
        @param:IoDispatcher private val io: CoroutineDispatcher,
    ) : ViewModel() {
        private val _ui = MutableStateFlow(LocalModelUi(supported = server.supported))
        val ui: StateFlow<LocalModelUi> = _ui.asStateFlow()

        /** Started from the screen and from [add] (on the IO thread), hence the concurrent map. */
        private val downloads = ConcurrentHashMap<String, Job>()

        /** Bumped after an add, download, cancel or delete so the models are read again from disk. */
        private val refresh = MutableStateFlow(0)

        init {
            viewModelScope.launch(io) {
                files.syncProvider()
                val specs = refresh.map { files.store.models().map { it to files.installed(it) } }
                combine(specs, files.progress, server.status) { list, progress, status ->
                    list.map { (spec, installed) -> LocalModelRow(spec, installed, progress[spec.id]) } to status
                }.collect { (rows, status) -> _ui.update { it.copy(rows = rows, status = status) } }
            }
            viewModelScope.launch(io) { refreshActive() }
        }

        fun download(spec: LocalModelSpec) {
            downloads.compute(spec.id) { _, running ->
                running?.takeIf { it.isActive } ?: viewModelScope.launch(io) {
                    files.download(spec).onFailure { e -> notify(e.message ?: "下载失败") }
                    refresh.update { it + 1 }
                }
            }
        }

        /** Records a model picked in the add dialog and starts fetching it right away. */
        fun add(spec: LocalModelSpec) {
            viewModelScope.launch(io) {
                runCatching { files.store.add(spec) }
                    .onSuccess { added ->
                        refresh.update { it + 1 }
                        download(added)
                        notify("开始下载「${added.name}」")
                    }.onFailure { e -> notify(e.message ?: "添加失败") }
            }
        }

        /** Stops the download; what arrived so far is kept and the next download continues from it. */
        fun cancelDownload(spec: LocalModelSpec) {
            downloads.remove(spec.id)?.cancel()
            refresh.update { it + 1 }
        }

        fun delete(spec: LocalModelSpec) {
            viewModelScope.launch(io) {
                val status = server.status.value
                val running = status is LocalServerStatus.Running && status.modelId == spec.id
                if (running) server.stop()
                files.delete(spec)
                refresh.update { it + 1 }
            }
        }

        /** One tap: load the model and serve it; the notice says why when it cannot. */
        fun start(spec: LocalModelSpec) {
            viewModelScope.launch(io) { server.start(spec)?.let { notify(it) } ?: notify("「${spec.name}」已就绪") }
        }

        fun stop() {
            viewModelScope.launch(io) { server.stop() }
        }

        /** The contact being talked to switches to [spec]; its next message wakes the model. */
        fun useForActive(spec: LocalModelSpec) {
            viewModelScope.launch(io) {
                val choice = ModelChoice(LocalModels.PROVIDER_ID, spec.id)
                val card = store.activeCharacter()
                if (card != null) {
                    characters.update(card.copy(model = choice))
                } else {
                    settings.updateAssistantModel(choice)
                }
                refreshActive()
                notify("「${_ui.value.activeName}」改用「${spec.name}」")
            }
        }

        fun noticeShown() {
            _ui.update { it.copy(notice = null) }
        }

        private suspend fun refreshActive() {
            val choice = store.modelChoice()
            val name = store.activeCharacter()?.name ?: settings.assistantName.first()
            val model = choice.model.takeIf { LocalModels.isLocal(choice.providerId) }
            _ui.update { it.copy(activeModel = model, activeName = name) }
        }

        private fun notify(text: String) {
            _ui.update { it.copy(notice = text) }
        }
    }
