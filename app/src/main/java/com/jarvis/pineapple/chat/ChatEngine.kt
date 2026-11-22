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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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
    private val infoProcessor: InfoProcessor,
    private val commandExecutor: SimpleCommandExecutor? = null,
    private val vmCommandBridge: VmCommandBridge? = null
) {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _lastSchedulingInfo = MutableStateFlow<String?>(null)
    val lastSchedulingInfo: StateFlow<String?> = _lastSchedulingInfo.asStateFlow()

    val conversations: StateFlow<List<Conversation>> = conversationRepository.conversations
    val currentConversationId: StateFlow<String?> = conversationRepository.currentConversationId
    val persistenceError: StateFlow<String?> = conversationRepository.lastError

    private var conversation = Conversation()

    init {
        val recentConversation = conversationRepository.getCurrentConversation()
        if (recentConversation != null) {
            conversation = recentConversation
            _messages.value = recentConversation.messages.toList()
        }
    }

    private fun finishDirectReply(content: String, reply: String, memoryNote: String): Flow<String> {
        conversation.messages.add(ChatMessage(role = Role.ASSISTANT, content = reply, isStreaming = false))
        _messages.value = conversation.messages.toList()
        _isGenerating.value = false
        conversationRepository.updateMessages(conversation.messages.toList())
        memorySystem.recordConversation(memoryNote)
        return flowOf(reply)
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
            val reply = if (result.isSuccess) result.translatedText else "翻译失败：${result.errorMessage}"
            return finishDirectReply(content, reply, "翻译请求：${content.take(50)}")
        }

        // ===== 虚拟机操控：命中“虚拟机/终端执行”指令时转发到真实 PRoot =====
        VmCommandParser.parse(content)?.let { execution ->
            val reply = executeVmCommand(execution)
            return finishDirectReply(content, reply, "虚拟机指令：${content.take(50)}")
        }

        if (AssistantCommandParser.isDirectCommand(content)) {
            val parsedCommand = AssistantCommandParser.parse(content)
            val reply = if (commandExecutor != null) {
                commandExecutor.execute(parsedCommand)
            } else {
                "无法执行该指令：系统控制器不可用"
            }
            return finishDirectReply(content, reply, "快捷指令：${content.take(50)}")
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

        val candidate = if (decision.shouldSchedule && decision.assistantModel != null) {
            decision.assistantModel
        } else {
            primaryModel
        }
        val activeModel = try {
            if (candidate.state != ModelState.READY) {
                candidate.load()
            }
            if (candidate.state == ModelState.READY) candidate else primaryModel
        } catch (e: kotlinx.coroutines.CancellationException) {
            _isGenerating.value = false
            throw e
        } catch (e: Exception) {
            _lastSchedulingInfo.value =
                "候选模型加载失败，已回退到 ${primaryModel.info.name}：${e.message ?: e.javaClass.simpleName}"
            primaryModel
        }

        val aiMsg = ChatMessage(role = Role.ASSISTANT, content = "", isStreaming = true)
        conversation.messages.add(aiMsg)
        _messages.value = conversation.messages.toList()

        // 将 stream() 的创建也纳入下方 catch，覆盖同步抛错的模型实现。
        val flow = flow { emitAll(activeModel.stream(fullPrompt, systemPrompt)) }
        val collected = StringBuilder()
        var failed = false

        return flow
            .onEach { chunk ->
                collected.append(chunk)
                val idx = conversation.messages.indexOfLast { it.isStreaming }
                if (idx >= 0) {
                    conversation.messages[idx] = conversation.messages[idx].copy(content = collected.toString())
                    _messages.value = conversation.messages.toList()
                }
            }
            .catch { error ->
                failed = true
                val message = "模型请求失败：${error.message ?: error.javaClass.simpleName}"
                val idx = conversation.messages.indexOfLast { it.isStreaming }
                if (idx >= 0) {
                    conversation.messages[idx] = conversation.messages[idx].copy(
                        content = message,
                        isStreaming = false
                    )
                    _messages.value = conversation.messages.toList()
                }
                _lastSchedulingInfo.value = message
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

                if (!failed && collected.isNotEmpty()) {
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

    // ==================== 对话导出 / 导入 / 归档 ====================

    /** 导出全部对话为 JSON 字符串。 */
    fun exportAllConversations(): String = conversationRepository.exportAll()

    /** 导出单个对话为 JSON 字符串；不存在返回 null。 */
    fun exportConversation(conversationId: String): String? =
        conversationRepository.exportConversation(conversationId)

    /** 导入对话（兼容全部/单个导出格式）；成功后同步当前会话消息。 */
    fun importConversations(json: String): Boolean {
        val ok = conversationRepository.importFromJson(json)
        if (ok) {
            val conv = conversationRepository.getCurrentConversation()
            if (conv != null) {
                conversation = conv
                _messages.value = conv.messages.toList()
            }
        }
        return ok
    }

    /** 归档或恢复对话；持久化失败返回 false。 */
    fun archiveConversation(conversationId: String, archived: Boolean): Boolean =
        conversationRepository.setArchived(conversationId, archived)

    // ==================== 虚拟机操控 ====================

    /**
     * 在真实虚拟机上执行命令并返回可读结果。
     * 找不到虚拟机、启动失败或执行异常时如实说明，绝不伪造成功输出。
     */
    private suspend fun executeVmCommand(execution: VmExecution): String {
        val bridge = vmCommandBridge ?: return "虚拟机能力不可用，无法执行该指令。"
        val vms = try {
            bridge.listVms()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return "读取虚拟机列表失败：${e.message ?: e.javaClass.simpleName}"
        }
        if (vms.isEmpty()) {
            return "当前没有已创建的虚拟机，请先在虚拟机页面下载发行版并创建虚拟机。"
        }
        val vm = resolveVm(vms, execution.vmName)
            ?: return "未找到名为「${execution.vmName}」的虚拟机。可用：${vms.joinToString { it.name }}"

        if (!vm.running) {
            val started = try {
                bridge.start(vm.id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (!started) return "虚拟机「${vm.name}」启动失败，未执行命令。"
        }

        val result = try {
            bridge.run(vm.id, execution.command)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return "虚拟机「${vm.name}」执行失败：${e.message ?: e.javaClass.simpleName}"
        }

        return buildString {
            appendLine("虚拟机：${vm.name}")
            appendLine("命令：${execution.command}")
            appendLine("退出码：${result.exitCode}")
            if (result.output.isBlank()) {
                appendLine("输出：（无）")
            } else {
                appendLine("输出：")
                appendLine(result.output.trimEnd())
            }
        }.trimEnd()
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
     * 未命中检索指令时返回空串；检索失败会明确注入错误，避免伪装成无结果。
     */
    private suspend fun buildRetrievalContext(content: String): String {
        val t = content.trim()
        val query = detectSearchQuery(t) ?: return ""

        return try {
            val retrieval = infoProcessor.retrieveInfo(query)
            if (retrieval.results.isEmpty()) {
                return "【实时检索无结果】\n查询：$query\n未检索到可用结果，请明确告知用户没有查到，不得编造实时信息。"
            }

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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            "【实时检索失败】\n查询：$query\n原因：${e.message ?: e.javaClass.simpleName}\n请明确告知用户检索失败，不得编造实时结果。"
        }
    }

    companion object {
        /**
         * 判断是否为需要联网的检索请求，并抽取查询词（纯函数，可单测）。
         * 支持“搜索/查一下/帮我查/上网查/查询…”及英文 search/look up，
         * 也支持“今天天气怎么样”这类含明确时效主题的提问。
         */
        internal fun detectSearchQuery(content: String): String? {
            val text = content.trim()
            if (text.isBlank()) return null
            val lower = text.lowercase()

            val explicit = listOf(
                "上网查", "网络搜索", "联网查", "帮我搜索", "帮忙搜索",
                "搜索一下", "搜一下", "查一下", "查一查", "帮我查", "帮我查询",
                "查询", "检索", "查找", "搜索"
            )
            val english = listOf("search for", "look up", "search", "google")
            val hit = explicit.firstOrNull { it in text } ?: english.firstOrNull { it in lower }
            if (hit != null) {
                val index = if (hit in text) text.indexOf(hit) else lower.indexOf(hit)
                var query = text.substring(index + hit.length)
                    .trim()
                    .trimStart('：', ':', '，', ',', '。', '.', ' ', '一', '下')
                query = query.removePrefix("关于").removePrefix("有关").trim()
                query = query.removeSuffix("的资料").removeSuffix("的信息").removeSuffix("的信息。").trim()
                return query.ifBlank { null }
            }

            val freshnessTopics = listOf(
                "天气", "新闻", "股价", "汇率", "比分", "赛程", "价格",
                "最新消息", "最新进展", "最新版本", "上映", "发布会",
                "weather", "news", "stock", "price", "exchange rate", "score"
            )
            val looksLikeQuestion = text.any { it in "?？吗呢" } ||
                listOf("多少", "怎么", "如何", "什么", "哪", "几点").any { it in text }
            if (looksLikeQuestion && freshnessTopics.any { it in lower }) return text
            return null
        }

        /**
         * 按名称选择虚拟机（纯函数，可单测）。
         * 未指定名称时优先运行中的虚拟机，否则取第一个；指定名称时先精确匹配再包含匹配。
         */
        internal fun resolveVm(vms: List<VmSummary>, name: String?): VmSummary? {
            if (vms.isEmpty()) return null
            if (name.isNullOrBlank()) return vms.firstOrNull { it.running } ?: vms.first()
            return vms.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: vms.firstOrNull { it.name.contains(name, ignoreCase = true) }
        }
    }
}
