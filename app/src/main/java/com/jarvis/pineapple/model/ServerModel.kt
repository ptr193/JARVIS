package com.jarvis.pineapple.model

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 个人服务器模型
 */
class ServerModel(
    id: String,
    name: String,
    private val serverUrl: String,
    private val httpClient: HttpClient
) : AiModel {
    override val info = ModelInfo(
        id = id, name = name, source = ModelSource.SERVER,
        description = "个人服务器模型", priority = 10
    )
    override var state: ModelState = ModelState.UNLOADED

    override suspend fun load() {
        state = ModelState.LOADING
        state = try {
            ModelState.READY
        } catch (e: Exception) {
            ModelState.ERROR
        }
    }

    override suspend fun unload() { state = ModelState.UNLOADED }

    override suspend fun generate(prompt: String, systemPrompt: String?): String {
        return try {
            val response = httpClient.post("$serverUrl/generate") {
                setBody(mapOf("prompt" to prompt, "system" to (systemPrompt ?: "")))
            }
            response.body()
        } catch (e: Exception) {
            "[服务器连接失败] ${e.message}"
        }
    }

    override fun stream(prompt: String, systemPrompt: String?): Flow<String> = flow {
        emit(generate(prompt, systemPrompt))
    }
}
