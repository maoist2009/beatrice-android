package org.voice.changer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.voice.changer.ui.AboutScreen
import org.voice.changer.ui.AppTheme

/** About page: static text, no engine state. */
class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { AboutScreen { finish() } } }
    }
}
