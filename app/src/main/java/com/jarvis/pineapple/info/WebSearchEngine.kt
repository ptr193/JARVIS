package com.jarvis.pineapple.info

import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 网络搜索引擎（功能 #19 真实实现）。
 *
 * 实现真实的网络搜索 API 调用，支持多个搜索引擎。
 */
class WebSearchEngine(
    private val httpClient: HttpClient = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 15_000
        }
    }
) {

    companion object {
        /**
         * 搜索引擎列表
         */
        val SEARCH_ENGINES = listOf(
            SearchEngine("DuckDuckGo", "https://html.duckduckgo.com/html/"),
            SearchEngine("Bing", "https://www.bing.com/search"),
            SearchEngine("Google", "https://www.google.com/search")
        )

        /** 部分搜索引擎会拒绝默认 Ktor UA，使用常见移动浏览器 UA。 */
        internal const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        internal fun buildSearchUrl(engine: SearchEngine, encodedQuery: String, language: String): String =
            when (engine.name) {
                "DuckDuckGo" -> "${engine.baseUrl}?q=$encodedQuery&kl=$language"
                "Bing" -> "${engine.baseUrl}?q=$encodedQuery&setlang=$language"
                "Google" -> "${engine.baseUrl}?q=$encodedQuery&hl=$language"
                else -> "${engine.baseUrl}?q=$encodedQuery"
            }

        /** 还原搜索引擎跳转链接，过滤非 http(s) 与站内导航。 */
        internal fun normalizeResultUrl(raw: String, engineName: String): String? {
            var url = raw.trim()
            when (engineName) {
                "DuckDuckGo" -> {
                    val marker = "uddg="
                    val index = url.indexOf(marker)
                    if (index >= 0) {
                        val encoded = url.substring(index + marker.length).substringBefore('&')
                        url = runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(url)
                    }
                }
                "Google" -> {
                    val marker = "/url?q="
                    val index = url.indexOf(marker)
                    if (index >= 0) {
                        val encoded = url.substring(index + marker.length).substringBefore('&')
                        url = runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(url)
                    }
                }
                "Bing" -> {
                    // 仅广告/跳转链接需要截断追踪参数；普通结果保留完整 URL。
                    if ("bing.com/ck/a" in url) url = url.substringBefore('&')
                }
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) return null
            val lower = url.lowercase()
            if (engineName == "Google" && "google." in lower && "/search" in lower) return null
            return url
        }

        internal fun cleanHtml(html: String): String =
            html
                .replace(Regex("<[^>]*>"), "")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ")
                .replace("\\s+".toRegex(), " ")
                .trim()

        internal fun parseResults(html: String, engineName: String, maxResults: Int): List<SearchResult> {
            val results = mutableListOf<SearchResult>()
            val seen = mutableSetOf<String>()

            fun add(rawUrl: String, rawTitle: String, rawSnippet: String) {
                if (results.size >= maxResults) return
                val url = normalizeResultUrl(rawUrl, engineName) ?: return
                val title = cleanHtml(rawTitle)
                if (title.isBlank()) return
                if (!seen.add(url)) return
                results.add(SearchResult(title, url, cleanHtml(rawSnippet), engineName))
            }

            when (engineName) {
                "DuckDuckGo" -> {
                    val linkPattern = Regex(
                        "<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
                        RegexOption.DOT_MATCHES_ALL
                    )
                    val snippetPattern = Regex(
                        "<a[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>",
                        RegexOption.DOT_MATCHES_ALL
                    )
                    val snippets = snippetPattern.findAll(html).map { it.groupValues[1] }.toList()
                    linkPattern.findAll(html).forEachIndexed { index, match ->
                        add(
                            match.groupValues[1],
                            match.groupValues[2],
                            snippets.getOrElse(index) { "" }
                        )
                    }
                }
                "Bing" -> {
                    val linkPattern = Regex(
                        "<h2><a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a></h2>",
                        RegexOption.DOT_MATCHES_ALL
                    )
                    val snippetPattern = Regex(
                        "<p[^>]*class=\"[^\"]*b_lineclamp[^\"]*\"[^>]*>(.*?)</p>",
                        RegexOption.DOT_MATCHES_ALL
                    )
                    val snippets = snippetPattern.findAll(html).map { it.groupValues[1] }.toList()
                    linkPattern.findAll(html).forEachIndexed { index, match ->
                        add(
                            match.groupValues[1],
                            match.groupValues[2],
                            snippets.getOrElse(index) { "" }
                        )
                    }
                }
                else -> {
                    val redirectPattern = Regex(
                        "<a[^>]*href=\"(/url\\?q=[^\"]+)\"[^>]*>(.*?)</a>",
                        RegexOption.DOT_MATCHES_ALL
                    )
                    redirectPattern.findAll(html).forEach { match ->
                        add(match.groupValues[1], match.groupValues[2], "")
                    }
                    if (results.isEmpty()) {
                        val generic = Regex(
                            "<a[^>]*href=\"(https?://[^\"]+)\"[^>]*>(.*?)</a>",
                            RegexOption.DOT_MATCHES_ALL
                        )
                        generic.findAll(html).forEach { match ->
                            add(match.groupValues[1], match.groupValues[2], "")
                        }
                    }
                }
            }
            return results
        }
    }

    /**
     * 搜索（单引擎）。
     */
    suspend fun search(
        query: String,
        engine: SearchEngine = SEARCH_ENGINES.first(),
        maxResults: Int = 10,
        language: String = "zh-CN"
    ): List<SearchResult> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = buildSearchUrl(engine, encodedQuery, language)
        val response = httpClient.get(url) {
            header(HttpHeaders.UserAgent, USER_AGENT)
            header(HttpHeaders.AcceptLanguage, "$language,zh;q=0.9,en;q=0.8")
        }
        if (response.status.value !in 200..299) {
            throw IOException("搜索请求失败：HTTP ${response.status.value}")
        }
        return parseResults(response.bodyAsText(), engine.name, maxResults)
    }

    /**
     * 依次尝试多个引擎：任一引擎成功且有结果即返回。
     * 全部请求失败时抛出 IOException（明确失败）；全部成功但无结果时返回空列表（明确无结果）。
     */
    suspend fun searchWithFallback(
        query: String,
        maxResults: Int = 10,
        language: String = "zh-CN",
        engines: List<SearchEngine> = SEARCH_ENGINES
    ): List<SearchResult> {
        val failures = mutableListOf<String>()
        for (engine in engines) {
            try {
                val results = search(query, engine, maxResults, language)
                if (results.isNotEmpty()) return results
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failures.add("${engine.name}: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        if (failures.size == engines.size) {
            throw IOException("搜索请求失败：${failures.joinToString("；")}")
        }
        return emptyList()
    }

    /**
     * 获取网页内容
     */
    suspend fun fetchPageContent(url: String): String {
        val response = httpClient.get(url)
        if (response.status.value !in 200..299) {
            throw IOException("网页读取失败：HTTP ${response.status.value}")
        }
        return extractMainContent(response.bodyAsText())
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
