package org.voice.changer.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.voice.changer.MainViewModel
import org.voice.changer.engine.Backend
import kotlin.math.roundToInt

/**
 * Home page: the live voice change plus everything the Xposed hook needs.
 *
 * "Live" and "use in other apps" are the same feature seen from two sides —
 * one plays the converted voice out of the speaker, the other hands it to the
 * microphone of a hooked app — so they share a page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun XposedScreen(vm: MainViewModel, onNavigate: (Page) -> Unit) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            vm.startLive()
        } else {
            vm.showError("Microphone permission is required for live mode")
        }
    }

    AppScaffold(
        title = "Voice Changer",
        page = Page.Home,
        onNavigate = onNavigate,
        subtitle = state.modelVersion ?: "rc.0 · RVC v2 · on-device",
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

        EngineCard(state, vm)

        HookCard(state, vm)

        state.error?.let { message -> ErrorCard(message) { vm.clearMessage() } }

        if (!state.ready && state.message != null) {
            Text(
                text = state.message.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun LiveCard(state: MainViewModel.UiState, onToggle: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            if (state.live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (state.live) Icons.Filled.Mic else Icons.Filled.MicOff,
                        contentDescription = null,
                        tint = if (state.live) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Live voice", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = when {
                            state.live -> "Speaking as ${state.modelName ?: "the target voice"}"
                            !state.ready -> "Waiting for a model"
                            else -> "Hear the conversion through the speaker"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = onToggle,
                    enabled = state.ready,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.live) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    ),
                ) { Text(if (state.live) "Stop" else "Start") }
            }
            if (state.live) {
                LinearProgressIndicator(
                    progress = { state.level },
                    modifier = Modifier.fillMaxWidth(),
                )
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
        Hint(
            "One backend is resident at a time. Beatrice needs no voice pack and " +
                "runs on the CPU; RVC uses the Hexagon NPU and the voice packs you " +
                "imported. Switching reloads the engine (about a second).",
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
            Hint(
                "Enable the “Voice Changer” module in your Xposed manager and tick " +
                    "the same apps in its scope. This switch only decides whether " +
                    "audio is converted.",
            )
        }
        ModuleStatus(state)
    }
    if (showTargets) {
        TargetDialog(state, vm) { showTargets = false }
    }
}

/**
 * What the Xposed side actually reports, which is less than what the manager
 * shows: the framework gives a module no way to read its own enable state or
 * scope, so the app displays the two honest signals — the manager that is
 * installed, and whether the module has ever reached it from a target process.
 */
@Composable
private fun ModuleStatus(state: MainViewModel.UiState) {
    HorizontalDivider(Modifier.padding(vertical = 6.dp))
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "Module status",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = when (val api = state.moduleApiVersion) {
                null -> "Not loaded into this app. That only means this app is not in the " +
                    "module's scope; it says nothing about whether the module is enabled."
                else -> "Loaded into this app (Xposed API $api): the module is enabled."
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (!state.hookEnabled) return
    val rows = if (state.hookAll) {
        state.hookSeen.toList().sortedByDescending { it.second }
    } else {
        state.hookTargets.map { it to state.hookSeen[it] }
    }
    val now = System.currentTimeMillis()
    rows.take(6).forEach { (pkg, at) ->
        val label = state.apps.firstOrNull { it.pkg == pkg }?.label ?: pkg
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(
                        if (at != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        CircleShape,
                    ),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Text(
                text = if (at == null) "not seen" else "seen ${ago(at, now)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Hint(
        if (rows.isEmpty()) {
            "Pick the apps above, then tick the same apps in the module's scope. Nothing " +
                "can be verified until a target has run the module at least once."
        } else {
            "A target turns “seen” when the module has run inside it: open that app once. " +
                "“not seen” means the scope does not include it, the module is off, or it has " +
                "not been opened since — force-stop it, then open it again."
        },
    )
}

private fun ago(at: Long, now: Long): String {
    val s = ((now - at) / 1000).coerceAtLeast(0)
    return when {
        s < 10 -> "just now"
        s < 90 -> "$s s ago"
        s < 5400 -> "${s / 60} min ago"
        else -> "${s / 3600} h ago"
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
