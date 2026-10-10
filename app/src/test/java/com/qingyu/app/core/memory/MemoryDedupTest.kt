package com.qingyu.app.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收录前相似度去重的回归测试喵。
 *
 * 钉住两件方向相反的事喵：
 * 1. 同一句话、以及"原句后面补一个从句"的复述，必须判为重复——这是模型最常见的灌库形态喵；
 * 2. 同主题的不同事实、句式相近的不同事情，不能误判为重复喵。
 *
 * 第 2 条比第 1 条重要：误杀的代价是"该记住的永远没记住"，而且用户无从察觉喵；
 * 多收一条只是记忆库略脏。所以门槛刻意偏保守喵。
 */
class MemoryDedupTest {

    @Test
    fun `完全相同的陈述判为重复`() {
        assertTrue(
            MemoryDedup.isDuplicate("用户喜欢喝美式咖啡", listOf("用户喜欢喝美式咖啡")),
        )
    }

    @Test
    fun `原句后面补一个从句仍判为重复`() {
        // 事实没变，只是补充了名字——这类复述最容易被按"新记忆"收进去喵
        assertTrue(
            MemoryDedup.isDuplicate(
                "用户养了一只橘猫，名字叫元宝",
                listOf("用户养了一只橘猫"),
            ),
        )
    }

    @Test
    fun `同主题但不同的事实不算重复`() {
        assertFalse(
            MemoryDedup.isDuplicate(
                "用户明天要去体检",
                listOf("用户养了一只橘猫，名字叫元宝"),
            ),
        )
    }

    @Test
    fun `句式相近的不同事实不算重复`() {
        // 美式和拿铁是两件事，不能因为句式像就吞掉一条喵
        assertFalse(
            MemoryDedup.isDuplicate(
                "用户喜欢喝美式咖啡",
                listOf("用户喜欢喝拿铁"),
            ),
        )
    }

    @Test
    fun `只有单字重叠的不算重复`() {
        // 「了」这类高频字不该制造相似度喵。
        // 这一条同时守着 MemoryTextIndex 的单字不计分：一旦单字参与打分，这里会变成 true喵。
        assertFalse(
            MemoryDedup.isDuplicate(
                "他今天喝咖啡了吗",
                listOf("用户养了一只橘猫，名字叫元宝"),
            ),
        )
    }

    @Test
    fun `候选与多条已有记忆比对时取最相似的那条`() {
        val existing = listOf("用户养了一只橘猫", "用户喜欢喝拿铁", "用户明天要去体检")
        assertEquals(1.0, MemoryDedup.maxSimilarity("用户喜欢喝拿铁", existing), 0.0001)
    }

    @Test
    fun `没有可比对象时不算重复`() {
        assertFalse(MemoryDedup.isDuplicate("用户养了一只橘猫", emptyList()))
        assertEquals(0.0, MemoryDedup.maxSimilarity("用户养了一只橘猫", emptyList()), 0.0)
    }

    @Test
    fun `空白候选不算重复`() {
        assertFalse(MemoryDedup.isDuplicate("   ", listOf("用户养了一只橘猫")))
    }

    @Test
    fun `阈值设为零即关闭相似度去重`() {
        assertFalse(
            MemoryDedup.isDuplicate(
                "用户喜欢喝美式咖啡",
                listOf("用户喜欢喝美式咖啡"),
                threshold = 0.0,
            ),
        )
    }

    @Test
    fun `相似度落在 0 到 1 之间`() {
        val score = MemoryDedup.maxSimilarity("用户养了一只橘猫", listOf("用户养了一只小狗"))
        assertTrue(score >= 0.0 && score <= 1.0)
    }
}
