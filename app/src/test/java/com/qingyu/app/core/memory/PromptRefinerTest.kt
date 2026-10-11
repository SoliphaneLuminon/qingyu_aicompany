package com.qingyu.app.core.memory

import com.qingyu.app.data.Persona
import com.qingyu.app.remote.PromptBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prompt 润色与降级的回归测试喵。
 *
 * 这里钉的是**降级纪律**：润色是锦上添花，任何时候它掉链子喵，
 * 都必须是无声地退回原生提示词，绝不能让一次对话失败或干等喵。
 */
class PromptRefinerTest {

    private companion object {
        const val RAW = "# 你是谁\n你是苏婉，一个真实的人。\n你记得用户喜欢喝美式咖啡。"
    }

    @Test
    fun `未启用时直接返回原生提示词且不发请求`() = runBlocking {
        var calls = 0
        val client = UtilityLlm { calls++; "润色后的内容" }
        val outcome = PromptRefiner.refine(
            rawPrompt = RAW,
            config = UtilityLlmConfig(enabled = false),
            client = client,
        )
        assertEquals(0, calls)
        assertEquals(RefineSource.NATIVE, outcome.source)
        assertEquals(RefineReason.DISABLED, outcome.reason)
        assertEquals(RAW, outcome.prompt)
        assertFalse(outcome.refined)
    }

    @Test
    fun `配置缺失时降级为原生提示词`() = runBlocking {
        var calls = 0
        val client = UtilityLlm { calls++; "润色后的内容" }
        val outcome = PromptRefiner.refine(
            rawPrompt = RAW,
            config = UtilityLlmConfig(enabled = true, provider = UtilityProvider.OPENAI),
            client = client,
            mainEndpoint = "",
            mainModel = "",
            mainApiKey = "",
        )
        assertEquals(0, calls)
        assertEquals(RefineReason.NO_CONFIG, outcome.reason)
        assertEquals(RAW, outcome.prompt)
    }

    @Test
    fun `润色成功时返回模型结果`() = runBlocking {
        val polished = "你是苏婉，一个真实的人。你记得对方喜欢喝美式咖啡。"
        val client = UtilityLlm { polished }
        val outcome = PromptRefiner.refine(
            rawPrompt = RAW,
            config = UtilityLlmConfig(enabled = true, provider = UtilityProvider.OPENAI, apiKey = "sk-test"),
            client = client,
        )
        assertEquals(RefineSource.MODEL, outcome.source)
        assertEquals(RefineReason.OK, outcome.reason)
        assertEquals(polished, outcome.prompt)
        assertTrue(outcome.refined)
    }

    @Test
    fun `超时后无缝降级为原生提示词`() = runBlocking {
        val client = UtilityLlm { delay(10_000); "永远不会用到的回复" }
        val outcome = PromptRefiner.refine(
            rawPrompt = RAW,
            config = UtilityLlmConfig(
                enabled = true,
                provider = UtilityProvider.OPENAI,
                apiKey = "sk-test",
                timeoutMs = 60L,
            ),
            client = client,
        )
        assertEquals(RefineSource.NATIVE, outcome.source)
        assertEquals(RefineReason.TIMEOUT, outcome.reason)
        assertEquals(RAW, outcome.prompt)
    }

    @Test
    fun `模型报错时降级为原生提示词`() = runBlocking {
        val client = UtilityLlm { throw IllegalStateException("网关 502") }
        val outcome = PromptRefiner.refine(
            rawPrompt = RAW,
            config = UtilityLlmConfig(enabled = true, provider = UtilityProvider.OPENAI, apiKey = "sk-test"),
            client = client,
        )
        assertEquals(RefineSource.NATIVE, outcome.source)
        assertEquals(RefineReason.ERROR, outcome.reason)
        assertEquals(RAW, outcome.prompt)
    }

    @Test
    fun `模型返回空内容时降级`() = runBlocking {
        val client = UtilityLlm { "   " }
        val outcome = PromptRefiner.refine(
            rawPrompt = RAW,
            config = UtilityLlmConfig(enabled = true, provider = UtilityProvider.OPENAI, apiKey = "sk-test"),
            client = client,
        )
        assertEquals(RefineReason.EMPTY, outcome.reason)
        assertEquals(RAW, outcome.prompt)
    }

    @Test
    fun `模型敷衍只回两个字时降级`() = runBlocking {
        val client = UtilityLlm { "好的" }
        val outcome = PromptRefiner.refine(
            rawPrompt = RAW,
            config = UtilityLlmConfig(enabled = true, provider = UtilityProvider.OPENAI, apiKey = "sk-test"),
            client = client,
        )
        assertEquals(RefineReason.TOO_SHORT, outcome.reason)
        assertEquals(RAW, outcome.prompt)
    }

    @Test
    fun `默认超时是 800 毫秒`() {
        assertEquals(800L, PromptRefiner.DEFAULT_TIMEOUT_MS)
    }

    @Test
    fun `没有执行器时也不会崩`() = runBlocking {
        val outcome = PromptRefiner.refine(
            rawPrompt = RAW,
            config = UtilityLlmConfig(enabled = true),
            client = null,
        )
        assertEquals(RefineSource.NATIVE, outcome.source)
        assertEquals(RAW, outcome.prompt)
    }

    @Test
    fun `润色指令点名保护方括号段落`() {
        // 只写"不许改事实/人设"时，小模型会把【发送要求】当成排版说明删掉，
        // 于是"每句话都要用逗号"永远到不了主模型，回复就变成一整段不带标点的话喵。
        val instruction = PromptRefiner.INSTRUCTION
        assertTrue("要提到【】段落：$instruction", instruction.contains("【】"))
        assertTrue("要求逐字保留", instruction.contains("逐字"))
        assertTrue("要点名发送要求", instruction.contains("发送要求"))
        assertTrue("要点名表情包", instruction.contains("表情包"))
    }

    @Test
    fun `能认出润色结果里的发送要求`() {
        // 引擎靠这个判断决定"采信润色"还是"退回原生提示词"喵。
        assertTrue(
            "留着发送要求就该认出来",
            PromptBuilder.keepsSendingRules(
                "# 你是谁\n你是苏婉。\n\n【发送要求——必须遵守】\n句内要用逗号。",
            ),
        )
        assertFalse(
            "被删掉了就该认不出来，从而退回原生提示词",
            PromptBuilder.keepsSendingRules("# 你是谁\n你是苏婉，说话自然连贯。"),
        )
    }

    @Test
    fun `原生系统提示词里确实带着发送要求`() {
        // 上面那条守卫的前提：原生提示词本身必须有这段，否则退回去也是白退喵。
        val prompt = PromptBuilder.systemPrompt(
            persona = Persona(id = 1L, name = "小语"),
            memories = emptyList(),
            suffix = "",
            burstReplies = true,
        )
        assertTrue("原生提示词该带发送要求", PromptBuilder.keepsSendingRules(prompt))
        assertTrue("并且明确要求用逗号", prompt.contains("逗号"))
    }

    @Test
    fun `关掉拆条时系统提示词里没有发送要求`() {
        val prompt = PromptBuilder.systemPrompt(
            persona = Persona(id = 1L, name = "小语"),
            memories = emptyList(),
            suffix = "",
            burstReplies = false,
        )
        assertFalse("关掉拆条就不该有这段", PromptBuilder.keepsSendingRules(prompt))
    }
}
