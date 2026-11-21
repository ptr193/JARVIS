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

class ServerModelTest {

    @Test
    fun `load validates configured server URL`() = runBlocking {
        val invalid = modelReturning(HttpStatusCode.OK, "{}", serverUrl = "not-a-url")
        invalid.load()
        assertEquals(ModelState.ERROR, invalid.state)

        val valid = modelReturning(HttpStatusCode.OK, "{}")
        valid.load()
        assertEquals(ModelState.READY, valid.state)
    }

    @Test
    fun `generate calls generate endpoint and parses answer`() = runBlocking {
        val model = modelReturning(HttpStatusCode.OK, """{"response":"服务器回答"}""")

        assertEquals("服务器回答", model.generate("问题", "人格"))
        assertEquals(ModelState.READY, model.state)
        assertEquals(listOf("服务器回答"), model.stream("问题", "人格").toList())
    }

    @Test
    fun `generate rejects HTTP error and enters error state`() = runBlocking {
        val model = modelReturning(HttpStatusCode.ServiceUnavailable, "offline")

        val error = runCatching { model.generate("问题", null) }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(error!!.message.orEmpty().contains("HTTP 503"))
        assertEquals(ModelState.ERROR, model.state)
    }

    @Test
    fun `generate rejects blank answer instead of reporting success`() = runBlocking {
        val model = modelReturning(HttpStatusCode.OK, """{"response":"  "}""")

        val error = runCatching { model.generate("问题", null) }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(error!!.message.orEmpty().contains("空回答"))
        assertEquals(ModelState.ERROR, model.state)
    }

    private fun modelReturning(
        status: HttpStatusCode,
        body: String,
        serverUrl: String = "https://server.example.test"
    ): ServerModel {
        val engine = MockEngine { request ->
            assertEquals("$serverUrl/generate", request.url.toString())
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        return ServerModel("server", "个人服务器", serverUrl, client)
    }
}
