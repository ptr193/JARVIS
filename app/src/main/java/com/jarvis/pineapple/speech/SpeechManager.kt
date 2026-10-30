package com.jarvis.pineapple.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * 语音管理器。
 *
 * 功能：
 * - 语音识别（ASR）
 * - 语音合成（TTS）
 * - 语音对话流程管理
 * - 语音唤醒
 */
class SpeechManager(private val context: Context) {

    // ==================== TTS 状态 ====================

    /**
     * TTS 状态
     */
    data class TtsState(
        val isReady: Boolean = false,
        val isSpeaking: Boolean = false,
        val isPaused: Boolean = false,
        val currentText: String = "",
        val error: String? = null
    )

    private val _ttsState = MutableStateFlow(TtsState())
    val ttsState: StateFlow<TtsState> = _ttsState.asStateFlow()

    // ==================== TTS 配置 ====================

    /**
     * TTS 配置
     */
    data class TtsConfig(
        val language: String = "zh-CN",
        val pitch: Float = 1.0f,           // 音调 0.5-2.0
        val speechRate: Float = 1.0f,      // 语速 0.5-2.0
        val volume: Float = 1.0f           // 音量 0.0-1.0
    )

    private var ttsConfig = TtsConfig()

    /**
     * 配置 TTS
     */
    fun configure(config: TtsConfig) {
        ttsConfig = config
        tts?.apply {
            language = Locale.forLanguageTag(config.language)
            setPitch(config.pitch)
            setSpeechRate(config.speechRate)
        }
    }

    /**
     * 获取 TTS 配置
     */
    fun getTtsConfig(): TtsConfig = ttsConfig

    // ==================== 语音合成 ====================

    private var tts: TextToSpeech? = null

    init {
        initializeTts()
    }

    private fun initializeTts() {
        // 部分真机/精简 ROM 没有 TTS 引擎，或引擎在初始化期间异常。
        // TextToSpeech 构造与回调都不得让异常冒泡到主线程（会直接闪退），故整体隔离。
        runCatching {
            tts = TextToSpeech(context) { status ->
                runCatching {
                    if (status == TextToSpeech.SUCCESS) {
                        tts?.apply {
                            language = Locale.forLanguageTag(ttsConfig.language)
                            setPitch(ttsConfig.pitch)
                            setSpeechRate(ttsConfig.speechRate)
                            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                                override fun onStart(utteranceId: String?) {
                                    _ttsState.value = _ttsState.value.copy(isSpeaking = true)
                                }

                                override fun onDone(utteranceId: String?) {
                                    _ttsState.value = _ttsState.value.copy(
                                        isSpeaking = false,
                                        isPaused = false
                                    )
                                }

                                @Deprecated("deprecated in TTS API")
                                override fun onError(utteranceId: String?) {
                                    _ttsState.value = _ttsState.value.copy(
                                        isSpeaking = false,
                                        error = "TTS 错误"
                                    )
                                }
                            })
                        }
                        _ttsState.value = _ttsState.value.copy(isReady = true)
                    } else {
                        _ttsState.value = _ttsState.value.copy(
                            error = "TTS 初始化失败"
                        )
                    }
                }.onFailure {
                    Log.w("SpeechManager", "TTS 回调处理失败", it)
                    _ttsState.value = _ttsState.value.copy(error = "TTS 初始化失败")
                }
            }
        }.onFailure {
            Log.e("SpeechManager", "TTS 初始化异常", it)
            tts = null
            _ttsState.value = _ttsState.value.copy(error = "TTS 初始化失败")
        }
    }

    /**
     * 语音合成（ speak ）
     */
    fun speak(text: String, utteranceId: String = "utterance_${System.currentTimeMillis()}") {
        if (_ttsState.value.isReady) {
            _ttsState.value = _ttsState.value.copy(
                currentText = text,
                isSpeaking = true
            )
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
    }

    /**
     * 停止语音合成
     */
    fun stopSpeaking() {
        tts?.stop()
        _ttsState.value = _ttsState.value.copy(
            isSpeaking = false,
            isPaused = false
        )
    }

    /**
     * 暂停语音合成
     */
    fun pauseSpeaking() {
        // Android TTS 不直接支持暂停，需要记录位置
        stopSpeaking()
        _ttsState.value = _ttsState.value.copy(isPaused = true)
    }

    /**
     * 继续语音合成
     */
    fun resumeSpeaking() {
        if (_ttsState.value.isPaused && _ttsState.value.currentText.isNotEmpty()) {
            speak(_ttsState.value.currentText)
        }
    }

    // ==================== 语音对话流程 ====================

    /**
     * 语音对话状态
     */
    data class VoiceConversationState(
        val isListening: Boolean = false,
        val isProcessing: Boolean = false,
        val isSpeaking: Boolean = false,
        val currentInput: String = "",
        val currentOutput: String = "",
        val error: String? = null
    )

    private val _conversationState = MutableStateFlow(VoiceConversationState())
    val conversationState: StateFlow<VoiceConversationState> = _conversationState.asStateFlow()

    /**
     * 开始语音对话
     */
    fun startVoiceConversation() {
        _conversationState.value = VoiceConversationState(
            isListening = true
        )
    }

    /**
     * 停止语音对话
     */
    fun stopVoiceConversation() {
        _conversationState.value = VoiceConversationState()
    }

    /**
     * 处理语音输入
     */
    fun processVoiceInput(text: String) {
        _conversationState.value = _conversationState.value.copy(
            isListening = false,
            isProcessing = true,
            currentInput = text
        )
    }

    /**
     * 处理完成
     */
    fun onProcessingComplete(response: String) {
        _conversationState.value = _conversationState.value.copy(
            isProcessing = false,
            isSpeaking = true,
            currentOutput = response
        )
        speak(response)
    }

    /**
     * 处理错误
     */
    fun onError(error: String) {
        _conversationState.value = _conversationState.value.copy(
            isListening = false,
            isProcessing = false,
            error = error
        )
    }

    // ==================== 清理 ====================

    /**
     * 释放资源
     */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    // ==================== 支持的语言 ====================

    companion object {
        /**
         * 支持的语言列表
         */
        val SUPPORTED_LANGUAGES = listOf(
            "zh-CN" to "中文（简体）",
            "zh-TW" to "中文（繁体）",
            "en-US" to "English (US)",
            "en-GB" to "English (UK)",
            "ja-JP" to "日本語",
            "ko-KR" to "한국어",
            "fr-FR" to "Français",
            "de-DE" to "Deutsch",
            "es-ES" to "Español",
            "ru-RU" to "Русский",
            "it-IT" to "Italiano",
            "pt-BR" to "Português (Brasil)",
            "ar-SA" to "العربية",
            "hi-IN" to "हिन्दी",
            "th-TH" to "ไทย",
            "vi-VN" to "Tiếng Việt",
            "id-ID" to "Bahasa Indonesia",
            "tr-TR" to "Türkçe",
            "pl-PL" to "Polski",
            "nl-NL" to "Nederlands",
            "sv-SE" to "Svenska",
            "fi-FI" to "Suomi",
            "da-DK" to "Dansk",
            "nb-NO" to "Norsk",
            "el-GR" to "Ελληνικά",
            "cs-CZ" to "Čeština",
            "hu-HU" to "Magyar",
            "ro-RO" to "Română",
            "uk-UA" to "Українська",
            "he-IL" to "עברית",
            "fa-IR" to "فارسی",
            "ur-PK" to "اردو",
            "bn-BD" to "বাংলা",
            "ta-IN" to "தமிழ்",
            "te-IN" to "తెలుగు",
            "mr-IN" to "मराठी",
            "gu-IN" to "ગુજરાતી",
            "kn-IN" to "ಕನ್ನಡ",
            "ml-IN" to "മലയാളം",
            "pa-IN" to "ਪੰਜਾਬੀ",
            "sw-KE" to "Kiswahili",
            "tl-PH" to "Filipino",
            "ca-ES" to "Català",
            "eu-ES" to "Euskara",
            "gl-ES" to "Galego",
            "is-IS" to "Íslenska",
            "ga-IE" to "Gaeilge",
            "sq-AL" to "Shqip",
            "mk-MK" to "Македонски",
            "sr-RS" to "Српски",
            "hr-HR" to "Hrvatski",
            "sl-SI" to "Slovenščina",
            "sk-SK" to "Slovenčina",
            "bg-BG" to "Български",
            "et-EE" to "Eesti",
            "lv-LV" to "Latviešu",
            "lt-LT" to "Lietuvių",
            "be-BY" to "Беларуская",
            "kk-KZ" to "Қазақ",
            "ky-KG" to "Кыргызча",
            "uz-UZ" to "Oʻzbek",
            "mn-MN" to "Монгол",
            "ne-NP" to "नेपाली",
            "si-LK" to "සිංහල",
            "my-MM" to "မြန်မာ",
            "km-KH" to "ខ្មែរ",
            "lo-LA" to "ລາວ",
            "hy-AM" to "Հայերեն",
            "az-AZ" to "Azərbaycan",
            "ka-GE" to "ქართული",
            "tg-TJ" to "Тоҷикӣ",
            "tk-TM" to "Türkmen",
            "ps-AF" to "پښتو",
            "sd-PK" to "سنڌي",
            "ku-TR" to "Kurdî",
            "am-ET" to "አማርኛ",
            "ti-ER" to "ትግርኛ",
            "om-ET" to "Afaan Oromoo",
            "zu-ZA" to "isiZulu",
            "af-ZA" to "Afrikaans",
            "xh-ZA" to "isiXhosa",
            "st-ZA" to "Sesotho",
            "tn-ZA" to "Setswana",
            "nso-ZA" to "Sepedi",
            "ve-ZA" to "Tshivenda",
            "ts-ZA" to "Xitsonga",
            "ss-ZA" to "siSwati",
            "nr-ZA" to "isiNdebele"
        )
    }
}
