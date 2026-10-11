package com.qingyu.app.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 温度 → 表情包积极度喵。
 *
 * 用户点名了 **0.6 必须是"只要库里有符合情境的就发"**，
 * 所以那条边界是本文件的重点，其余档位只保证单调递增喵。
 */
class StickerEagernessTest {

    @Test
    fun `温度 0_6 落在「有符合的就发」这一档`() {
        // 用户原话：温度 0.6 基本就意味着，只要表情包库里有符合情境的，他就会发表情包喵。
        assertEquals(
            "0.6 必须是 EAGER",
            StickerEagerness.Level.EAGER,
            StickerEagerness.levelFor(0.6),
        )
    }

    @Test
    fun `默认温度 0_7 也在同一档`() {
        // 默认值就该是"会主动发表情包"的手感，不然新装的用户会觉得它很木喵。
        assertEquals(
            "默认 0.7 也该是 EAGER",
            StickerEagerness.Level.EAGER,
            StickerEagerness.levelFor(0.7),
        )
    }

    @Test
    fun `温度越高档位越积极`() {
        // 用户的核心要求：温度越高，发表情包的频率越高喵。
        val samples = listOf(0.0, 0.3, 0.5, 0.6, 0.9, 1.5)
        val levels = samples.map { StickerEagerness.levelFor(it) }
        levels.zipWithNext().forEach { (lower, higher) ->
            assertTrue(
                "温度升高时档位不该倒退：$levels",
                higher.ordinal > lower.ordinal,
            )
        }
    }

    @Test
    fun `低温几乎不发`() {
        assertEquals(StickerEagerness.Level.OFF, StickerEagerness.levelFor(0.0))
        assertEquals(StickerEagerness.Level.OFF, StickerEagerness.levelFor(0.19))
        assertEquals(StickerEagerness.Level.RARE, StickerEagerness.levelFor(0.2))
    }

    @Test
    fun `高温非常频繁`() {
        assertEquals(StickerEagerness.Level.FREQUENT, StickerEagerness.levelFor(0.8))
        assertEquals(StickerEagerness.Level.CONSTANT, StickerEagerness.levelFor(1.2))
        assertEquals(StickerEagerness.Level.CONSTANT, StickerEagerness.levelFor(2.0))
    }

    @Test
    fun `超出范围也不会没有档位`() {
        // 设置页滑块是 0..2，但别让越界值掉进"没有档位"的漏洞喵。
        assertEquals("负数按最低档", StickerEagerness.Level.OFF, StickerEagerness.levelFor(-1.0))
        assertEquals("超上限按最高档", StickerEagerness.Level.CONSTANT, StickerEagerness.levelFor(9.9))
    }

    @Test
    fun `每一档都有说明`() {
        StickerEagerness.Level.entries.forEach { level ->
            val text = StickerEagerness.instruction(level)
            assertTrue("$level 的说明不该为空", text.isNotBlank())
        }
    }

    @Test
    fun `EAGER 的说明写明「有符合的就发」`() {
        val text = StickerEagerness.instruction(StickerEagerness.Level.EAGER)
        assertTrue("要点明不用犹豫：$text", text.contains("符合"))
        assertTrue("要给个具体频率，模型才有可对齐的锚：$text", text.contains("2～3"))
    }

    @Test
    fun `各档说明互不相同`() {
        // 文案撞车的话温度就白调了喵。
        val texts = StickerEagerness.Level.entries.map { StickerEagerness.instruction(it) }
        assertEquals("每档说明必须各不相同", texts.size, texts.toSet().size)
    }
}
