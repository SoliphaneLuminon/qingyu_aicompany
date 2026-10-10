package com.qingyu.app.remote

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 请求体构造的回归测试喵。
 *
 * 重点是多模态：文本消息的 `content` 是字符串，带图消息的 `content` 是数组喵。
 * 写错了**不会报错**，只会让模型看不见图——这种 bug 只能靠测试发现喵。
 */
class ChatPayloadTest {

    private val imageUrl = "data:image/jpeg;base64,AAAA"

    @Test
    fun `纯文本消息的 content 是字符串`() {
        val message = ChatPayload.message(ChatTurn("user", "在吗"))
        assertEquals("user", message.getString("role"))
        assertTrue("content 应当是字符串", message.get("content") is String)
        assertEquals("在吗", message.getString("content"))
    }

    @Test
    fun `带图消息的 content 是数组且先文本后图片`() {
        val message = ChatPayload.message(
            ChatTurn("user", "看看这个", images = listOf(imageUrl)),
        )
        val content = message.get("content")
        assertTrue("content 应当是数组", content is JSONArray)
        val parts = content as JSONArray
        assertEquals(2, parts.length())
        assertEquals("text", parts.getJSONObject(0).getString("type"))
        assertEquals("看看这个", parts.getJSONObject(0).getString("text"))
        assertEquals("image_url", parts.getJSONObject(1).getString("type"))
        assertEquals(imageUrl, parts.getJSONObject(1).getJSONObject("image_url").getString("url"))
    }

    @Test
    fun `只发图不写字时不塞空的文本段`() {
        val message = ChatPayload.message(ChatTurn("user", "", images = listOf(imageUrl)))
        val parts = message.get("content") as JSONArray
        assertEquals(1, parts.length())
        assertEquals("image_url", parts.getJSONObject(0).getString("type"))
    }

    @Test
    fun `多张图按顺序都带上`() {
        val message = ChatPayload.message(
            ChatTurn("user", "三张", images = listOf("data:1", "data:2", "data:3")),
        )
        val parts = message.get("content") as JSONArray
        assertEquals(4, parts.length())
        assertEquals("data:1", parts.getJSONObject(1).getJSONObject("image_url").getString("url"))
        assertEquals("data:3", parts.getJSONObject(3).getJSONObject("image_url").getString("url"))
    }

    @Test
    fun `完整请求体的必需字段齐全`() {
        val payload = ChatPayload.build(
            model = "gpt-4o-mini",
            turns = listOf(
                ChatTurn("system", "你是苏婉"),
                ChatTurn("user", "在吗"),
            ),
            temperature = 0.8,
            topP = 1.0,
            maxTokens = 1024,
            stream = true,
        )
        assertEquals("gpt-4o-mini", payload.getString("model"))
        assertTrue(payload.getBoolean("stream"))
        assertEquals(0.8, payload.getDouble("temperature"), 0.001)
        assertEquals(1024, payload.getInt("max_tokens"))
        val messages = payload.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
    }

    @Test
    fun `额外参数会被合并进请求体`() {
        val payload = ChatPayload.build(
            model = "m",
            turns = listOf(ChatTurn("user", "x")),
            temperature = 0.5,
            topP = 1.0,
            maxTokens = 100,
            stream = false,
            extraBody = JSONObject().put("frequency_penalty", 0.5),
        )
        assertEquals(0.5, payload.getDouble("frequency_penalty"), 0.001)
    }

    @Test
    fun `系统提示词里确实包含联网资料`() {
        val turn = ChatTurn("system", "你是苏婉")
        assertFalse(turn.content.contains("联网资料"))
    }

    @Test
    fun `语音请求体字段名符合 OpenAI 兼容规范`() {
        val body = SpeechRequest.body(
            model = "tts-1",
            voice = "nova",
            format = "mp3",
            text = "轻语语音测试",
            speed = 1.2f,
        )
        assertEquals("tts-1", body.getString("model"))
        assertEquals("nova", body.getString("voice"))
        assertEquals("mp3", body.getString("response_format"))
        assertEquals("轻语语音测试", body.getString("input"))
        assertEquals(1.2, body.getDouble("speed"), 0.001)
    }

    @Test
    fun `语音参数留空时有合理缺省`() {
        val body = SpeechRequest.body(model = "m", voice = "", format = "", text = "hi", speed = 1f)
        assertEquals("alloy", body.getString("voice"))
        assertEquals("mp3", body.getString("response_format"))
    }

    @Test
    fun `语速被限制在接口允许的范围内`() {
        // 官方范围 0.25 ~ 4.0，超出会被直接拒绝喵
        assertEquals(0.25, SpeechRequest.body("m", "alloy", "mp3", "x", 0.01f).getDouble("speed"), 0.001)
        assertEquals(4.0, SpeechRequest.body("m", "alloy", "mp3", "x", 99f).getDouble("speed"), 0.001)
    }

    @Test
    fun `过长的文本会被截断，避免请求被拒`() {
        val body = SpeechRequest.body("m", "alloy", "mp3", "字".repeat(9000), 1f)
        assertEquals(4096, body.getString("input").length)
    }

    @Test
    fun `识图提示词要求不编造内容`() {
        assertTrue(VisionPrompt.SYSTEM.contains("不要猜测"))
        assertTrue(VisionPrompt.SYSTEM.contains("只写你确实看到的"))
    }
}
