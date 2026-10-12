package com.qingyu.app.remote

import com.qingyu.app.data.ChatMessage
import com.qingyu.app.data.MessageKind
import com.qingyu.app.data.MessageRole
import com.qingyu.app.data.Persona
import com.qingyu.app.data.repo.StickerDirective
import com.qingyu.app.data.repo.StickerEagerness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 历史消息在提示词里长什么样喵。
 *
 * 重点是**表情包**：它的正文是表情名（"开心"这种普通词），
 * 直接当正文送进去，模型分不清"他打了开心两个字"和"他发了个叫开心的表情"喵。
 */
class StickerPromptRenderingTest {

    private fun persona() = Persona(id = 1L, name = "小语")

    private fun userSticker(name: String) = ChatMessage(
        conversationId = 1L,
        role = MessageRole.USER,
        content = name,
        timestamp = 1L,
        kind = MessageKind.STICKER,
    )

    private fun assistantSticker(name: String) = ChatMessage(
        conversationId = 1L,
        role = MessageRole.ASSISTANT,
        content = name,
        timestamp = 2L,
        kind = MessageKind.STICKER,
    )

    @Test
    fun `用户发的表情渲染成对方发来`() {
        val turns = PromptBuilder.turns(
            persona = persona(),
            memories = emptyList(),
            history = listOf(userSticker("开心")),
            suffix = "",
            burstReplies = false,
            historyLimit = 20,
        )
        val content = turns.last().content
        assertTrue("应当写明是对方发的：$content", content.contains("对方发来"))
        assertTrue("应当带上表情名：$content", content.contains("开心"))
    }

    @Test
    fun `助手发过的表情渲染成它该写的指令本身`() {
        // 这是"回复里掺杂（你发来一个表情包（表情包名字））"的根因喵：
        // 助手那一侧的历史原本也写成旁注，模型看到"我以前是这么说话的"，
        // 就照着抄回正文，用户于是收到一条以假乱真的旁注喵。
        // 现在渲染成指令本身——**照抄自己与正确输出变成同一件事**喵。
        val turns = PromptBuilder.turns(
            persona = persona(),
            memories = emptyList(),
            history = listOf(assistantSticker("笑到打滚")),
            suffix = "",
            burstReplies = false,
            historyLimit = 20,
        )
        val content = turns.last().content
        assertEquals("自己发过的表情就该是合法指令", "【表情包：笑到打滚】", content)
        assertTrue("不该再出现旁注形态：$content", !content.contains("你发了"))
    }

    @Test
    fun `自己发过的表情渲染结果能被指令解析器认回来`() {
        // 渲染与解析必须是同一套约定，否则"照抄自己"仍会漏成正文文字喵。
        val rendered = PromptBuilder.turns(
            persona = persona(),
            memories = emptyList(),
            history = listOf(assistantSticker("笑到打滚")),
            suffix = "",
            burstReplies = false,
            historyLimit = 20,
        ).last().content

        val parsed = StickerDirective.parse(rendered, setOf("笑到打滚"))
        assertEquals("正文该被剥空", "", parsed.text)
        assertEquals("该解析回同一个表情", listOf("笑到打滚"), parsed.stickerNames)
    }

    @Test
    fun `对方发的表情仍渲染成旁注且不带多余空格`() {
        // 对方不会写指令，只能由我们转述"这是一个表情、不是两个汉字"喵。
        val content = PromptBuilder.turns(
            persona = persona(),
            memories = emptyList(),
            history = listOf(userSticker("开心")),
            suffix = "",
            burstReplies = false,
            historyLimit = 20,
        ).last().content
        assertEquals("（对方发来一个表情包：开心）", content)
    }

    @Test
    fun `同一个表情两次渲染出的文字完全一致`() {
        // 这是"连发同一张表情却每次都像头一回见"的根因所在喵：
        // 以前表情走识图通道、每发一次重新描述一遍，措辞每次都不一样，
        // 模型读到的就是三段不同的话。现在必须是稳定的同一串字喵。
        val first = PromptBuilder.turns(
            persona = persona(),
            memories = emptyList(),
            history = listOf(userSticker("开心")),
            suffix = "",
            burstReplies = false,
            historyLimit = 20,
        ).last().content

        val second = PromptBuilder.turns(
            persona = persona(),
            memories = emptyList(),
            history = listOf(userSticker("开心")),
            suffix = "",
            burstReplies = false,
            historyLimit = 20,
        ).last().content

        assertEquals("同一张表情每次都必须渲染成同一串字", first, second)
    }

    @Test
    fun `名字为空的表情也能渲染`() {
        val turns = PromptBuilder.turns(
            persona = persona(),
            memories = emptyList(),
            history = listOf(userSticker("")),
            suffix = "",
            burstReplies = false,
            historyLimit = 20,
        )
        assertTrue("不该崩，也该有个说法", turns.last().content.contains("表情包"))
    }

    @Test
    fun `普通文字消息原样送进去`() {
        val message = ChatMessage(
            conversationId = 1L,
            role = MessageRole.USER,
            content = "今天天气不错",
            timestamp = 1L,
        )
        val turns = PromptBuilder.turns(
            persona = persona(),
            memories = emptyList(),
            history = listOf(message),
            suffix = "",
            burstReplies = false,
            historyLimit = 20,
        )
        assertEquals("普通文字不该被包装", "今天天气不错", turns.last().content)
    }

    @Test
    fun `有可用表情时系统提示词里说明怎么发`() {
        val turns = PromptBuilder.turns(
            persona = persona(),
            memories = emptyList(),
            history = listOf(userSticker("开心")),
            suffix = "",
            burstReplies = false,
            historyLimit = 20,
            stickerNames = listOf("开心", "难过"),
        )
        val system = turns.first().content
        assertTrue("要告诉模型怎么写指令：$system", system.contains("【表情包：名字】"))
        assertTrue("要列出可用表情", system.contains("开心") && system.contains("难过"))
    }

    @Test
    fun `没有可用表情时不说自己也能发`() {
        // 说了它就会写指令，而本地会把认不出的名字丢掉，它却以为发出去了喵。
        val turns = PromptBuilder.turns(
            persona = persona(),
            memories = emptyList(),
            history = listOf(userSticker("开心")),
            suffix = "",
            burstReplies = false,
            historyLimit = 20,
            stickerNames = emptyList(),
        )
        val system = turns.first().content
        assertTrue("不该教它怎么发", !system.contains("【表情包：名字】"))
        assertTrue("但仍要说清对方发的表情怎么对待", system.contains("表情包"))
    }

    @Test
    fun `温度越高，提示词里的发表情包频率要求越积极`() {
        // 用户要求：聊天温度越高，它发表情包的频率就越高喵。
        fun systemFor(temperature: Double) = PromptBuilder.turns(
            persona = persona(),
            memories = emptyList(),
            history = listOf(userSticker("开心")),
            suffix = "",
            burstReplies = false,
            historyLimit = 20,
            stickerNames = listOf("开心", "难过"),
            stickerEagerness = StickerEagerness.levelFor(temperature),
        ).first().content

        val cold = systemFor(0.0)
        val eager = systemFor(0.6)
        val hot = systemFor(1.5)

        assertTrue("低温该说几乎不用：$cold", cold.contains("几乎不用"))
        assertTrue("0.6 该说「有符合的就发」：$eager", eager.contains("符合"))
        assertTrue("高温该说非常频繁：$hot", hot.contains("非常频繁"))
        assertTrue("三档文案不该一样", cold != eager && eager != hot)
    }

    @Test
    fun `一个表情都没有时不提频率`() {
        // 没表情可发还叮嘱它多发，它只会写出一条指令却被本地丢掉喵。
        val system = PromptBuilder.turns(
            persona = persona(),
            memories = emptyList(),
            history = listOf(userSticker("开心")),
            suffix = "",
            burstReplies = false,
            historyLimit = 20,
            stickerNames = emptyList(),
            stickerEagerness = StickerEagerness.Level.CONSTANT,
        ).first().content
        assertTrue("不该出现频率说明：$system", !system.contains("发表情包的频率"))
    }
}
