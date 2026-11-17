package org.voice.changer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.voice.changer.ui.AppTheme
import org.voice.changer.ui.RvcScreen
import org.voice.changer.ui.SharedVm

/** RVC page: NPU backend, voice packs and its tunables. */
class RvcActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vm = SharedVm.get(application)
        setContent { AppTheme { RvcScreen(vm) { finish() } } }
    }
}
