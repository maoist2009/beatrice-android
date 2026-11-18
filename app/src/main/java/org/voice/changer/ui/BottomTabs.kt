package org.voice.changer.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

/** The five pages, in bottom-bar order. */
enum class Page(val label: String, val icon: ImageVector) {
    Home("Voice", Icons.Filled.Mic),
    Rvc("RVC", Icons.Filled.RecordVoiceOver),
    Beatrice("Beatrice", Icons.Filled.GraphicEq),
    Convert("Convert", Icons.Filled.FileDownload),
    About("About", Icons.Filled.Info),
}

/** Material 3 bottom navigation, one item per page. */
@Composable
fun BottomTabs(page: Page, onNavigate: (Page) -> Unit) {
    NavigationBar {
        Page.entries.forEach { item ->
            NavigationBarItem(
                selected = item == page,
                onClick = { if (item != page) onNavigate(item) },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(item.label) },
            )
        }
    }
}
