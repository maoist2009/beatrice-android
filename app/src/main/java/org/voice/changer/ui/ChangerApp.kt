package org.voice.changer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import org.voice.changer.MainViewModel

/**
 * The whole app: one activity, five pages behind a bottom bar.
 *
 * The pages share one [MainViewModel], so a parameter moved on one page is
 * what the others show and a conversion keeps running while you look at
 * something else. The saveable state holder keeps each page's scroll
 * position, so switching tabs and coming back does not jump to the top.
 */
@Composable
fun ChangerApp(vm: MainViewModel = viewModel()) {
    var page by rememberSaveable { mutableStateOf(Page.Home) }
    val pages = rememberSaveableStateHolder()

    // Back from a tab returns home; from home it leaves the app.
    BackHandler(enabled = page != Page.Home) { page = Page.Home }

    pages.SaveableStateProvider(page) {
        when (page) {
            Page.Home -> XposedScreen(vm) { page = it }
            Page.Rvc -> RvcScreen(vm) { page = it }
            Page.Beatrice -> BeatriceScreen(vm) { page = it }
            Page.Convert -> ConvertScreen(vm) { page = it }
            Page.About -> AboutScreen { page = it }
        }
    }
}
