@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.assistant.SetupChecklist
import com.danielealbano.androidremotecontrolmcp.services.assistant.SetupState
import com.danielealbano.androidremotecontrolmcp.services.assistant.SetupStep

/**
 * What is still to be set up, each with one button that goes straight there. Shown above the
 * conversation until the list is empty: a new user is never left facing a chat that cannot answer
 * with no word on why.
 */
@Composable
fun SetupBanner(
    state: SetupState,
    onStep: (SetupStep) -> Unit,
    modifier: Modifier = Modifier,
) {
    val steps = SetupChecklist.missing(state)
    if (steps.isEmpty()) return
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.titleMedium)
            steps.forEach { step -> SetupRow(step, onStep) }
        }
    }
}

@Composable
private fun SetupRow(
    step: SetupStep,
    onStep: (SetupStep) -> Unit,
) {
    val words = wordsOf(step)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(stringResource(words.title), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(words.why),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Button(onClick = { onStep(step) }, modifier = Modifier.heightIn(min = BUTTON_HEIGHT)) {
            Text(stringResource(words.button))
        }
    }
}

/** A step's title, the one-line reason for it, and what its button says. */
private class StepWords(
    val title: Int,
    val why: Int,
    val button: Int,
)

private fun wordsOf(step: SetupStep): StepWords =
    when (step) {
        SetupStep.MODEL -> {
            StepWords(R.string.setup_model_title, R.string.setup_model_why, R.string.setup_model_button)
        }

        SetupStep.ACCESSIBILITY -> {
            StepWords(R.string.setup_a11y_title, R.string.setup_a11y_why, R.string.setup_a11y_button)
        }

        SetupStep.SPEECH -> {
            StepWords(R.string.setup_speech_title, R.string.setup_speech_why, R.string.setup_speech_button)
        }
    }

private val BUTTON_HEIGHT = 48.dp
