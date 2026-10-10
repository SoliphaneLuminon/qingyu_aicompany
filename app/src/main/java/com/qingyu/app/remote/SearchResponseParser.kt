package com.qingyu.app.remote

import com.qingyu.app.core.textOrEmpty
import com.qingyu.app.data.SearchHit
import com.qingyu.app.data.SearchProvider
import org.json.JSONArray
import org.json.JSONObject

/**
 * 联网搜索的报文解析：纯函数，有单元测试喵。
 *
 * 两家的返回结构差别不小，而且都出现过"结果藏在嵌套数组里"、"字段名相同但含义不同"的情况喵，
 * 所以先把解析钉住（见 SearchResponseParserTest喵）：
 * - 博查 Bocha：`{"data":{"webPages":{"value":[{"name","url","summary","datePublished"}]}}}`喵
 * - Tavily：`{"results":[{"title","url","content","published_date"}]}`
 */
object SearchResponseParser {

    fun parse(provider: SearchProvider, body: String, limit: Int): List<SearchHit> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val hits = when (provider) {
            SearchProvider.BOCHA -> parseBocha(root)
            SearchProvider.TAVILY -> parseTavily(root)
        }
        return hits
            .filter { it.url.isNotBlank() }
            .map { it.copy(snippet = it.snippet.take(600)) }
            .take(limit)
    }

    /** 从响应里挖出人类可读的错误信息（如果有喵）。 */
    fun errorMessage(body: String): String? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        root.optJSONObject("error")?.let { error ->
            val text = error.textOrEmpty("message").ifEmpty { error.textOrEmpty("code") }
            if (text.isNotEmpty()) return text
        }
        root.optJSONObject("data")?.let { data ->
            if (data.optInt("code", 0) != 0) {
                val text = data.textOrEmpty("msg").ifEmpty { data.textOrEmpty("message") }
                if (text.isNotEmpty()) return text
            }
        }
        root.textOrEmpty("detail").takeIf { it.isNotEmpty() }?.let { return it }
        root.textOrEmpty("message").takeIf { it.isNotEmpty() }?.let { return it }
        return null
    }

    private fun parseBocha(root: JSONObject): List<SearchHit> {
        val value = root.optJSONObject("data")
            ?.optJSONObject("webPages")
            ?.optJSONArray("value")
            ?: root.optJSONObject("data")?.optJSONArray("value")
            ?: return emptyList()
        return value.objects().map { item ->
            SearchHit(
                title = item.textOrEmpty("name").ifEmpty { item.textOrEmpty("title") },
                url = item.textOrEmpty("url").ifEmpty { item.textOrEmpty("siteUrl") },
                snippet = item.textOrEmpty("summary").ifEmpty { item.textOrEmpty("snippet") },
                publishedAt = item.textOrEmpty("datePublished").ifEmpty { item.textOrEmpty("dateLastCrawled") },
            )
        }
    }

    private fun parseTavily(root: JSONObject): List<SearchHit> {
        val results = root.optJSONArray("results") ?: return emptyList()
        return results.objects().map { item ->
            SearchHit(
                title = item.textOrEmpty("title"),
                url = item.textOrEmpty("url"),
                snippet = item.textOrEmpty("content").ifEmpty { item.textOrEmpty("raw_content") },
                publishedAt = item.textOrEmpty("published_date"),
            )
        }
    }

    /** org.json 的数组遍历：把 null 元素与奇怪类型挡在外面喵。 */
    private fun JSONArray.objects(): List<JSONObject> =
        (0 until length()).mapNotNull { index ->
            if (isNull(index)) null else optJSONObject(index)
        }
}

/**
 * 搜索请求的地址与请求体喵。
 *
 * 两家都是 POST + JSON，key 走 Authorization 头喵。
 */
object SearchRequest {

    const val BOCHA_URL = "https://api.bochaai.com/v1/web-search"
    const val TAVILY_URL = "https://api.tavily.com/search"

    fun url(provider: SearchProvider, override: String): String {
        val custom = override.trim()
        if (custom.isNotEmpty()) return custom
        return when (provider) {
            SearchProvider.BOCHA -> BOCHA_URL
            SearchProvider.TAVILY -> TAVILY_URL
        }
    }

    fun body(provider: SearchProvider, query: String, count: Int): JSONObject = when (provider) {
        SearchProvider.BOCHA -> JSONObject().apply {
            put("query", query)
            put("count", count)
            put("summary", true)
            put("freshness", "noLimit")
        }

        SearchProvider.TAVILY -> JSONObject().apply {
            put("query", query)
            put("max_results", count)
            put("search_depth", "basic")
            put("include_answer", false)
            put("include_raw_content", false)
        }
    }

    /** Tavily 也接受 key 放在 body 里，但 Bearer 头两家通吃喵。 */
    fun authorization(apiKey: String): String = "Bearer ${apiKey.trim()}"
}
