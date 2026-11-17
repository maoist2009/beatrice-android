package org.voice.changer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.voice.changer.ui.AppTheme
import org.voice.changer.ui.BeatriceScreen
import org.voice.changer.ui.SharedVm

/** Beatrice page: CPU backend and its tunables. */
class BeatriceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vm = SharedVm.get(application)
        setContent { AppTheme { BeatriceScreen(vm) { finish() } } }
    }
}
