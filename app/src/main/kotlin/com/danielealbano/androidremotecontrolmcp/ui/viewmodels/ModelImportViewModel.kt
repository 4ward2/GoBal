package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.localmodel.GgufInfo
import com.danielealbano.androidremotecontrolmcp.services.localmodel.LocalModelPlan
import com.danielealbano.androidremotecontrolmcp.services.localmodel.LocalModelServer
import com.danielealbano.androidremotecontrolmcp.services.localmodel.LocalModelSpec
import com.danielealbano.androidremotecontrolmcp.services.localmodel.ModelHub
import com.danielealbano.androidremotecontrolmcp.services.localmodel.ModelHubClient
import com.danielealbano.androidremotecontrolmcp.services.localmodel.ModelLink
import com.danielealbano.androidremotecontrolmcp.services.localmodel.ModelLinks
import com.danielealbano.androidremotecontrolmcp.services.localmodel.RepoFile
import com.danielealbano.androidremotecontrolmcp.services.localmodel.RepoFiles
import com.danielealbano.androidremotecontrolmcp.services.localmodel.RepoModels
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The repository's file list as it is being read. */
sealed interface RepoListing {
    data object Idle : RepoListing

    data object Loading : RepoListing

    data class Failed(
        val message: String,
    ) : RepoListing

    data class Ready(
        val link: ModelLink,
        val models: RepoModels,
    ) : RepoListing
}

/**
 * The add dialog: the hub is picked first (null until then), then a link is pasted; the file list
 * is read on its own, one weights file is picked (the suggested one to start with), and the
 * file's header gives the context and memory shown before downloading.
 */
data class ModelImportUi(
    val open: Boolean = false,
    val hub: ModelHub? = null,
    val link: String = "",
    val listing: RepoListing = RepoListing.Idle,
    val weights: RepoFile? = null,
    val vision: Boolean = true,
    val header: GgufInfo? = null,
    val readingHeader: Boolean = false,
    /** The phone's total memory, to say up front when a model cannot run on it. */
    val totalMemory: Long = 0,
) {
    val ready: RepoListing.Ready? get() = listing as? RepoListing.Ready

    val projector: RepoFile? get() =
        ready
            ?.models
            ?.projectors
            ?.let { RepoFiles.preferredProjector(it) }
            ?.takeIf { vision }

    /** What would be added, once a file is picked; memory and context follow the header when read. */
    val plan: LocalModelSpec?
        get() {
            val listed = ready
            val file = weights
            return if (listed == null ||
                file == null
            ) {
                null
            } else {
                LocalModelPlan.spec(listed.link.hub, listed.link.repo, file, projector, header)
            }
        }

    val tooBig: Boolean get() = totalMemory > 0 && (plan?.ramBytes ?: 0) > totalMemory
}

@HiltViewModel
class ModelImportViewModel
    @Inject
    constructor(
        private val hubs: ModelHubClient,
        private val server: LocalModelServer,
        @param:IoDispatcher private val io: CoroutineDispatcher,
    ) : ViewModel() {
        private val _ui = MutableStateFlow(ModelImportUi())
        val ui: StateFlow<ModelImportUi> = _ui.asStateFlow()

        private var listJob: Job? = null
        private var headerJob: Job? = null

        fun open() {
            _ui.value = ModelImportUi(open = true, totalMemory = server.totalMemory())
        }

        fun close() {
            listJob?.cancel()
            headerJob?.cancel()
            _ui.value = ModelImportUi()
        }

        /** Picks (or changes) the hub; a link already pasted is read again from the new hub. */
        fun chooseHub(hub: ModelHub) {
            _ui.update { it.copy(hub = hub) }
            relist(immediately = true)
        }

        fun editLink(text: String) {
            if (text == _ui.value.link) return
            _ui.update { it.copy(link = text) }
            relist(immediately = false)
        }

        fun chooseWeights(file: RepoFile) {
            _ui.update { it.copy(weights = file) }
            readHeader(file)
        }

        fun setVision(on: Boolean) {
            _ui.update { it.copy(vision = on) }
        }

        /**
         * Reads the pasted link after a short pause in typing (a paste arrives at once, typing
         * character by character would otherwise ask the hub for every prefix).
         */
        private fun relist(immediately: Boolean) {
            listJob?.cancel()
            headerJob?.cancel()
            val state = _ui.value
            val hub = state.hub ?: return
            val link = ModelLinks.parse(state.link, hub)
            if (link == null) {
                _ui.update { it.copy(listing = RepoListing.Idle, weights = null, header = null) }
                return
            }
            // A ModelScope link while a Hugging Face hub is chosen (or the reverse) switches the hub.
            _ui.update { it.copy(hub = link.hub, listing = RepoListing.Loading, weights = null, header = null) }
            listJob =
                viewModelScope.launch(io) {
                    if (!immediately) delay(TYPING_PAUSE_MS)
                    val result = hubs.list(link)
                    result.fold(
                        onSuccess = { models ->
                            val named = models.weights.firstOrNull { it.path == link.file }
                            val pick = named ?: RepoFiles.preferred(models.weights)
                            _ui.update { it.copy(listing = RepoListing.Ready(link, models), weights = pick) }
                            pick?.let { readHeader(it) }
                        },
                        onFailure = { e -> _ui.update { it.copy(listing = RepoListing.Failed(e.message ?: "读取失败")) } },
                    )
                }
        }

        private fun readHeader(file: RepoFile) {
            headerJob?.cancel()
            val link = _ui.value.ready?.link ?: return
            _ui.update { it.copy(header = null, readingHeader = true) }
            headerJob =
                viewModelScope.launch(io) {
                    val info = hubs.header(link.hub.fileUrl(link.repo, file.path))
                    _ui.update { if (it.weights == file) it.copy(header = info, readingHeader = false) else it }
                }
        }

        private companion object {
            const val TYPING_PAUSE_MS = 600L
        }
    }
