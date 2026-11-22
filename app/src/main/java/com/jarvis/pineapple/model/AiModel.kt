package com.jarvis.pineapple.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

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
    val isDefault: Boolean = false,
    val maxTokens: Int = 2048,
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val topK: Int = 40
)

/**
 * 图片输入（base64 编码，不含 data URL 前缀）。
 */
data class ImageInput(
    val base64: String,
    val mimeType: String
)

/**
 * 统一模型接口（load / generate / stream / unload）
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

    /** 是否支持图片输入（当前仅 OpenAI 兼容 API 模型支持）。 */
    val supportsImages: Boolean get() = false

    /** 带图片的非流式生成；不支持视觉的实现应保持 [supportsImages] 为 false。 */
    suspend fun generateWithImage(prompt: String, systemPrompt: String?, image: ImageInput): String =
        "${info.name} 不支持图片输入"

    /** 带图片的流式生成；不支持视觉的实现应保持 [supportsImages] 为 false。 */
    fun streamWithImage(prompt: String, systemPrompt: String?, image: ImageInput): Flow<String> =
        flowOf("${info.name} 不支持图片输入")
}
