package org.voice.changer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.voice.changer.ui.AppTheme
import org.voice.changer.ui.SharedVm
import org.voice.changer.ui.XposedScreen

/** Home page: live voice change and the Xposed hook. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vm = SharedVm.get(application)
        setContent {
            AppTheme {
                XposedScreen(vm = vm) { target -> startActivity(Intent(this, target)) }
            }
        }
    }
}
