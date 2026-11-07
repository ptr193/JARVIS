package com.jarvis.pineapple.chat

import com.jarvis.pineapple.autonomous.MemorySystem
import com.jarvis.pineapple.info.FilterCriteria
import com.jarvis.pineapple.info.InfoProcessor
import com.jarvis.pineapple.info.IntelligenceItem
import com.jarvis.pineapple.model.AiModel
import com.jarvis.pineapple.model.ModelCapabilityScheduler
import com.jarvis.pineapple.model.ModelScheduler
import com.jarvis.pineapple.model.ModelState
import com.jarvis.pineapple.personality.PersonalityRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach

/**
 * 对话引擎。
 * - 多轮对话上下文管理
 * - 消息发送与接收
 * - 流式回复
 * - 对话历史持久化
 * - 人格 prompt 注入
 * - 记忆系统上下文注入（REQ-11）
 * - 模型能力短板自动调度（REQ-13）
 */
class ChatEngine(
    private val modelScheduler: ModelScheduler,
    private val personalityRepository: PersonalityRepository,
    private val conversationRepository: ConversationRepository,
    private val memorySystem: MemorySystem,
    private val capabilityScheduler: ModelCapabilityScheduler,
    private val conversationEnhancer: ConversationEnhancer,
    private val infoProcessor: InfoProcessor
) {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _lastSchedulingInfo = MutableStateFlow<String?>(null)
    val lastSchedulingInfo: StateFlow<String?> = _lastSchedulingInfo.asStateFlow()

    val conversations: StateFlow<List<Conversation>> = conversationRepository.conversations
    val currentConversationId: StateFlow<String?> = conversationRepository.currentConversationId

    private var conversation = Conversation()

    init {
        val recentConversation = conversationRepository.getCurrentConversation()
        if (recentConversation != null) {
            conversation = recentConversation
            _messages.value = recentConversation.messages.toList()
        }
    }

    private fun ensureConversation() {
        if (conversationRepository.getCurrentConversation() == null) {
            conversation = conversationRepository.createConversation()
            _messages.value = conversation.messages.toList()
        }
    }

    suspend fun sendUserMessage(content: String): Flow<String> {
        ensureConversation()
        val userMsg = ChatMessage(role = Role.USER, content = content)
        conversation.messages.add(userMsg)
        _messages.value = conversation.messages.toList()

        _isGenerating.value = true

        // ===== 翻译指令（#12/#13）：直接调用翻译引擎，无需经过大模型 =====
        parseTranslationRequest(content)?.let { (text, target) ->
            val result = conversationEnhancer.translate(text, targetLang = target)
            val reply = result.translatedText.ifBlank { "翻译失败：翻译服务不可用" }
            conversation.messages.add(ChatMessage(role = Role.ASSISTANT, content = reply, isStreaming = false))
            _messages.value = conversation.messages.toList()
            _isGenerating.value = false
            conversationRepository.updateMessages(conversation.messages.toList())
            memorySystem.recordConversation("翻译请求：${content.take(50)}")
            return flowOf(reply)
        }

        val loadedModel = modelScheduler.resolveOrLoad()
        if (loadedModel == null) {
            val error = ModelScheduler.NO_MODEL_MESSAGE
            _lastSchedulingInfo.value = error
            conversation.messages.add(ChatMessage(role = Role.ASSISTANT, content = error, isStreaming = false))
            _messages.value = conversation.messages.toList()
            _isGenerating.value = false
            conversationRepository.updateMessages(conversation.messages.toList())
            return flowOf(error)
        }

        val primaryModel: AiModel = loadedModel
        val personality = personalityRepository.personality.first()
        val systemPrompt = personalityRepository.buildSystemPrompt(personality)

        val decision = capabilityScheduler.analyzeAndDecide(content)
        _lastSchedulingInfo.value = decision.reason

        // ===== 对话增强（#9/#10/#13-#18）：情绪适配、意图澄清、语境感知、反讽与修辞区分 =====
        val emotional = conversationEnhancer.analyzeEmotion(content)
        val intent = conversationEnhancer.analyzeIntentConfidence(content)
        val sarcasm = conversationEnhancer.detectSarcasm(content)
        val statementType = conversationEnhancer.classifyStatement(content)

        val context = buildContextPrompt()
        val memoryContext = memorySystem.buildMemoryContext(content)

        // ===== 网络搜索 / 情报处理（#19-#25）：命中检索类指令时注入真实检索结果 =====
        val infoContext = buildRetrievalContext(content)

        val enhancerDirective = buildString {
            appendLine("【对话增强上下文】")
            appendLine("- 当前时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date())}")
            appendLine("- 用户情绪倾向：${emotional.userEmotion}（置信度 ${"%.2f".format(emotional.confidence)}），请在语气上做适配")
            appendLine("- 识别到的语句类型：$statementType，意图：${intent.intent}（置信度 ${"%.2f".format(intent.confidence)}）")
            if (statementType == ConversationEnhancer.StatementType.RHETORIC) {
                appendLine("- 该语句更可能是修辞表达而非指令，请勿直接执行操作")
            }
            if (sarcasm.isSarcastic) {
                appendLine("- 检测到反讽（实际含义：${sarcasm.actualMeaning}），请按真实含义回应")
            }
            if (intent.needsConfirmation) {
                appendLine("- 意图不明确，请在回答前以一句话向用户确认意图")
            }
        }

        val fullPrompt = buildString {
            if (memoryContext.isNotEmpty()) {
                append(memoryContext)
                appendLine()
            }
            if (infoContext.isNotEmpty()) {
                append(infoContext)
                appendLine()
            }
            append(enhancerDirective)
            appendLine()
            append(context)
            append("\n用户：").append(content)
            append("\nJARVIS：")
        }

        val aiMsg = ChatMessage(role = Role.ASSISTANT, content = "", isStreaming = true)
        conversation.messages.add(aiMsg)
        _messages.value = conversation.messages.toList()

        val candidate = if (decision.shouldSchedule && decision.assistantModel != null) {
            decision.assistantModel
        } else {
            primaryModel
        }
        if (candidate.state != ModelState.READY) {
            candidate.load()
        }
        val activeModel = if (candidate.state == ModelState.READY) candidate else primaryModel

        val flow = activeModel.stream(fullPrompt, systemPrompt)
        val collected = StringBuilder()

        return flow
            .onEach { chunk ->
                collected.append(chunk)
                val idx = conversation.messages.indexOfLast { it.isStreaming }
                if (idx >= 0) {
                    conversation.messages[idx] = conversation.messages[idx].copy(content = collected.toString())
                    _messages.value = conversation.messages.toList()
                }
            }
            .onCompletion {
                val idx = conversation.messages.indexOfLast { it.isStreaming }
                if (idx >= 0) {
                    // 语气与情绪适配（#9）：对最终回复做后处理
                    val raw = conversation.messages[idx].content
                    val adjusted = conversationEnhancer.adjustResponseTone(raw, emotional.userEmotion)
                    conversation.messages[idx] = conversation.messages[idx].copy(content = adjusted, isStreaming = false)
                    _messages.value = conversation.messages.toList()
                }
                _isGenerating.value = false

                conversationRepository.updateMessages(conversation.messages.toList())

                if (collected.isNotEmpty()) {
                    val summary = "用户问：${content.take(50)}... JARVIS答：${collected.toString().take(100)}..."
                    memorySystem.recordConversation(summary)
                }
            }
    }

    fun newConversation(title: String = "新对话") {
        conversation = conversationRepository.createConversation(title)
        _messages.value = emptyList()
        _lastSchedulingInfo.value = null
    }

    fun switchConversation(conversationId: String) {
        conversationRepository.switchConversation(conversationId)
        val conv = conversationRepository.getCurrentConversation()
        if (conv != null) {
            conversation = conv
            _messages.value = conv.messages.toList()
        }
    }

    fun deleteConversation(conversationId: String) {
        conversationRepository.deleteConversation(conversationId)
        val conv = conversationRepository.getCurrentConversation()
        if (conv != null) {
            conversation = conv
            _messages.value = conv.messages.toList()
        } else {
            conversation = Conversation()
            _messages.value = emptyList()
        }
    }

    private fun buildContextPrompt(): String {
        val recent = conversation.messages.takeLast(20)
        return recent.joinToString("\n") { msg ->
            when (msg.role) {
                Role.USER -> "用户：${msg.content}"
                Role.ASSISTANT -> "JARVIS：${msg.content}"
                Role.SYSTEM -> ""
            }
        }
    }

    fun clearConversation() {
        conversation.messages.clear()
        _messages.value = emptyList()
        conversationRepository.updateMessages(emptyList())
    }

    // ==================== 翻译指令解析（#12/#13） ====================

    /** 解析“翻译/translate”指令，返回 (原文, 目标语言)；非翻译指令返回 null。 */
    private fun parseTranslationRequest(content: String): Pair<String, String>? {
        val t = content.trim()
        val lower = t.lowercase()
        if (!t.startsWith("翻译") && !lower.startsWith("translate")) return null

        val target = when {
            t.contains("英文") || lower.contains("english") -> "en"
            t.contains("中文") || lower.contains("chinese") -> "zh"
            t.contains("日文") || lower.contains("japanese") -> "ja"
            t.contains("韩文") || lower.contains("korean") -> "ko"
            else -> if (conversationEnhancer.detectLanguage(t) == "zh") "en" else "zh"
        }

        var text = t.removePrefix("翻译").removePrefix("translate").trim()
        for (prefix in listOf(
            "成英文", "为英文", "到英文", "英文",
            "成中文", "为中文", "到中文", "中文",
            "成日文", "为日文", "日文", "成韩文", "为韩文", "韩文",
            "to english", "into english", "to chinese", "into chinese"
        )) {
            if (text.lowercase().startsWith(prefix)) {
                text = text.substring(prefix.length)
                break
            }
        }
        text = text.trimStart('：', ':', '，', ',', '-', ' ')
        return if (text.isBlank()) null else text to target
    }

    // ==================== 网络搜索与情报处理（#19-#25） ====================

    /**
     * 命中检索类指令时执行真实网络检索，并对结果做筛选/汇总/优先级排序后注入上下文。
     * 未命中或检索失败时返回空串，不影响正常对话。
     */
    private suspend fun buildRetrievalContext(content: String): String {
        val t = content.trim()
        val triggers = listOf("搜索", "查一下", "帮我查", "查找", "检索", "最新")
        val trigger = triggers.firstOrNull { t.startsWith(it) } ?: return ""
        var query = t.substring(trigger.length).trimStart('：', ':', '，', ' ', '一', '下')
        if (query.isBlank()) query = t

        return try {
            val retrieval = infoProcessor.retrieveInfo(query)
            if (retrieval.results.isEmpty()) return ""

            val items = retrieval.results.mapIndexed { i, r ->
                IntelligenceItem(
                    id = "search_$i",
                    title = r.title,
                    content = r.snippet,
                    source = r.source,
                    relevance = (0.9f - i * 0.05f).coerceIn(0f, 1f),
                    priority = retrieval.results.size - i
                )
            }
            val filtered = infoProcessor.filterIntelligence(items, FilterCriteria(minRelevance = 0.3f))
            val sorted = infoProcessor.sortByPriority(filtered)
            val summary = infoProcessor.summarizeIntelligence(sorted)

            buildString {
                appendLine("【实时检索结果（来源：网络搜索）】")
                appendLine("- 查询：$query")
                appendLine("- 结果数：${summary.totalCount}，平均相关度：${"%.2f".format(summary.averageRelevance)}")
                summary.topItems.take(5).forEach {
                    appendLine("· ${it.title}（${it.source}）：${it.content}")
                }
                appendLine("请优先基于以上实时结果回答；若信息不足请明确说明。")
            }
        } catch (e: Exception) {
            ""
        }
    }
}
