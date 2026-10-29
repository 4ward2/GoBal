package com.danielealbano.androidremotecontrolmcp.services.provider

import com.danielealbano.androidremotecontrolmcp.data.model.ModelProvider
import com.danielealbano.androidremotecontrolmcp.services.chat.ModelInfo
import com.danielealbano.androidremotecontrolmcp.services.chat.reportedContexts
import com.danielealbano.androidremotecontrolmcp.services.provider.web.ApiCandidate
import com.danielealbano.androidremotecontrolmcp.services.provider.web.DocsFinding

/** Whether the key in the card has been tried against the service's model list. */
sealed interface KeyCheck {
    data object Idle : KeyCheck

    data object Checking : KeyCheck

    data class Ok(
        val models: List<ModelInfo>,
    ) : KeyCheck

    data class Failed(
        val message: String,
    ) : KeyCheck
}

/** Reading a documentation page to find the service's API address. */
sealed interface DocsImport {
    data object None : DocsImport

    data class Reading(
        val host: String,
    ) : DocsImport

    data class Found(
        val host: String,
    ) : DocsImport

    data class Failed(
        val message: String,
    ) : DocsImport
}

/**
 * The "添加服务商" card waiting for the user, whichever input it came from.
 *
 * @param existing the saved service this one would update (same address or same name).
 * @param activeName the contact that would switch to this service if [useForActive] is on.
 * @param activeProviderId the service that contact talks through now; blank for the phone's model.
 * @param alternatives other addresses the documentation gave, tried when the first one fails.
 * @param keyUnproven the service's model list answered without a key too, so the key itself is
 *   only proven by the first conversation.
 */
data class ProviderSetup(
    val draft: ProviderDraft,
    val existing: ModelProvider? = null,
    val check: KeyCheck = KeyCheck.Idle,
    val model: String = "",
    val useForActive: Boolean = false,
    val activeName: String = "",
    val activeProviderId: String = "",
    val docs: DocsImport = DocsImport.None,
    val alternatives: List<ApiCandidate> = emptyList(),
    val keyUnproven: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
) {
    val canSave: Boolean
        get() =
            !saving &&
                draft.name.isNotBlank() &&
                draft.baseUrl.trim().startsWith("http") &&
                draft.apiKey.isNotBlank()

    /** The contact already talks through the service being updated: nothing to switch. */
    val activeAlreadyUses: Boolean get() = existing != null && existing.id == activeProviderId

    /** Enough filled in to ask the service for its models. */
    val canCheck: Boolean get() = draft.baseUrl.trim().startsWith("http") && draft.apiKey.isNotBlank()
}

/** The card's decisions as pure functions, so they can be tested without a phone. */
object ProviderSetupLogic {
    /** The saved service a draft refers to: same address first, then same name. */
    fun matchExisting(
        providers: List<ModelProvider>,
        draft: ProviderDraft,
    ): ModelProvider? {
        val url = normalize(draft.baseUrl)
        val byUrl = providers.firstOrNull { url.isNotEmpty() && normalize(it.baseUrl) == url }
        return byUrl ?: providers.firstOrNull { draft.name.isNotBlank() && it.name.equals(draft.name.trim(), true) }
    }

    /**
     * What the documentation page says, applied to the draft: its best address (as the matching
     * preset when there is one), the service's name, and the model its examples use.
     */
    fun withFinding(
        draft: ProviderDraft,
        finding: DocsFinding,
    ): ProviderDraft {
        val best = finding.candidates.first()
        val preset = ProviderPresets.byHost(best.baseUrl)
        val placed =
            if (preset != null) {
                withPreset(draft, preset)
            } else {
                draft.copy(baseUrl = best.baseUrl, kind = best.kind, name = draft.name.ifBlank { finding.name })
            }
        return placed.copy(model = draft.model.ifBlank { finding.exampleModel })
    }

    /** Switching the service in the card fills its name, kind and address; "自定义" keeps what is typed. */
    fun withPreset(
        draft: ProviderDraft,
        preset: ProviderPreset?,
    ): ProviderDraft =
        if (preset == null) {
            draft.copy(presetId = null)
        } else {
            draft.copy(presetId = preset.id, name = preset.name, kind = preset.kind, baseUrl = preset.baseUrl)
        }

    /**
     * The model named in the user's example or the documentation if the service offers it, else
     * the preset's usual one, else the one already in use, else the first.
     */
    fun pickModel(
        models: List<ModelInfo>,
        preset: ProviderPreset?,
        existing: ModelProvider?,
        hint: String = "",
    ): String {
        val ids = models.map { it.id }
        val preferred = hint.takeIf { it in ids } ?: preset?.preferredModel?.takeIf { it in ids }
        val current = existing?.models?.firstOrNull { it in ids }
        return preferred ?: current ?: ids.firstOrNull().orEmpty()
    }

    /**
     * What gets saved. An update keeps the service's id so contacts using it keep working, and,
     * when a preset was picked (the card has no name field then), the name the user gave it.
     */
    fun toProvider(
        setup: ProviderSetup,
        newId: String,
    ): ModelProvider {
        val fetched = (setup.check as? KeyCheck.Ok)?.models.orEmpty()
        val existing = setup.existing
        val draft = setup.draft
        val reported = fetched.reportedContexts()
        return ModelProvider(
            id = existing?.id ?: newId,
            name = if (existing != null && draft.presetId != null) existing.name else draft.name.trim(),
            kind = draft.kind,
            baseUrl = draft.baseUrl.trim().trimEnd('/'),
            apiKey = draft.apiKey.trim(),
            models = fetched.map { it.id }.ifEmpty { existing?.models.orEmpty() },
            contextWindow = existing?.contextWindow ?: 0,
            modelContexts = if (fetched.isEmpty()) existing?.modelContexts.orEmpty() else reported,
        )
    }

    /**
     * A failed model-list request in words an older user can act on. The service's own reply is
     * left out: it is long, technical, and sometimes echoes part of the key.
     */
    fun describeFailure(message: String): String {
        val code =
            HTTP_STATUS
                .find(message)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
        return when (code) {
            null -> "连不上服务商，检查一下网络或地址"
            HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> "密钥不对或已经失效（HTTP $code）"
            HTTP_NOT_FOUND -> "这个地址找不到模型列表，检查一下地址（HTTP $code）"
            else -> "服务商返回了错误（HTTP $code），可以稍后再试"
        }
    }

    private fun normalize(url: String): String = url.trim().trimEnd('/').lowercase()

    private val HTTP_STATUS = Regex("^HTTP (\\d{3})")
    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_FORBIDDEN = 403
    private const val HTTP_NOT_FOUND = 404
}
