package com.jarvis.pineapple.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val JarvisBlue = Color(0xFF1E88E5)
val JarvisDark = Color(0xFF0D1117)
val JarvisAccent = Color(0xFF39FF14)
val JarvisSurface = Color(0xFF161B22)

private val DarkColors = darkColorScheme(
    primary = JarvisBlue,
    secondary = JarvisAccent,
    background = JarvisDark,
    surface = JarvisSurface,
    onPrimary = Color.White,
    onBackground = Color.White,
    onSurface = Color(0xFFE6EDF3)
)

private val LightColors = lightColorScheme(
    primary = JarvisBlue,
    secondary = JarvisAccent,
    background = Color(0xFFF5F5F5),
    surface = Color.White
)

@Composable
fun JarvisTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
