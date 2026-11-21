package com.jarvis.pineapple.model

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * API 模型（OpenAI 兼容协议）
 */
class ApiModel(
    id: String,
    name: String,
    private val baseUrl: String,
    private val apiKey: String,
    private val modelName: String,
    private val httpClient: HttpClient
) : AiModel {
    override val info = ModelInfo(
        id = id, name = name, source = ModelSource.API,
        description = "云端 API 模型", priority = 20
    )
    override var state: ModelState = ModelState.READY

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun load() { state = ModelState.READY }
    override suspend fun unload() { state = ModelState.UNLOADED }

    override suspend fun generate(prompt: String, systemPrompt: String?): String {
        val messages = buildList {
            systemPrompt?.let { add(Message("system", it)) }
            add(Message("user", prompt))
        }
        val body = ChatRequest(model = modelName, messages = messages, stream = false)
        val response = httpClient.post("$baseUrl/chat/completions") {
            header("Authorization", "Bearer $apiKey")
            header("Content-Type", "application/json")
            setBody(body)
        }
        if (!response.status.isSuccess()) {
            throw ApiRequestException(response.status.value, response.bodyAsText().take(MAX_ERROR_BODY))
        }
        val result: ChatResponse = response.body()
        return result.choices.firstOrNull()?.message?.content?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("API 返回空回答")
    }

    override fun stream(prompt: String, systemPrompt: String?): Flow<String> = flow {
        val messages = buildList {
            systemPrompt?.let { add(Message("system", it)) }
            add(Message("user", prompt))
        }
        val body = ChatRequest(model = modelName, messages = messages, stream = true)
        httpClient.preparePost("$baseUrl/chat/completions") {
            header("Authorization", "Bearer $apiKey")
            header("Content-Type", "application/json")
            header("Accept", "text/event-stream")
            setBody(body)
        }.execute { response ->
            if (!response.status.isSuccess()) {
                throw ApiRequestException(response.status.value, response.bodyAsText().take(MAX_ERROR_BODY))
            }
            val channel = response.bodyAsChannel()
            var receivedData = false
            var emittedContent = false
            while (true) {
                val line = channel.readUTF8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") break
                if (payload.isEmpty()) continue
                receivedData = true
                val chunk = try {
                    json.decodeFromString<ChatChunkResponse>(payload)
                } catch (e: Exception) {
                    throw IllegalStateException("API 返回了无效的流式数据", e)
                }
                chunk.choices.firstOrNull()?.delta?.content?.takeIf { it.isNotEmpty() }?.let {
                    emittedContent = true
                    emit(it)
                }
            }
            check(receivedData) { "API 未返回流式数据" }
            check(emittedContent) { "API 返回空回答" }
        }
    }
}

private const val MAX_ERROR_BODY = 500

private class ApiRequestException(status: Int, body: String) : IllegalStateException(
    "API 请求失败（HTTP $status）" + if (body.isBlank()) "" else "：$body"
)

@Serializable
data class Message(val role: String = "", val content: String = "")

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<Message>,
    val stream: Boolean = false,
    val temperature: Double = 0.7
)

@Serializable
data class ChatResponse(
    val choices: List<Choice> = emptyList()
) {
    @Serializable
    data class Choice(val message: Message = Message("", ""))
}

@Serializable
data class ChatChunkResponse(
    val choices: List<ChunkChoice> = emptyList()
) {
    @Serializable
    data class ChunkChoice(val delta: Message = Message("", ""))
}
