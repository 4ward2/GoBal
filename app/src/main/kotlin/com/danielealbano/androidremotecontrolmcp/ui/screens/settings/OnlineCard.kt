@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.OnlineViewModel

/**
 * Staying connected while the app is not on screen: on unless switched off. Whether to exempt
 * the app from battery optimisation is the user's own decision, made in the system's dialog; the
 * card only says what happens without it and opens that dialog when asked to.
 */
@Composable
internal fun OnlineCard(viewModel: OnlineViewModel = hiltViewModel()) {
    val background by viewModel.background.collectAsStateWithLifecycle()
    val exempt by viewModel.exempt.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val title = MaterialTheme.typography.titleMedium
                Text(stringResource(R.string.online_title), Modifier.weight(1f), style = title)
                Switch(checked = background, onCheckedChange = viewModel::setBackground)
            }
            val about = if (background) R.string.online_on else R.string.online_off
            Text(stringResource(about), style = MaterialTheme.typography.bodyMedium)
            if (background && !exempt) {
                Text(stringResource(R.string.online_doze), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { askExemption(context) }) { Text(stringResource(R.string.online_exempt)) }
            }
        }
    }
}

/** Opens the system's own question; the answer is the user's. */
private fun askExemption(context: Context) {
    val ask =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(ask) }.onFailure { runCatching { context.startActivity(list) } }
}
