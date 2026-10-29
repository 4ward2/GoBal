@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.AssistViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AssistViewModel = hiltViewModel(),
) {
    val config by viewModel.assistConfig.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_assist_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.settings_back),
                    )
                }
            },
            windowInsets = WindowInsets(0),
        )
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            AssistToggle(
                title = stringResource(R.string.assist_splash_skip_title),
                description = stringResource(R.string.assist_splash_skip_description),
                checked = config.splashSkipEnabled,
                onCheckedChange = viewModel::updateSplashSkipEnabled,
            )
            AssistToggle(
                title = stringResource(R.string.assist_otp_autofill_title),
                description = stringResource(R.string.assist_otp_autofill_description),
                checked = config.otpAutofillEnabled,
                onCheckedChange = viewModel::updateOtpAutofillEnabled,
            )
            SmsPermissionRow()
            Text(
                text = stringResource(R.string.assist_requirements_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun AssistToggle(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
    )
}

@Composable
private fun SmsPermissionRow() {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result -> granted = result }
    ListItem(
        headlineContent = { Text(stringResource(R.string.assist_sms_permission_title)) },
        supportingContent = {
            Text(
                stringResource(
                    if (granted) R.string.assist_sms_permission_granted else R.string.assist_sms_permission_missing,
                ),
            )
        },
        trailingContent = {
            if (!granted) {
                TextButton(onClick = { launcher.launch(Manifest.permission.READ_SMS) }) {
                    Text(stringResource(R.string.assist_sms_permission_grant))
                }
            }
        },
    )
}
