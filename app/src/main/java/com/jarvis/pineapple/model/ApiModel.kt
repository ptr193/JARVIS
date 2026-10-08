package com.jarvis.pineapple.model

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
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
        val result: ChatResponse = response.body()
        return result.choices.firstOrNull()?.message?.content ?: ""
    }

    override fun stream(prompt: String, systemPrompt: String?): Flow<String> = flow {
        val messages = buildList {
            systemPrompt?.let { add(Message("system", it)) }
            add(Message("user", prompt))
        }
        val body = ChatRequest(model = modelName, messages = messages, stream = true)
        val response = httpClient.post("$baseUrl/chat/completions") {
            header("Authorization", "Bearer $apiKey")
            header("Content-Type", "application/json")
            setBody(body)
        }
        val text = response.bodyAsText()
        // 简易 SSE 解析
        text.lineSequence().forEach { line ->
            if (line.startsWith("data: ") && !line.contains("[DONE]")) {
                val json = line.removePrefix("data: ").trim()
                runCatching {
                    val chunk = Json.decodeFromString<ChatChunkResponse>(json)
                    chunk.choices.firstOrNull()?.delta?.content?.let { emit(it) }
                }
            }
        }
    }
}

@Serializable
data class Message(val role: String, val content: String)

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
