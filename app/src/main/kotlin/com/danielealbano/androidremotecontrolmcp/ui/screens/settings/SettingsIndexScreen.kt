@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.AppRegistration
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.AppTier
import com.danielealbano.androidremotecontrolmcp.ui.LocalTier
import com.danielealbano.androidremotecontrolmcp.ui.navigation.SettingsRoute

private data class Entry(
    val icon: ImageVector,
    val titleRes: Int,
    val subtitleRes: Int,
    val route: SettingsRoute,
    /** The least tier that shows it: what agents on computers and the MCP server need is the developer's. */
    val tier: AppTier = if (route in DEVELOPER_ROUTES) AppTier.DEVELOPER else AppTier.OFFICE,
)

private val DEVELOPER_ROUTES =
    setOf(
        SettingsRoute.Computers,
        SettingsRoute.Dashboard,
        SettingsRoute.Shell,
        SettingsRoute.Server,
        SettingsRoute.General,
        SettingsRoute.Access,
        SettingsRoute.Security,
        SettingsRoute.Tunnel,
        SettingsRoute.McpTools,
        SettingsRoute.ChannelSettings,
        SettingsRoute.Storage,
    )

private data class Group(
    val titleRes: Int,
    val entries: List<Entry>,
)

private val Groups =
    listOf(
        Group(
            R.string.settings_group_vault,
            listOf(
                Entry(
                    Icons.Default.Shield,
                    R.string.settings_vault_title,
                    R.string.settings_vault_subtitle,
                    SettingsRoute.Vault,
                ),
            ),
        ),
        Group(
            R.string.settings_group_assistant,
            listOf(
                Entry(
                    Icons.Default.Accessibility,
                    R.string.settings_assist_title,
                    R.string.settings_assist_subtitle,
                    SettingsRoute.Assist,
                ),
                Entry(
                    Icons.Default.AutoMode,
                    R.string.settings_skills_title,
                    R.string.settings_skills_subtitle,
                    SettingsRoute.Skills,
                ),
                Entry(
                    Icons.Default.History,
                    R.string.settings_tasks_title,
                    R.string.settings_tasks_subtitle,
                    SettingsRoute.TaskHistory,
                ),
                Entry(
                    Icons.Default.Psychology,
                    R.string.settings_memory_title,
                    R.string.settings_memory_subtitle,
                    SettingsRoute.MemoryLibrary,
                ),
                Entry(
                    Icons.Default.AppRegistration,
                    R.string.settings_grants_title,
                    R.string.settings_grants_subtitle,
                    SettingsRoute.AppGrants,
                ),
                Entry(
                    Icons.AutoMirrored.Filled.Chat,
                    R.string.settings_chat_title,
                    R.string.settings_chat_subtitle,
                    SettingsRoute.Chat,
                ),
                Entry(
                    Icons.Default.SmartToy,
                    R.string.settings_providers_title,
                    R.string.settings_providers_subtitle,
                    SettingsRoute.Providers,
                ),
            ),
        ),
        Group(
            R.string.settings_group_service,
            listOf(
                Entry(
                    Icons.Default.Computer,
                    R.string.settings_computers_title,
                    R.string.settings_computers_subtitle,
                    SettingsRoute.Computers,
                ),
                Entry(
                    Icons.Default.Dashboard,
                    R.string.remote_entry_dashboard_title,
                    R.string.remote_entry_dashboard_subtitle,
                    SettingsRoute.Dashboard,
                ),
                Entry(
                    Icons.Default.Forum,
                    R.string.settings_weixin_title,
                    R.string.settings_weixin_subtitle,
                    SettingsRoute.Weixin,
                ),
                Entry(
                    Icons.Default.Terminal,
                    R.string.settings_shell_title,
                    R.string.settings_shell_subtitle,
                    SettingsRoute.Shell,
                ),
                Entry(
                    Icons.Default.Dns,
                    R.string.settings_server_title,
                    R.string.settings_server_subtitle,
                    SettingsRoute.Server,
                ),
                Entry(
                    Icons.Default.Tune,
                    R.string.settings_general_title,
                    R.string.settings_general_subtitle,
                    SettingsRoute.General,
                ),
                Entry(
                    Icons.Default.Key,
                    R.string.settings_access_title,
                    R.string.settings_access_subtitle,
                    SettingsRoute.Access,
                ),
                Entry(
                    Icons.Default.Lock,
                    R.string.settings_security_title,
                    R.string.settings_security_subtitle,
                    SettingsRoute.Security,
                ),
                Entry(
                    Icons.Default.Cloud,
                    R.string.settings_tunnel_title,
                    R.string.settings_tunnel_subtitle,
                    SettingsRoute.Tunnel,
                ),
                Entry(
                    Icons.Default.Build,
                    R.string.settings_mcp_tools_title,
                    R.string.settings_mcp_tools_subtitle,
                    SettingsRoute.McpTools,
                ),
                Entry(
                    Icons.Default.CellTower,
                    R.string.settings_channel_title,
                    R.string.event_channel_subtitle,
                    SettingsRoute.ChannelSettings,
                ),
            ),
        ),
        Group(
            R.string.settings_group_device,
            listOf(
                Entry(
                    Icons.Default.AdminPanelSettings,
                    R.string.settings_permissions_title,
                    R.string.settings_permissions_subtitle,
                    SettingsRoute.Permissions,
                ),
                Entry(
                    Icons.Default.Folder,
                    R.string.settings_storage_title,
                    R.string.settings_storage_subtitle,
                    SettingsRoute.Storage,
                ),
            ),
        ),
        Group(
            R.string.settings_group_other,
            listOf(
                Entry(
                    Icons.Default.Palette,
                    R.string.settings_appearance_title,
                    R.string.settings_appearance_subtitle,
                    SettingsRoute.Appearance,
                ),
                Entry(
                    Icons.Default.Tune,
                    R.string.tier_title,
                    R.string.tier_subtitle,
                    SettingsRoute.Mode,
                ),
                Entry(
                    Icons.Default.Backup,
                    R.string.settings_backup_title,
                    R.string.settings_backup_subtitle,
                    SettingsRoute.Backup,
                ),
                Entry(
                    Icons.Default.Info,
                    R.string.settings_about_title,
                    R.string.settings_about_subtitle,
                    SettingsRoute.About,
                ),
            ),
        ),
    )

@Composable
fun SettingsIndexScreen(
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The simplest tier has no settings of its own: a setup page reached from the chat goes back
    // there, not to an index of things it does not show.
    val tier = LocalTier.current
    if (tier == AppTier.SIMPLE) LaunchedEffect(Unit) { onBack() }
    val shown =
        remember(tier) {
            Groups
                .map { group -> group.copy(entries = group.entries.filter { tier.atLeast(it.tier) }) }
                .filter { it.entries.isNotEmpty() }
        }
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item(key = "title") {
            // Settings is reached from the drawer, not a tab: it needs its own way back to the chat.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.settings_back),
                    )
                }
                Text(stringResource(R.string.tab_settings), style = MaterialTheme.typography.headlineLarge)
            }
        }
        items(shown, key = { it.titleRes }) { group -> SettingsGroup(group, onNavigate) }
    }
}

@Composable
private fun SettingsGroup(
    group: Group,
    onNavigate: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(top = 16.dp)) {
        Text(
            stringResource(group.titleRes),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
        )
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                group.entries.forEachIndexed { index, entry ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 68.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    SettingsEntry(entry, onClick = { onNavigate(entry.route.route) })
                }
            }
        }
    }
}

@Composable
private fun SettingsEntry(
    entry: Entry,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, color = Color.Transparent) {
        ListItem(
            headlineContent = { Text(stringResource(entry.titleRes), style = MaterialTheme.typography.titleMedium) },
            supportingContent = { Text(stringResource(entry.subtitleRes)) },
            leadingContent = {
                Box(
                    modifier =
                        Modifier
                            .size(36.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        entry.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
            trailingContent = {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}
