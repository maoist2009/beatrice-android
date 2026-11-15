package com.beatrice.lite

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.beatrice.lite.engine.Backend
import kotlin.math.roundToInt

private val LightScheme = lightColorScheme(
    primary = Color(0xFF4F4BC4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E1FF),
    onPrimaryContainer = Color(0xFF120E6B),
    secondary = Color(0xFF5C5A70),
    background = Color(0xFFF7F7FB),
    surface = Color.White,
    surfaceVariant = Color(0xFFEDEDF4),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFC2C0FF),
    onPrimary = Color(0xFF201A75),
    primaryContainer = Color(0xFF37318C),
    onPrimaryContainer = Color(0xFFE3E1FF),
    background = Color(0xFF121216),
    surface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFF2A2A33),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    BeatriceScreen()
                }
            }
        }
    }
}

private fun displayName(context: android.content.Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun BeatriceScreen(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    var showParams by remember { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.importModel(uri)
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.pickAudio(uri, displayName(context, uri))
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            vm.startLive()
        } else {
            vm.showError("Microphone permission is required for live mode")
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Beatrice", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = state.modelVersion ?: "rc.0 · RVC v2 · on-device",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (state.ready) {
                        IconButton(onClick = { showParams = !showParams }) {
                            Icon(Icons.Filled.Tune, contentDescription = "Voice controls")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            LiveCard(state) {
                if (state.live) {
                    vm.stopLive()
                } else {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        vm.startLive()
                    } else if (android.os.Build.VERSION.SDK_INT >= 33) {
                        micPermission.launch(
                            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS),
                        )
                    } else {
                        micPermission.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                    }
                }
            }

            ModelCard(
                state = state,
                onImport = { folderPicker.launch(null) },
            )

            EngineCard(state, vm)

            HookCard(state, vm)

            if (state.ready) {
                if (state.voices.isNotEmpty()) {
                    SectionCard(title = "Voice") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.voices.forEachIndexed { i, name ->
                                FilterChip(
                                    selected = state.voice == i,
                                    onClick = { vm.setVoice(i) },
                                    label = { Text(name) },
                                    leadingIcon = if (state.voice == i) {
                                        { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                    } else null,
                                )
                            }
                        }
                    }
                }

                AnimatedVisibility(visible = showParams) {
                    ParamsCard(state, vm)
                }

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
                }

                ConvertButton(state = state, onConvert = vm::convert)

                if (state.resultSeconds > 0 || state.converting) {
                    ResultCard(state, vm)
                }
            }

            state.error?.let { message ->
                ErrorCard(message) { vm.clearMessage() }
            }

            if (!state.ready && state.message != null) {
                Text(
                    text = state.message.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LiveCard(state: MainViewModel.UiState, onToggle: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (state.live) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .background(
                            if (state.live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            CircleShape,
                        )
                        .clickable(enabled = state.ready && !state.busy) { onToggle() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (state.live) Icons.Filled.MicOff else Icons.Filled.Mic,
                        contentDescription = if (state.live) "Stop live conversion" else "Start live conversion",
                        tint = if (state.live) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(32.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = when {
                            state.live -> "Listening"
                            state.ready -> "Go live"
                            else -> "Live voice change"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (state.live) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = when {
                            state.live -> "Tap the button to stop"
                            state.ready -> "Microphone → ${state.modelName ?: "voice"} → speaker"
                            else -> "Load a model below to enable the microphone"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.live) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.live) {
                LinearProgressIndicator(
                    progress = { (state.level * 3f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Headphones recommended — speakers will feed back into the microphone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                )
            }
        }
    }
}

@Composable
private fun ModelCard(state: MainViewModel.UiState, onImport: () -> Unit) {
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
                                .background(if (state.ready) Color(0xFF2FA36B) else Color(0xFFC9C9D4), CircleShape),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = state.modelName ?: "No model loaded",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = when {
                            state.importing -> "Copying the folder…"
                            state.loadingModel -> "Preparing the on-device engine…"
                            state.ready -> state.modelVersion ?: "ready"
                            else -> "Pick a Beatrice model folder or an RVC pack"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onImport, enabled = !state.busy) {
                    Text(if (state.ready) "Replace" else "Import")
                }
            }
        }
    }
}

@Composable
private fun EngineCard(state: MainViewModel.UiState, vm: MainViewModel) {
    // Only worth showing when both backends have their files installed.
    if (!state.hasBeatrice || !state.hasRvc) return
    SectionCard(title = "Engine") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.backend == Backend.BEATRICE,
                onClick = { vm.setBackend(Backend.BEATRICE) },
                enabled = !state.busy && !state.live,
                label = { Text("Beatrice · CPU") },
            )
            FilterChip(
                selected = state.backend == Backend.RVC,
                onClick = { vm.setBackend(Backend.RVC) },
                enabled = !state.busy && !state.live,
                label = { Text("RVC · NPU") },
            )
        }
        Text(
            text = "One backend is resident at a time. Beatrice needs no voice pack and " +
                "runs on the CPU; RVC uses the Hexagon NPU and the voice packs you imported. " +
                "Switching reloads the engine (about a second).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HookCard(state: MainViewModel.UiState, vm: MainViewModel) {
    var showTargets by remember { mutableStateOf(false) }
    SectionCard(title = "Use in other apps") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Route another app's microphone", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = when {
                        !state.hookEnabled -> "Speak in WeChat (or any chosen app) with this voice."
                        state.hookActive -> "Active in ${state.hookedPkg} · ${state.hookFrames / 16000} s converted"
                        else -> "Waiting for a target app to open the microphone"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = state.hookEnabled, onCheckedChange = vm::setHookEnabled)
        }
        if (state.hookEnabled) {
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            if (state.hookActive && state.hookLatencyMs > 0) {
                Text(
                    text = "≈ ${state.hookLatencyMs} ms added delay " +
                        "(look-ahead ${state.hookLookaheadMs} + queue ${state.hookQueueMs}; " +
                        "inference ${"%.0f".format(state.hookInferMs)} ms)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Every app", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = if (state.hookAll) "All apps except recorders" else "${state.hookTargets.size} app(s) selected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.hookAll, onCheckedChange = vm::setHookAll)
            }
            OutlinedButton(
                onClick = { showTargets = true },
                enabled = !state.hookAll && state.apps.isNotEmpty(),
            ) { Text("Choose apps") }
            Text(
                text = "Enable the “Beatrice Lite” module in your Xposed manager and tick the same apps " +
                    "in its scope. This switch only decides whether audio is converted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (showTargets) {
        TargetDialog(state, vm) { showTargets = false }
    }
}

@Composable
private fun TargetDialog(state: MainViewModel.UiState, vm: MainViewModel, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Apps to convert") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(state.apps, key = { it.pkg }) { app ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.toggleHookTarget(app.pkg) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = state.hookTargets.contains(app.pkg),
                            onCheckedChange = { vm.toggleHookTarget(app.pkg) },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            Text(
                                app.pkg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

@Composable
private fun ParamsCard(state: MainViewModel.UiState, vm: MainViewModel) {
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
            if (!state.isRvc) {
                ParamSlider("Formant", "%+.1f".format(state.formant), state.formant, -2f..2f, 7) { vm.setFormant(it) }
                ParamSlider("Intonation", "%.2f".format(state.intonation), state.intonation, 0f..2f, 19) { vm.setIntonation(it) }
                ParamSlider("Pitch correction", "%.2f".format(state.correction), state.correction, 0f..1f, 19) { vm.setCorrection(it) }
                ParamSlider("VQ neighbours", "${state.vq}", state.vq.toFloat(), 0f..8f, 7) { vm.setVq(it.roundToInt()) }
            } else {
                ParamSlider("Protect", "%.2f".format(state.protect), state.protect, 0f..0.5f, 19) { vm.setProtect(it) }
                ParamSlider("Loudness match", "%.2f".format(state.rmsMix), state.rmsMix, 0f..1f, 19) { vm.setRmsMix(it) }
                ParamSlider("f0 smoothing", "${state.filter}", state.filter.toFloat(), 1f..7f, 5) { vm.setFilter(it.roundToInt()) }
            }
            ParamSlider("Output level", "%.0f%%".format(state.output * 100), state.output, 0.2f..2f, 17) { vm.setOutput(it) }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text(
                text = if (state.isRvc) {
                    "RVC runs on the Hexagon NPU: pitch and output level are instant, protect/loudness smoothing apply on the next block."
                } else {
                    "Pitch and formant are the two controls worth reaching for first; VQ trades clarity for a closer match to the target speaker."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ParamSlider(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(top = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
        )
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

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}
