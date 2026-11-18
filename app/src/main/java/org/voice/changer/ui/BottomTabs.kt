package org.voice.changer.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
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
import org.voice.changer.AboutActivity
import org.voice.changer.BeatriceActivity
import org.voice.changer.ConvertActivity
import org.voice.changer.MainActivity
import org.voice.changer.RvcActivity

/** The five pages, in bottom-bar order. */
enum class Page(val label: String, val icon: ImageVector, val activity: Class<*>) {
    Home("Voice", Icons.Filled.Mic, MainActivity::class.java),
    Rvc("RVC", Icons.Filled.RecordVoiceOver, RvcActivity::class.java),
    Beatrice("Beatrice", Icons.Filled.GraphicEq, BeatriceActivity::class.java),
    Convert("Convert", Icons.Filled.FileDownload, ConvertActivity::class.java),
    About("About", Icons.Filled.Info, AboutActivity::class.java),
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

/**
 * Switches page. The target replaces the current one on the stack, so the
 * system back button leaves the app instead of walking through every tab you
 * happened to visit, and the shared ViewModel keeps the state anyway.
 */
fun Activity.openPage(page: Page) {
    if (page.activity == javaClass) return
    startActivity(Intent(this, page.activity))
    finish()
}

/** Pages other than home send back to it rather than closing the app. */
fun ComponentActivity.backToHome() {
    onBackPressedDispatcher.addCallback(
        this,
        object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                startActivity(Intent(this@backToHome, MainActivity::class.java))
                finish()
            }
        },
    )
}
