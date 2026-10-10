package com.qingyu.app.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式/非流式报文解析的回归测试喵。
 *
 * 这里的第一个用例是**一种很隐蔽的故障**：思考型模型在思考阶段发出的 chunk 是喵
 * `{"delta":{"content":null,"reasoning_content":"…"}}`，而 `org.json` 的 `optString()`喵
 * 对 JSON null 返回字符串 `"null"`——结果聊天里出现满屏的 "nullnullnull…"喵。
 * 所以每个用例都围绕"JSON null 绝不能变成文本"展开喵。
 */
class ChatStreamParserTest {

    @Test
    fun `思考阶段的 chunk 不能把 content 的 null 当成正文`() {
        // 这就是造成满屏 null 的真实报文形状喵
        val chunk = """{"choices":[{"delta":{"content":null,"reasoning_content":"他"},"index":0}]}"""
        val delta = ChatStreamParser.parseChunk(chunk)
        assertEquals(ChatDelta.Reasoning("他"), delta)
    }

    @Test
    fun `content 是 JSON null 时解析结果里没有 Content`() {
        val chunk = """{"choices":[{"delta":{"content":null}}]}"""
        assertNull(ChatStreamParser.parseChunk(chunk))
    }

    @Test
    fun `reasoning_content 是 JSON null 时不会产出 reasoning`() {
        val chunk = """{"choices":[{"delta":{"content":"在啊","reasoning_content":null}}]}"""
        assertEquals(ChatDelta.Content("在啊"), ChatStreamParser.parseChunk(chunk))
    }

    @Test
    fun `把 null 写成字符串也当成没有内容`() {
        val chunk = """{"choices":[{"delta":{"content":"null","reasoning_content":"null"}}]}"""
        assertNull(ChatStreamParser.parseChunk(chunk))
        assertNull(ChatStreamParser.parseChunk("""{"choices":[{"delta":{"content":"NULL"}}]}"""))
    }

    @Test
    fun `正常流式分片逐个解析`() {
        assertEquals(ChatDelta.Content("在"), ChatStreamParser.parseChunk("""{"choices":[{"delta":{"content":"在"}}]}"""))
        assertEquals(ChatDelta.Content("啊"), ChatStreamParser.parseChunk("""{"choices":[{"delta":{"content":"啊"}}]}"""))
    }

    @Test
    fun `兼容 reasoning 字段名`() {
        val chunk = """{"choices":[{"delta":{"reasoning":"在想"}}]}"""
        assertEquals(ChatDelta.Reasoning("在想"), ChatStreamParser.parseChunk(chunk))
    }

    @Test
    fun `空 delta 与无 choices 返回 null 而不是崩掉`() {
        assertNull(ChatStreamParser.parseChunk("""{"choices":[{"delta":{}}]}"""))
        assertNull(ChatStreamParser.parseChunk("""{"choices":[]}"""))
        assertNull(ChatStreamParser.parseChunk("""{"id":"x"}"""))
        assertNull(ChatStreamParser.parseChunk("不是 json"))
        assertNull(ChatStreamParser.parseChunk(""))
    }

    @Test
    fun `finish_reason 的 null 与字符串 null 都不算结束原因`() {
        assertNull(ChatStreamParser.parseFinishReason("""{"choices":[{"finish_reason":null}]}"""))
        assertNull(ChatStreamParser.parseFinishReason("""{"choices":[{"finish_reason":"null"}]}"""))
        assertEquals("length", ChatStreamParser.parseFinishReason("""{"choices":[{"finish_reason":"length"}]}"""))
    }

    @Test
    fun `接口错误会被抛成 Failure`() {
        val error = runCatching {
            ChatStreamParser.parseChunk("""{"error":{"message":"额度不足","type":"insufficient_quota"}}""")
        }.exceptionOrNull()
        assertTrue(error is ChatClient.Failure)
        assertEquals("额度不足", error?.message)
    }

    @Test
    fun `错误消息为 null 时给出兜底文案`() {
        val error = runCatching {
            ChatStreamParser.parseChunk("""{"error":{"message":null}}""")
        }.exceptionOrNull()
        assertTrue(error is ChatClient.Failure)
        assertEquals("接口返回错误", error?.message)
    }

    @Test
    fun `非流式响应不会被 null 污染`() {
        val body = """
            {"choices":[{"message":{"role":"assistant","content":"在啊，怎么啦？","reasoning_content":null},"finish_reason":"stop"}]}
        """.trimIndent()
        val (reasoning, content) = ChatStreamParser.parseWholeBody(body)
        assertEquals("", reasoning)
        assertEquals("在啊，怎么啦？", content)
    }

    @Test
    fun `非流式响应带思考时两部分分开返回`() {
        val body = """
            {"choices":[{"message":{"content":"在啊","reasoning_content":"他这么突然喊我"},"finish_reason":"stop"}]}
        """.trimIndent()
        val (reasoning, content) = ChatStreamParser.parseWholeBody(body)
        assertEquals("他这么突然喊我", reasoning)
        assertEquals("在啊", content)
    }

    @Test
    fun `非流式整包会补一个 Done`() {
        val body = """{"choices":[{"message":{"content":"好"},"finish_reason":"length"}]}"""
        val deltas = ChatStreamParser.wholeBodyDeltas(body, started = 0L)
        assertEquals(2, deltas.size)
        assertEquals(ChatDelta.Content("好"), deltas[0])
        val done = deltas[1]
        assertTrue(done is ChatDelta.Done)
        assertEquals("length", (done as ChatDelta.Done).finishReason)
    }

    @Test
    fun `兜底清理只吃重复的 null 噪音，不动正文里的词`() {
        // 真故障形态：重复出现喵
        assertEquals("", PromptBuilder.stripNullArtifacts("nullnullnullnull"))
        assertEquals("就这？", PromptBuilder.stripNullArtifacts("null null null 就这？"))
        assertEquals("就这？俩字打发我", PromptBuilder.stripNullArtifacts("nullnull就这？俩字打发我"))
        assertEquals("就这？", PromptBuilder.stripNullArtifacts("nullnullnullnullnull就这？"))
        // 正文里真实提到 null 时必须原样留着（空格分隔的单个 null 不是噪音喵）
        assertEquals("这里是 null 值", PromptBuilder.stripNullArtifacts("这里是 null 值"))
        assertEquals("这个字段是 null，要注意", PromptBuilder.stripNullArtifacts("这个字段是 null，要注意"))
        // 真词不能被误伤喵
        assertEquals("normal", PromptBuilder.stripNullArtifacts("normal"))
        assertEquals("nullable", PromptBuilder.stripNullArtifacts("nullable"))
        assertEquals("annulment", PromptBuilder.stripNullArtifacts("annulment"))
        assertEquals("这段是干净的就原样返回", PromptBuilder.stripNullArtifacts("这段是干净的就原样返回"))
    }
}
