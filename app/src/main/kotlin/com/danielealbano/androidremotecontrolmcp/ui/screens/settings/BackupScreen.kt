@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.BackupViewModel

private const val ZIP = "application/zip"
private const val FILE_NAME = "assistant-backup.zip"

/** Save everything the user made to one file, or put such a file back. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BackupViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val save =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ZIP)) { uri ->
            uri?.let(viewModel::exportTo)
        }
    val open =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(viewModel::importFrom)
        }
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_backup_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    val back = stringResource(R.string.settings_back)
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = back)
                }
            },
            windowInsets = WindowInsets(0),
        )
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.backup_what), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.backup_what_not),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )
            val wide = Modifier.fillMaxWidth().heightIn(min = 52.dp)
            Button(onClick = { save.launch(FILE_NAME) }, enabled = !ui.working, modifier = wide) {
                Text(stringResource(R.string.backup_export))
            }
            OutlinedButton(
                onClick = { open.launch(arrayOf(ZIP, "application/octet-stream")) },
                enabled = !ui.working,
                modifier = wide.padding(top = 12.dp),
            ) {
                Text(stringResource(R.string.backup_import))
            }
            Text(
                stringResource(R.string.backup_import_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            val large = MaterialTheme.typography.bodyLarge
            ui.result?.let { Text(it, style = large, modifier = Modifier.padding(top = 20.dp)) }
        }
    }
}
