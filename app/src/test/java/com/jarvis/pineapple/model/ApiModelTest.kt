package com.jarvis.pineapple.model

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiModelTest {

    @Test
    fun `generate parses OpenAI compatible response`() = runBlocking {
        val model = modelReturning(
            HttpStatusCode.OK,
            """{"id":"chat-1","choices":[{"index":0,"message":{"role":"assistant","content":"你好","refusal":null},"finish_reason":"stop"}],"usage":{}}"""
        )

        assertEquals("你好", model.generate("hello", null))
    }

    @Test
    fun `generate rejects HTTP error`() = runBlocking {
        val model = modelReturning(HttpStatusCode.Unauthorized, """{"error":"bad key"}""")

        val error = runCatching { model.generate("hello", null) }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(error!!.message.orEmpty().contains("HTTP 401"))
    }

    @Test
    fun `stream emits chunks as they are decoded`() = runBlocking {
        val model = modelReturning(
            HttpStatusCode.OK,
            "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"你\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n" +
                "data: [DONE]\n\n",
            "text/event-stream"
        )

        assertEquals(listOf("你", "好"), model.stream("hello", null).toList())
    }

    @Test
    fun `stream rejects malformed chunks instead of ignoring them`() = runBlocking {
        val model = modelReturning(
            HttpStatusCode.OK,
            "data: {not-json}\n\ndata: [DONE]\n\n",
            "text/event-stream"
        )

        val error = runCatching { model.stream("hello", null).toList() }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(error!!.message.orEmpty().contains("无效的流式数据"))
    }

    private fun modelReturning(
        status: HttpStatusCode,
        body: String,
        contentType: String = "application/json"
    ): ApiModel {
        val engine = MockEngine {
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, contentType)
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        return ApiModel("api", "API", "https://example.test/v1", "key", "model", client)
    }
}
