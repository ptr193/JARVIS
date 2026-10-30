package com.jarvis.pineapple.chat

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar

/**
 * 对话增强器（功能 #9-#10, #13-#18）。
 *
 * 功能：
 * - 语气与情绪适配
 * - 主动询问与确认
 * - 外语实时转译
 * - 极简指令执行
 * - 语境感知应答
 * - 反讽识别与回应
 * - 修辞与指令区分
 * - 隐含意图推断
 */
class ConversationEnhancer(private val context: Context) {

    // ==================== 语气与情绪适配（#9） ====================

    /**
     * 情绪状态
     */
    data class EmotionalState(
        val userEmotion: Emotion = Emotion.NEUTRAL,
        val confidence: Float = 0f,
        val detectedAt: Long = System.currentTimeMillis()
    )

    enum class Emotion {
        HAPPY, SAD, ANGRY, ANXIOUS, EXCITED, TIRED, NEUTRAL
    }

    private val _emotionalState = MutableStateFlow(EmotionalState())
    val emotionalState: StateFlow<EmotionalState> = _emotionalState.asStateFlow()

    /**
     * 分析用户情绪
     */
    fun analyzeEmotion(message: String): EmotionalState {
        val lowerMessage = message.lowercase()

        val emotion = when {
            // 积极情绪
            lowerMessage.containsAny("开心", "高兴", "太好了", "棒", "优秀", "感谢", "happy", "great", "thanks") -> Emotion.HAPPY
            lowerMessage.containsAny("兴奋", "期待", "激动", "excited", "looking forward") -> Emotion.EXCITED

            // 消极情绪
            lowerMessage.containsAny("难过", "伤心", "失望", "沮丧", "sad", "disappointed") -> Emotion.SAD
            lowerMessage.containsAny("生气", "愤怒", "烦", "讨厌", "angry", "annoyed") -> Emotion.ANGRY
            lowerMessage.containsAny("焦虑", "担心", "紧张", "anxious", "worried") -> Emotion.ANXIOUS
            lowerMessage.containsAny("累", "疲惫", "困", "tired", "exhausted") -> Emotion.TIRED

            // 默认中性
            else -> Emotion.NEUTRAL
        }

        val state = EmotionalState(
            userEmotion = emotion,
            confidence = 0.75f
        )
        _emotionalState.value = state
        return state
    }

    /**
     * 根据情绪调整回复风格
     */
    fun adjustResponseTone(response: String, emotion: Emotion): String {
        return when (emotion) {
            Emotion.HAPPY -> response // 保持正常风格
            Emotion.EXCITED -> response // 保持正常风格
            Emotion.SAD -> "我理解您的感受。$response\n\n如果您需要倾诉，我随时在这里。"
            Emotion.ANGRY -> "我理解您的不满。$response\n\n让我尽力帮您解决问题。"
            Emotion.ANXIOUS -> "请放心，一切都会好起来的。$response"
            Emotion.TIRED -> "您今天辛苦了。$response\n\n记得适当休息。"
            Emotion.NEUTRAL -> response
        }
    }

    // ==================== 主动询问与确认（#10） ====================

    /**
     * 意图置信度
     */
    data class IntentConfidence(
        val intent: String,
        val confidence: Float,
        val needsConfirmation: Boolean,
        val clarificationQuestion: String? = null
    )

    /**
     * 分析意图置信度
     */
    fun analyzeIntentConfidence(message: String): IntentConfidence {
        val intent = classifyIntent(message)
        val confidence = calculateConfidence(message, intent)

        val needsConfirmation = confidence < 0.7f
        val clarificationQuestion = if (needsConfirmation) {
            generateClarificationQuestion(message, intent)
        } else {
            null
        }

        return IntentConfidence(
            intent = intent,
            confidence = confidence,
            needsConfirmation = needsConfirmation,
            clarificationQuestion = clarificationQuestion
        )
    }

    private fun classifyIntent(message: String): String {
        val lowerMessage = message.lowercase()
        return when {
            lowerMessage.containsAny("打开", "开启", "启动", "open", "start") -> "OPEN_APP"
            lowerMessage.containsAny("关闭", "关掉", "停止", "close", "stop") -> "CLOSE_APP"
            lowerMessage.containsAny("发送", "发短信", "send") -> "SEND_MESSAGE"
            lowerMessage.containsAny("打电话", "拨打", "call") -> "MAKE_CALL"
            lowerMessage.containsAny("设置", "修改", "更改", "set", "change") -> "SETTINGS"
            lowerMessage.containsAny("搜索", "查找", "查询", "search", "find") -> "SEARCH"
            else -> "UNKNOWN"
        }
    }

    private fun calculateConfidence(message: String, intent: String): Float {
        // 基于消息长度、关键词匹配度等计算置信度
        val lengthScore = (message.length.coerceAtMost(50) / 50f) * 0.3f
        val keywordScore = if (intent != "UNKNOWN") 0.6f else 0.2f
        val contextScore = 0.1f

        return (lengthScore + keywordScore + contextScore).coerceIn(0f, 1f)
    }

    private fun generateClarificationQuestion(message: String, intent: String): String {
        return when (intent) {
            "OPEN_APP" -> "请问您想打开哪个应用？"
            "CLOSE_APP" -> "请问您想关闭哪个应用？"
            "SEND_MESSAGE" -> "请问您想发送给谁？内容是什么？"
            "MAKE_CALL" -> "请问您想拨打给谁？"
            "SETTINGS" -> "请问您想修改什么设置？"
            "SEARCH" -> "请问您想搜索什么内容？"
            else -> "抱歉，我没有理解您的意思。能请您详细说明一下吗？"
        }
    }

    // ==================== 外语实时转译（#13） ====================

    /**
     * 转译结果
     */
    data class TranslationResult(
        val originalText: String,
        val translatedText: String,
        val sourceLanguage: String,
        val targetLanguage: String,
        val confidence: Float
    )

    /**
     * 实时转译
     */
    suspend fun translate(text: String, sourceLang: String = "auto", targetLang: String = "zh"): TranslationResult {
        // 使用 TranslationEngine 进行真实翻译
        val engine = TranslationEngine()
        val result = engine.translate(text, targetLang, sourceLang)
        return TranslationResult(
            originalText = result.originalText,
            translatedText = result.translatedText,
            sourceLanguage = result.sourceLanguage,
            targetLanguage = result.targetLanguage,
            confidence = result.confidence
        )
    }

    /**
     * 检测语言
     */
    fun detectLanguage(text: String): String {
        if (text.isEmpty()) return "unknown"
        var chineseCount = 0
        var japaneseCount = 0
        var koreanCount = 0
        var russianCount = 0
        var englishCount = 0
        for (ch in text) {
            val code = ch.code
            when {
                code in 0x4E00..0x9FFF -> chineseCount++
                code in 0x3040..0x309F || code in 0x30A0..0x30FF -> japaneseCount++
                code in 0xAC00..0xD7AF -> koreanCount++
                code in 0x0400..0x04FF || code in 0x0500..0x052F -> russianCount++
                (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') -> englishCount++
            }
        }
        val counts = listOf("zh" to chineseCount, "ja" to japaneseCount, "ko" to koreanCount, "ru" to russianCount, "en" to englishCount)
        return counts.maxByOrNull { it.second }?.first ?: "unknown"
    }

    // ==================== 极简指令执行（#14） ====================

    /**
     * 极简指令
     */
    data class SimpleCommand(
        val action: String,
        val parameters: Map<String, String>,
        val isExecutable: Boolean
    )

    /**
     * 解析极简指令
     */
    fun parseSimpleCommand(message: String): SimpleCommand {
        val lowerMessage = message.lowercase().trim()

        return when {
            // 打开应用
            lowerMessage.containsAny("打开", "open") -> {
                val appName = extractAppName(message)
                SimpleCommand("OPEN_APP", mapOf("app" to appName), true)
            }
            // 关闭应用
            lowerMessage.containsAny("关闭", "关掉", "close") -> {
                val appName = extractAppName(message)
                SimpleCommand("CLOSE_APP", mapOf("app" to appName), true)
            }
            // 调节音量
            lowerMessage.containsAny("音量", "volume") -> {
                val level = extractNumber(message)
                SimpleCommand("SET_VOLUME", mapOf("level" to level), true)
            }
            // 调节亮度
            lowerMessage.containsAny("亮度", "brightness") -> {
                val level = extractNumber(message)
                SimpleCommand("SET_BRIGHTNESS", mapOf("level" to level), true)
            }
            // 无法解析
            else -> SimpleCommand("UNKNOWN", emptyMap(), false)
        }
    }

    private fun extractAppName(message: String): String {
        // 提取应用名称
        val keywords = listOf("打开", "开启", "启动", "关闭", "关掉", "open", "close")
        var result = message
        for (keyword in keywords) {
            result = result.replace(keyword, "", ignoreCase = true)
        }
        return result.trim()
    }

    private fun extractNumber(message: String): String {
        val regex = Regex("\\d+")
        return regex.find(message)?.value ?: "50"
    }

    // ==================== 语境感知应答（#15） ====================

    /**
     * 语境信息
     */
    data class ContextInfo(
        val timeOfDay: TimeOfDay = TimeOfDay.UNKNOWN,
        val currentApp: String = "",
        val location: String = "",
        val recentActivity: String = ""
    )

    enum class TimeOfDay {
        MORNING, AFTERNOON, EVENING, NIGHT, UNKNOWN
    }

    /**
     * 获取当前语境
     */
    fun getCurrentContext(): ContextInfo {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)

        val timeOfDay = when (hour) {
            in 6..11 -> TimeOfDay.MORNING
            in 12..17 -> TimeOfDay.AFTERNOON
            in 18..22 -> TimeOfDay.EVENING
            else -> TimeOfDay.NIGHT
        }

        return ContextInfo(
            timeOfDay = timeOfDay,
            currentApp = "",
            location = "",
            recentActivity = ""
        )
    }

    /**
     * 基于语境调整回复
     */
    fun contextAwareResponse(response: String, context: ContextInfo): String {
        return when (context.timeOfDay) {
            TimeOfDay.MORNING -> "早上好。$response"
            TimeOfDay.AFTERNOON -> "下午好。$response"
            TimeOfDay.EVENING -> "晚上好。$response"
            TimeOfDay.NIGHT -> "夜深了，注意休息。$response"
            TimeOfDay.UNKNOWN -> response
        }
    }

    // ==================== 反讽识别与回应（#16） ====================

    /**
     * 反讽识别结果
     */
    data class SarcasmDetectionResult(
        val isSarcastic: Boolean = false,
        val confidence: Float = 0f,
        val actualMeaning: String = ""
    )

    /**
     * 识别反讽
     */
    fun detectSarcasm(message: String): SarcasmDetectionResult {
        val lowerMessage = message.lowercase()

        // 反讽关键词和模式
        val sarcasmIndicators = listOf(
            "真好", "真棒", "厉害", "了不起", "太好了",
            "wow", "great", "awesome", "fantastic", "perfect"
        )

        val hasSarcasmIndicator = sarcasmIndicators.any { lowerMessage.contains(it) }
        val hasExclamation = message.count { it == '!' } > 1
        val hasQuotes = message.contains("\"") || message.contains("'")

        val isSarcastic = hasSarcasmIndicator && (hasExclamation || hasQuotes)

        return SarcasmDetectionResult(
            isSarcastic = isSarcastic,
            confidence = if (isSarcastic) 0.7f else 0.3f,
            actualMeaning = if (isSarcastic) "检测到反讽，实际意思可能相反" else message
        )
    }

    // ==================== 修辞与指令区分（#17） ====================

    /**
     * 语句类型
     */
    enum class StatementType {
        COMMAND,    // 指令
        QUESTION,   // 疑问
        STATEMENT,  // 陈述
        RHETORIC    // 修辞
    }

    /**
     * 区分修辞与指令
     */
    fun classifyStatement(message: String): StatementType {
        val trimmed = message.trim()

        // 疑问句
        if (trimmed.endsWith("?") || trimmed.endsWith("？") ||
            trimmed.containsAny("吗", "呢", "什么", "为什么", "怎么", "哪", "谁", "多少")) {
            return StatementType.QUESTION
        }

        // 指令句
        if (trimmed.containsAny("请", "帮我", "给我", "打开", "关闭", "发送", "拨打", "设置") ||
            trimmed.startsWith("请") || trimmed.startsWith("帮")) {
            return StatementType.COMMAND
        }

        // 修辞句（反问、感叹等）
        if (trimmed.containsAny("难道", "岂不是", "何必", "何苦") ||
            (trimmed.endsWith("!") && trimmed.length < 10)) {
            return StatementType.RHETORIC
        }

        return StatementType.STATEMENT
    }

    // ==================== 隐含意图推断（#18） ====================

    /**
     * 隐含意图
     */
    data class ImplicitIntent(
        val surfaceIntent: String,
        val implicitIntent: String,
        val confidence: Float,
        val reasoning: String
    )

    /**
     * 推断隐含意图
     */
    fun inferImplicitIntent(message: String): ImplicitIntent {
        val lowerMessage = message.lowercase()

        // 表面意图
        val surfaceIntent = classifyIntent(message)

        // 推断隐含意图
        val implicitIntent = when {
            // "好热" -> 可能想开空调
            lowerMessage.containsAny("热", "好热", "太热") -> "ADJUST_TEMPERATURE"
            // "好冷" -> 可能想关空调或开暖气
            lowerMessage.containsAny("冷", "好冷", "太冷") -> "ADJUST_TEMPERATURE"
            // "好暗" -> 可能想开灯
            lowerMessage.containsAny("暗", "好暗", "看不清") -> "TURN_ON_LIGHT"
            // "好吵" -> 可能想关音乐或调低音量
            lowerMessage.containsAny("吵", "好吵", "太吵") -> "REDUCE_VOLUME"
            // "好无聊" -> 可能想找点事做
            lowerMessage.containsAny("无聊", "好无聊", "bored") -> "ENTERTAINMENT"
            // "好饿" -> 可能想点外卖或找餐厅
            lowerMessage.containsAny("饿", "好饿", "hungry") -> "FOOD_DELIVERY"
            // 无法推断
            else -> surfaceIntent
        }

        val confidence = if (implicitIntent != surfaceIntent) 0.65f else 0.9f

        return ImplicitIntent(
            surfaceIntent = surfaceIntent,
            implicitIntent = implicitIntent,
            confidence = confidence,
            reasoning = if (implicitIntent != surfaceIntent) {
                "基于语境推断用户真实需求"
            } else {
                "表面意图即为真实意图"
            }
        )
    }

    private fun String.containsAny(vararg keywords: String): Boolean {
        return keywords.any { this.contains(it, ignoreCase = true) }
    }
}
