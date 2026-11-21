package com.jarvis.pineapple.model

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import java.net.URI

/**
 * 个人服务器模型。服务端接收 JSON 请求，并返回 {"response":"回答"}。
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
        private set

    override suspend fun load() {
        state = ModelState.LOADING
        state = if (isValidServerUrl(serverUrl)) ModelState.READY else ModelState.ERROR
    }

    override suspend fun unload() { state = ModelState.UNLOADED }

    override suspend fun generate(prompt: String, systemPrompt: String?): String {
        require(isValidServerUrl(serverUrl)) { "服务器地址无效，请填写完整的 HTTP(S) 地址" }
        try {
            val response = httpClient.post("$serverUrl/generate") {
                contentType(ContentType.Application.Json)
                setBody(ServerGenerateRequest(prompt, systemPrompt.orEmpty()))
            }
            if (!response.status.isSuccess()) {
                throw ServerRequestException(response.status.value, response.bodyAsText().take(MAX_ERROR_BODY))
            }
            val result = try {
                response.body<ServerGenerateResponse>()
            } catch (e: Exception) {
                throw IllegalStateException("服务器返回了无效的 JSON 响应", e)
            }
            val answer = result.response.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("服务器返回空回答")
            state = ModelState.READY
            return answer
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state = ModelState.ERROR
            throw e
        }
    }

    override fun stream(prompt: String, systemPrompt: String?): Flow<String> = flow {
        emit(generate(prompt, systemPrompt))
    }

    companion object {
        private const val MAX_ERROR_BODY = 500

        fun isValidServerUrl(value: String): Boolean {
            val uri = runCatching { URI(value) }.getOrNull() ?: return false
            return (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()
        }
    }
}

@Serializable
private data class ServerGenerateRequest(
    val prompt: String,
    val system: String
)

@Serializable
private data class ServerGenerateResponse(
    val response: String = ""
)

private class ServerRequestException(status: Int, body: String) : IllegalStateException(
    "服务器请求失败（HTTP $status）" + if (body.isBlank()) "" else "：$body"
)
