package org.voice.changer.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

private val LightScheme = lightColorScheme(
    primary = Color(0xFF4F4BC4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E1FF),
    onPrimaryContainer = Color(0xFF120E6B),
    secondary = Color(0xFF5C5A70),
    background = Color(0xFFF7F7FB),
    surface = Color.White,
    surfaceVariant = Color(0xFFEDEDF4),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFC2C0FF),
    onPrimary = Color(0xFF201A75),
    primaryContainer = Color(0xFF37318C),
    onPrimaryContainer = Color(0xFFE3E1FF),
    background = Color(0xFF121216),
    surface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFF2A2A33),
)

/** Material 3 colours shared by every page. */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            content()
        }
    }
}
