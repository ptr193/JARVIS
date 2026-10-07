package com.jarvis.pineapple.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 本地模型基类（LiteRT-LM 抽象层）。
 * 预留 LiteRT-LM 推理框架接入，后续可替换具体实现。
 */
abstract class LocalModel(override val info: ModelInfo) : AiModel {
    override var state: ModelState = ModelState.UNLOADED
        protected set

    override suspend fun load() {
        state = ModelState.LOADING
        try {
            onLoad()
            state = ModelState.READY
        } catch (e: Exception) {
            state = ModelState.ERROR
            throw e
        }
    }

    override suspend fun unload() {
        onUnload()
        state = ModelState.UNLOADED
    }

    protected open suspend fun onLoad() {}
    protected open fun onUnload() {}

    override fun stream(prompt: String, systemPrompt: String?): Flow<String> = flow {
        emit(generate(prompt, systemPrompt))
    }
}

/**
 * 用户自有本地模型（默认，最高优先级）
 */
class UserLocalModel : LocalModel(
    ModelInfo(
        id = "local_user",
        name = "用户自有模型",
        source = ModelSource.LOCAL_USER,
        description = "用户自行提供的本地模型",
        priority = 100,
        isDefault = true
    )
) {
    override suspend fun generate(prompt: String, systemPrompt: String?): String {
        // 预留：由用户配置具体模型路径后通过 LiteRT-LM 推理
        return "[本地模型响应] $prompt"
    }
}

/**
 * LiteRT 本地模型
 */
class LiteRtModel(
    id: String,
    name: String,
    description: String = ""
) : LocalModel(
    ModelInfo(
        id = id,
        name = name,
        source = ModelSource.LOCAL_LITERT,
        description = description,
        priority = 50
    )
) {
    override suspend fun generate(prompt: String, systemPrompt: String?): String {
        return "[${info.name}] $prompt"
    }
}

/**
 * 默认内置的 5 个 LiteRT 模型
 */
object BuiltInModels {
    val liteRtModels = listOf(
        LiteRtModel("gemma4_e2b_2_5b", "Gemma 4 E2B 2.5B", "Google Gemma 4 2.5B"),
        LiteRtModel("gemma4_12b", "Gemma 4 12B", "Google Gemma 4 12B"),
        LiteRtModel("lfm2_5_1_7b", "LFM 2.5 1.7B", "LFM 2.5 1.7B"),
        LiteRtModel("qwen3_0_6b", "Qwen 3 0.6B", "Qwen 3 0.6B"),
        LiteRtModel("phi4_1_5b", "Phi-4 1.5B", "Microsoft Phi-4 1.5B")
    )
}
