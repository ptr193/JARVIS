package com.jarvis.pineapple.info

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchEngineTest {

    @Test
    fun searchParsesActualResultsAndHonorsLimit() = runBlocking {
        val engine = engineReturning(
            HttpStatusCode.OK,
            """
                <a class="result__a" href="https://example.com/1">First &amp; result</a>
                <a class="result__snippet">First snippet</a>
                <a class="result__a" href="https://example.com/2">Second</a>
            """.trimIndent()
        )

        val results = engine.search("query", maxResults = 1)

        assertEquals(1, results.size)
        assertEquals("First & result", results.single().title)
        assertEquals("First snippet", results.single().snippet)
    }

    @Test
    fun successfulResponseWithoutMatchesIsAnEmptyResult() = runBlocking {
        val engine = engineReturning(HttpStatusCode.OK, "<html>no results</html>")

        assertTrue(engine.search("query").isEmpty())
    }

    @Test
    fun httpFailureIsNotConvertedToAnEmptyResult() = runBlocking {
        val engine = engineReturning(HttpStatusCode.ServiceUnavailable, "offline")

        val error = runCatching { engine.search("query") }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals("搜索请求失败：HTTP 503", error?.message)
    }

    @Test
    fun pageFetchPropagatesNetworkFailure() = runBlocking {
        val engine = WebSearchEngine(HttpClient(MockEngine { throw IOException("offline") }))

        val error = runCatching { engine.fetchPageContent("https://example.com") }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals("offline", error?.message)
    }

    private fun engineReturning(status: HttpStatusCode, body: String): WebSearchEngine {
        val mockEngine = MockEngine {
            respond(body, status, headersOf(HttpHeaders.ContentType, "text/html"))
        }
        return WebSearchEngine(HttpClient(mockEngine))
    }
}
