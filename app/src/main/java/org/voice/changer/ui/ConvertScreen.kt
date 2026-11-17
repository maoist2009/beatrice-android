package org.voice.changer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.voice.changer.MainViewModel
import kotlin.math.roundToInt

/** Offline conversion: pick a clip, convert it, play or save the result. */
@Composable
fun ConvertScreen(vm: MainViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.pickAudio(uri, displayName(context, uri))
    }

    AppScaffold(
        title = "Convert",
        subtitle = "Offline · whole file",
        onBack = onBack,
    ) {
        SectionCard(title = "Input") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { audioPicker.launch(arrayOf("audio/*")) }) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.inputName == null) "Pick audio" else "Replace audio")
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = state.inputName ?: "No clip imported",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                    )
                    if (state.inputSeconds > 0) {
                        Text(
                            text = "%.1f s · 16 kHz mono".format(state.inputSeconds),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Hint("The whole file goes through the same streaming pipeline as live mode.")
        }

        ConvertButton(state = state, onConvert = vm::convert)

        if (state.resultSeconds > 0 || state.converting) {
            ResultCard(state, vm)
        }

        state.error?.let { message -> ErrorCard(message) { vm.clearMessage() } }
    }
}

@Composable
private fun ConvertButton(state: MainViewModel.UiState, onConvert: () -> Unit) {
    val enabled = state.ready && state.inputName != null && !state.busy
    if (state.converting) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Converting… ${(state.progress * 100).roundToInt()}%",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "The first run also warms the CPU kernels.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                )
            }
        }
    } else {
        Button(
            onClick = onConvert,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            contentPadding = PaddingValues(horizontal = 20.dp),
        ) {
            Text("Convert", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun ResultCard(state: MainViewModel.UiState, vm: MainViewModel) {
    SectionCard(title = "Converted") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "%.1f s · %d kHz mono".format(state.resultSeconds, state.outputRate / 1000),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = vm::togglePlay, enabled = state.resultSeconds > 0) {
                Icon(
                    if (state.playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(if (state.playing) "Stop" else "Play")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = vm::save, enabled = state.resultSeconds > 0) {
                Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Save")
            }
        }
        state.savedName?.let {
            Text(
                text = "Saved $it to Music/Beatrice",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
