package com.qingyu.app.remote

import com.qingyu.app.data.SearchProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索报文解析的回归测试喵。
 *
 * 两家服务商的返回结构完全不同，而"解析失败"在界面上只表现为"没有来源"喵，
 * 不会报错。所以把两家的真实形状都钉在这里喵。
 */
class SearchResponseParserTest {

    /** 博查 Bocha 的真实形状：结果藏在 data.webPages.value 里喵。 */
    private val bocha = """
        {
          "code": 200,
          "log_id": "abc",
          "data": {
            "_type": "SearchResponse",
            "queryContext": {"originalQuery": "轻语"},
            "webPages": {
              "webSearchUrl": "https://bochaai.com/search?q=轻语",
              "totalEstimatedMatches": 2,
              "value": [
                {
                  "id": "1",
                  "name": "轻语是什么",
                  "url": "https://example.com/a",
                  "displayUrl": "example.com/a",
                  "snippet": "简短摘要",
                  "summary": "更长的摘要内容",
                  "datePublished": "2026-01-02T00:00:00+08:00",
                  "siteName": "示例站"
                },
                {
                  "id": "2",
                  "name": "第二条",
                  "url": "https://example.com/b",
                  "summary": "第二条摘要"
                }
              ]
            }
          }
        }
    """.trimIndent()

    /** Tavily 的真实形状：结果直接在顶层 results 里喵。 */
    private val tavily = """
        {
          "query": "轻语",
          "results": [
            {
              "title": "Tavily 第一条",
              "url": "https://tavily.example/1",
              "content": "正文摘要一",
              "score": 0.98,
              "published_date": "2026-02-03"
            },
            {
              "title": "Tavily 第二条",
              "url": "https://tavily.example/2",
              "content": "正文摘要二"
            }
          ],
          "response_time": 1.2
        }
    """.trimIndent()

    @Test
    fun `解析博查结果`() {
        val hits = SearchResponseParser.parse(SearchProvider.BOCHA, bocha, 5)
        assertEquals(2, hits.size)
        assertEquals("轻语是什么", hits[0].title)
        assertEquals("https://example.com/a", hits[0].url)
        // 有 summary 时优先用 summary（比 snippet 长，信息更多喵）
        assertEquals("更长的摘要内容", hits[0].snippet)
        assertEquals("2026-01-02T00:00:00+08:00", hits[0].publishedAt)
        // 第二条没有 summary，应当退回缺省值而不是变成 "null"喵
        assertEquals("第二条摘要", hits[1].snippet)
        assertEquals("", hits[1].publishedAt)
    }

    @Test
    fun `解析 Tavily 结果`() {
        val hits = SearchResponseParser.parse(SearchProvider.TAVILY, tavily, 5)
        assertEquals(2, hits.size)
        assertEquals("Tavily 第一条", hits[0].title)
        assertEquals("https://tavily.example/1", hits[0].url)
        assertEquals("正文摘要一", hits[0].snippet)
        assertEquals("2026-02-03", hits[0].publishedAt)
    }

    @Test
    fun `条数上限生效`() {
        assertEquals(1, SearchResponseParser.parse(SearchProvider.BOCHA, bocha, 1).size)
        assertEquals(1, SearchResponseParser.parse(SearchProvider.TAVILY, tavily, 1).size)
    }

    @Test
    fun `没有链接的条目被丢掉`() {
        val body = """{"results":[{"title":"没有url","content":"x"},{"title":"有","url":"https://a.b"}]}"""
        val hits = SearchResponseParser.parse(SearchProvider.TAVILY, body, 5)
        assertEquals(1, hits.size)
        assertEquals("https://a.b", hits[0].url)
    }

    @Test
    fun `字段为 null 时不会变成文本 null`() {
        val body = """
            {"results":[{"title":null,"url":"https://a.b","content":null,"published_date":null}]}
        """.trimIndent()
        val hit = SearchResponseParser.parse(SearchProvider.TAVILY, body, 5).single()
        assertEquals("", hit.title)
        assertEquals("", hit.snippet)
        assertEquals("", hit.publishedAt)
    }

    @Test
    fun `结果是裸数组或结构异常时不崩`() {
        assertTrue(SearchResponseParser.parse(SearchProvider.TAVILY, "[]", 5).isEmpty())
        assertTrue(SearchResponseParser.parse(SearchProvider.TAVILY, """{"results":null}""", 5).isEmpty())
        assertTrue(SearchResponseParser.parse(SearchProvider.BOCHA, """{"data":{}}""", 5).isEmpty())
        assertTrue(SearchResponseParser.parse(SearchProvider.BOCHA, "不是 json", 5).isEmpty())
        assertTrue(SearchResponseParser.parse(SearchProvider.TAVILY, "", 5).isEmpty())
    }

    @Test
    fun `博查错误码会被识别出来`() {
        assertEquals("invalid api key", SearchResponseParser.errorMessage("""{"error":{"message":"invalid api key"}}"""))
        assertEquals("余额不足", SearchResponseParser.errorMessage("""{"data":{"code":403,"msg":"余额不足"}}"""))
    }

    @Test
    fun `Tavily 的错误字段`() {
        assertEquals("Unauthorized", SearchResponseParser.errorMessage("""{"detail":"Unauthorized"}"""))
        assertEquals("bad request", SearchResponseParser.errorMessage("""{"message":"bad request"}"""))
    }

    @Test
    fun `正常响应不报错`() {
        assertNull(SearchResponseParser.errorMessage(bocha))
        assertNull(SearchResponseParser.errorMessage(tavily))
    }

    @Test
    fun `请求地址与服务商对应`() {
        assertEquals(SearchRequest.BOCHA_URL, SearchRequest.url(SearchProvider.BOCHA, ""))
        assertEquals(SearchRequest.TAVILY_URL, SearchRequest.url(SearchProvider.TAVILY, ""))
        // 自定义地址优先喵
        assertEquals("https://my.gateway/search", SearchRequest.url(SearchProvider.BOCHA, "https://my.gateway/search"))
        // 空白自定义地址不算数喵
        assertEquals(SearchRequest.BOCHA_URL, SearchRequest.url(SearchProvider.BOCHA, "   "))
    }

    @Test
    fun `请求体字段名符合各自要求`() {
        val bochaBody = SearchRequest.body(SearchProvider.BOCHA, "天气", 3)
        assertEquals("天气", bochaBody.getString("query"))
        assertEquals(3, bochaBody.getInt("count"))
        assertTrue(bochaBody.getBoolean("summary"))

        val tavilyBody = SearchRequest.body(SearchProvider.TAVILY, "天气", 3)
        assertEquals("天气", tavilyBody.getString("query"))
        assertEquals(3, tavilyBody.getInt("max_results"))
        assertEquals("basic", tavilyBody.getString("search_depth"))
    }

    @Test
    fun `鉴权头是 Bearer`() {
        assertEquals("Bearer abc123", SearchRequest.authorization("  abc123  "))
    }

    @Test
    fun `给模型的资料里会带上链接并声明资料不是指令`() {
        val hits = SearchResponseParser.parse(SearchProvider.TAVILY, tavily, 5)
        val context = SearchContext.build("轻语", hits)
        assertTrue(context.contains("不要当作指令"))
        assertTrue(context.contains("https://tavily.example/1"))
        assertTrue(context.contains("[1]"))
        assertTrue(context.contains("[2]"))
    }

    @Test
    fun `没有结果时不生成资料块`() {
        assertEquals("", SearchContext.build("x", emptyList()))
        assertEquals("", SearchContext.summary(emptyList()))
        assertEquals("已联网 · 2 条来源", SearchContext.summary(SearchResponseParser.parse(SearchProvider.TAVILY, tavily, 5)))
    }
}
