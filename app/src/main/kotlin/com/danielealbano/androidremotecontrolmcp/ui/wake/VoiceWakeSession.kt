package com.danielealbano.androidremotecontrolmcp.ui.wake

import android.content.Context
import android.content.Intent
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.agent.AgentRunner
import com.danielealbano.androidremotecontrolmcp.services.assistant.AssistantLocalFirst
import com.danielealbano.androidremotecontrolmcp.services.assistant.LocalOutcome
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatApiResult
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatBackend
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatBubble
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatHttpService
import com.danielealbano.androidremotecontrolmcp.services.chat.ConversationRef
import com.danielealbano.androidremotecontrolmcp.services.chat.OutgoingMessages
import com.danielealbano.androidremotecontrolmcp.services.chat.PhoneHandoff
import com.danielealbano.androidremotecontrolmcp.services.chat.PhoneMcpChatLogic
import com.danielealbano.androidremotecontrolmcp.services.chat.RootHelper
import com.danielealbano.androidremotecontrolmcp.services.chat.VoskSpeechClient
import com.danielealbano.androidremotecontrolmcp.services.localmodel.LocalModelGate
import com.danielealbano.androidremotecontrolmcp.services.localmodel.LocalModels
import com.danielealbano.androidremotecontrolmcp.services.skill.RecordMode
import com.danielealbano.androidremotecontrolmcp.services.skill.RecordingSession
import com.danielealbano.androidremotecontrolmcp.ui.MainActivity
import com.danielealbano.androidremotecontrolmcp.ui.navigation.TopLevelRoute
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.PhoneTasks
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

data class VoiceWakeUiState(
    val phase: VoiceWakeLogic.Phase = VoiceWakeLogic.Phase.Listening,
    val transcript: String = "",
    val error: String? = null,
    val shouldFinish: Boolean = false,
    /** 从聊天页「AI 录制」进来：辉光转蓝，说的话直接交给手机 agent，做成后自动归档成技能。 */
    val aiRecording: Boolean = false,
)

/** Where a wake's words can go besides the chat model: local rules, the on-device model, an AI recording. */
class VoiceWakeRouting
    @Inject
    constructor(
        val localFirst: AssistantLocalFirst,
        val localModel: LocalModelGate,
        val recording: RecordingSession,
        val agent: AgentRunner,
    )

/**
 * 一次语音唤醒的完整流程（听写 → 分派 → 回执）。
 *
 * 之所以不直接写在 ViewModel 里：唤醒界面有两条宿主路径，
 * 解锁态走 [VoiceWakeOverlayService] 的悬浮窗（Activity 必须先 finish 掉，
 * 触摸才能落到下层应用），锁屏/息屏态仍走 [VoiceWakeViewModel] + Activity。
 * 两边共用这一个 session，避免流程逻辑出现两份。
 *
 * **不是 @Singleton**：每次唤醒都要一个全新实例，否则 [started] 会挡住第二次触发。
 */
class VoiceWakeSession
    @Inject
    constructor(
        @ApplicationContext private val app: Context,
        private val backend: ChatBackend,
        private val vosk: VoskSpeechClient,
        private val routing: VoiceWakeRouting,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        private val _ui = MutableStateFlow(VoiceWakeUiState())
        val ui: StateFlow<VoiceWakeUiState> = _ui.asStateFlow()

        /**
         * 麦克风音量包络（0..1），驱动唤醒页的边缘辉光。
         *
         * 单独一条流，不并进 [VoiceWakeUiState]：它每 ~200ms 变一次，塞进 UI state
         * 会让文案那几个 Text 跟着一起重组，白白浪费。
         */
        private val _level = MutableStateFlow(0f)
        val level: StateFlow<Float> = _level.asStateFlow()

        private val started = AtomicBoolean(false)
        private val cancelled = AtomicBoolean(false)
        private var scope: CoroutineScope? = null

        private suspend fun prewarm() {
            val choice = backend.store.modelChoice()
            if (LocalModels.isLocal(choice.providerId)) routing.localModel.wake(choice.model)
        }

        fun missingMic() {
            _ui.update {
                it.copy(phase = VoiceWakeLogic.Phase.NeedMic, error = "需要麦克风权限才能语音输入")
            }
        }

        fun begin(hostScope: CoroutineScope) {
            if (!started.compareAndSet(false, true)) return
            scope = hostScope
            cancelled.set(false)
            _ui.update { it.copy(aiRecording = routing.recording.mode.value == RecordMode.AI) }
            // 当前联系人用的是本机模型：趁用户说话的这几秒先把模型加载起来。
            hostScope.launch(ioDispatcher) { prewarm() }
            hostScope.launch(ioDispatcher) {
                ChatHttpService.start(app)
                _ui.update { it.copy(phase = VoiceWakeLogic.Phase.Listening, error = null) }
                vosk.prepare { backend.loopback.ensureStt() }
                val spoken =
                    runCatching {
                        vosk.listenOnce(
                            isCancelled = { cancelled.get() },
                            onLevel = { avg -> _level.value = VoiceWakeLogic.normalizeLevel(avg) },
                        )
                    }
                _level.value = 0f
                if (cancelled.get() || spoken.exceptionOrNull()?.message == VoskSpeechClient.CANCELLED_MESSAGE) {
                    _ui.update { it.copy(shouldFinish = true) }
                    return@launch
                }
                spoken.fold(
                    onSuccess = { raw ->
                        val text = raw.trim()
                        if (text.isEmpty()) {
                            _ui.update {
                                it.copy(
                                    phase = VoiceWakeLogic.Phase.Error,
                                    error = "没听清，请再试一次",
                                )
                            }
                            finishAfterAck(ok = false, error = null)
                        } else {
                            dispatch(text)
                        }
                    },
                    onFailure = { e ->
                        _ui.update {
                            it.copy(
                                phase = VoiceWakeLogic.Phase.Error,
                                error = e.message ?: "听写失败",
                            )
                        }
                        finishAfterAck(ok = false, error = e.message ?: "听写失败")
                    },
                )
            }
        }

        fun dismiss() {
            endAiRecording()
            cancelled.set(true)
            _level.value = 0f
            _ui.update { it.copy(shouldFinish = true) }
        }

        fun cancelListen() {
            endAiRecording()
            cancelled.set(true)
        }

        private fun endAiRecording() {
            if (_ui.value.aiRecording) routing.recording.end()
        }

        private suspend fun dispatch(text: String) {
            _level.value = 0f
            val local = routing.localFirst.handle(text)
            if (local is LocalOutcome.Handled) {
                handOver(local.message)
                return
            }
            _ui.update {
                it.copy(phase = VoiceWakeLogic.Phase.Sending, transcript = text, error = null)
            }
            val user = PhoneMcpChatLogic.newBubble(role = "user", content = text)
            val at = backend.store.here()
            val pending = backend.store.loadAt(at) + user
            backend.store.saveAt(at, pending)
            // AI 录制：不管这句话像不像手机操作，都交给 agent 去做（做成后它会自动归档成技能）。
            val aiRecording = _ui.value.aiRecording
            if (aiRecording) endAiRecording()
            if (aiRecording || (local as? LocalOutcome.Forward)?.phoneControl == true) {
                // Same as the chat: always the in-app agent, so the task shows its progress.
                toPhone(text, pending, at)
                return
            }
            val apiMessages = OutgoingMessages.toModelMessages(backend.store.systemPrompt(), pending)
            val choice = backend.store.modelChoice()
            val result =
                runCatching { backend.gateway.chat(apiMessages, choice) }
                    .getOrElse { ChatApiResult(ok = false, error = it.message.orEmpty()) }
            if (result.ok) backend.presence.markAlive(choice) else backend.presence.markFailed(choice)
            if (result.ok && PhoneHandoff.taskIn(result.content) != null) {
                // The model saw a phone request the local rules missed: the phone does it.
                toPhone(text, pending, at)
            } else if (result.ok) {
                val reply = backend.store.receive(result.content)
                backend.store.saveAt(at, pending + PhoneMcpChatLogic.newBubble(role = "assistant", content = reply))
                finishAfterAck(ok = true, error = null)
            } else {
                val err = PhoneMcpChatLogic.formatChatError(result.error.ifBlank { "empty" })
                finishAfterAck(ok = false, error = err)
            }
        }

        /** Same as the chat: always the in-app agent, so the task shows its progress. */
        private suspend fun toPhone(
            text: String,
            pending: List<ChatBubble>,
            at: ConversationRef,
        ) {
            val noModel = backend.store.modelChoice().onDevice
            val started = PhoneTasks.start(noModel, RootHelper.present()) { routing.agent.start(text) }
            backend.store.saveAt(at, pending + started.bubble)
            finishAfterAck(ok = started.error == null, error = started.error)
        }

        /**
         * A vault sentence, handled on the phone: queued for confirmation in the app, which on the
         * lock screen means unlocking first. The value is never shown on the overlay or logged.
         */
        private fun handOver(message: String?) {
            if (message != null) {
                _ui.update { it.copy(phase = VoiceWakeLogic.Phase.Error, transcript = "", error = message) }
                finishAfterAck(ok = false, error = message)
                return
            }
            _ui.update { it.copy(phase = VoiceWakeLogic.Phase.Sending, transcript = CONFIRM_IN_APP, error = null) }
            val open =
                Intent(app, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_OPEN_TAB, TopLevelRoute.Chat.route)
            runCatching { app.startActivity(open) }
            finishAfterAck(ok = true, error = null)
        }

        private fun finishAfterAck(
            ok: Boolean,
            error: String?,
        ) {
            val host = scope ?: return
            host.launch(ioDispatcher) {
                _ui.update {
                    it.copy(
                        phase = if (ok) VoiceWakeLogic.Phase.Done else VoiceWakeLogic.Phase.Error,
                        error = error ?: it.error,
                    )
                }
                delay(if (ok) DONE_LINGER_MS else ERROR_LINGER_MS)
                _ui.update { it.copy(shouldFinish = true) }
            }
        }

        companion object {
            private const val CONFIRM_IN_APP = "请在手机上确认要存进保险箱的内容"
            private const val DONE_LINGER_MS = 1_100L
            private const val ERROR_LINGER_MS = 2_400L
        }
    }
