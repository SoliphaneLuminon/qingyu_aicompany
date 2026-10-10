package com.qingyu.app.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地检索索引的回归测试喵。
 *
 * 重点钉住两件容易悄悄坏掉的事喵：
 * 1. 中文**相邻二元组**的切法——一旦退化成单字，检索精度会掉一大截却毫无报错喵；
 * 2. 相似度必须是归一化后的余弦值，落在 0..1，否则唤醒概率会被放大到恒真喵。
 */
class MemoryTextIndexTest {

    @Test
    fun `空文本不产生 token`() {
        assertEquals(emptyList<String>(), MemoryTextIndex.tokenize("   "))
    }

    @Test
    fun `中文切出单字与相邻二元组`() {
        val tokens = MemoryTextIndex.tokenize("喜欢喝美式")
        // 单字 5 个 + 二元组 4 个喵
        assertEquals(9, tokens.size)
        assertTrue(tokens.contains("喜欢"))
        assertTrue(tokens.contains("美式"))
        assertTrue(tokens.contains("欢喝"))
    }

    @Test
    fun `标点不会跨过去造出假二元组`() {
        val tokens = MemoryTextIndex.tokenize("咖啡，拉花")
        assertTrue(tokens.contains("咖啡"))
        assertTrue(tokens.contains("拉花"))
        // 逗号两侧的字不构成二元组喵
        assertTrue(!tokens.contains("啡拉"))
    }

    @Test
    fun `拉丁词整体成 token 且忽略过短片段`() {
        val tokens = MemoryTextIndex.tokenize("I use Qwen 3.5 Max, ok")
        assertTrue(tokens.contains("qwen"))
        assertTrue(tokens.contains("3.5"))
        assertTrue(tokens.contains("use"))
        // 单个字母要丢掉，否则每个英文句子都会互相"相关"喵
        assertTrue(!tokens.contains("i"))
    }

    @Test
    fun `相同的文本相似度为 1`() {
        val document = MemoryTextIndex.document(1L, "用户喜欢喝美式咖啡")
        assertEquals(1.0, MemoryTextIndex.similarity(document.terms, document.terms), 0.0001)
    }

    @Test
    fun `完全无关的文本相似度为 0`() {
        val a = MemoryTextIndex.document(1L, "用户养了一只橘猫")
        val b = MemoryTextIndex.document(2L, "明天下雨记得带伞")
        assertEquals(0.0, MemoryTextIndex.similarity(a.terms, b.terms), 0.0001)
    }

    @Test
    fun `部分重叠的相似度落在 0 和 1 之间`() {
        val a = MemoryTextIndex.document(1L, "用户喜欢喝美式咖啡")
        val b = MemoryTextIndex.document(2L, "用户喜欢喝拿铁")
        val score = MemoryTextIndex.similarity(a.terms, b.terms)
        assertTrue(score > 0.0 && score < 1.0)
    }

    @Test
    fun `权重向量已归一化`() {
        val document = MemoryTextIndex.document(1L, "咖啡咖啡咖啡拿铁")
        val norm = document.terms.values.sumOf { it * it }
        assertEquals(1.0, norm, 0.0001)
    }
}
