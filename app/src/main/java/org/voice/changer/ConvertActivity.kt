package org.voice.changer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.voice.changer.ui.AppTheme
import org.voice.changer.ui.backToHome
import org.voice.changer.ui.openPage
import org.voice.changer.ui.ConvertScreen
import org.voice.changer.ui.SharedVm

/** Convert page: offline file conversion. */
class ConvertActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backToHome()
        val vm = SharedVm.get(application)
        setContent { AppTheme { ConvertScreen(vm) { openPage(it) } } }
    }
}
