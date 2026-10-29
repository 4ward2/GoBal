@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.agent.AppAnswer
import com.danielealbano.androidremotecontrolmcp.services.agent.AppApprovalRequest
import com.danielealbano.androidremotecontrolmcp.services.agent.AppApprovals
import com.danielealbano.androidremotecontrolmcp.ui.theme.AndroidRemoteControlMcpTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Shown on the phone's own screen when the assistant is about to work in an app it has not
 * been allowed in: only this time, always, or no. Leaving the screen any other way is a no.
 */
@AndroidEntryPoint
class AgentApprovalActivity : ComponentActivity() {
    @Inject lateinit var approvals: AppApprovals

    private var answeredFor: String? = null

    /** The question this screen shows; leaving only refuses that one, never a newer one. */
    private var shownRequestId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AndroidRemoteControlMcpTheme {
                val request by approvals.pending.collectAsState()
                val current = request
                if (current == null) {
                    finish()
                } else {
                    shownRequestId = current.id
                    AppApprovalCard(current, onAnswer = { answer(current, it) })
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Leaving the question (home, recents, screen off) counts as "no".
        if (!isChangingConfigurations) {
            approvals.pending.value
                ?.takeIf { it.id == shownRequestId }
                ?.let { answer(it, AppAnswer.DENY) }
        }
    }

    private fun answer(
        request: AppApprovalRequest,
        answer: AppAnswer,
    ) {
        if (answeredFor == request.id) return
        answeredFor = request.id
        approvals.answer(request.id, answer)
        finish()
    }
}

@Composable
private fun AppApprovalCard(
    request: AppApprovalRequest,
    onAnswer: (AppAnswer) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.agent_approval_title, request.appLabel),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(stringResource(R.string.agent_approval_body), style = MaterialTheme.typography.bodyLarge)
                Text(request.packageName, style = MaterialTheme.typography.bodySmall)
                val wide = Modifier.fillMaxWidth().heightIn(min = BUTTON_HEIGHT)
                Button(onClick = { onAnswer(AppAnswer.ONCE) }, modifier = wide) {
                    Text(stringResource(R.string.agent_approval_once))
                }
                FilledTonalButton(onClick = { onAnswer(AppAnswer.ALWAYS) }, modifier = wide) {
                    Text(stringResource(R.string.agent_approval_always))
                }
                OutlinedButton(onClick = { onAnswer(AppAnswer.DENY) }, modifier = wide) {
                    Text(stringResource(R.string.agent_approval_deny))
                }
            }
        }
    }
}

private val BUTTON_HEIGHT = 52.dp
