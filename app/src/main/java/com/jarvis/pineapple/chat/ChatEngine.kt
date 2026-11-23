package com.jarvis.pineapple.chat

import com.jarvis.pineapple.autonomous.MemorySystem
import com.jarvis.pineapple.info.FilterCriteria
import com.jarvis.pineapple.info.InfoProcessor
import com.jarvis.pineapple.info.IntelligenceItem
import com.jarvis.pineapple.model.AiModel
import com.jarvis.pineapple.model.ImageInput
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
class ChatEngine private constructor(
    private val modelScheduler: ModelScheduler,
    private val conversationRepository: ConversationRepository,
    private val memorySystem: MemorySystem,
    private val capabilityScheduler: ModelCapabilityScheduler,
    private val conversationEnhancer: ConversationEnhancer,
    private val infoProcessor: InfoProcessor?,
    private val commandExecutor: SimpleCommandExecutor?,
    private val vmCommandBridge: VmCommandBridge?,
    private val rootCommandBridge: RootCommandBridge?,
    private val systemPromptProvider: suspend () -> String
) {
    constructor(
        modelScheduler: ModelScheduler,
        personalityRepository: PersonalityRepository,
        conversationRepository: ConversationRepository,
        memorySystem: MemorySystem,
        capabilityScheduler: ModelCapabilityScheduler,
        conversationEnhancer: ConversationEnhancer,
        infoProcessor: InfoProcessor,
        commandExecutor: SimpleCommandExecutor? = null,
        vmCommandBridge: VmCommandBridge? = null,
        rootCommandBridge: RootCommandBridge? = null
    ) : this(
        modelScheduler = modelScheduler,
        conversationRepository = conversationRepository,
        memorySystem = memorySystem,
        capabilityScheduler = capabilityScheduler,
        conversationEnhancer = conversationEnhancer,
        infoProcessor = infoProcessor,
        commandExecutor = commandExecutor,
        vmCommandBridge = vmCommandBridge,
        rootCommandBridge = rootCommandBridge,
        systemPromptProvider = {
            val personality = personalityRepository.personality.first()
            personalityRepository.buildSystemPrompt(personality)
        }
    )
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _lastSchedulingInfo = MutableStateFlow<String?>(null)
    val lastSchedulingInfo: StateFlow<String?> = _lastSchedulingInfo.asStateFlow()

    val conversations: StateFlow<List<Conversation>> = conversationRepository.conversations
    val currentConversationId: StateFlow<String?> = conversationRepository.currentConversationId
    private val _persistenceError = MutableStateFlow<String?>(null)
    val persistenceError: StateFlow<String?> = _persistenceError.asStateFlow()

    private var conversation = Conversation()

    private enum class DangerousActionType { VM, HOST_ROOT }

    /** 只保存重新核验所需的身份与命令，绝不保留可直接执行的旧闭包。 */
    private data class PendingDangerousAction(
        val conversationId: String,
        val type: DangerousActionType,
        val command: String,
        val vmId: String? = null
    )

    private var pendingDangerousAction: PendingDangerousAction? = null

    init {
        val recentConversation = conversationRepository.getCurrentConversation()
        if (recentConversation != null) {
            conversation = recentConversation
            _messages.value = recentConversation.messages.toList()
        }
        refreshPersistenceError()
    }

    private fun refreshPersistenceError() {
        val errors = listOfNotNull(conversationRepository.lastError.value, memorySystem.lastError.value)
            .filter { it.isNotBlank() }
            .distinct()
        _persistenceError.value = if (errors.isEmpty()) null else errors.joinToString("\n")
    }

    private fun persistenceSuffix(conversationSaved: Boolean, memorySaved: Boolean): String {
        if (conversationSaved && memorySaved) return ""
        val failures = buildList {
            if (!conversationSaved) add("对话记录保存失败")
            if (!memorySaved) add("长期记忆保存失败")
        }
        return "\n\n【记录保存警告】${failures.joinToString("；")}。" +
            "该提示与上述命令执行或内容生成结果相互独立。"
    }

    private fun persistOutcome(reply: String, memoryNote: String, userText: String): String {
        val conversationSaved = conversationRepository.updateMessages(conversation.messages.toList())
        val memorySaved = memorySystem.recordConversationTurn(
            conversationId = conversation.id,
            userText = userText,
            assistantText = reply,
            summary = memoryNote
        )
        refreshPersistenceError()
        return reply + persistenceSuffix(conversationSaved, memorySaved)
    }

    private fun finishDirectReply(content: String, reply: String, memoryNote: String): Flow<String> {
        conversation.messages.add(ChatMessage(role = Role.ASSISTANT, content = reply, isStreaming = false))
        val visibleReply = persistOutcome(reply, memoryNote, content)
        conversation.messages[conversation.messages.lastIndex] =
            conversation.messages.last().copy(content = visibleReply)
        _messages.value = conversation.messages.toList()
        _isGenerating.value = false
        return flowOf(visibleReply)
    }

    /** 返回 null 表示当前会话可用；否则返回应直接展示给用户的错误。 */
    private fun ensureConversation(): String? {
        val current = conversationRepository.getCurrentConversation()
        if (current != null) {
            if (conversation.id != current.id) {
                conversation = current
                _messages.value = current.messages.toList()
            }
            return null
        }
        val created = conversationRepository.createConversation()
        refreshPersistenceError()
        if (created == null) {
            val detail = conversationRepository.lastError.value?.let { " $it" }.orEmpty()
            val error = "无法创建当前会话，已停止处理该指令，未执行命令或模型。$detail"
            _lastSchedulingInfo.value = error
            _isGenerating.value = false
            return error
        }
        conversation = created
        _messages.value = created.messages.toList()
        return null
    }

    suspend fun sendUserMessage(
        content: String,
        image: ImageInput? = null,
        appendUserMessage: Boolean = true
    ): Flow<String> {
        ensureConversation()?.let { return flowOf(it) }
        if (appendUserMessage) {
            val userMsg = ChatMessage(role = Role.USER, content = content)
            conversation.messages.add(userMsg)
            _messages.value = conversation.messages.toList()
        }

        _isGenerating.value = true

        // ===== 危险特权操作二次确认：未确认前不会执行任何命令 =====
        val pending = pendingDangerousAction
        if (pending != null) {
            when {
                isConfirmationText(content) -> {
                    pendingDangerousAction = null
                    val reply = try {
                        confirmDangerousAction(pending)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        _isGenerating.value = false
                        throw e
                    } catch (e: Exception) {
                        "执行失败：${e.message ?: e.javaClass.simpleName}"
                    }
                    return finishDirectReply(content, reply, "危险操作确认请求：${content.take(50)}")
                }
                isCancelText(content) -> {
                    pendingDangerousAction = null
                    return finishDirectReply(content, "已取消该危险操作，未执行任何命令。", "危险操作取消")
                }
                else -> pendingDangerousAction = null
            }
        }

        // ===== 本地指令统一入口：仅当消息以 “/” 或 “／” 开头时才解析执行 =====
        // 正常对话一律交给模型，避免硬编码关键词误判劫持真实聊天。
        val commandBody = AssistantCommandParser.explicitCommandBody(content)
        if (commandBody != null) {
            // 宿主机 root 指令：真实 su 执行，未授权/未 root 如实告知。
            RootCommandParser.parse(commandBody)?.let { command ->
                val reply = executeRootCommand(command)
                return finishDirectReply(content, reply, "宿主机 root 指令：${content.take(50)}")
            }

            // 翻译指令（#12/#13）：直接调用翻译引擎，无需经过大模型。
            parseTranslationRequest(commandBody)?.let { (text, target) ->
                val result = conversationEnhancer.translate(text, targetLang = target)
                val reply = if (result.isSuccess) result.translatedText else "翻译失败：${result.errorMessage}"
                return finishDirectReply(content, reply, "翻译请求：${content.take(50)}")
            }

            // 虚拟机桌面/浏览器：命中后直接调用真实桌面管理。
            VmDesktopCommandParser.parse(commandBody)?.let { request ->
                val reply = executeVmDesktop(request)
                return finishDirectReply(content, reply, "虚拟机桌面：${content.take(50)}")
            }

            // 虚拟机操控：命中“虚拟机/终端执行”指令时转发到真实 PRoot。
            VmCommandParser.parse(commandBody)?.let { execution ->
                val reply = executeVmCommand(execution)
                return finishDirectReply(content, reply, "虚拟机指令：${content.take(50)}")
            }

            if (AssistantCommandParser.isDirectCommand(commandBody)) {
                val parsedCommand = AssistantCommandParser.parse(commandBody)
                val reply = if (commandExecutor != null) {
                    commandExecutor.execute(parsedCommand)
                } else {
                    "无法执行该指令：系统控制器不可用"
                }
                return finishDirectReply(content, reply, "快捷指令：${content.take(50)}")
            }

            return finishDirectReply(
                content,
                AssistantCommandParser.unknownCommandMessage(commandBody),
                "未识别的本地指令：${content.take(50)}"
            )
        }

        val loadedModel = modelScheduler.resolveOrLoad()
        if (loadedModel == null) {
            val error = ModelScheduler.NO_MODEL_MESSAGE
            _lastSchedulingInfo.value = error
            return finishDirectReply(content, error, "无模型响应：${content.take(50)}")
        }

        val primaryModel: AiModel = loadedModel
        val systemPrompt = systemPromptProvider()

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

        if (image != null && !activeModel.supportsImages) {
            return finishDirectReply(
                content,
                "当前模型（${activeModel.info.name}）不支持图片输入。" +
                    "请在 设置 → 服务 配置支持视觉的 API 模型后重试，或改用文字描述图片内容。",
                "图片输入不受支持：${content.take(50)}"
            )
        }

        val aiMsg = ChatMessage(role = Role.ASSISTANT, content = "", isStreaming = true)
        conversation.messages.add(aiMsg)
        _messages.value = conversation.messages.toList()

        // 将 stream() 的创建也纳入下方 catch，覆盖同步抛错的模型实现。
        val flow = if (image != null) {
            flow { emitAll(activeModel.streamWithImage(fullPrompt, systemPrompt, image)) }
        } else {
            flow { emitAll(activeModel.stream(fullPrompt, systemPrompt)) }
        }
        val collected = StringBuilder()
        var failed = false
        var lastEmitAt = 0L

        return flow
            .onEach { chunk ->
                collected.append(chunk)
                // 流式回复按 60ms 节流刷新，避免逐 token 触发整页重组造成卡顿。
                val now = System.currentTimeMillis()
                if (now - lastEmitAt < 60) return@onEach
                lastEmitAt = now
                val idx = conversation.messages.indexOfLast { it.id == aiMsg.id }
                if (idx >= 0) {
                    conversation.messages[idx] = conversation.messages[idx].copy(content = collected.toString())
                    _messages.value = conversation.messages.toList()
                }
            }
            .catch { error ->
                failed = true
                if (error is kotlinx.coroutines.CancellationException) {
                    _lastSchedulingInfo.value = "模型请求已取消"
                    throw error
                }
                val message = "模型请求失败：${error.message ?: error.javaClass.simpleName}"
                val idx = conversation.messages.indexOfLast { it.id == aiMsg.id }
                if (idx >= 0) {
                    // 节流可能跳过最后一次状态刷新，以完整累积内容为准，避免丢失末尾 token。
                    val partial = collected.toString()
                    val contentWithError = if (partial.isBlank()) message else "$partial\n\n$message"
                    conversation.messages[idx] = conversation.messages[idx].copy(content = contentWithError)
                    _messages.value = conversation.messages.toList()
                }
                _lastSchedulingInfo.value = message
            }
            .onCompletion { cause ->
                val cancelled = cause is kotlinx.coroutines.CancellationException
                if (cancelled) failed = true
                val idx = conversation.messages.indexOfLast { it.id == aiMsg.id }
                // 状态刷新有 60ms 节流，收尾必须以完整累积内容为准，避免丢失最后一段 token。
                val raw = if (failed && !cancelled && idx >= 0) {
                    conversation.messages[idx].content
                } else {
                    collected.toString()
                }
                val terminalReply = if (cancelled) {
                    val base = raw.trimEnd()
                    if (base.isBlank()) "模型请求已取消。" else "$base\n\n模型请求已取消。"
                } else {
                    raw
                }
                val finalReply = if (!failed && !cancelled) {
                    conversationEnhancer.adjustResponseTone(terminalReply, emotional.userEmotion)
                } else {
                    terminalReply
                }
                val summary = when {
                    cancelled -> "用户问：${content.take(50)}... JARVIS回复已取消：${raw.take(100)}"
                    failed -> "用户问：${content.take(50)}... JARVIS响应失败：${raw.take(100)}"
                    else -> "用户问：${content.take(50)}... JARVIS答：${finalReply.take(100)}..."
                }
                if (idx >= 0) {
                    conversation.messages[idx] = conversation.messages[idx].copy(
                        content = finalReply,
                        isStreaming = false
                    )
                }
                val visibleReply = persistOutcome(finalReply, summary, content)
                if (idx >= 0) {
                    conversation.messages[idx] = conversation.messages[idx].copy(content = visibleReply)
                }
                _messages.value = conversation.messages.toList()
                _isGenerating.value = false
            }
    }

    fun newConversation(title: String = "新对话"): Boolean {
        pendingDangerousAction = null
        val created = conversationRepository.createConversation(title)
        refreshPersistenceError()
        if (created == null) return false
        conversation = created
        _messages.value = created.messages.toList()
        _lastSchedulingInfo.value = null
        return true
    }

    fun switchConversation(conversationId: String): Boolean {
        pendingDangerousAction = null
        if (!conversationRepository.switchConversation(conversationId)) {
            refreshPersistenceError()
            return false
        }
        val conv = conversationRepository.getCurrentConversation() ?: return false
        conversation = conv
        _messages.value = conv.messages.toList()
        refreshPersistenceError()
        return true
    }

    /** 删除单条消息并持久化；返回是否保存成功。 */
    fun deleteMessage(messageId: String): Boolean {
        val idx = conversation.messages.indexOfFirst { it.id == messageId }
        if (idx < 0) return false
        conversation.messages.removeAt(idx)
        val saved = conversationRepository.updateMessages(conversation.messages.toList())
        refreshPersistenceError()
        _messages.value = conversation.messages.toList()
        return saved
    }

    /**
     * 为“重新生成”移除最后一条已完成的助手回复，并返回对应的用户输入。
     * 不满足条件（正在生成或最后一条不是助手回复）时返回 null。
     */
    fun prepareRegeneration(): String? {
        if (_isGenerating.value) return null
        val last = conversation.messages.lastOrNull() ?: return null
        if (last.role != Role.ASSISTANT || last.isStreaming) return null
        conversation.messages.removeAt(conversation.messages.lastIndex)
        val user = conversation.messages.lastOrNull { it.role == Role.USER }
        if (user == null) {
            conversation.messages.add(last)
            return null
        }
        _messages.value = conversation.messages.toList()
        return user.content
    }

    fun deleteConversation(conversationId: String): Boolean {
        pendingDangerousAction = null
        if (!conversationRepository.deleteConversation(conversationId)) {
            refreshPersistenceError()
            return false
        }
        val conv = conversationRepository.getCurrentConversation()
        if (conv != null) {
            conversation = conv
            _messages.value = conv.messages.toList()
        } else {
            conversation = Conversation()
            _messages.value = emptyList()
        }
        refreshPersistenceError()
        return true
    }

    // ==================== 对话导出 / 导入 / 归档 ====================

    /** 导出全部对话为 JSON 字符串。 */
    fun exportAllConversations(): String = conversationRepository.exportAll()

    /** 导出单个对话为 JSON 字符串；不存在返回 null。 */
    fun exportConversation(conversationId: String): String? =
        conversationRepository.exportConversation(conversationId)

    /** 导入对话（兼容全部/单个导出格式）；成功后同步当前会话消息。 */
    fun importConversations(json: String): Boolean {
        pendingDangerousAction = null
        val ok = conversationRepository.importFromJson(json)
        if (ok) {
            val conv = conversationRepository.getCurrentConversation()
            if (conv != null) {
                conversation = conv
                _messages.value = conv.messages.toList()
            } else {
                conversation = Conversation()
                _messages.value = emptyList()
            }
        }
        refreshPersistenceError()
        return ok
    }

    /** 归档或恢复对话；持久化失败返回 false。 */
    fun archiveConversation(conversationId: String, archived: Boolean): Boolean {
        val saved = conversationRepository.setArchived(conversationId, archived)
        refreshPersistenceError()
        return saved
    }

    // ==================== 虚拟机操控 ====================

    /**
     * 在真实虚拟机上执行命令并返回可读结果。
     * 找不到虚拟机、启动失败或执行异常时如实说明，绝不伪造成功输出。
     */
    private suspend fun executeVmCommand(execution: VmExecution): String {
        val bridge = vmCommandBridge ?: return "虚拟机能力不可用，无法执行该指令。"
        val (vm, error) = resolveVmForAction(execution.vmName)
        if (error != null) return error
        val target = vm ?: return "未找到可用虚拟机。"

        if (isDangerousCommand(execution.command)) {
            val conversationId = conversationRepository.currentConversationId.value
            if (conversationId == null || conversationId != conversation.id) {
                return "当前会话已变化，危险命令未执行，请重新发起指令。"
            }
            pendingDangerousAction = PendingDangerousAction(
                conversationId = conversationId,
                type = DangerousActionType.VM,
                command = execution.command,
                vmId = target.id
            )
            return buildString {
                appendLine("检测到危险命令，需要二次确认：")
                appendLine("虚拟机：${target.name}")
                appendLine("命令：${execution.command}")
                appendLine("该命令可能删除数据或破坏系统。回复“确认执行”继续，回复“取消”放弃。")
            }.trimEnd()
        }
        return runVmCommand(target, execution.command)
    }

    private suspend fun confirmDangerousAction(pending: PendingDangerousAction): String {
        if (conversationRepository.currentConversationId.value != pending.conversationId ||
            conversation.id != pending.conversationId ||
            conversationRepository.conversations.value.none { it.id == pending.conversationId }
        ) {
            return "当前会话已变化，原危险操作已失效，未执行任何命令。"
        }
        return when (pending.type) {
            DangerousActionType.VM -> confirmVmAction(pending)
            DangerousActionType.HOST_ROOT -> confirmRootAction(pending.command)
        }
    }

    private suspend fun confirmVmAction(pending: PendingDangerousAction): String {
        val bridge = vmCommandBridge ?: return "虚拟机能力不可用，危险操作未执行。"
        val vms = try {
            bridge.listVms()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return "确认前读取虚拟机列表失败，危险操作未执行：${e.message ?: e.javaClass.simpleName}"
        }
        val target = vms.firstOrNull { it.id == pending.vmId }
            ?: return "目标虚拟机已不存在，原危险操作已失效，未执行任何命令。"
        if (!target.running) {
            val started = try {
                bridge.start(target.id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (!started) return "目标虚拟机当前未运行且启动失败，危险操作未执行。"
        }
        return runVmCommand(target, pending.command)
    }

    private suspend fun confirmRootAction(command: String): String {
        val bridge = rootCommandBridge ?: return "宿主机 root 能力不可用，危险操作未执行。"
        val status = try {
            bridge.status()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return "确认前 root 状态探测失败，危险操作未执行：${e.message ?: e.javaClass.simpleName}"
        }
        if (!status.allowed) return "root 授权已撤销，原危险操作已失效，未执行任何命令。"
        if (!status.granted) return "确认前 root 权限已不可用，危险操作未执行：${status.detail}"
        return runRootCommand(bridge, command)
    }

    /** 实际执行虚拟机命令并如实回传输出与退出码。 */
    private suspend fun runVmCommand(target: VmSummary, command: String): String {
        val bridge = vmCommandBridge ?: return "虚拟机能力不可用，无法执行该指令。"
        val result = try {
            bridge.run(target.id, command)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return "虚拟机「${target.name}」执行失败：${e.message ?: e.javaClass.simpleName}"
        }

        return buildString {
            appendLine("虚拟机：${target.name}")
            appendLine("命令：$command")
            appendLine("退出码：${result.exitCode}")
            if (result.output.isBlank()) {
                appendLine("输出：（无）")
            } else {
                appendLine("输出：")
                appendLine(result.output.trimEnd())
            }
        }.trimEnd()
    }

    /** 宿主机 root 指令入口：授权检查 + 危险命令二次确认。 */
    private suspend fun executeRootCommand(command: String): String {
        val bridge = rootCommandBridge ?: return "宿主机 root 能力不可用。"
        val status = try {
            bridge.status()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return "root 状态探测失败：${e.message ?: e.javaClass.simpleName}"
        }
        if (!status.allowed) {
            return "已关闭“允许 AI 使用宿主机 root”。请先在系统控制中开启授权，再执行该命令。"
        }
        if (!status.granted) {
            return "设备当前无法提供 root 权限：${status.detail}"
        }
        if (isDangerousCommand(command)) {
            val conversationId = conversationRepository.currentConversationId.value
            if (conversationId == null || conversationId != conversation.id) {
                return "当前会话已变化，危险 root 命令未执行，请重新发起指令。"
            }
            pendingDangerousAction = PendingDangerousAction(
                conversationId = conversationId,
                type = DangerousActionType.HOST_ROOT,
                command = command
            )
            return buildString {
                appendLine("检测到危险 root 命令，需要二次确认：")
                appendLine("命令：$command")
                appendLine("该命令将以宿主机 root 身份执行，可能删除数据或破坏系统。")
                appendLine("回复“确认执行”继续，回复“取消”放弃。")
            }.trimEnd()
        }
        return runRootCommand(bridge, command)
    }

    private suspend fun runRootCommand(bridge: RootCommandBridge, command: String): String {
        val result = try {
            bridge.run(command)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return "宿主机 root 命令执行失败：${e.message ?: e.javaClass.simpleName}"
        }
        return buildString {
            appendLine("宿主机 root 命令：$command")
            appendLine("退出码：${result.exitCode}")
            if (result.output.isBlank()) {
                appendLine("输出：（无）")
            } else {
                appendLine("输出：")
                appendLine(result.output.trimEnd())
            }
        }.trimEnd()
    }

    /** 打开虚拟机桌面或桌面内浏览器，并把真实结果返回给对话。 */
    private suspend fun executeVmDesktop(request: VmDesktopRequest): String {
        val bridge = vmCommandBridge ?: return "虚拟机能力不可用，无法执行该指令。"
        val (vm, error) = resolveVmForAction(request.vmName)
        if (error != null) return error
        val target = vm ?: return "未找到可用虚拟机。"
        return try {
            val result = when (request.action) {
                VmDesktopAction.DESKTOP -> bridge.openDesktop(target.id)
                VmDesktopAction.BROWSER -> bridge.openBrowser(target.id)
            }
            if (result.ok) result.message else "虚拟机「${target.name}」：${result.message}"
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            "虚拟机「${target.name}」桌面操作失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * 按名称解析虚拟机；未运行时先尝试启动。
     * @return 第一个值为目标虚拟机（找不到时为 null），第二个值为需要直接返回的错误说明。
     */
    private suspend fun resolveVmForAction(vmName: String?): Pair<VmSummary?, String?> {
        val bridge = vmCommandBridge ?: return null to "虚拟机能力不可用，无法执行该指令。"
        val vms = try {
            bridge.listVms()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return null to "读取虚拟机列表失败：${e.message ?: e.javaClass.simpleName}"
        }
        if (vms.isEmpty()) {
            return null to "当前没有已创建的虚拟机，请先在虚拟机页面下载发行版并创建虚拟机。"
        }
        val vm = resolveVm(vms, vmName)
            ?: return null to "未找到名为「$vmName」的虚拟机。可用：${vms.joinToString { it.name }}"
        if (vm.running) return vm to null
        val started = try {
            bridge.start(vm.id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        return if (started) vm to null else vm to "虚拟机「${vm.name}」启动失败。"
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

    fun clearConversation(): Boolean {
        pendingDangerousAction = null
        if (!conversationRepository.updateMessages(emptyList())) {
            refreshPersistenceError()
            return false
        }
        conversation.messages.clear()
        _messages.value = emptyList()
        refreshPersistenceError()
        return true
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
        val processor = infoProcessor ?: return "【实时检索失败】\n查询：$query\n信息处理器不可用。"

        return try {
            val retrieval = processor.retrieveInfo(query)
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
            val filtered = processor.filterIntelligence(items, FilterCriteria(minRelevance = 0.3f))
            val sorted = processor.sortByPriority(filtered)
            val summary = processor.summarizeIntelligence(sorted)

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

        /** JVM 行为测试使用纯 Kotlin 入口；生产仍统一走公开 Android 依赖构造器。 */
        internal fun createForTest(
            modelScheduler: ModelScheduler,
            conversationRepository: ConversationRepository,
            memorySystem: MemorySystem,
            capabilityScheduler: ModelCapabilityScheduler,
            conversationEnhancer: ConversationEnhancer,
            commandExecutor: SimpleCommandExecutor? = null,
            vmCommandBridge: VmCommandBridge? = null,
            rootCommandBridge: RootCommandBridge? = null,
            systemPromptProvider: suspend () -> String
        ): ChatEngine = ChatEngine(
            modelScheduler = modelScheduler,
            conversationRepository = conversationRepository,
            memorySystem = memorySystem,
            capabilityScheduler = capabilityScheduler,
            conversationEnhancer = conversationEnhancer,
            infoProcessor = null,
            commandExecutor = commandExecutor,
            vmCommandBridge = vmCommandBridge,
            rootCommandBridge = rootCommandBridge,
            systemPromptProvider = systemPromptProvider
        )

        /** 危险命令特征：命中后必须经用户二次确认才允许执行。 */
        private val DANGEROUS_PATTERNS = listOf(
            Regex("\\brm\\s+[^\\n]*-[a-zA-Z]*[rf][a-zA-Z]*\\s+/(?:\\s|$)"),
            Regex("\\brm\\s+[^\\n]*-(?:rf|fr|R|fR|rF)\\b"),
            Regex("\\bmkfs(?:\\.[a-z0-9]+)?\\b"),
            Regex("\\bdd\\s+[^\\n]*\\bof=/dev/"),
            Regex("\\b(?:shutdown|reboot|halt|poweroff)\\b"),
            Regex("\\biptables\\s+[^\\n]*(?:-F|--flush)"),
            Regex("\\b(?:userdel|groupdel)\\b"),
            Regex("\\bchmod\\s+[^\\n]*\\b777\\s+/(?:\\s|$)"),
            Regex(":\\(\\)\\s*\\{\\s*:\\|:&\\s*\\}\\s*;"),
            Regex("\\b(?:mv|cp|rm)\\b[^\\n]*\\s/(?:system|vendor|boot|data)(?:\\s|/|$)"),
            Regex("\\b(?:wipe|format)\\b[^\\n]*\\b(?:data|system|boot)\\b")
        )

        /** 判断命令是否属于危险操作（纯函数，可单测）。 */
        internal fun isDangerousCommand(command: String): Boolean {
            val normalized = command.trim()
            if (normalized.isEmpty()) return false
            return DANGEROUS_PATTERNS.any { it.containsMatchIn(normalized) }
        }

        internal fun isConfirmationText(text: String): Boolean {
            val normalized = text.trim().lowercase()
            return normalized in setOf("确认", "确认执行", "确定", "确定执行", "继续", "继续执行", "是", "执行", "yes", "y", "confirm")
        }

        internal fun isCancelText(text: String): Boolean {
            val normalized = text.trim().lowercase()
            return normalized in setOf("取消", "取消执行", "不要", "不用", "否", "放弃", "no", "n", "cancel")
        }
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
