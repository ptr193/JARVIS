package com.jarvis.pineapple.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 界面自定义设置仓库（规格书 4.3 界面自定义）。
 *
 * 真实持久化：背景图（用户经 SAF 选择的图片 URI）与主题色（用户选择的颜色值）,
 * 均写入应用私有 SharedPreferences 并通过 StateFlow 实时驱动界面重绘。
 */
class UiCustomizationRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _backgroundUri = MutableStateFlow(prefs.getString(KEY_BACKGROUND, null))
    val backgroundUri: StateFlow<String?> = _backgroundUri.asStateFlow()

    private val _themeColor = MutableStateFlow(prefs.getLong(KEY_THEME_COLOR, 0L))
    /** 0 表示使用默认主题色。 */
    val themeColor: StateFlow<Long> = _themeColor.asStateFlow()

    fun setBackgroundUri(uri: String?) {
        prefs.edit().apply {
            if (uri == null) remove(KEY_BACKGROUND) else putString(KEY_BACKGROUND, uri)
        }.apply()
        _backgroundUri.value = uri
    }

    fun setThemeColor(argb: Long) {
        prefs.edit().putLong(KEY_THEME_COLOR, argb).apply()
        _themeColor.value = argb
    }

    fun clearThemeColor() = setThemeColor(0L)

    /** 可选主题色预设（供设置页展示）。 */
    companion object {
        private const val PREFS = "jarvis_ui"
        private const val KEY_BACKGROUND = "background_uri"
        private const val KEY_THEME_COLOR = "theme_color"

        /** 预设主题色（名称 → ARGB，必须用 Long 字面量避免 Int 溢出）。 */
        val PRESET_COLORS: List<Pair<String, Long>> = listOf(
            "JARVIS 蓝" to 0xFF1E88E5L,
            "反应堆青" to 0xFF00E5FFL,
            "方舟白" to 0xFFECEFF1L,
            "警戒红" to 0xFFE53935L,
            "贾维斯金" to 0xFFFFC107L,
            "翠绿" to 0xFF39FF14L,
            "暗紫" to 0xFF7C4DFFL,
            "冰蓝" to 0xFF00B0FFL
        )

        fun toArgb(stored: Long): Int = (stored and 0xFFFFFFFFL).toInt()

        fun isCustom(stored: Long): Boolean = stored != 0L
    }
}
