package com.jarvis.pineapple.model

import android.content.Context
import com.google.ai.edge.litertlm.LlmInferenceSession
import com.google.ai.edge.litertlm.LlmInferenceSession.LlmInferenceSessionOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

/**
 * 本地模型基类（LiteRT-LM 适配层）。
 * 通过 LiteRT-LM 的 LlmInferenceSession 加载 .litertlm 模型并执行推理。
 *
 * 模型文件由用户自行准备，放入应用私有目录：
 *   context.filesDir/models/<modelId>.litertlm
 *
 * 若模型文件不存在，generate() 会返回明确的提示信息，
 * 而非伪造 AI 回复。
 */
abstract class LocalModel(
    override val info: ModelInfo,
    protected val context: Context,
    protected val modelFileName: String
) : AiModel {

    override var state: ModelState = ModelState.UNLOADED
        protected set

    protected var session: LlmInferenceSession? = null

    protected fun modelFile(): File =
        File(context.filesDir, "models/$modelFileName")

    override suspend fun load() {
        state = ModelState.LOADING
        val file = modelFile()
        if (!file.exists()) {
            state = ModelState.ERROR
            return
        }
        try {
            val options = LlmInferenceSessionOptions(file.absolutePath).apply {
                maxTokens = info.maxTokens
                temperature = info.temperature
                topP = info.topP
                topK = info.topK
            }
            session = LlmInferenceSession.createFromOptions(context, options)
            state = ModelState.READY
        } catch (e: Exception) {
            state = ModelState.ERROR
            session = null
        }
    }

    override suspend fun unload() {
        try {
            session?.close()
        } catch (_: Exception) {
        }
        session = null
        state = ModelState.UNLOADED
    }

    override suspend fun generate(prompt: String, systemPrompt: String?): String {
        val s = session ?: return modelMissingMessage()
        return try {
            val full = buildPrompt(systemPrompt, prompt)
            s.generateResponse(full)
        } catch (e: Exception) {
            "[本地模型推理失败] ${e.message}"
        }
    }

    override fun stream(prompt: String, systemPrompt: String?): Flow<String> = flow {
        val s = session
        if (s == null) {
            emit(modelMissingMessage())
            return@flow
        }
        try {
            val full = buildPrompt(systemPrompt, prompt)
            s.generateResponseAsync(full).collect { chunk -> emit(chunk) }
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
 * 模型文件名由用户在设置中指定，默认 user_model.litertlm。
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
 * 默认内置的 5 个 LiteRT 模型。
 * 对应 configs/models/ 下的 YAML 配置。
 */
object BuiltInModels {
    fun liteRtModels(context: Context): List<LiteRtModel> = listOf(
        LiteRtModel(
            context = context,
            id = "gemma4_e2b_2_5b",
            name = "Gemma 4 E2B 2.5B",
            description = "Google Gemma 4 2.5B Edge-to-Cloud",
            maxTokens = 2048, temperature = 0.7f, topP = 0.95f, topK = 40
        ),
        LiteRtModel(
            context = context,
            id = "gemma4_12b",
            name = "Gemma 4 12B",
            description = "Google Gemma 4 12B",
            maxTokens = 4096, temperature = 0.7f, topP = 0.95f, topK = 40
        ),
        LiteRtModel(
            context = context,
            id = "lfm2_5_1_7b",
            name = "LFM 2.5 1.7B",
            description = "Light Foundation Model 2.5 1.7B",
            maxTokens = 2048, temperature = 0.7f, topP = 0.9f, topK = 50
        ),
        LiteRtModel(
            context = context,
            id = "qwen3_0_6b",
            name = "Qwen 3 0.6B",
            description = "通义千问 Qwen 3 0.6B",
            maxTokens = 1024, temperature = 0.7f, topP = 0.9f, topK = 50
        ),
        LiteRtModel(
            context = context,
            id = "phi4_1_5b",
            name = "Phi-4 1.5B",
            description = "Microsoft Phi-4 1.5B",
            maxTokens = 2048, temperature = 0.7f, topP = 0.9f, topK = 50
        )
    )
}
