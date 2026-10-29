package com.danielealbano.androidremotecontrolmcp.services.assistant

import android.app.KeyguardManager
import android.content.Context
import com.danielealbano.androidremotecontrolmcp.data.model.ModelChoice
import com.danielealbano.androidremotecontrolmcp.data.repository.AssistSettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.agent.AgentRun
import com.danielealbano.androidremotecontrolmcp.services.agent.AgentRunner
import com.danielealbano.androidremotecontrolmcp.services.agent.RunStatus
import com.danielealbano.androidremotecontrolmcp.services.agent.TaskOrigin
import com.danielealbano.androidremotecontrolmcp.services.character.CharacterPrompt
import com.danielealbano.androidremotecontrolmcp.services.character.CharacterRepository
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatMessage
import com.danielealbano.androidremotecontrolmcp.services.chat.ContextBudget
import com.danielealbano.androidremotecontrolmcp.services.chat.ModelGateway
import com.danielealbano.androidremotecontrolmcp.services.memory.MemoryLibrary
import com.danielealbano.androidremotecontrolmcp.services.memory.MemoryRepository
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteChats
import com.danielealbano.androidremotecontrolmcp.services.remote.RemoteContact
import com.danielealbano.androidremotecontrolmcp.services.vault.VaultRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The assistant's talking side for a message from outside: the same assistant as in the app
 * (its name, its profile, the memory library, the model the user chose for it), and the agents
 * on the paired computers.
 */
@Singleton
class DeskTalk
    @Inject
    constructor(
        private val gateway: ModelGateway,
        private val settings: AssistSettingsRepository,
        private val library: MemoryLibrary,
        private val remote: RemoteChats,
        private val characters: CharacterRepository,
    ) {
        private val turns = ArrayDeque<ChatMessage>()

        /**
         * The model the user chose for the assistant; null when none is chosen. Talking still works
         * then where the phone has its own default model (the rooted helper); operating the phone
         * from outside needs a chosen one, because only the in-app agent has the guards for it.
         */
        suspend fun model(): ModelChoice? = settings.assistantModel.first().takeIf { it.providerId.isNotBlank() }

        /** The assistant's answer to [text], or why there is none. */
        suspend fun chat(text: String): String {
            // As in the app: with no model chosen, the phone's own default answers, where there is one.
            val choice = settings.assistantModel.first()
            val provider = settings.providers.first().firstOrNull { it.id == choice.providerId }
            val memory = library.select(MemoryRepository.ASSISTANT, text, ContextBudget.window(provider, choice.model))
            val name = settings.assistantName.first()
            val system = CharacterPrompt.assistantSystem(name, settings.assistantProfile.first(), memory.prompt)
            val asked = ChatMessage("user", text)
            val messages = listOf(ChatMessage("system", system)) + synchronized(turns) { turns.toList() } + asked
            val reply = gateway.chat(messages, choice)
            if (reply.ok) remember(asked, ChatMessage("assistant", reply.content))
            val unanswered = if (choice.providerId.isBlank()) NO_MODEL else NO_ANSWER
            return if (reply.ok) reply.content else unanswered
        }

        /** What the agent on a computer whose contact is called [contact] answers to [text]. */
        suspend fun askComputer(
            contact: String,
            text: String,
        ): String {
            characters.load()
            val agents = characters.characters.value.filter { RemoteContact.of(it.model) != null }
            val card = agents.firstOrNull { it.name == contact }
            val reply = card?.let { remote.talk(it.model, text) {} }
            return when {
                card == null || reply == null -> "没有叫「$contact」的电脑代理联系人。"
                reply.ok -> reply.content
                else -> "「$contact」没有回应：${reply.error}"
            }
        }

        private fun remember(vararg said: ChatMessage) =
            synchronized(turns) {
                turns.addAll(said)
                while (turns.size > KEPT_MESSAGES) turns.removeFirst()
            }

        companion object {
            const val NO_MODEL = "还没有给助手选模型。请先在手机上的应用里选一个，我才能回答和办事。"
            private const val NO_ANSWER = "模型这会儿没有回应，过一会儿再试。"
            private const val KEPT_MESSAGES = 16
        }
    }

/**
 * Where a message from outside the app meets the assistant. It goes through the same local
 * rules as a sentence typed in the app, and a phone task runs through the same agent with the
 * same guards; what differs is only what an order from far away is not allowed to do
 * ([DeskRules]), and that every word going back out is cleared of vault values first.
 */
@Singleton
class AssistantDesk
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val localFirst: AssistantLocalFirst,
        private val runner: AgentRunner,
        private val vault: VaultRepository,
        private val talk: DeskTalk,
    ) {
        private val started = ArrayDeque<Long>()

        /**
         * Takes in [message] from the channel called [source] and answers through [say], which may
         * be called more than once (a phone task is acknowledged first and reported when it ends).
         */
        suspend fun handle(
            message: DeskMessage,
            mayOperate: Boolean,
            source: String,
            say: suspend (String) -> Unit,
        ) {
            val out: suspend (String) -> Unit = { text -> say(vault.redactor.value.redact(text)) }
            when (val decision = DeskRules.early(message) ?: byLocalRules(message, mayOperate)) {
                is DeskDecision.Say -> out(decision.text)
                DeskDecision.Stop -> out(stop())
                is DeskDecision.AskComputer -> out(talk.askComputer(decision.contact, decision.text))
                is DeskDecision.Operate -> operate(decision.goal, source, out)
                DeskDecision.Chat -> out(talk.chat(DeskRules.chatText(message)))
            }
        }

        /** The app's own local rules first: what they keep from the model in the app, they keep from it here. */
        private suspend fun byLocalRules(
            message: DeskMessage,
            mayOperate: Boolean,
        ): DeskDecision =
            when (val local = localFirst.handle(message.said)) {
                is LocalOutcome.Handled -> DeskDecision.Say(local.message ?: NEEDS_THE_PHONE)
                is LocalOutcome.Forward -> DeskRules.late(message, state(mayOperate), local.phoneControl)
            }

        private fun state(mayOperate: Boolean): DeskState {
            val locked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
            val recent = synchronized(started) { started.toList() }
            return DeskState(System.currentTimeMillis(), mayOperate, locked, runner.busy, recent)
        }

        private fun stop(): String {
            val running = runner.busy
            if (running) runner.stop()
            return if (running) "已经让它停下了。" else "现在没有在做的事。"
        }

        private suspend fun operate(
            goal: String,
            source: String,
            out: suspend (String) -> Unit,
        ) {
            val choice = talk.model()
            val id = choice?.let { runner.start(goal, TaskOrigin(source, it)) }
            if (id == null) {
                out(if (choice == null) NO_MODEL_TO_OPERATE else DeskRules.BUSY)
                return
            }
            synchronized(started) {
                started += System.currentTimeMillis()
                while (started.size > DeskRules.MAX_TASKS) started.removeFirst()
            }
            // Said before anything moves: the screen is about to leave whatever the user is looking at.
            out("收到，开始在手机上做：$goal\n屏幕会切到别的应用；想让它停就发「停」。")
            val finished = runner.runs.current
            val ended = withTimeoutOrNull(TASK_TIMEOUT_MS) { finished.first { it?.id == id && it.status.finished } }
            out(ended?.let(::report) ?: "还没做完，我先不等了；手机通知栏里能看到进度，也可以停下它。")
        }

        private fun report(run: AgentRun): String =
            when (run.status) {
                RunStatus.DONE -> "做完了：${run.result}"

                // Paying, a captcha, a login: nothing said here can wave these through.
                RunStatus.HANDED_OVER -> "这一步需要你在手机上亲自操作：${run.result}"

                // An app nobody allowed on the phone cannot be allowed from here.
                RunStatus.STOPPED -> run.result + if (NOT_ALLOWED in run.result) NEEDS_A_GRANT else ""

                else -> "没做成：${run.result}"
            }

        private companion object {
            const val NEEDS_THE_PHONE = "这句话没有发给模型。它需要你在手机上确认：打开应用就能看到。"
            const val TASK_TIMEOUT_MS = 600_000L
            const val NO_MODEL_TO_OPERATE =
                "要通过微信操作手机，得先在手机上的应用里给助手选一个服务商模型（现在用的是手机自带的默认模型，只能聊天）。"
            const val NOT_ALLOWED = "没有允许操作"
            const val NEEDS_A_GRANT = "\n某个应用第一次被操作，要先在手机上点过「始终允许」；这一步不能在微信里代办。"
        }
    }
