package com.jarvis.pineapple.model

import kotlinx.coroutines.flow.Flow

/**
 * 模型状态
 */
enum class ModelState { UNLOADED, LOADING, READY, ERROR }

/**
 * 模型来源类型
 */
enum class ModelSource { LOCAL_USER, LOCAL_LITERT, API, SERVER }

/**
 * 模型描述
 */
data class ModelInfo(
    val id: String,
    val name: String,
    val source: ModelSource,
    val description: String = "",
    val priority: Int = 0,
    val isDefault: Boolean = false
)

/**
 * 统一模型接口（load / generate / stream / unload）
 * 所有本地模型、API 模型、服务器模型均实现此接口。
 */
interface AiModel {
    val info: ModelInfo
    val state: ModelState

    suspend fun load()
    suspend fun unload()

    /** 非流式生成 */
    suspend fun generate(prompt: String, systemPrompt: String?): String

    /** 流式生成，按 token 推送 */
    fun stream(prompt: String, systemPrompt: String?): Flow<String>
}
