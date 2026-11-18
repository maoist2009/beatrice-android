package org.voice.changer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** About page: what the app is, which engines it runs and where the parts come from. */
@Composable
fun AboutScreen(onNavigate: (Page) -> Unit) {
    val context = LocalContext.current
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
    }

    AppScaffold(
        title = "About",
        page = Page.About,
        onNavigate = onNavigate,
        subtitle = version?.let { "Version $it" },
    ) {
        SectionCard(title = "What this is") {
            Text(
                text = "An on-device voice changer. Two engines run entirely on the " +
                    "phone — no audio leaves the device — and the same converted " +
                    "stream can be played out of the speaker, written to a file, or " +
                    "handed to another app's microphone through an Xposed module.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        SectionCard(title = "Engines") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Beatrice rc.0", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "CPU backend. Needs no voice pack, so it is the one that " +
                        "works out of the box; pitch and formant shape the result.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("RVC v2", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "Runs the encoder, RMVPE pitch extraction and decoder on " +
                        "the Hexagon NPU from imported voice packs. A pack may ship a " +
                        "retrieval index; the RVC page can blend it in.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionCard(title = "Use in other apps") {
            Text(
                text = "The Xposed module runs inside the hooked app, captures its " +
                    "microphone and asks this app for the converted audio. Enable the " +
                    "module in your Xposed manager and scope it to the apps you want; " +
                    "the switch on the home page only decides whether audio is " +
                    "converted at all.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        SectionCard(title = "Credits") {
            Text(
                text = "RVC v2 architecture and the RMVPE pitch model come from the " +
                    "Retrieval-based Voice Conversion project. The Qualcomm QNN " +
                    "toolchain runs the NPU graphs. Both are used under their " +
                    "respective open-source licences.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
