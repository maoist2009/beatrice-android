package org.voice.changer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.voice.changer.MainViewModel
import org.voice.changer.engine.Backend
import kotlin.math.roundToInt

/** Beatrice page: the CPU backend, its model and its own tunables. */
@Composable
fun BeatriceScreen(vm: MainViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsState()

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.importModel(uri)
    }

    AppScaffold(
        title = "Beatrice",
        subtitle = "rc.0 · CPU",
        onBack = onBack,
    ) {
        ModelCard(state) { folderPicker.launch(null) }

        if (state.ready && !state.isRvc) {
            BeatriceParams(state, vm)
        } else if (state.hasBeatrice) {
            SectionCard(title = "Not loaded") {
                Text(
                    text = "Another engine is resident. Only one backend runs at a time, " +
                        "so loading Beatrice swaps the CPU graphs in.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { vm.setBackend(Backend.BEATRICE) }, enabled = !state.busy) {
                    Text("Load Beatrice")
                }
            }
        }

        state.error?.let { message -> ErrorCard(message) { vm.clearMessage() } }
    }
}

@Composable
private fun BeatriceParams(state: MainViewModel.UiState, vm: MainViewModel) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Voice controls",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ParamSlider("Pitch", "%+.0f st".format(state.pitch), state.pitch, -24f..24f, 47) { vm.setPitch(it) }
            ParamSlider("Formant", "%+.1f".format(state.formant), state.formant, -2f..2f, 7) { vm.setFormant(it) }
            ParamSlider("Intonation", "%.2f".format(state.intonation), state.intonation, 0f..2f, 19) { vm.setIntonation(it) }
            ParamSlider("Pitch correction", "%.2f".format(state.correction), state.correction, 0f..1f, 19) { vm.setCorrection(it) }
            ParamSlider("VQ neighbours", "${state.vq}", state.vq.toFloat(), 0f..8f, 7) { vm.setVq(it.roundToInt()) }

            HorizontalDivider(Modifier.padding(vertical = 6.dp))

            ParamSlider(
                "Noise gate",
                if (state.noiseGate >= 0f) "off" else "%.0f dB".format(state.noiseGate),
                state.noiseGate,
                -70f..0f,
                69,
            ) { vm.setNoiseGate(it) }
            Hint(
                "Input below the threshold never reaches the model, so a quiet " +
                    "room is not synthesised into the target voice. 0 dB is off.",
            )

            ParamSlider(
                "Output level",
                "%.0f%%".format(state.output * 100),
                state.output,
                0.2f..2f,
                17,
            ) { vm.setOutput(it) }

            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Hint(
                "Pitch and formant are the two controls worth reaching for first; " +
                    "VQ trades clarity for a closer match to the target speaker.",
            )
        }
    }
}
