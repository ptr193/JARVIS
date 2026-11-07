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
    // 读取用户自定义主题色（规格书 4.3），未设置时使用默认主题
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
