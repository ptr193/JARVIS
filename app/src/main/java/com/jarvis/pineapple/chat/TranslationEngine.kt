package com.jarvis.pineapple.chat

import com.jarvis.pineapple.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/**
 * HTTP 翻译客户端。
 *
 * 服务地址由 BuildConfig.TRANSLATE_API_BASE_URL 配置，响应须为 JSON 且包含
 * `translated_text` 字符串。未配置、HTTP 失败或响应无效都会返回明确错误，
 * 不会把原文或搜索摘要冒充译文。
 */
class TranslationEngine(
    private val apiBaseUrl: String = BuildConfig.TRANSLATE_API_BASE_URL
) {
    private val httpClient = HttpClient(Android)

    data class TranslationResult(
        val originalText: String,
        val translatedText: String,
        val sourceLanguage: String,
        val targetLanguage: String,
        val confidence: Float,
        val alternatives: List<String> = emptyList(),
        val errorMessage: String? = null
    ) {
        val isSuccess: Boolean get() = errorMessage == null
    }

    data class LanguageInfo(val code: String, val name: String, val nativeName: String)

    suspend fun translate(
        text: String,
        targetLanguage: String = "zh",
        sourceLanguage: String = "auto"
    ): TranslationResult {
        if (text.isBlank()) return failure(text, sourceLanguage, targetLanguage, "翻译内容为空")
        if (apiBaseUrl.isBlank()) {
            return failure(text, sourceLanguage, targetLanguage, "未配置翻译服务地址")
        }
        if (apiBaseUrl.contains("duckduckgo", ignoreCase = true)) {
            return failure(text, sourceLanguage, targetLanguage, "DuckDuckGo 搜索页不是翻译服务，请配置真实翻译 API")
        }
        return withContext(Dispatchers.IO) {
            try {
                val encodedText = URLEncoder.encode(text, "UTF-8")
                val response = httpClient.get(
                    "$apiBaseUrl?q=$encodedText&sl=$sourceLanguage&tl=$targetLanguage"
                )
                if (response.status.value !in 200..299) {
                    failure(text, sourceLanguage, targetLanguage, "翻译服务返回 HTTP ${response.status.value}")
                } else {
                    parseGenericResponse(text, sourceLanguage, targetLanguage, response.bodyAsText())
                }
            } catch (e: Exception) {
                failure(text, sourceLanguage, targetLanguage, "翻译服务不可用: ${e.message ?: "网络错误"}")
            }
        }
    }

    suspend fun detectLanguage(text: String): String = withContext(Dispatchers.Default) {
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
    }

    suspend fun translateBatch(
        texts: List<String>,
        targetLanguage: String = "zh",
        sourceLanguage: String = "auto"
    ): List<TranslationResult> = texts.map { translate(it, targetLanguage, sourceLanguage) }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        internal fun parseGenericResponse(
            originalText: String,
            sourceLanguage: String,
            targetLanguage: String,
            responseText: String
        ): TranslationResult {
            val translated = try {
                json.parseToJsonElement(responseText).jsonObject["translated_text"]
                    ?.jsonPrimitive?.contentOrNull
            } catch (_: Exception) {
                null
            }
            return if (translated.isNullOrBlank()) {
                failure(originalText, sourceLanguage, targetLanguage, "翻译服务响应缺少 translated_text")
            } else {
                TranslationResult(
                    originalText = originalText,
                    translatedText = translated,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    confidence = 0f
                )
            }
        }

        internal fun failure(
            originalText: String,
            sourceLanguage: String,
            targetLanguage: String,
            message: String
        ) = TranslationResult(
            originalText = originalText,
            translatedText = "",
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            confidence = 0f,
            errorMessage = message
        )

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
