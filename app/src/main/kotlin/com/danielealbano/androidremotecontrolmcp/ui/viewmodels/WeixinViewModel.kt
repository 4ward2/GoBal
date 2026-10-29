package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.im.weixin.WeixinAccount
import com.danielealbano.androidremotecontrolmcp.services.im.weixin.WeixinChannel
import com.danielealbano.androidremotecontrolmcp.services.im.weixin.WeixinClient
import com.danielealbano.androidremotecontrolmcp.services.im.weixin.WeixinQr
import com.danielealbano.androidremotecontrolmcp.services.im.weixin.WeixinQrStatus
import com.danielealbano.androidremotecontrolmcp.services.im.weixin.WeixinWire
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

/** Where the login with Weixin stands on the page. */
enum class WeixinStep {
    IDLE,
    FETCHING,

    /** The code is on screen, waiting to be scanned. */
    SHOWING,

    /** Scanned; the owner still has to confirm in Weixin. */
    SCANNED,

    /** Weixin shows a number the owner has to type here. */
    NEEDS_CODE,

    /** Logged in; the owner is asked what Weixin may make the phone do before anything is kept. */
    CONFIRMING,
    FAILED,
}

data class WeixinUiState(
    val step: WeixinStep = WeixinStep.IDLE,
    /** The link to draw as a QR code. */
    val qr: String = "",
    val error: String = "",
)

/**
 * One login with Weixin, from asking for a code to the confirmation in Weixin. It shows where
 * it stands through [ui] and ends with the confirmed login in [confirmed], or with none.
 */
internal class WeixinLoginFlow(
    private val client: WeixinClient,
    private val ui: MutableStateFlow<WeixinUiState>,
) {
    /** Where to ask, about which code, and how often a new code was needed. */
    private class Attempt(
        var qr: WeixinQr?,
        var base: String = WeixinWire.LOGIN_BASE,
        var refreshes: Int = 0,
    )

    @Volatile
    var confirmed: WeixinQrStatus? = null

    private var last = ""

    /** The number Weixin showed the owner, once typed. */
    @Volatile
    var typedCode = ""

    suspend fun run(knownTokens: List<String>) {
        val attempt = Attempt(client.qr(knownTokens))
        if (attempt.qr == null) Log.w(TAG, "login: no code from the service")
        val deadline = System.currentTimeMillis() + LOGIN_TIMEOUT_MS
        while (attempt.qr != null && System.currentTimeMillis() < deadline && confirmed == null) {
            val qr = attempt.qr ?: break
            if (ui.value.step == WeixinStep.FETCHING) ui.value = WeixinUiState(WeixinStep.SHOWING, qr.content)
            val status = client.status(attempt.base, qr.handle, typedCode)
            took(status, attempt)
            if (status.status != NEED_CODE) delay(POLL_GAP_MS)
        }
        val failed = WeixinUiState(WeixinStep.FAILED, error = NOT_DONE)
        ui.value = if (confirmed != null) WeixinUiState(WeixinStep.CONFIRMING) else failed
    }

    /** One answer about the scan, acted on. */
    private suspend fun took(
        status: WeixinQrStatus,
        attempt: Attempt,
    ) {
        // The service's own word for where the scan stands, and nothing else: no token, no id.
        if (status.status != last) Log.i(TAG, "login: " + status.status)
        last = status.status
        when (status.status) {
            "scaned" -> ui.update { it.copy(step = WeixinStep.SCANNED) }

            NEED_CODE -> awaitCode()

            "scaned_but_redirect" -> attempt.base = "https://" + status.redirectHost

            "expired", "verify_code_blocked" -> renew(attempt)

            "confirmed" -> if (status.token.isNotEmpty() && status.userId.isNotEmpty()) confirmed = status

            // This Weixin account is logged in on this phone already.
            "binded_redirect" -> attempt.qr = null
        }
    }

    /** The code ran out, or the number was typed wrong too often: a new code, a few times at most. */
    private suspend fun renew(attempt: Attempt) {
        attempt.refreshes++
        typedCode = ""
        attempt.qr = if (attempt.refreshes > MAX_REFRESHES) null else client.qr(emptyList())
        ui.value = WeixinUiState(WeixinStep.FETCHING)
    }

    /** Shows the box for the number and waits until the owner has typed it. */
    private suspend fun awaitCode() {
        typedCode = ""
        ui.update { it.copy(step = WeixinStep.NEEDS_CODE) }
        while (typedCode.isEmpty()) delay(CODE_CHECK_MS)
    }

    private companion object {
        const val LOGIN_TIMEOUT_MS = 480_000L
        const val POLL_GAP_MS = 1_000L
        const val CODE_CHECK_MS = 300L
        const val MAX_REFRESHES = 3
        const val NEED_CODE = "need_verifycode"
        const val TAG = "MCP:Weixin"
        const val NOT_DONE = "没有登录成功：二维码过期了、网络不通，或者这个微信已经登录过。可以再试一次。"
    }
}

/**
 * The Weixin page: the owner logs in by scanning with their own Weixin (nothing is logged in on
 * their behalf), and nothing is kept until they have also said what the channel may do.
 */
@HiltViewModel
class WeixinViewModel
    @Inject
    constructor(
        private val channel: WeixinChannel,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        val account: StateFlow<WeixinAccount> = channel.account
        val trouble: StateFlow<String> = channel.trouble
        private val _ui = MutableStateFlow(WeixinUiState())
        val ui: StateFlow<WeixinUiState> = _ui.asStateFlow()
        private var login: Job? = null
        private var flow = WeixinLoginFlow(channel.client, _ui)

        fun startLogin() {
            login?.cancel()
            _ui.value = WeixinUiState(WeixinStep.FETCHING)
            val known = listOfNotNull(account.value.token.takeIf { it.isNotEmpty() })
            flow = WeixinLoginFlow(channel.client, _ui)
            login = viewModelScope.launch(ioDispatcher) { flow.run(known) }
        }

        fun cancelLogin() {
            login?.cancel()
            _ui.value = WeixinUiState()
        }

        /** The number Weixin showed the owner. */
        fun submitCode(code: String) {
            _ui.update { it.copy(step = WeixinStep.SCANNED) }
            flow.typedCode = code.trim()
        }

        /** The answer to "may a message from Weixin make this phone act": the login is kept only now. */
        fun finishLogin(mayOperate: Boolean) {
            val status = flow.confirmed ?: return
            flow.confirmed = null
            val kept = WeixinAccount(status.token, status.botId, status.baseUrl, ownerId = status.userId)
            edit { kept.copy(mayOperate = mayOperate) }
            _ui.value = WeixinUiState()
        }

        fun setMayOperate(on: Boolean) = edit { it.copy(mayOperate = on) }

        fun setEnabled(on: Boolean) = edit { it.copy(enabled = on) }

        /** Deletes the login from this phone. */
        fun logout() = edit { WeixinAccount() }

        private fun edit(change: (WeixinAccount) -> WeixinAccount) {
            viewModelScope.launch(ioDispatcher) { channel.change(change) }
        }
    }
