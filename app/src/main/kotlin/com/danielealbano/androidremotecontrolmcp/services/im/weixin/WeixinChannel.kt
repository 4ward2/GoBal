package com.danielealbano.androidremotecontrolmcp.services.im.weixin

import android.content.Context
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.BuildConfig
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.assistant.AssistantDesk
import com.danielealbano.androidremotecontrolmcp.services.assistant.DeskMessage
import com.danielealbano.androidremotecontrolmcp.services.im.render.ChannelRenderer
import com.danielealbano.androidremotecontrolmcp.services.im.render.ChannelTraits
import com.danielealbano.androidremotecontrolmcp.services.im.render.OutboundPart
import com.danielealbano.androidremotecontrolmcp.services.link.LinkCenter
import com.danielealbano.androidremotecontrolmcp.services.link.ManagedLink
import com.danielealbano.androidremotecontrolmcp.services.remote.ChannelHooks
import com.danielealbano.androidremotecontrolmcp.services.remote.KeystoreSealer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Which messages are let through to the assistant. Pure. */
object WeixinGate {
    /**
     * Only what the owner (the one who scanned the login code) wrote, one to one. A message from
     * anyone else, from a group, or written by a bot is dropped without an answer: answering would
     * tell a stranger that something listens here.
     */
    fun admits(
        message: WeixinMessage,
        ownerId: String,
    ): Boolean {
        val fromOwner = ownerId.isNotEmpty() && message.from == ownerId
        return fromOwner && message.type == WeixinWire.USER_MESSAGE && message.groupId.isEmpty()
    }

    /** What the assistant is given: the owner's own words, and what came along marked as such. */
    fun toDesk(message: WeixinMessage): DeskMessage {
        val attached = if (message.hasMedia) MEDIA_NOTE else ""
        val material = listOf(message.quoted, attached).filter { it.isNotEmpty() }.joinToString("\n")
        return DeskMessage(message.said, material, message.createdAt)
    }

    private const val MEDIA_NOTE = "（对方还发来了图片、语音或文件，这里读不到它的内容）"
}

/**
 * The Weixin channel: the owner writes to their own bot in Weixin, and the same assistant as in
 * the app answers. It is one more link of the connection manager (a long poll that the service
 * holds open; no polling of its own on top), kept only while a login exists and the channel is
 * switched on.
 */
@Singleton
class WeixinChannel
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val desk: AssistantDesk,
        private val hooks: ChannelHooks,
        private val links: LinkCenter,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
        private val store = WeixinStore(File(context.filesDir, FILE), KeystoreSealer(KEY_ALIAS))
        private val agent = "Brooce/" + BuildConfig.VERSION_NAME.substringBefore('+')
        val client = WeixinClient(OkHttpClient(), ioDispatcher, agent)
        private val _account = MutableStateFlow(WeixinAccount())

        /** The login as it stands; the token in it is for this package only and never shown. */
        val account: StateFlow<WeixinAccount> = _account.asStateFlow()
        private val linkOnline = MutableStateFlow(false)
        private val _trouble = MutableStateFlow("")

        /** Why the channel is not working, in the user's words; "" when it is. */
        val trouble: StateFlow<String> = _trouble.asStateFlow()
        private var polling: Job? = null

        private val link =
            object : ManagedLink {
                override val id = "weixin"
                override val label = "微信"
                override val online: StateFlow<Boolean> = linkOnline.asStateFlow()

                override fun open() {
                    polling = scope.launch { poll() }
                }

                override fun close() {
                    polling?.cancel()
                    linkOnline.value = false
                }

                // A long poll is its own heartbeat: nothing is asked, and no timer wakes the phone for it.
                override val beats = false

                override suspend fun beat(): Boolean? = null
            }

        /** Called once when the app's process starts. */
        fun start() {
            scope.launch { change { it } }
        }

        /** Replaces the stored account by [edit] of it, and keeps or drops the link accordingly. */
        @Synchronized
        fun change(edit: (WeixinAccount) -> WeixinAccount) {
            val next = edit(store.load())
            if (next.loggedIn) store.save(next) else store.clear()
            _account.value = next
            links.setChannels(if (next.loggedIn && next.enabled) listOf(link) else emptyList())
        }

        private suspend fun poll() {
            var failures = 0
            var pollMs = 0L
            while (scope.isActive && _account.value.loggedIn) {
                val updates = client.updates(_account.value, pollMs)
                val stale = updates?.code == WeixinWire.STALE_TOKEN
                val good = updates?.takeIf { it.code == 0 }
                linkOnline.value = good != null
                _trouble.value = if (stale) EXPIRED else ""
                if (good != null) {
                    failures = 0
                    pollMs = good.pollMs
                    took(good)
                } else {
                    failures++
                    // A login that no longer holds is not retried in a hurry; anything else is
                    // paced with the app's other links.
                    if (stale) delay(STALE_PAUSE_MS) else hooks.reconnects.pause(failures)
                }
            }
        }

        private fun took(updates: WeixinUpdates) {
            if (updates.messages.isNotEmpty()) hooks.heard()
            val owner = _account.value.ownerId
            val mine = updates.messages.filter { WeixinGate.admits(it, owner) }
            val dropped = updates.messages.size - mine.size
            if (dropped > 0) Log.i(TAG, "dropped $dropped message(s) not from the owner")
            val token = mine.lastOrNull { it.contextToken.isNotEmpty() }?.contextToken
            change { it.copy(cursor = updates.cursor.ifEmpty { it.cursor }, contextToken = token ?: it.contextToken) }
            // Each message is answered on its own: a long phone task must not keep "停" from being heard.
            mine.forEach { message ->
                scope.launch { desk.handle(WeixinGate.toDesk(message), _account.value.mayOperate, SOURCE, ::say) }
            }
        }

        /** Sends an answer. Weixin shows plain text only: Markdown is rendered down, long answers go in pieces. */
        private suspend fun say(text: String) {
            val parts =
                ChannelRenderer.render(text, TRAITS).flatMap { part ->
                    if (part is OutboundPart.Picture) ChannelRenderer.downgrade(part, TRAITS.maxChars) else listOf(part)
                }
            parts.filterIsInstance<OutboundPart.Text>().forEach { part ->
                val code = client.send(_account.value, part.text)
                if (code != 0) Log.w(TAG, "an answer was not taken (code ${code ?: "none"})")
            }
        }

        companion object {
            const val SOURCE = "weixin"
            private const val TAG = "MCP:Weixin"

            /** Under `files/`; listed in the backup exclusion rules. */
            const val FILE = "im/weixin.bin"
            private const val KEY_ALIAS = "phonemcp_weixin_v1"
            private const val STALE_PAUSE_MS = 3_600_000L
            private const val EXPIRED = "微信登录已经失效，需要重新扫码登录。"

            /** Plain text; pictures are not sent yet; the client this follows cuts text at 4000. */
            private val TRAITS = ChannelTraits(markdown = false, images = false, files = false, maxChars = 4_000)
        }
    }
