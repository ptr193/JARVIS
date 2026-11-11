package com.jarvis.pineapple.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color

val JarvisBlue = Color(0xFF3D6B8A)
val JarvisDark = Color(0xFF1C1916)
val JarvisAccent = Color(0xFFC9A227)
val JarvisSurface = Color(0xFF26221E)

private val DarkColors = darkColorScheme(
    primary = JarvisAccent,
    secondary = Color(0xFFD4B896),
    tertiary = Color(0xFF8B9A7D),
    background = JarvisDark,
    surface = JarvisSurface,
    surfaceVariant = Color(0xFF2F2A24),
    onPrimary = Color(0xFF1C1916),
    onSecondary = Color(0xFF1C1916),
    onBackground = Color(0xFFEDE6D9),
    onSurface = Color(0xFFE8DFD0),
    onSurfaceVariant = Color(0xFFC9BBA8)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF6B5A3E),
    secondary = Color(0xFFA89070),
    tertiary = Color(0xFF6E7A62),
    background = Color(0xFFF6F0E6),
    surface = Color(0xFFFFF8EE),
    surfaceVariant = Color(0xFFEFE6D6),
    onPrimary = Color.White,
    onSecondary = Color(0xFF1C1916),
    onBackground = Color(0xFF3D3428),
    onSurface = Color(0xFF3D3428),
    onSurfaceVariant = Color(0xFF5C5246)
)

@Composable
fun JarvisTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val uiRepo = remember {
        runCatching {
            org.koin.core.context.GlobalContext.get().get<UiCustomizationRepository>()
        }.getOrNull()
    }
    val customColor by (uiRepo?.themeColor ?: kotlinx.coroutines.flow.MutableStateFlow(0L)).collectAsState()

    val colors = if (darkTheme) DarkColors else LightColors
    val finalColors = if (customColor != 0L) {
        val accent = Color(UiCustomizationRepository.toArgb(customColor))
        colors.copy(primary = accent, secondary = accent)
    } else {
        colors
    }
    MaterialTheme(colorScheme = finalColors, content = content)
}
