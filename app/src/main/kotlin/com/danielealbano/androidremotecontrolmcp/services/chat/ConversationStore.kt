package com.danielealbano.androidremotecontrolmcp.services.chat

import com.danielealbano.androidremotecontrolmcp.data.model.ModelChoice
import com.danielealbano.androidremotecontrolmcp.data.repository.AssistSettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.character.CharacterCard
import com.danielealbano.androidremotecontrolmcp.services.character.CharacterPrompt
import com.danielealbano.androidremotecontrolmcp.services.character.CharacterRepository
import com.danielealbano.androidremotecontrolmcp.services.character.StatusTag
import com.danielealbano.androidremotecontrolmcp.services.memory.MemoryLibrary
import com.danielealbano.androidremotecontrolmcp.services.memory.MemoryRepository
import com.danielealbano.androidremotecontrolmcp.services.memory.MemorySelection
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** One conversation on disk: whose it is ([owner], a character id or the assistant) and which of theirs. */
data class ConversationRef(
    val owner: String,
    val id: String,
)

/**
 * The conversation the app is currently having: with the plain assistant, or with the active
 * role-play character. Each character keeps its own history and gets its own system prompt, and
 * a fresh conversation with a character opens with the card's greeting. Shared by the chat screen
 * and the power-button wake so the two never disagree about who is talking.
 */
@Singleton
class ConversationStore
    @Inject
    constructor(
        private val characters: CharacterRepository,
        private val assistSettings: AssistSettingsRepository,
        private val history: PhoneMcpChatHistory,
        private val library: MemoryLibrary,
        val drafts: DraftStore,
        val catalog: ConversationCatalog,
    ) {
        suspend fun activeCharacter(): CharacterCard? {
            characters.load()
            val id = characters.activeId.value
            return id?.let { active -> characters.characters.value.firstOrNull { it.id == active } }
        }

        /** Who the active conversation is with: a character id, or the assistant (also the memory-file owner). */
        suspend fun owner(): String = activeCharacter()?.id ?: MemoryRepository.ASSISTANT

        /** Which memories the last [systemPrompt] carried, and which had to stay out. */
        @Volatile
        var memoryUse: MemorySelection = MemorySelection()
            private set

        /**
         * The card's text (or the assistant's profile) plus the memories that fit [query], the
         * newest thing the user said: chosen by the app, within what the model in use may carry.
         * With [sending] false it is only a look (how much room the prompt takes): no memory is
         * marked as used, and [memoryUse] still tells of the last prompt that was really sent.
         */
        suspend fun systemPrompt(
            query: String = "",
            sending: Boolean = true,
        ): String {
            val card = activeCharacter()
            val choice = card?.model ?: assistSettings.assistantModel.first()
            val provider = assistSettings.providers.first().firstOrNull { it.id == choice.providerId }
            val window = ContextBudget.window(provider, choice.model)
            val chosen = library.select(card?.id ?: MemoryRepository.ASSISTANT, query, window, mark = sending)
            if (sending) memoryUse = chosen
            val files = chosen.prompt
            return if (card != null) {
                CharacterPrompt.system(card, memory = files)
            } else {
                val name = assistSettings.assistantName.first()
                CharacterPrompt.assistantSystem(name, assistSettings.assistantProfile.first(), files)
            }
        }

        /** Which model answers the active conversation. */
        suspend fun modelChoice(): ModelChoice = activeCharacter()?.model ?: assistSettings.assistantModel.first()

        /** The conversation that is open right now. */
        suspend fun here(): ConversationRef = owner().let { ConversationRef(it, catalog.activeId(it)) }

        /** The conversation [at]; a character's empty conversation starts with its greeting. */
        suspend fun loadAt(at: ConversationRef): List<ChatBubble> {
            characters.load()
            val card = characters.characters.value.firstOrNull { it.id == at.owner }
            val stored = history.loadAt(at.owner, at.id)
            val greeting = card?.takeIf { stored.isEmpty() }?.let { CharacterPrompt.greeting(it) }
            return if (greeting == null) {
                stored
            } else {
                val opened = listOf(PhoneMcpChatLogic.newBubble(role = "assistant", content = greeting))
                history.saveAt(at.owner, at.id, opened)
                opened
            }
        }

        /**
         * Saves [messages] as the conversation [at]. Whoever holds messages names the conversation
         * they belong to: by the time a reply arrives the user may have opened another one, and
         * "the open conversation" would then be the wrong file to overwrite.
         */
        fun saveAt(
            at: ConversationRef,
            messages: List<ChatBubble>,
        ) = history.saveAt(at.owner, at.id, messages)

        /**
         * A model reply, ready to show: a character's `<status>` tag is taken out and remembered
         * as its new status line; the assistant's replies are only cleaned.
         */
        suspend fun receive(reply: String): String {
            val split = StatusTag.split(reply)
            val card = activeCharacter()
            if (card != null && split.status != null) characters.updateStatus(card.id, split.status)
            return split.text
        }

        /** The key the active conversation's unsent text is kept under in [drafts]. */
        suspend fun draftKey(): String = owner().let { "$it/${catalog.activeId(it)}" }

        /** Empties the active conversation; a character's restarts from its greeting. */
        suspend fun clear(): List<ChatBubble> {
            val at = here()
            saveAt(at, emptyList())
            return loadAt(at)
        }
    }
