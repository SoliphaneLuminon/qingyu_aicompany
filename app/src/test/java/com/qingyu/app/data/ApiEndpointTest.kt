package com.qingyu.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 接口地址补全：用户会填出各种形态，这里把能想到的都钉住喵。
 *
 * 三种外部能力（对话、语音合成、识图）共用这一份逻辑，所以这里的用例喵
 * 同时也是语音与识图地址的保证喵。
 */
class ApiEndpointTest {

    @Test
    fun `补全域名`() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            Endpoints.chat("https://api.deepseek.com"),
        )
    }

    @Test
    fun `已经带 v1 不重复追加`() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            Endpoints.chat("https://api.deepseek.com/v1"),
        )
    }

    @Test
    fun `末尾斜杠会被吃掉`() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            Endpoints.chat("https://api.deepseek.com/v1/"),
        )
    }

    @Test
    fun `末尾空格会被吃掉`() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            Endpoints.chat("  https://api.deepseek.com/v1  "),
        )
    }

    @Test
    fun `整条地址原样保留`() {
        val full = "https://gateway.example.com/v1/chat/completions"
        assertEquals(full, Endpoints.chat(full))
        assertEquals(full, Endpoints.chat("$full/"))
    }

    @Test
    fun `本地推理服务`() {
        assertEquals(
            "http://127.0.0.1:11434/v1/chat/completions",
            Endpoints.chat("http://127.0.0.1:11434"),
        )
        assertEquals(
            "http://192.168.1.8:1234/v1/chat/completions",
            Endpoints.chat("http://192.168.1.8:1234/v1"),
        )
    }

    @Test
    fun `v1beta 与 openai 这类前缀不再重复加 v1`() {
        assertEquals(
            "https://example.com/v1beta/chat/completions",
            Endpoints.chat("https://example.com/v1beta"),
        )
        assertEquals(
            "https://example.com/openai/chat/completions",
            Endpoints.chat("https://example.com/openai"),
        )
    }

    @Test
    fun `空输入返回空串而不是拼出半条地址`() {
        assertEquals("", Endpoints.chat(""))
        assertEquals("", Endpoints.chat("   "))
    }

    @Test
    fun `语音合成地址`() {
        assertEquals(
            "https://api.openai.com/v1/audio/speech",
            Endpoints.speech("https://api.openai.com/v1"),
        )
        assertEquals(
            "https://api.siliconflow.cn/v1/audio/speech",
            Endpoints.speech("https://api.siliconflow.cn"),
        )
        // 用户直接贴完整路径也要能用喵
        val full = "https://example.com/v1/audio/speech"
        assertEquals(full, Endpoints.speech(full))
    }

    @Test
    fun `识图走 chat 路径`() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            Endpoints.chat("https://api.example.com/v1"),
        )
    }

    @Test
    fun `自定义路径也能拼`() {
        assertEquals(
            "https://example.com/v1/audio/transcriptions",
            ApiEndpoint.resolve("https://example.com", Endpoints.TRANSCRIPTION_PATH),
        )
    }

    @Test
    fun `没配地址时语音与识图都返回空`() {
        assertEquals("", Endpoints.speech(""))
        assertEquals("", Endpoints.chat(""))
    }
}
