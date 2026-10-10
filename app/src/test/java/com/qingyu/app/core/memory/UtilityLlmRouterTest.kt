package com.qingyu.app.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小模型服务商切换的回归测试喵。
 *
 * 钉的是"自由切换 OpenAI / DeepSeek / 本地 Ollama"这条需求喵：
 * 每种 provider 都得有默认地址与默认模型，且**本地 Ollama 不该因为没有 key 就判定不可用**喵。
 */
class UtilityLlmRouterTest {

    private companion object {
        const val MAIN_ENDPOINT = "https://api.deepseek.com/v1/chat/completions"
        const val MAIN_MODEL = "deepseek-chat"
        const val MAIN_KEY = "sk-main"
    }

    @Test
    fun `未启用时判定不可用`() {
        val resolved = UtilityLlmRouter.resolve(
            config = UtilityLlmConfig(enabled = false),
            mainEndpoint = MAIN_ENDPOINT,
            mainModel = MAIN_MODEL,
            mainApiKey = MAIN_KEY,
        )
        assertFalse(resolved.usable)
    }

    @Test
    fun `跟随主模型时复用主连接的地址与模型`() {
        val resolved = UtilityLlmRouter.resolve(
            config = UtilityLlmConfig(enabled = true, provider = UtilityProvider.MAIN),
            mainEndpoint = MAIN_ENDPOINT,
            mainModel = MAIN_MODEL,
            mainApiKey = MAIN_KEY,
        )
        assertTrue(resolved.usable)
        assertEquals(MAIN_ENDPOINT, resolved.endpoint)
        assertEquals(MAIN_MODEL, resolved.model)
        assertEquals(MAIN_KEY, resolved.apiKey)
    }

    @Test
    fun `切到 OpenAI 时用它的默认地址与模型`() {
        val resolved = UtilityLlmRouter.resolve(
            config = UtilityLlmConfig(enabled = true, provider = UtilityProvider.OPENAI, apiKey = "sk-openai"),
            mainEndpoint = MAIN_ENDPOINT,
            mainModel = MAIN_MODEL,
            mainApiKey = MAIN_KEY,
        )
        assertTrue(resolved.usable)
        assertEquals("https://api.openai.com/v1/chat/completions", resolved.endpoint)
        assertEquals("gpt-4o-mini", resolved.model)
        assertEquals("sk-openai", resolved.apiKey)
    }

    @Test
    fun `切到 DeepSeek 时用它的默认地址与模型`() {
        val resolved = UtilityLlmRouter.resolve(
            config = UtilityLlmConfig(enabled = true, provider = UtilityProvider.DEEPSEEK, apiKey = "sk-ds"),
            mainEndpoint = MAIN_ENDPOINT,
            mainModel = MAIN_MODEL,
            mainApiKey = MAIN_KEY,
        )
        assertTrue(resolved.usable)
        assertEquals("https://api.deepseek.com/v1/chat/completions", resolved.endpoint)
        assertEquals("deepseek-chat", resolved.model)
    }

    @Test
    fun `本地 Ollama 没有 key 也能用`() {
        val resolved = UtilityLlmRouter.resolve(
            config = UtilityLlmConfig(enabled = true, provider = UtilityProvider.OLLAMA),
            mainEndpoint = MAIN_ENDPOINT,
            mainModel = MAIN_MODEL,
            mainApiKey = MAIN_KEY,
        )
        assertTrue(resolved.usable)
        assertEquals("http://localhost:11434/v1/chat/completions", resolved.endpoint)
    }

    @Test
    fun `需要 key 的服务商缺 key 时不可用`() {
        val resolved = UtilityLlmRouter.resolve(
            config = UtilityLlmConfig(enabled = true, provider = UtilityProvider.OPENAI),
            mainEndpoint = MAIN_ENDPOINT,
            mainModel = MAIN_MODEL,
            mainApiKey = "",
        )
        assertFalse(resolved.usable)
        assertTrue(resolved.reason.contains("API Key"))
    }

    @Test
    fun `Ollama 不走 JSON 模式`() {
        val request = UtilityLlmRouter.request(
            config = UtilityLlmConfig(enabled = true, provider = UtilityProvider.OLLAMA),
            mainEndpoint = MAIN_ENDPOINT,
            mainModel = MAIN_MODEL,
            mainApiKey = MAIN_KEY,
            systemInstruction = "hi",
            userContent = "hello",
            jsonMode = true,
        )
        assertTrue(request != null)
        assertFalse(request!!.jsonMode)
    }

    @Test
    fun `其他服务商按请求开启 JSON 模式`() {
        val request = UtilityLlmRouter.request(
            config = UtilityLlmConfig(enabled = true, provider = UtilityProvider.OPENAI, apiKey = "k"),
            mainEndpoint = MAIN_ENDPOINT,
            mainModel = MAIN_MODEL,
            mainApiKey = MAIN_KEY,
            systemInstruction = "hi",
            userContent = "hello",
            jsonMode = true,
        )
        assertTrue(request!!.jsonMode)
    }

    @Test
    fun `不可用时 request 返回 null`() {
        val request = UtilityLlmRouter.request(
            config = UtilityLlmConfig(enabled = false),
            mainEndpoint = MAIN_ENDPOINT,
            mainModel = MAIN_MODEL,
            mainApiKey = MAIN_KEY,
            systemInstruction = "hi",
            userContent = "hello",
        )
        assertEquals(null, request)
    }
}
