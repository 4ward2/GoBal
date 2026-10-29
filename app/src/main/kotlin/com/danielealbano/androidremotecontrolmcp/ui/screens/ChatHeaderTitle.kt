@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Toc
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.AppTier
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatHttpService
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatOutline
import com.danielealbano.androidremotecontrolmcp.services.chat.PhoneMcpChatLogic
import com.danielealbano.androidremotecontrolmcp.services.chat.Presence
import com.danielealbano.androidremotecontrolmcp.services.remote.LinkState
import com.danielealbano.androidremotecontrolmcp.ui.LocalTier
import com.danielealbano.androidremotecontrolmcp.ui.chat.AttachActions
import com.danielealbano.androidremotecontrolmcp.ui.chat.CallOptionsSheet
import com.danielealbano.androidremotecontrolmcp.ui.chat.CameraShots
import com.danielealbano.androidremotecontrolmcp.ui.chat.ChatComposer
import com.danielealbano.androidremotecontrolmcp.ui.chat.ChatPresentation
import com.danielealbano.androidremotecontrolmcp.ui.chat.ChatScreenActions
import com.danielealbano.androidremotecontrolmcp.ui.chat.ChatSearch
import com.danielealbano.androidremotecontrolmcp.ui.chat.ChatSearchBar
import com.danielealbano.androidremotecontrolmcp.ui.chat.ChatViewState
import com.danielealbano.androidremotecontrolmcp.ui.chat.ComposerActions
import com.danielealbano.androidremotecontrolmcp.ui.chat.ComposerState
import com.danielealbano.androidremotecontrolmcp.ui.chat.ConversationsHost
import com.danielealbano.androidremotecontrolmcp.ui.chat.ErrorNotice
import com.danielealbano.androidremotecontrolmcp.ui.chat.GroupsButton
import com.danielealbano.androidremotecontrolmcp.ui.chat.InteractionState
import com.danielealbano.androidremotecontrolmcp.ui.chat.MessageInteractionHost
import com.danielealbano.androidremotecontrolmcp.ui.chat.MessageListState
import com.danielealbano.androidremotecontrolmcp.ui.chat.MicActions
import com.danielealbano.androidremotecontrolmcp.ui.chat.OutlineSheet
import com.danielealbano.androidremotecontrolmcp.ui.chat.RemotePermissionCards
import com.danielealbano.androidremotecontrolmcp.ui.chat.SelectionActions
import com.danielealbano.androidremotecontrolmcp.ui.chat.SelectionBar
import com.danielealbano.androidremotecontrolmcp.ui.chat.SetupBanner
import com.danielealbano.androidremotecontrolmcp.ui.chat.SkillActions
import com.danielealbano.androidremotecontrolmcp.ui.chat.Speaker
import com.danielealbano.androidremotecontrolmcp.ui.chat.rememberChatViewState
import com.danielealbano.androidremotecontrolmcp.ui.chat.rememberInteractionState
import com.danielealbano.androidremotecontrolmcp.ui.components.AssistantAvatar
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.TierUpgradeDialog
import com.danielealbano.androidremotecontrolmcp.ui.theme.PresenceBlocked
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.AssistViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.DictationState
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.DictationViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MessageAction
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.NativeChatUiState
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.NativeChatViewModel
import com.danielealbano.androidremotecontrolmcp.utils.PermissionUtils
import kotlinx.coroutines.launch

/** Who the conversation is with: the avatar with its presence dot, the name, and the line under it. */
@Composable
internal fun HeaderTitle(
    ui: NativeChatUiState,
    speaker: Speaker,
) {
    PresenceAvatar(speaker, headerPresence(ui))
    Spacer(Modifier.width(12.dp))
    Column {
        Text(
            speaker.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // Away states (隐身 / 勿扰 / 拉黑) win over the character's mood line: the API is down.
        val mood = ui.character?.status?.ifBlank { null } ?: stringResource(R.string.chat_status_online)
        val line = ui.remote?.line ?: if (ui.presence.reachable) mood else ui.presence.label
        Text(
            // No model at all is not "away": say what is missing instead of a mood.
            if (ui.setup.model) line else stringResource(R.string.setup_no_model),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The dot on the avatar: the model's API for an ordinary contact, the link for an agent on a computer. */
private fun headerPresence(ui: NativeChatUiState): Presence {
    val link = ui.remote?.link
    return when {
        link == LinkState.ONLINE -> Presence.ONLINE
        link != null -> Presence.CHECKING
        ui.setup.model -> ui.presence
        else -> Presence.CHECKING
    }
}

/** The avatar with a presence dot on its bottom-right corner, the way messengers show it. */
@Composable
private fun PresenceAvatar(
    speaker: Speaker,
    presence: Presence,
) {
    Box {
        AssistantAvatar(name = speaker.name, size = HEADER_AVATAR, image = speaker.avatar)
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(PRESENCE_DOT)
                .background(MaterialTheme.colorScheme.surface, CircleShape)
                .padding(PRESENCE_RING)
                .background(presenceColor(presence), CircleShape),
        )
    }
}

/** Green online, grey invisible / checking, red busy, dark red blocked: the messenger convention. */
@Composable
private fun presenceColor(presence: Presence) =
    when (presence) {
        Presence.ONLINE -> MaterialTheme.colorScheme.tertiary
        Presence.BUSY -> MaterialTheme.colorScheme.error
        Presence.BLOCKED -> PresenceBlocked
        Presence.INVISIBLE, Presence.CHECKING -> MaterialTheme.colorScheme.outline
    }

private val HEADER_AVATAR = 44.dp
private val PRESENCE_DOT = 14.dp
private val PRESENCE_RING = 2.dp
