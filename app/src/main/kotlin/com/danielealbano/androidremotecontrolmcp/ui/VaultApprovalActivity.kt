@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui

import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.services.vault.VaultApprovalCoordinator
import com.danielealbano.androidremotecontrolmcp.services.vault.VaultApprovalRequest
import com.danielealbano.androidremotecontrolmcp.services.vault.VaultApprovalResult
import com.danielealbano.androidremotecontrolmcp.ui.theme.AndroidRemoteControlMcpTheme
import com.danielealbano.androidremotecontrolmcp.utils.ApiLevel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * "审批卡": shown on the physical screen when the agent wants to write a vault value into
 * another app. Sensitive slots additionally require the device credential / strong biometric
 * when the device has a secure lock screen. Leaving the screen any other way denies.
 */
@AndroidEntryPoint
class VaultApprovalActivity : ComponentActivity() {
    @Inject lateinit var coordinator: VaultApprovalCoordinator

    private var answeredFor: String? = null

    /** The request this card is showing; leaving only denies that one, never a newer one. */
    private var shownRequestId: String? = null

    /** The device-credential screen is a separate activity; don't treat it as leaving. */
    private var authenticating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContent {
            AndroidRemoteControlMcpTheme {
                val request by coordinator.pending.collectAsState()
                val current = request
                if (current == null) {
                    finish()
                } else {
                    shownRequestId = current.id
                    ApprovalCard(
                        request = current,
                        onAllow = { remember -> allow(current, remember) },
                        onDeny = { answer(current, approved = false, remember = false) },
                    )
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Leaving the card (home, recents, screen off) counts as "no".
        if (!isChangingConfigurations && !authenticating) {
            coordinator.pending.value
                ?.takeIf { it.id == shownRequestId }
                ?.let { answer(it, approved = false, remember = false) }
        }
    }

    private fun allow(
        request: VaultApprovalRequest,
        remember: Boolean,
    ) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!request.sensitive || keyguard?.isDeviceSecure != true) {
            answer(request, approved = true, remember = remember)
            return
        }
        authenticating = true
        val prompt =
            BiometricPrompt
                .Builder(this)
                .setTitle("确认填写「${request.slotLabel}」")
                .setSubtitle("填入 ${request.appLabel}")
        if (ApiLevel.atLeast(Build.VERSION_CODES.R)) {
            prompt.setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION")
            prompt.setDeviceCredentialAllowed(true)
        }
        prompt
            .build()
            .authenticate(
                CancellationSignal(),
                mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                        authenticating = false
                        answer(request, approved = true, remember = false)
                    }

                    override fun onAuthenticationError(
                        errorCode: Int,
                        errString: CharSequence?,
                    ) {
                        authenticating = false
                        answer(request, approved = false, remember = false)
                    }
                },
            )
    }

    private fun answer(
        request: VaultApprovalRequest,
        approved: Boolean,
        remember: Boolean,
    ) {
        if (answeredFor == request.id) return
        answeredFor = request.id
        coordinator.complete(request.id, VaultApprovalResult(approved = approved, remember = remember))
        finish()
    }
}

@Composable
private fun ApprovalCard(
    request: VaultApprovalRequest,
    onAllow: (remember: Boolean) -> Unit,
    onDeny: () -> Unit,
) {
    var remember by remember(request.id) { mutableStateOf(false) }
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("助手想填写你的私人信息", style = MaterialTheme.typography.titleLarge)
                Text(
                    "把「${request.slotLabel}」填入「${request.appLabel}」的输入框。" +
                        "内容由手机直接填写，助手看不到具体内容。",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(request.packageName, style = MaterialTheme.typography.bodySmall)
                if (request.sensitive) {
                    Text(
                        "这是敏感信息，每次填写都需要你确认。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (request.rememberable) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = remember,
                            onCheckedChange = { remember = it },
                            modifier = Modifier.semantics { contentDescription = "以后在此应用直接填写" },
                        )
                        Text("以后在这个应用里直接填写", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    OutlinedButton(onClick = onDeny) { Text("拒绝") }
                    Button(onClick = { onAllow(remember) }) { Text("允许") }
                }
            }
        }
    }
}
