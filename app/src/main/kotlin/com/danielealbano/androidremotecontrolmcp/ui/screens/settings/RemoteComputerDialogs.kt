@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ComputerRow
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ManualAddress

private const val DEFAULT_PORT = "6767"

/** What a dialog of this page says and whether it may be confirmed yet. */
private class AskWords(
    val title: Int,
    val confirm: Int,
    val ready: Boolean = true,
    /** What is typed is a credential: the window cannot be captured or shown in recents. */
    val secret: Boolean = false,
)

@Composable
private fun Ask(
    words: AskWords,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    body: @Composable () -> Unit,
) {
    val policy = if (words.secret) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = policy),
        title = { Text(stringResource(words.title)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { body() } },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = words.ready) { Text(stringResource(words.confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_rename_cancel)) } },
    )
}

@Composable
private fun Note(text: Int) {
    val faint = MaterialTheme.colorScheme.onSurfaceVariant
    Text(stringResource(text), style = MaterialTheme.typography.bodySmall, color = faint)
}

/** One line of a form. */
@Composable
private fun Field(
    value: String,
    label: Int,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(value, onChange, label = { Text(stringResource(label)) }, singleLine = true)
}

/** The pairing link is the credential: the window it is typed into cannot be captured. */
@Composable
internal fun PasteLinkDialog(
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var link by rememberSaveable { mutableStateOf("") }
    val words = AskWords(R.string.computers_paste, R.string.computers_bind, link.isNotBlank(), secret = true)
    Ask(words, { onDone(link) }, onDismiss) {
        Note(R.string.computers_paste_how)
        OutlinedTextField(link, { link = it }, modifier = Modifier.fillMaxWidth().height(140.dp))
    }
}

/** A computer reached by its address: a LAN, an overlay network the phone already joined, frp, a tunnel's domain. */
@Composable
internal fun ManualAddressDialog(
    onDone: (ManualAddress) -> Unit,
    onDismiss: () -> Unit,
) {
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf(DEFAULT_PORT) }
    var tls by rememberSaveable { mutableStateOf(false) }
    var password by rememberSaveable { mutableStateOf("") }
    val ready = host.isNotBlank() && port.toIntOrNull() != null
    val typed = ManualAddress(host, port, tls, password)
    val words = AskWords(R.string.computers_manual, R.string.computers_bind, ready, secret = true)
    Ask(words, { onDone(typed) }, onDismiss) {
        Note(R.string.computers_manual_how)
        Field(host, R.string.computers_host) { host = it }
        OutlinedTextField(
            port,
            { port = it },
            label = { Text(stringResource(R.string.computers_port)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(checked = tls, onCheckedChange = { tls = it })
            Text(stringResource(R.string.computers_tls))
        }
        OutlinedTextField(
            password,
            { password = it },
            label = { Text(stringResource(R.string.computers_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        Note(R.string.computers_manual_rule)
    }
}

@Composable
internal fun UnpairDialog(
    computer: ComputerRow,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Ask(AskWords(R.string.computers_unpair, R.string.computers_unpair), onConfirm, onDismiss) {
        Text(stringResource(R.string.computers_unpair_what, computer.name))
    }
}

/** Another relay than the one the pairing named, for someone who runs their own. */
@Composable
internal fun RelayDialog(
    computer: ComputerRow,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var endpoint by rememberSaveable { mutableStateOf(computer.relayOverride) }
    Ask(AskWords(R.string.computers_relay, R.string.chat_rename_save), { onDone(endpoint) }, onDismiss) {
        Note(R.string.computers_relay_how)
        Field(endpoint, R.string.computers_relay_address) { endpoint = it }
    }
}
