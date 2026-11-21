package org.voice.changer.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Mic
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
import androidx.core.content.ContextCompat
import org.voice.changer.MainViewModel
import org.voice.changer.audio.Recorder
import kotlin.math.roundToInt

/**
 * Offline conversion: pick a clip or record one, convert it, then play or save
 * the result. Both sources end up as the same 16 kHz buffer, so the page only
 * ever has one input.
 */
@Composable
fun ConvertScreen(vm: MainViewModel, onNavigate: (Page) -> Unit) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.pickAudio(uri, displayName(context, uri))
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            vm.startRecording()
        } else {
            vm.showError("Microphone permission is required to record")
        }
    }

    AppScaffold(
        title = "Convert",
        page = Page.Convert,
        onNavigate = onNavigate,
        subtitle = "Offline · whole clip",
    ) {
        InputCard(
            state = state,
            vm = vm,
            onPick = { audioPicker.launch(arrayOf("audio/*")) },
            onRecord = {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    vm.startRecording()
                } else {
                    micPermission.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                }
            },
        )

        ConvertButton(state = state, onConvert = vm::convert)

        if (state.resultSeconds > 0 || state.converting) {
            ResultCard(state, vm)
        }

        state.error?.let { message -> ErrorCard(message) { vm.clearMessage() } }
    }
}

@Composable
private fun InputCard(
    state: MainViewModel.UiState,
    vm: MainViewModel,
    onPick: () -> Unit,
    onRecord: () -> Unit,
) {
    SectionCard(title = "Input") {
        if (state.recording) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Recording %.1f s".format(state.recordSeconds),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = vm::stopRecording) {
                    Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Stop")
                }
            }
            LinearProgressIndicator(
                progress = { state.recordLevel.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Hint(
                "Up to ${Recorder.MAX_SECONDS} s at 16 kHz mono. It stops by itself at the " +
                    "limit; leave the app in front while recording."
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onPick, enabled = !state.busy && !state.live) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.inputName == null) "Pick audio" else "Replace")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onRecord, enabled = !state.busy && !state.live) {
                    Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Record")
                }
            }
            if (state.inputName != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = state.inputName,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                        )
                        Text(
                            text = "%.1f s · 16 kHz mono · %s".format(
                                state.inputSeconds,
                                if (state.inputFromMic) "microphone" else "file",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = vm::toggleInputPlay) {
                        Icon(
                            if (state.playingInput) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (state.playingInput) "Stop" else "Preview")
                    }
                }
            }
            Hint(
                "A recording and a picked file take the same path: the streaming pipeline " +
                    "live mode uses, fed to the end of the clip."
            )
        }
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
                    text = elapsedLine(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                )
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = engineLine(state),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
}

/** "RVC · mygf_wh → 40 kHz": what the clip will actually be converted with. */
private fun engineLine(state: MainViewModel.UiState): String =
    if (!state.ready) {
        "No engine loaded"
    } else {
        "%s · %s → %d kHz".format(
            if (state.isRvc) "RVC" else "Beatrice",
            state.modelName ?: "?",
            state.outputRate / 1000,
        )
    }

private fun elapsedLine(state: MainViewModel.UiState): String {
    val elapsed = state.convertElapsedMs / 1000.0
    val p = state.progress
    val eta = if (p > 0.02 && p < 1f) elapsed * (1 - p) / p else 0.0
    return if (eta > 0.5) {
        "%.1f s elapsed · about %.0f s left".format(elapsed, eta)
    } else {
        "%.1f s elapsed".format(elapsed)
    }
}

@Composable
private fun ResultCard(state: MainViewModel.UiState, vm: MainViewModel) {
    SectionCard(title = "Converted") {
        Text(
            text = "%.1f s · %d kHz mono".format(state.resultSeconds, state.outputRate / 1000),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (state.rtf > 0.0) {
            Text(
                text = "RTF %.2f · %.1f× realtime · %.1f s of work".format(
                    state.rtf,
                    1.0 / state.rtf,
                    state.convertWallMs / 1000.0,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Button(onClick = vm::togglePlay, enabled = state.resultSeconds > 0) {
                Icon(
                    if (state.playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(if (state.playing) "Stop" else "Play")
            }
            OutlinedButton(onClick = vm::save, enabled = state.resultSeconds > 0) {
                Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Save")
            }
        }
        if (state.rtf > 0.0) {
            Hint("RTF is processing time divided by audio length: below 1.0 means faster than realtime.")
        }
        state.savedName?.let {
            Text(
                text = "Saved %s (%.0f KB) to Music/Beatrice".format(
                    it,
                    44.0 + state.resultSeconds * state.outputRate * 2.0,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
