@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import com.danielealbano.androidremotecontrolmcp.data.model.TextSize
import com.danielealbano.androidremotecontrolmcp.data.model.ThemeMode
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.AppearanceViewModel

/** Light / dark / follow the phone, and three text sizes. Changes apply at once. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppearanceScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AppearanceViewModel = hiltViewModel(),
) {
    val appearance by viewModel.appearance.collectAsStateWithLifecycle()
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_appearance_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    val back = stringResource(R.string.settings_back)
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = back)
                }
            },
            windowInsets = WindowInsets(0),
        )
        Choice(stringResource(R.string.appearance_theme)) {
            ThemeMode.entries.forEach { mode ->
                Chip(stringResource(themeLabel(mode)), mode == appearance.theme) {
                    viewModel.update(appearance.copy(theme = mode))
                }
            }
        }
        Choice(stringResource(R.string.appearance_text_size)) {
            TextSize.entries.forEach { size ->
                Chip(stringResource(sizeLabel(size)), size == appearance.textSize) {
                    viewModel.update(appearance.copy(textSize = size))
                }
            }
        }
        Text(
            stringResource(R.string.appearance_sample),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Choice(
    title: String,
    chips: @Composable () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() } },
    )
}

@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.heightIn(min = 48.dp),
    )
}

private fun themeLabel(mode: ThemeMode): Int =
    when (mode) {
        ThemeMode.SYSTEM -> R.string.appearance_theme_system
        ThemeMode.LIGHT -> R.string.appearance_theme_light
        ThemeMode.DARK -> R.string.appearance_theme_dark
    }

private fun sizeLabel(size: TextSize): Int =
    when (size) {
        TextSize.STANDARD -> R.string.appearance_text_standard
        TextSize.LARGE -> R.string.appearance_text_large
        TextSize.EXTRA_LARGE -> R.string.appearance_text_extra_large
    }
