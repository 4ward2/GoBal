@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.group.Group
import com.danielealbano.androidremotecontrolmcp.services.group.GroupMessage
import com.danielealbano.androidremotecontrolmcp.services.group.GroupProtocol
import com.danielealbano.androidremotecontrolmcp.ui.chat.MessageText
import com.danielealbano.androidremotecontrolmcp.ui.navigation.TopLevelRoute
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.CandidateRow
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.GroupDraft
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.GroupView
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.GroupViewModel

/** The pages that lie over the chat besides Settings: a contact's page, or the group chats. */
@Composable
internal fun SidePage(
    route: String,
    newGroup: Boolean,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    if (route == TopLevelRoute.Group.route) {
        GroupScreen(startNew = newGroup, onBack = onBack, modifier = modifier)
    } else {
        ContactScreen(onBack, modifier)
    }
}

/**
 * Group chats: the list of groups, the page that makes a new one from whoever answers right
 * now, and a group's conversation. [startNew] opens the new-group page straight away.
 */
@Composable
fun GroupScreen(
    startNew: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GroupViewModel = hiltViewModel(),
) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val view by viewModel.view.collectAsStateWithLifecycle()
    LaunchedEffect(startNew) { if (startNew) viewModel.startDraft() }
    val open = view.group
    val current = draft
    Column(modifier.fillMaxSize()) {
        when {
            current != null -> NewGroupPage(current, viewModel)
            open != null -> GroupChatPage(open, view, viewModel)
            else -> GroupList(groups, viewModel, onBack)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Bar(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, maxLines = 1) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
            }
        },
        actions = { actions() },
        windowInsets = WindowInsets(0),
    )
}

@Composable
private fun GroupList(
    groups: List<Group>,
    viewModel: GroupViewModel,
    onBack: () -> Unit,
) {
    Bar(stringResource(R.string.group_list_title), onBack) {
        IconButton(onClick = viewModel::startDraft) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.group_new))
        }
    }
    if (groups.isEmpty()) Text(stringResource(R.string.group_none), Modifier.padding(24.dp))
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(groups, key = { it.id }) { group ->
            Card(Modifier.fillMaxWidth().clickable { viewModel.open(group.id) }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(group.name, style = MaterialTheme.typography.titleMedium)
                        Text(group.members.joinToString("、") { it.name }, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { viewModel.delete(group.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.group_delete))
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.NewGroupPage(
    draft: GroupDraft,
    viewModel: GroupViewModel,
) {
    BackHandler(onBack = viewModel::cancelDraft)
    Bar(stringResource(R.string.group_new), viewModel::cancelDraft)
    LazyColumn(
        Modifier.weight(1f, fill = true),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "name") {
            OutlinedTextField(
                draft.name,
                viewModel.drafting::rename,
                Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.group_name)) },
                singleLine = true,
            )
        }
        item(key = "state") {
            if (draft.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
            val hint = if (draft.scanning) R.string.group_scanning else R.string.group_pick
            Text(stringResource(hint), style = MaterialTheme.typography.bodySmall)
            if (!draft.scanning && draft.rows.isEmpty()) Text(stringResource(R.string.group_nobody))
        }
        items(draft.rows, key = { it.candidate.member.id }) { row -> CandidateCard(row, viewModel) }
    }
    Button(onClick = viewModel::create, enabled = draft.ready, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(stringResource(R.string.group_create))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CandidateCard(
    row: CandidateRow,
    viewModel: GroupViewModel,
) {
    val member = row.candidate.member
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = row.included, onCheckedChange = { viewModel.drafting.toggle(member.id) })
                Column(Modifier.weight(1f)) {
                    Text(member.name, style = MaterialTheme.typography.titleMedium)
                    Text(row.candidate.where, style = MaterialTheme.typography.bodySmall)
                }
                FilterChip(
                    selected = row.admin,
                    onClick = { viewModel.drafting.toggleAdmin(member.id) },
                    label = { Text(stringResource(R.string.group_admin)) },
                )
            }
            // A member on the phone may answer with several models: each becomes a worker of its own.
            if (row.included && row.candidate.options.size > 1) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.candidate.options.forEach { model ->
                        FilterChip(
                            selected = model in row.models,
                            onClick = { viewModel.drafting.toggleModel(member.id, model) },
                            label = { Text(model.model) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.GroupChatPage(
    group: Group,
    view: GroupView,
    viewModel: GroupViewModel,
) {
    BackHandler(onBack = viewModel::close)
    Bar(group.name + stringResource(R.string.group_count, group.members.size), viewModel::close)
    val list = rememberLazyListState()
    val last = view.messages.lastIndex
    LaunchedEffect(last) { if (last >= 0) list.animateScrollToItem(last) }
    LazyColumn(
        Modifier.weight(1f, fill = true),
        state = list,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(view.messages, key = { it.id }) { message -> GroupBubble(message) }
    }
    if (view.working) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (view.busyElsewhere) Text(stringResource(R.string.group_busy), Modifier.padding(horizontal = 16.dp))
    GroupComposer(view, viewModel)
}

@Composable
private fun GroupBubble(message: GroupMessage) {
    val mine = message.from == GroupProtocol.USER
    val colors = MaterialTheme.colorScheme
    val tone = if (mine) colors.primaryContainer else colors.surfaceContainerHigh
    val who = if (mine) stringResource(R.string.group_me) else message.from
    val role = if (message.admin) stringResource(R.string.group_admin_mark) else ""
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Text(who + role, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        Card(colors = CardDefaults.cardColors(containerColor = tone)) {
            Column(Modifier.padding(12.dp)) { MessageText(message.text, search = "") }
        }
    }
}

@Composable
private fun GroupComposer(
    view: GroupView,
    viewModel: GroupViewModel,
) {
    var text by rememberSaveable { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().imePadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            text,
            { text = it },
            Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.group_say)) },
            enabled = !view.working && !view.busyElsewhere,
            maxLines = 4,
        )
        if (view.working) {
            IconButton(onClick = viewModel::stop) {
                Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.group_stop))
            }
        } else {
            val send = {
                viewModel.say(text)
                text = ""
            }
            IconButton(onClick = send, enabled = text.isNotBlank() && !view.busyElsewhere) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.group_send))
            }
        }
    }
}
