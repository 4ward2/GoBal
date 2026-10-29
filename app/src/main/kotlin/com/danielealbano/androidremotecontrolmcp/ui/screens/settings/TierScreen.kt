@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.AppTier
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.TierViewModel

private fun titleOf(tier: AppTier): Int =
    when (tier) {
        AppTier.SIMPLE -> R.string.tier_simple
        AppTier.OFFICE -> R.string.tier_office
        AppTier.DEVELOPER -> R.string.tier_developer
    }

private fun descriptionOf(tier: AppTier): Int =
    when (tier) {
        AppTier.SIMPLE -> R.string.tier_simple_desc
        AppTier.OFFICE -> R.string.tier_office_desc
        AppTier.DEVELOPER -> R.string.tier_developer_desc
    }

/** Settings → 其他 → 使用模式: how much of the app is shown. The developer tier is offered once unlocked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TierScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TierViewModel = hiltViewModel(),
) {
    val current by viewModel.tier.collectAsStateWithLifecycle()
    val unlocked by viewModel.developerUnlocked.collectAsStateWithLifecycle()
    val offered = AppTier.entries.filter { it != AppTier.DEVELOPER || unlocked || current == AppTier.DEVELOPER }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tier_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        val back = stringResource(R.string.settings_back)
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = back)
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).selectableGroup()) {
            offered.forEach { tier ->
                ListItem(
                    headlineContent = { Text(stringResource(titleOf(tier))) },
                    supportingContent = { Text(stringResource(descriptionOf(tier))) },
                    leadingContent = { RadioButton(selected = tier == current, onClick = null) },
                    modifier =
                        Modifier.selectable(
                            selected = tier == current,
                            role = Role.RadioButton,
                            onClick = { viewModel.set(tier) },
                        ),
                )
            }
        }
    }
}

/** Asked from the simplest tier, where there are no settings: the hidden way out, for the family helping. */
@Composable
fun TierUpgradeDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tier_upgrade_title)) },
        text = { Text(stringResource(R.string.tier_upgrade_body)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.tier_upgrade_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.tier_cancel)) } },
    )
}
