package org.voice.changer.ui

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import org.voice.changer.MainViewModel

/**
 * One [MainViewModel] for the whole process.
 *
 * The engine and the stored parameters are process-wide, but a ViewModel is
 * not: a page opened with its own instance would show the values it captured
 * when it was created, and the sliders on the other pages would go stale the
 * moment anything changed. All pages therefore share this instance.
 */
object SharedVm {
    private val store = ViewModelStore()

    @Volatile
    private var instance: MainViewModel? = null

    fun get(app: Application): MainViewModel = instance ?: synchronized(this) {
        instance ?: ViewModelProvider(
            store,
            ViewModelProvider.AndroidViewModelFactory.getInstance(app),
        ).get(MainViewModel::class.java).also { instance = it }
    }
}
