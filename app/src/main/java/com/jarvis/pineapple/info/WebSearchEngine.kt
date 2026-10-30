package com.jarvis.pineapple.info

import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder

/**
 * 网络搜索引擎（功能 #19 真实实现）。
 *
 * 实现真实的网络搜索 API 调用，支持多个搜索引擎。
 */
class WebSearchEngine {

    private val httpClient = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
    }

    companion object {
        /**
         * 搜索引擎列表
         */
        val SEARCH_ENGINES = listOf(
            SearchEngine("DuckDuckGo", "https://html.duckduckgo.com/html/"),
            SearchEngine("Bing", "https://www.bing.com/search"),
            SearchEngine("Google", "https://www.google.com/search")
        )
    }

    /**
     * 搜索
     */
    suspend fun search(
        query: String,
        engine: SearchEngine = SEARCH_ENGINES.first(),
        maxResults: Int = 10,
        language: String = "zh-CN"
    ): List<SearchResult> {
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = buildSearchUrl(engine, encodedQuery, language)
            val response = httpClient.get(url)
            parseSearchResults(response.bodyAsText(), engine, maxResults)
        } catch (e: Exception) {
            // 返回空列表
            emptyList()
        }
    }

    /**
     * 构建搜索 URL
     */
    private fun buildSearchUrl(engine: SearchEngine, query: String, language: String): String {
        return when (engine.name) {
            "DuckDuckGo" -> "${engine.baseUrl}?q=$query&kl=$language"
            "Bing" -> "${engine.baseUrl}?q=$query&setlang=$language"
            "Google" -> "${engine.baseUrl}?q=$query&hl=$language"
            else -> "${engine.baseUrl}?q=$query"
        }
    }

    /**
     * 解析搜索结果
     */
    private fun parseSearchResults(
        html: String,
        engine: SearchEngine,
        maxResults: Int
    ): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        when (engine.name) {
            "DuckDuckGo" -> {
                // 解析 DuckDuckGo HTML 结果
                val resultPattern = Regex("<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>")
                val snippetPattern = Regex("<a[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>")

                val links = resultPattern.findAll(html).toList()
                val snippets = snippetPattern.findAll(html).toList()

                links.take(maxResults).forEachIndexed { index, match ->
                    val url = match.groupValues[1]
                    val title = cleanHtml(match.groupValues[2])
                    val snippet = if (index < snippets.size) {
                        cleanHtml(snippets[index].groupValues[1])
                    } else {
                        ""
                    }
                    results.add(SearchResult(title, url, snippet, engine.name))
                }
            }
            "Bing" -> {
                // 解析 Bing HTML 结果
                val resultPattern = Regex("<h2><a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a></h2>")
                val snippetPattern = Regex("<p[^>]*class=\"b_lineclamp[^\"]*\"[^>]*>(.*?)</p>")

                val links = resultPattern.findAll(html).toList()
                val snippets = snippetPattern.findAll(html).toList()

                links.take(maxResults).forEachIndexed { index, match ->
                    val url = match.groupValues[1]
                    val title = cleanHtml(match.groupValues[2])
                    val snippet = if (index < snippets.size) {
                        cleanHtml(snippets[index].groupValues[1])
                    } else {
                        ""
                    }
                    results.add(SearchResult(title, url, snippet, engine.name))
                }
            }
            else -> {
                // 通用解析
                val resultPattern = Regex("<a[^>]*href=\"(https?://[^\"]+)\"[^>]*>(.*?)</a>")
                val links = resultPattern.findAll(html).take(maxResults)

                links.forEach { match ->
                    val url = match.groupValues[1]
                    val title = cleanHtml(match.groupValues[2])
                    results.add(SearchResult(title, url, "", engine.name))
                }
            }
        }

        return results
    }

    /**
     * 清理 HTML 标签
     */
    private fun cleanHtml(html: String): String {
        return html
            .replace(Regex("<[^>]*>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .trim()
    }

    /**
     * 获取网页内容
     */
    suspend fun fetchPageContent(url: String): String {
        return try {
            val response = httpClient.get(url)
            val html = response.bodyAsText()
            extractMainContent(html)
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 提取网页主要内容
     */
    private fun extractMainContent(html: String): String {
        // 移除脚本和样式
        val withoutScripts = html
            .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<[^>]*>"), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()

        return withoutScripts.take(5000)
    }

    /**
     * 搜索引擎信息
     */
    data class SearchEngine(
        val name: String,
        val baseUrl: String
    )

    /**
     * 搜索结果
     */
    data class SearchResult(
        val title: String,
        val url: String,
        val snippet: String,
        val source: String
    )
}
