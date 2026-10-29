@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.im.weixin.WeixinAccount
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.WeixinStep
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.WeixinUiState
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.WeixinViewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter

/** The Weixin channel: logging in with the owner's own scan, and what the channel may do. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeixinScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WeixinViewModel = hiltViewModel(),
) {
    val account by viewModel.account.collectAsStateWithLifecycle()
    val trouble by viewModel.trouble.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_weixin_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    val back = stringResource(R.string.settings_back)
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = back)
                }
            },
            windowInsets = WindowInsets(0),
        )
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(PaddingValues(16.dp)),
            Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.weixin_about), style = MaterialTheme.typography.bodyMedium)
            if (trouble.isNotEmpty()) Text(trouble, color = MaterialTheme.colorScheme.error)
            when {
                ui.step == WeixinStep.CONFIRMING -> ConfirmCard(viewModel::finishLogin)
                account.loggedIn -> AccountCard(account, viewModel)
                else -> LoginCard(ui, viewModel)
            }
            Text(stringResource(R.string.weixin_power), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LoginCard(
    ui: WeixinUiState,
    viewModel: WeixinViewModel,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), Arrangement.spacedBy(12.dp), Alignment.CenterHorizontally) {
            when (ui.step) {
                WeixinStep.IDLE, WeixinStep.FAILED -> {
                    if (ui.error.isNotEmpty()) Text(ui.error, color = MaterialTheme.colorScheme.error)
                    Button(onClick = viewModel::startLogin) { Text(stringResource(R.string.weixin_login)) }
                }

                WeixinStep.FETCHING -> {
                    Text(stringResource(R.string.weixin_fetching))
                }

                WeixinStep.NEEDS_CODE -> {
                    CodeBox(viewModel::submitCode)
                }

                else -> {
                    QrCode(ui.qr)
                    val hint = if (ui.step == WeixinStep.SCANNED) R.string.weixin_scanned else R.string.weixin_scan
                    Text(stringResource(hint))
                    TextButton(onClick = viewModel::cancelLogin) { Text(stringResource(R.string.chat_rename_cancel)) }
                }
            }
        }
    }
}

@Composable
private fun CodeBox(onDone: (String) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    Text(stringResource(R.string.weixin_code_hint))
    OutlinedTextField(code, { code = it }, singleLine = true)
    Button(onClick = { onDone(code) }, enabled = code.isNotBlank()) { Text(stringResource(R.string.weixin_code_send)) }
}

/** The one explicit step before the login is kept: what a message from Weixin may make this phone do. */
@Composable
private fun ConfirmCard(onAnswer: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.weixin_confirm_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.weixin_confirm_body), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { onAnswer(true) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.weixin_confirm_allow))
            }
            OutlinedButton(onClick = { onAnswer(false) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.weixin_confirm_chat_only))
            }
        }
    }
}

@Composable
private fun AccountCard(
    account: WeixinAccount,
    viewModel: WeixinViewModel,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.weixin_logged_in), style = MaterialTheme.typography.titleMedium)
            Toggle(R.string.weixin_enabled, account.enabled, viewModel::setEnabled)
            Toggle(R.string.weixin_may_operate, account.mayOperate, viewModel::setMayOperate)
            val about = if (account.mayOperate) R.string.weixin_operate_on else R.string.weixin_operate_off
            Text(stringResource(about), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = viewModel::logout) { Text(stringResource(R.string.weixin_logout)) }
        }
    }
}

@Composable
private fun Toggle(
    label: Int,
    on: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = onChange)
    }
}

/** Draws [content] as a QR code, dark on white whatever the theme: a scanner needs the contrast. */
@Composable
private fun QrCode(content: String) {
    val matrix =
        remember(content) {
            runCatching { QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0) }.getOrNull()
        }
    val label = stringResource(R.string.weixin_qr_label)
    Canvas(
        Modifier
            .size(260.dp)
            .background(Color.White)
            .padding(12.dp)
            .semantics { contentDescription = label },
    ) {
        matrix?.let { draw(it, size) }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.draw(
    matrix: BitMatrix,
    area: Size,
) {
    val cell = area.width / matrix.width
    for (y in 0 until matrix.height) {
        for (x in 0 until matrix.width) {
            if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 1f, cell + 1f))
        }
    }
}
