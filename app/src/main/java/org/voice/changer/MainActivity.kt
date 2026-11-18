package org.voice.changer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.voice.changer.ui.AppTheme
import org.voice.changer.ui.ChangerApp

/** The app's only activity; pages live in [ChangerApp]. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { ChangerApp() } }
    }
}
