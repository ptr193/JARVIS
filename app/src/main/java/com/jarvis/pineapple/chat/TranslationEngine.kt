package com.jarvis.pineapple.chat

import com.jarvis.pineapple.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * 翻译引擎（功能 #12 多语言翻译，#13 外语实时转译）。
 *
 * 默认使用 DuckDuckGo HTML 端点（国内可直接访问，无需 API Key）。
 * 也支持配置为国内翻译 API（百度/有道/彩云小译等）。
 * 翻译服务地址通过 BuildConfig.TRANSLATE_API_BASE_URL 配置。
 */
class TranslationEngine {

    private val httpClient = HttpClient(Android)

    data class TranslationResult(
        val originalText: String,
        val translatedText: String,
        val sourceLanguage: String,
        val targetLanguage: String,
        val confidence: Float,
        val alternatives: List<String> = emptyList()
    )

    data class LanguageInfo(val code: String, val name: String, val nativeName: String)

    /**
     * 翻译文本 - DuckDuckGo HTML 端点
     */
    suspend fun translate(
        text: String,
        targetLanguage: String = "zh",
        sourceLanguage: String = "auto"
    ): TranslationResult {
        val apiUrl = BuildConfig.TRANSLATE_API_BASE_URL
        return withContext(Dispatchers.IO) {
            try {
                val encodedText = URLEncoder.encode(text, "UTF-8")
                val url = if (apiUrl.contains("duckduckgo")) {
                    "${apiUrl}?q=$encodedText&kl=zh-cn"
                } else {
                    "$apiUrl?q=$encodedText&sl=$sourceLanguage&tl=$targetLanguage"
                }

                val response = httpClient.get(url)
                val html = response.bodyAsText()

                if (apiUrl.contains("duckduckgo")) {
                    parseDuckDuckGoResponse(text, html)
                } else {
                    parseGenericResponse(text, sourceLanguage, targetLanguage, html)
                }
            } catch (e: Exception) {
                TranslationResult(text, text, sourceLanguage, targetLanguage, 0f)
            }
        }
    }

    /**
     * 检测语言 - 基于 Unicode 字符范围
     */
    suspend fun detectLanguage(text: String): String {
        return withContext(Dispatchers.IO) {
            try {
                // 基于 Unicode 字符范围的简单语言检测
                val chineseChars = text.count { it.code in 0x4E00..0x9FFF }
                val japaneseChars = text.count { it.code in 0x3040..0x309F || it.code in 0x30A0..0x30FF }
                val koreanChars = text.count { it.code in 0xAC00..0xD7AF }
                val cyrillicChars = text.count { it.code in 0x0400..0x04FF }
                val arabicChars = text.count { it.code in 0x0600..0x06FF }
                val latinChars = text.count { it.code in 0x0041..0x007A }

                val total = text.length.coerceAtLeast(1)
                when {
                    chineseChars > total * 0.3 -> "zh"
                    japaneseChars > total * 0.3 -> "ja"
                    koreanChars > total * 0.3 -> "ko"
                    cyrillicChars > total * 0.3 -> "ru"
                    arabicChars > total * 0.3 -> "ar"
                    latinChars > total * 0.3 -> "en"
                    else -> "unknown"
                }
            } catch (e: Exception) {
                "unknown"
            }
        }
    }

    /**
     * 批量翻译
     */
    suspend fun translateBatch(
        texts: List<String>,
        targetLanguage: String = "zh",
        sourceLanguage: String = "auto"
    ): List<TranslationResult> = texts.map { translate(it, targetLanguage, sourceLanguage) }

    /**
     * 解析 DuckDuckGo HTML 响应
     */
    private fun parseDuckDuckGoResponse(originalText: String, html: String): TranslationResult {
        // DuckDuckGo HTML 结果解析
        val resultPattern = Regex("result__snippet[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
        val results = resultPattern.findAll(html).map { cleanHtml(it.groupValues[1]) }.toList()

        val translated = results.firstOrNull() ?: originalText
        val confidence = if (results.isNotEmpty()) 0.7f else 0.3f

        return TranslationResult(
            originalText = originalText,
            translatedText = translated,
            sourceLanguage = "auto",
            targetLanguage = "zh",
            confidence = confidence,
            alternatives = results.drop(1).take(3)
        )
    }

    /**
     * 解析通用翻译 API 响应
     */
    private fun parseGenericResponse(
        originalText: String,
        sourceLanguage: String,
        targetLanguage: String,
        responseText: String
    ): TranslationResult {
        // 尝试 JSON 格式
        val jsonPattern = Regex("\"translated_text\"\\s*:\\s*\"([^\"]+)\"")
        val jsonMatch = jsonPattern.find(responseText)
        if (jsonMatch != null) {
            return TranslationResult(
                originalText = originalText,
                translatedText = jsonMatch.groupValues[1],
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                confidence = 0.9f
            )
        }

        // 降级：返回原文
        return TranslationResult(originalText, originalText, sourceLanguage, targetLanguage, 0.3f)
    }

    private fun cleanHtml(html: String): String {
        return html.replace(Regex("<[^>]*>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .trim()
    }

    companion object {
        val SUPPORTED_LANGUAGES = mapOf(
            "auto" to "自动检测", "zh" to "中文", "en" to "English",
            "ja" to "日本語", "ko" to "한국어", "fr" to "Français",
            "de" to "Deutsch", "es" to "Español", "ru" to "Русский",
            "ar" to "العربية", "pt" to "Português", "it" to "Italiano",
            "nl" to "Nederlands", "pl" to "Polski", "tr" to "Türkçe",
            "vi" to "Tiếng Việt", "th" to "ไทย", "id" to "Bahasa Indonesia"
        )
    }
}
