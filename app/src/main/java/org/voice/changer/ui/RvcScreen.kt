package org.voice.changer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.voice.changer.MainViewModel
import org.voice.changer.engine.Backend
import kotlin.math.roundToInt

/** RVC page: the NPU backend, its voice packs and its own tunables. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RvcScreen(vm: MainViewModel, onNavigate: (Page) -> Unit) {
    val state by vm.state.collectAsState()

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.importModel(uri)
    }

    AppScaffold(
        title = "RVC",
        page = Page.Rvc,
        onNavigate = onNavigate,
        subtitle = "RVC v2 · Hexagon NPU",
    ) {
        ModelCard(
            state = state,
            backend = Backend.RVC,
            onImport = { folderPicker.launch(null) },
            onLoad = { vm.setBackend(Backend.RVC) },
        )

        if (state.ready && state.voices.isNotEmpty()) {
            SectionCard(title = "Voice pack") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.voices.forEachIndexed { i, name ->
                        val hasIndex = state.voiceHasIndex.getOrNull(i) == true
                        FilterChip(
                            selected = state.voice == i,
                            onClick = { vm.setVoice(i) },
                            label = { Text(if (hasIndex) "$name · index" else name) },
                            leadingIcon = if (state.voice == i) {
                                {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            } else null,
                        )
                    }
                }
                Hint("“· index” marks a pack that ships a retrieval index; see Index rate below.")
            }
        }

        if (state.ready && state.isRvc) {
            RvcParams(state, vm)
        }

        PipelineCard(
                stages = listOf(
                    "hubert c0 / r / enc" to "content features",
                    "rmvpe" to "f0 contour",
                    "encflow" to "speaker encoder",
                    "flowreverse" to "latent flow",
                    "decnsf" to "waveform",
                ),
                note = "QNN graphs on the Hexagon NPU: HuBERT and RMVPE included, one " +
                    "250 ms block per 4000 input samples.",
            )

        state.error?.let { message -> ErrorCard(message) { vm.clearMessage() } }
    }
}

/**
 * Status card for one backend's model.
 *
 * It shows that backend's own model even when the other one is resident:
 * sharing a single "current model" card meant the Beatrice page labelled
 * itself with whatever RVC pack happened to be loaded.
 */
@Composable
fun ModelCard(
    state: MainViewModel.UiState,
    backend: Backend,
    onImport: () -> Unit,
    onLoad: () -> Unit,
) {
    val resident = state.ready && state.backend == backend
    val installed = if (backend == Backend.RVC) state.hasRvc else state.hasBeatrice
    val name = when {
        resident -> state.modelName
        backend == Backend.RVC -> state.rvcName
        else -> state.beatriceName
    } ?: if (backend == Backend.RVC) "No voice pack" else "No model"
    val subtitle = when {
        !installed -> if (backend == Backend.RVC) {
            "Import a pack to use this engine"
        } else {
            "Import a model folder to use this engine"
        }
        resident -> state.modelVersion ?: "ready"
        backend == Backend.RVC -> state.rvcSubtitle ?: "RVC v2 · Hexagon NPU"
        else -> state.beatriceSubtitle ?: "rc.0 · on-device CPU"
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.loadingModel || state.importing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(if (resident) Color(0xFF2FA36B) else Color(0xFFC9C9D4), CircleShape),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = when {
                            state.importing -> "Copying the folder…"
                            state.loadingModel && state.backend == backend -> "Preparing the on-device engine…"
                            else -> subtitle
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onImport, enabled = !state.busy) {
                    Text(if (installed) "Replace" else "Import")
                }
            }
            if (installed && !resident) {
                Button(
                    onClick = onLoad,
                    enabled = !state.busy && !state.live,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Load " + if (backend == Backend.RVC) "RVC" else "Beatrice")
                }
            }
        }
    }
}

@Composable
private fun RvcParams(state: MainViewModel.UiState, vm: MainViewModel) {
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
            ParamSlider("Protect", "%.2f".format(state.protect), state.protect, 0f..0.5f, 19) { vm.setProtect(it) }
            ParamSlider("Loudness match", "%.2f".format(state.rmsMix), state.rmsMix, 0f..1f, 19) { vm.setRmsMix(it) }
            ParamSlider("f0 smoothing", "${state.filter}", state.filter.toFloat(), 1f..7f, 5) { vm.setFilter(it.roundToInt()) }

            Text(
                text = "Block-tail f0",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.f0Tail == 0,
                    onClick = { vm.setF0Tail(0) },
                    label = { Text("Quality") },
                )
                FilterChip(
                    selected = state.f0Tail >= 1,
                    onClick = { vm.setF0Tail(1) },
                    label = { Text("Low latency") },
                )
            }
            Hint(
                if (state.f0Tail >= 1) {
                    "−100 ms: the block's last f0 frames come from the earliest " +
                        "window that still contains them and its tail is zero-padded " +
                        "instead of waited for. Everything but the block-tail pitch " +
                        "is unchanged."
                } else {
                    "Waits for the RMVPE window that centres the block's " +
                        "last f0 frames — the most accurate block-tail pitch, " +
                        "at the cost of the longest look-ahead."
                },
            )

            HorizontalDivider(Modifier.padding(vertical = 6.dp))

            val hasIndex = state.voiceHasIndex.getOrNull(state.voice) == true
            ParamSlider(
                "Index rate",
                if (state.indexRate <= 0f) "off" else "%.2f".format(state.indexRate),
                state.indexRate,
                0f..1f,
                19,
                onFinished = vm::commitIndexChange,
            ) { vm.setIndexRate(it) }
            if (state.indexRate > 0f) {
                ParamSlider(
                    "Index probes",
                    if (state.nprobe <= 0) "auto" else "${state.nprobe}",
                    state.nprobe.toFloat(),
                    0f..16f,
                    15,
                    onFinished = vm::commitIndexChange,
                ) { vm.setNprobe(it.roundToInt()) }
            }
            Hint(
                if (hasIndex) {
                    "Retrieval blends the closest frames of this pack's .index into " +
                        "the encoder output; higher is closer to the reference timbre " +
                        "and a little slower per block."
                } else {
                    "This pack has no .index, so retrieval stays off whatever the " +
                        "rate is. Import a pack that ships one to enable it."
                },
            )

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
                "RVC runs on the Hexagon NPU: pitch and output level are instant, " +
                    "protect/loudness smoothing apply on the next block.",
            )
        }
    }
}
