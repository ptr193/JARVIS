package com.jarvis.pineapple.model

import android.app.ActivityManager
import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.SamplerConfig
import com.jarvis.pineapple.util.DownloadLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本地模型基类（LiteRT-LM 适配层）。
 */
abstract class LocalModel(
    override val info: ModelInfo,
    protected val context: Context,
    protected val modelFileName: String
) : AiModel {

    override var state: ModelState = ModelState.UNLOADED
        protected set

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    /** 最近一次加载失败的原因；用于把“文件缺失”和“内存不足/加载失败”区分开。 */
    var lastLoadError: String? = null
        protected set

    protected open fun modelFile(): File =
        File(context.filesDir, "models/$modelFileName")

    /**
     * 加载模型。
     *
     * 关键约束：`Engine.initialize()` 会映射上百 MB 模型并初始化 native 运行时，
     * 必须在 IO 线程执行；之前在主线程执行会长时间卡住 UI，严重时被系统判定无响应。
     * 同时这里捕获 `Throwable`（含 UnsatisfiedLinkError / OutOfMemoryError），
     * 避免异常逃逸到全局未捕获处理器导致整进程闪退。
     */
    override suspend fun load(): Unit = withContext(Dispatchers.IO) {
        state = ModelState.LOADING
        lastLoadError = null
        val file = modelFile()
        if (!file.exists()) {
            state = ModelState.ERROR
            lastLoadError = modelMissingMessage()
            return@withContext
        }
        checkMemoryBudget(file)?.let { reason ->
            state = ModelState.ERROR
            lastLoadError = reason
            DownloadLogger.safeLog("LocalModel", "${info.name} 未加载：$reason")
            return@withContext
        }
        try {
            DownloadLogger.safeLog(
                "LocalModel",
                "开始加载 ${info.name}（${file.length() / (1024 * 1024)} MB）"
            )
            val config = EngineConfig(
                modelPath = file.absolutePath,
                backend = Backend.CPU()
            )
            engine = Engine(config).also { it.initialize() }
            val samplerConfig = SamplerConfig(
                topK = info.topK,
                topP = info.topP.toDouble(),
                temperature = info.temperature.toDouble()
            )
            val convConfig = ConversationConfig(
                samplerConfig = samplerConfig,
                maxOutputToken = info.maxTokens
            )
            conversation = engine?.createConversation(convConfig)
            state = ModelState.READY
            DownloadLogger.safeLog("LocalModel", "${info.name} 加载完成")
        } catch (e: CancellationException) {
            runCatching { conversation?.close() }
            runCatching { engine?.close() }
            state = ModelState.UNLOADED
            engine = null
            conversation = null
            throw e
        } catch (e: Throwable) {
            runCatching { conversation?.close() }
            runCatching { engine?.close() }
            state = ModelState.ERROR
            engine = null
            conversation = null
            lastLoadError = "模型加载失败：${e.message ?: e.javaClass.simpleName}"
            DownloadLogger.safeLog("LocalModel", lastLoadError!!, e)
        }
    }

    /** 峰值内存粗估约为模型文件的 1.5 倍；明显不足时直接给结论，避免 OOM 把进程杀掉。 */
    private fun checkMemoryBudget(file: File): String? {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return null
        val info = ActivityManager.MemoryInfo()
        runCatching { manager.getMemoryInfo(info) }
        if (info.availMem <= 0L) return null
        val required = file.length() * 3 / 2
        if (info.availMem < required) {
            return "可用内存不足：加载该模型约需 ${required / (1024 * 1024)} MB，" +
                "当前可用 ${info.availMem / (1024 * 1024)} MB。请关闭其他应用后重试，或改选更小的模型。"
        }
        return null
    }

    override suspend fun unload() {
        try {
            conversation?.close()
            engine?.close()
        } catch (_: Exception) {
        }
        conversation = null
        engine = null
        state = ModelState.UNLOADED
    }

    @OptIn(ExperimentalApi::class)
    override suspend fun generate(prompt: String, systemPrompt: String?): String {
        val conv = conversation ?: return modelMissingMessage()
        return try {
            val full = buildPrompt(systemPrompt, prompt)
            val message = conv.sendMessage(full)
            conv.renderMessageIntoString(message)
        } catch (e: Exception) {
            "[本地模型推理失败] ${e.message}"
        }
    }

    @OptIn(ExperimentalApi::class)
    override fun stream(prompt: String, systemPrompt: String?): Flow<String> = flow {
        val conv = conversation
        if (conv == null) {
            emit(modelMissingMessage())
            return@flow
        }
        try {
            val full = buildPrompt(systemPrompt, prompt)
            conv.sendMessageAsync(full).collect { message ->
                emit(conv.renderMessageIntoString(message))
            }
        } catch (e: Exception) {
            emit("[本地模型流式推理失败] ${e.message}")
        }
    }.flowOn(Dispatchers.Default)

    protected open fun buildPrompt(systemPrompt: String?, userPrompt: String): String {
        return buildString {
            systemPrompt?.let { append(it).append("\n\n") }
            append(userPrompt)
        }
    }

    protected fun modelMissingMessage(): String {
        val path = modelFile().absolutePath
        return "模型文件未就绪：$path\n" +
                "请将 ${info.name} 的 .litertlm 模型文件放置到上述路径。"
    }
}

/**
 * 用户自有本地模型（默认，最高优先级）。
 */
class UserLocalModel(
    context: Context,
    customFileName: String = "user_model.litertlm"
) : LocalModel(
    info = ModelInfo(
        id = "local_user",
        name = "用户自有模型",
        source = ModelSource.LOCAL_USER,
        description = "用户自行提供的本地模型",
        priority = 100,
        isDefault = true,
        maxTokens = 2048,
        temperature = 0.7f,
        topP = 0.95f,
        topK = 40
    ),
    context = context,
    modelFileName = customFileName
)

/**
 * LiteRT 本地模型（Gemma 4 / LFM / Qwen 等）。
 */
class LiteRtModel(
    context: Context,
    id: String,
    name: String,
    description: String = "",
    maxTokens: Int = 2048,
    temperature: Float = 0.7f,
    topP: Float = 0.95f,
    topK: Int = 40
) : LocalModel(
    info = ModelInfo(
        id = id,
        name = name,
        source = ModelSource.LOCAL_LITERT,
        description = description,
        priority = 50,
        maxTokens = maxTokens,
        temperature = temperature,
        topP = topP,
        topK = topK
    ),
    context = context,
    modelFileName = "${id}.litertlm"
)

/**
 * 下载目录中的模型与调度器共用同一份配置，避免下载成功后找不到对应本地模型。
 */
object BuiltInModels {
    fun liteRtModels(context: Context): List<LiteRtModel> =
        ModelDownloadManager.DEFAULT_MODELS.map { config ->
            val chatSampling = config.id.startsWith("qwen") || config.id.startsWith("lfm") ||
                config.id.startsWith("deepseek")
            LiteRtModel(
                context = context,
                id = config.id,
                name = config.name,
                description = "${config.name}（LiteRT-LM）",
                maxTokens = when {
                    config.id == "gemma4_12b" -> 4096
                    config.id.startsWith("qwen3_0_6b") -> 1024
                    else -> 2048
                },
                temperature = 0.7f,
                topP = if (chatSampling) 0.9f else 0.95f,
                topK = if (chatSampling) 50 else 40
            )
        } + LiteRtModel(
            context = context,
            id = "phi4_1_5b",
            name = "Phi-4 1.5B",
            description = "Microsoft Phi-4 1.5B",
            maxTokens = 2048, temperature = 0.7f, topP = 0.9f, topK = 50
        )
}
