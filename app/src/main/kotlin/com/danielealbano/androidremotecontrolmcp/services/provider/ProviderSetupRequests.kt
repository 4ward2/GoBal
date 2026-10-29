package com.danielealbano.androidremotecontrolmcp.services.provider

import com.danielealbano.androidremotecontrolmcp.data.model.ModelChoice
import com.danielealbano.androidremotecontrolmcp.data.model.ModelProvider
import com.danielealbano.androidremotecontrolmcp.data.repository.AssistSettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.character.CharacterRepository
import com.danielealbano.androidremotecontrolmcp.services.chat.ConversationStore
import com.danielealbano.androidremotecontrolmcp.services.chat.ModelGateway
import com.danielealbano.androidremotecontrolmcp.services.chat.ModelInfo
import com.danielealbano.androidremotecontrolmcp.services.chat.PresenceMonitor
import com.danielealbano.androidremotecontrolmcp.services.provider.web.ApiUrls
import com.danielealbano.androidremotecontrolmcp.services.provider.web.DocsApiFinder
import com.danielealbano.androidremotecontrolmcp.services.provider.web.WebPages
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a sentence about a model service waits for the user's "保存", whichever input it came
 * from. Holding it here keeps the key out of intents, chat history and anything sent to a model.
 * Network calls: the service's documentation page when a link was given (read without the key),
 * and the service's own model list, made with the key only at addresses that page's company owns.
 */

/** One run of the key check: the address that answered (or the last one tried) and its result. */
private data class CheckAttempt(
    val draft: ProviderDraft,
    val result: Result<List<ModelInfo>>,
    val keyUnproven: Boolean,
)

private const val HTTP_OK_FIRST = 200
private const val HTTP_OK_LAST = 299
private val HTTP_OK = HTTP_OK_FIRST..HTTP_OK_LAST

@Singleton
class ProviderSetupRequests
    @Inject
    constructor(
        private val settings: AssistSettingsRepository,
        private val gateway: ModelGateway,
        private val store: ConversationStore,
        private val characters: CharacterRepository,
        private val presence: PresenceMonitor,
        private val pages: WebPages,
    ) {
        private val _pending = MutableStateFlow<ProviderSetup?>(null)
        val pending: StateFlow<ProviderSetup?> = _pending.asStateFlow()

        private var saved: List<ModelProvider> = emptyList()

        /** Opens the card for [draft]; the latest sentence replaces any card still open. */
        suspend fun offer(draft: ProviderDraft) {
            saved = settings.providers.first()
            val existing = ProviderSetupLogic.matchExisting(saved, draft)
            val choice = store.modelChoice()
            val filled = if (draft.name.isBlank() && existing != null) draft.copy(name = existing.name) else draft
            val reading = filled.sourceUrl.isNotBlank() && filled.baseUrl.isBlank()
            val docs = if (reading) DocsImport.Reading(ApiUrls.hostOf(filled.sourceUrl).orEmpty()) else DocsImport.None
            _pending.value =
                ProviderSetup(
                    draft = filled,
                    existing = existing,
                    useForActive = choice.onDevice,
                    activeName = store.activeCharacter()?.name ?: settings.assistantName.first(),
                    activeProviderId = choice.providerId,
                    docs = docs,
                )
        }

        /**
         * Reads the documentation page the user linked (without the key) and fills in the API
         * address it gives; its other addresses are kept to try if the first one fails. Blocking:
         * call it off the main thread.
         */
        suspend fun importDocs() {
            val start = _pending.value?.takeIf { it.docs is DocsImport.Reading } ?: return
            val source = start.draft.sourceUrl
            val page = runCatching { pages.get(source) }.getOrNull()?.takeIf { it.code in HTTP_OK }
            val finding = page?.let { DocsApiFinder.find(it.url, it.text) }
            _pending.update { setup ->
                val stillReading = setup != null && setup.draft.sourceUrl == source && setup.docs is DocsImport.Reading
                if (!stillReading || setup == null) return@update setup
                when {
                    finding == null -> {
                        setup.copy(docs = DocsImport.Failed("打不开这个网页，可以在上面手动填写地址"))
                    }

                    finding.candidates.isEmpty() -> {
                        setup.copy(docs = DocsImport.Failed("这个网页里没找到接口地址，可以在上面手动填写"))
                    }

                    else -> {
                        val draft = ProviderSetupLogic.withFinding(setup.draft, finding)
                        setup.copy(
                            draft = draft,
                            existing = ProviderSetupLogic.matchExisting(saved, draft),
                            docs = DocsImport.Found(ApiUrls.hostOf(page.url).orEmpty()),
                            alternatives = finding.candidates.drop(1),
                            check = KeyCheck.Idle,
                        )
                    }
                }
            }
        }

        /** Changes the draft; a changed address, key or format makes the last check stale. */
        fun edit(change: (ProviderDraft) -> ProviderDraft) {
            _pending.update { setup ->
                setup?.let {
                    val draft = change(it.draft)
                    val old = it.draft
                    val stale = draft.baseUrl != old.baseUrl || draft.apiKey != old.apiKey || draft.kind != old.kind
                    it.copy(
                        draft = draft,
                        existing = ProviderSetupLogic.matchExisting(saved, draft),
                        check = if (stale) KeyCheck.Idle else it.check,
                        error = null,
                    )
                }
            }
        }

        /** Choices that do not touch the draft: the model to use, whether the contact switches. */
        fun choose(change: (ProviderSetup) -> ProviderSetup) {
            _pending.update { it?.let(change) }
        }

        /**
         * Asks the service for its models with the key in the card. A result that arrives after the
         * address or key changed is dropped, since it no longer says anything about the card.
         */
        suspend fun check() {
            val start = _pending.value?.takeIf { it.canCheck && it.check != KeyCheck.Checking } ?: return
            // Only if nothing changed meanwhile: a plain write here would put back an older draft
            // over characters typed a moment ago. A newer draft brings its own check.
            if (!_pending.compareAndSet(start, start.copy(check = KeyCheck.Checking))) return
            val attempt = firstWorking(start)
            _pending.update { setup ->
                if (setup == null || setup.draft != start.draft) return@update setup
                attempt.result.fold(
                    onSuccess = { models ->
                        val preset = ProviderPresets.byId(attempt.draft.presetId)
                        val existing = ProviderSetupLogic.matchExisting(saved, attempt.draft)
                        setup.copy(
                            draft = attempt.draft,
                            existing = existing,
                            alternatives = emptyList(),
                            check = KeyCheck.Ok(models),
                            keyUnproven = attempt.keyUnproven,
                            model = ProviderSetupLogic.pickModel(models, preset, existing, attempt.draft.model),
                        )
                    },
                    onFailure = { e ->
                        setup.copy(check = KeyCheck.Failed(ProviderSetupLogic.describeFailure(e.message.orEmpty())))
                    },
                )
            }
        }

        /**
         * The card's address first, then the other addresses the documentation gave, until one
         * answers with the key. A service without a preset is also asked without the key: when its
         * list is public too, the key is only proven by the first conversation, and the card says so.
         */
        private suspend fun firstWorking(setup: ProviderSetup): CheckAttempt {
            val others = setup.alternatives.map { setup.draft.copy(baseUrl = it.baseUrl, kind = it.kind) }
            val drafts = listOf(setup.draft) + others
            var last: Result<List<ModelInfo>> = Result.failure(IllegalStateException("no address"))
            for (draft in drafts) {
                val provider = ProviderSetupLogic.toProvider(setup.copy(draft = draft), newId = "")
                last = gateway.listModels(provider)
                if (last.isSuccess) {
                    val open = draft.presetId == null && gateway.listModels(provider.copy(apiKey = "")).isSuccess
                    return CheckAttempt(draft, last, open)
                }
            }
            return CheckAttempt(setup.draft, last, keyUnproven = false)
        }

        fun dismiss() {
            val current = _pending.value
            if (current != null && !current.saving) _pending.compareAndSet(current, null)
        }

        /**
         * Saves the service, and points the active contact at it when asked. Safe to call twice:
         * only the first call saves.
         *
         * @return the saved service's name, or null when nothing was saved.
         */
        suspend fun confirm(): String? {
            val current = _pending.value?.takeIf { it.canSave }
            val claimed = current != null && _pending.compareAndSet(current, current.copy(saving = true, error = null))
            if (!claimed || current == null) return null
            val provider = ProviderSetupLogic.toProvider(current, newId = UUID.randomUUID().toString())
            return runCatching {
                settings.saveProvider(provider)
                if (current.useForActive && !current.activeAlreadyUses) useFor(provider, current)
            }.fold(
                onSuccess = {
                    _pending.value = null
                    provider.name
                },
                onFailure = { e ->
                    _pending.update { it?.copy(saving = false, error = e.message ?: "保存失败") }
                    null
                },
            )
        }

        private suspend fun useFor(
            provider: ModelProvider,
            setup: ProviderSetup,
        ) {
            val choice = ModelChoice(provider.id, setup.model.ifBlank { provider.models.firstOrNull().orEmpty() })
            val card = store.activeCharacter()
            if (card != null) characters.update(card.copy(model = choice)) else settings.updateAssistantModel(choice)
            // The key was just shown to work: no need to wait for the next probe to light the dot.
            if (setup.check is KeyCheck.Ok) presence.markAlive(choice)
        }
    }
