package com.cone.agent.remote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cone.agent.R
import kotlinx.coroutines.launch

/** Same pending-question card as ConeCode: options and a free-form answer share one scoped action. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RemoteQuestionCard(question: RemoteQuestion, viewModel: RemoteViewModel) {
    val cx = LocalRemotePalette.current
    val scope = rememberCoroutineScope()
    val connected = viewModel.state.collectAsStateWithLifecycle().value.phase == RemotePhase.CONNECTED
    var answer by rememberSaveable(question.id) { mutableStateOf("") }
    var submitting by remember(question.id) { mutableStateOf(false) }
    var failed by remember(question.id) { mutableStateOf(false) }
    val enabled = question.canReply && connected && !submitting
    val submit: (String) -> Unit = { value ->
        if (enabled && value.isNotBlank()) {
            submitting = true
            failed = false
            scope.launch {
                if (!viewModel.answerQuestion(question, value)) { submitting = false; failed = true }
            }
        }
    }
    Surface(
        color = cx.bg2, border = BorderStroke(1.dp, cx.border), shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.remote_question_title), color = cx.accent, style = RemoteType.bodySmall)
            Text(question.content, color = cx.text, style = RemoteType.serifBody)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                question.options.forEach { option ->
                    OutlinedButton(onClick = { submit(option) }, enabled = enabled,
                        shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, cx.border)) {
                        Text(option, style = RemoteType.bodyLarge)
                    }
                }
            }
            OutlinedTextField(
                value = answer, onValueChange = { answer = it.take(16_384) }, enabled = enabled,
                label = { Text(stringResource(R.string.remote_question_answer)) },
                textStyle = RemoteType.body, maxLines = 4, modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (submitting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else TextButton(enabled = enabled && answer.isNotBlank(), onClick = { submit(answer) }) {
                    Text(stringResource(R.string.remote_send))
                }
            }
            if (failed) Text(stringResource(R.string.remote_question_failed), color = cx.danger, style = RemoteType.bodySmall)
        }
    }
}
