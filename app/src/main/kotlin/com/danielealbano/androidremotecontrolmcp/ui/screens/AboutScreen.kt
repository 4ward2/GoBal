@file:Suppress("FunctionNaming", "LongMethod")

package com.danielealbano.androidremotecontrolmcp.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.BuildConfig
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.AppTier
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.TierViewModel

private const val GITHUB_URL = "https://github.com/4ward2/GoBal"
private const val LICENSE_URL = "https://github.com/danielealbano/android-remote-control-mcp/blob/main/LICENSE"
private const val ISSUES_URL = "https://github.com/4ward2/GoBal/issues"
private const val UPSTREAM_URL = "https://github.com/danielealbano/android-remote-control-mcp"
private const val DBIP_URL = "https://db-ip.com"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    tierViewModel: TierViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val tier by tierViewModel.tier.collectAsStateWithLifecycle()
    // Like the phone's own developer options: the version line tapped seven times unlocks the developer tier.
    var taps by remember { mutableIntStateOf(0) }
    val onVersionTap = {
        if (tier != AppTier.DEVELOPER) {
            taps++
            val left = DEVELOPER_TAPS - taps
            when {
                left <= 0 -> {
                    tierViewModel.set(AppTier.DEVELOPER)
                    Toast.makeText(context, R.string.tier_developer_on, Toast.LENGTH_SHORT).show()
                }

                left <= DEVELOPER_HINT_FROM -> {
                    val more = context.getString(R.string.tier_developer_more, left)
                    Toast.makeText(context, more, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.tab_about)) },
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                }
            },
            windowInsets = WindowInsets(0),
        )
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
        ) {
            // App icon + name + version
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(colorResource(R.color.ic_launcher_background)),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_launcher_foreground),
                        contentDescription = stringResource(R.string.app_name),
                        modifier = Modifier.size(72.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable(onClick = onVersionTap),
                )
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // Author section
            Text(
                text = stringResource(R.string.about_author_section),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.about_author_name),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier.clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)))
                    },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // Links section
            Text(
                text = stringResource(R.string.about_links_section),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))

            ListItem(
                headlineContent = { Text(stringResource(R.string.about_link_github)) },
                leadingContent = {
                    Icon(Icons.Default.Code, contentDescription = null)
                },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                modifier =
                    Modifier.clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)))
                    },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.about_link_license)) },
                leadingContent = {
                    Icon(Icons.Default.Description, contentDescription = null)
                },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                modifier =
                    Modifier.clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(LICENSE_URL)))
                    },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.about_link_issues)) },
                leadingContent = {
                    Icon(Icons.Default.ReportProblem, contentDescription = null)
                },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                modifier =
                    Modifier.clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ISSUES_URL)))
                    },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // Attributions section
            Text(
                text = stringResource(R.string.about_attributions_section),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.about_attribution_upstream),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier.clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UPSTREAM_URL)))
                    },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.about_attribution_dbip),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier.clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DBIP_URL)))
                    },
            )

            Spacer(Modifier.height(24.dp))

            // Footer
            Text(
                text = stringResource(R.string.about_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private const val DEVELOPER_TAPS = 7
private const val DEVELOPER_HINT_FROM = 3
