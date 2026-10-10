package com.qingyu.app.core.memory

import com.qingyu.app.data.MemoryCategory
import com.qingyu.app.data.MemoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 检索与唤醒总控的回归测试喵。
 *
 * 这里钉的是"人设优先"这条铁律，以及三条屏蔽/放行规则的可解释性喵：
 * 每条没被想起来的记忆都必须带得上理由，否则调试页上就只能看到一片空白喵。
 */
class MemoryRecallTest {

    private fun memory(
        id: Long,
        content: String = "用户喜欢喝咖啡",
        importance: Int = 3,
        emotionalWeight: Double = 0.5,
        vividness: Double = 1.0,
        recallCount: Int = 0,
        lastRecalledTurn: Int = MemoryDecay.NEVER_RECALLED_TURN,
        isUserEdited: Boolean = false,
        isPermanent: Boolean = false,
        enabled: Boolean = true,
    ): MemoryItem = MemoryItem(
        id = id,
        personaId = 1L,
        category = MemoryCategory.PREFERENCE,
        content = content,
        importance = importance,
        enabled = enabled,
        emotionalWeight = emotionalWeight,
        vividness = vividness,
        recallCount = recallCount,
        lastRecalledTurn = lastRecalledTurn,
        isUserEdited = isUserEdited,
        isPermanent = isPermanent,
    )

    @Test
    fun `人设节点一定被唤醒且排在最前`() {
        val persona = memory(id = 7L, content = "伴侣说话温柔", isPermanent = true)
        val ordinary = listOf(
            memory(id = 1L, content = "用户喜欢喝咖啡"),
            memory(id = 2L, content = "用户喜欢跑步"),
        )
        val decisions = MemoryRecall.evaluate(
            memories = listOf(ordinary.first(), ordinary.last(), persona),
            query = "她平时什么样",
            currentTurn = 3,
        )
        assertEquals(1.0, decisions.first().probability, 0.0)
        assertTrue(decisions.first().recalled)
        assertTrue(decisions.first().memory.isPermanent)
    }

    @Test
    fun `人设节点不受冷却影响`() {
        val persona = memory(id = 1L, isPermanent = true, lastRecalledTurn = 99)
        val decisions = MemoryRecall.evaluate(
            memories = listOf(persona),
            query = "你是谁",
            currentTurn = 100,
        )
        assertEquals(1, decisions.size)
        assertTrue(decisions.single().recalled)
    }

    @Test
    fun `普通记忆在冷却期内被屏蔽并给出理由`() {
        val item = memory(id = 1L, lastRecalledTurn = 90)
        val decisions = MemoryRecall.evaluate(
            memories = listOf(item),
            query = "咖啡",
            currentTurn = 95,
        )
        assertEquals(1, decisions.size)
        val decision = decisions.single()
        assertFalse(decision.recalled)
        assertEquals(RecallSkipReason.COOLING_DOWN, decision.reason)
    }

    @Test
    fun `鲜活度低于阈值的记忆被淡忘`() {
        val item = memory(id = 1L, vividness = 0.1)
        val decisions = MemoryRecall.evaluate(
            memories = listOf(item),
            query = "咖啡",
            currentTurn = 40,
        )
        val decision = decisions.single()
        assertFalse(decision.recalled)
        assertEquals(RecallSkipReason.FADING, decision.reason)
    }

    @Test
    fun `关闭遗忘曲线后淡忘的记忆也能想起`() {
        val item = memory(id = 1L, vividness = 0.05)
        val decisions = MemoryRecall.evaluate(
            memories = listOf(item),
            query = "咖啡",
            currentTurn = 40,
            // 关掉概率只是为了让断言确定——这一条要验证的是"淡忘不再屏蔽"喵
            config = RecallConfig(decayEnabled = false, probabilityEnabled = false),
        )
        assertTrue(decisions.single().recalled)
    }

    @Test
    fun `关闭概率唤醒后相关记忆全部注入`() {
        val items = List(20) { index -> memory(id = index.toLong() + 1L) }
        val decisions = MemoryRecall.evaluate(
            memories = items,
            query = "咖啡",
            currentTurn = 40,
            config = RecallConfig(limit = 20, probabilityEnabled = false),
        )
        assertEquals(20, decisions.size)
        assertTrue(decisions.all { it.recalled })
    }

    @Test
    fun `注入条数不超过上限`() {
        val items = List(50) { index -> memory(id = index.toLong() + 1L) }
        val decisions = MemoryRecall.evaluate(
            memories = items,
            query = "咖啡",
            currentTurn = 40,
            config = RecallConfig(limit = 6),
        )
        // limit 约束的是"最终注入几条"，不是"留档几条"喵：
        // decisions 会把本轮考察过的候选都记下来，调试页要靠它回答"为什么没想起来"喵。
        assertTrue(MemoryRecall.recalled(decisions).size <= 6)
        assertTrue(decisions.size <= items.size)
    }

    @Test
    fun `同样的输入得到同样的结果`() {
        val items = List(30) { index -> memory(id = index.toLong() + 1L) }
        val first = MemoryRecall.evaluate(items, "咖啡", 40).map { it.memory.id to it.recalled }
        val second = MemoryRecall.evaluate(items, "咖啡", 40).map { it.memory.id to it.recalled }
        assertEquals(first, second)
    }

    @Test
    fun `概率唤醒有起有落而不是全有或全无`() {
        // 30 条一模一样权重的记忆，唤醒概率 0.4，应当有中有不中喵
        val items = List(30) { index -> memory(id = index.toLong() + 1L) }
        val recalled = MemoryRecall.recalled(
            MemoryRecall.evaluate(items, "咖啡", 41, RecallConfig(limit = 30)),
        )
        assertTrue("不应全部唤醒", recalled.size < 30)
        assertTrue("也不应一条都唤不醒", recalled.isNotEmpty())
    }

    @Test
    fun `停用的记忆不参与`() {
        val item = memory(id = 1L, enabled = false)
        assertEquals(0, MemoryRecall.evaluate(listOf(item), "咖啡", 40).size)
    }

    @Test
    fun `冷却期结束后可以再次想起`() {
        val item = memory(id = 1L, lastRecalledTurn = 10, recallCount = 1)
        val decisions = MemoryRecall.evaluate(
            memories = listOf(item),
            query = "咖啡",
            currentTurn = 40,
            config = RecallConfig(probabilityEnabled = false),
        )
        assertTrue(decisions.single().recalled)
    }
}
