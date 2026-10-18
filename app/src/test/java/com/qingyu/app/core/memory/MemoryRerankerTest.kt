package com.qingyu.app.core.memory

import com.qingyu.app.data.MemoryCategory
import com.qingyu.app.data.MemoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆精排的纯逻辑喵。
 *
 * 用户报的是「记忆注入太严苛，每次几乎没有记忆可以注入」喵。
 * 这个功能的目的是**让记忆更容易被想起来**，所以下面一半测试钉的是
 * 「该救回来的救回来了」，另一半钉「不该翻案的没翻案」喵。
 */
class MemoryRerankerTest {

    private fun item(id: Long, text: String, permanent: Boolean = false) = MemoryItem(
        id = id,
        personaId = 1L,
        category = MemoryCategory.PREFERENCE,
        content = text,
        importance = 5,
        enabled = true,
        emotionalWeight = 0.0,
        vividness = 1.0,
        lastRecalledTurn = MemoryDecay.NEVER_RECALLED_TURN,
        isPermanent = permanent,
    )

    private fun decision(
        id: Long,
        text: String,
        recalled: Boolean,
        reason: RecallSkipReason?,
        permanent: Boolean = false,
        roll: Double = 0.0,
        similarity: Double = 0.5,
    ) = RecallDecision(
        memory = item(id, text, permanent),
        similarity = similarity,
        probability = 0.2,
        roll = roll,
        recalled = recalled,
        reason = reason,
    )

    // ---------------------------------------------------------- 解析

    @Test
    fun `解析 JSON 数组形态的编号`() {
        assertEquals(setOf(0, 2, 4), MemoryReranker.parseKeptIds("[1,3,5]"))
    }

    @Test
    fun `解析裸列表形态的编号`() {
        // 各家模型对 response_format 的支持参差不齐，只认一种会经常空手而归喵。
        assertEquals(setOf(0, 1), MemoryReranker.parseKeptIds("1, 2"))
        assertEquals(setOf(0), MemoryReranker.parseKeptIds("相关的有：1"))
    }

    @Test
    fun `完全没有数字时返回 null`() {
        // null 与空集合含义不同喵：null 表示解析失败（要退回统计结果），
        // 空集合表示模型明确说一条都不相关喵。
        assertNull(MemoryReranker.parseKeptIds("没有相关的"))
    }

    @Test
    fun `零不会变成合法编号`() {
        // 编号是 1 基的，0 减一之后是 -1，必须被丢掉喵。
        assertEquals(setOf(0), MemoryReranker.parseKeptIds("[0,1]"))
    }

    // ---------------------------------------------------------- 判定套用

    @Test
    fun `模型说相关的能越过骰子被唤醒`() {
        // **这是整个功能的意义所在**喵：统计概率没中只是运气问题，
        // 语义确实相关才是该不该想起的真正依据喵。
        val decisions = listOf(
            decision(1, "他喜欢喝美式", recalled = false, reason = RecallSkipReason.LOST_ROLL, roll = 0.9),
        )
        val out = MemoryReranker.applyJudgement(decisions, relevantIds = setOf(1L), limit = 6)
        assertTrue("相关的那条该被救回来", out[0].recalled)
        assertNull("救回来之后不该再标着概率未中", out[0].reason)
    }

    @Test
    fun `模型说无关的不再注入`() {
        val decisions = listOf(
            decision(1, "他喜欢喝美式", recalled = true, reason = null),
        )
        val out = MemoryReranker.applyJudgement(decisions, relevantIds = emptySet(), limit = 6)
        assertFalse("模型判无关就不该注入", out[0].recalled)
    }

    @Test
    fun `已淡忘的记忆不能被模型翻案`() {
        // 硬性屏蔽的意义是这条已经记不清了，若模型一句话就能推翻，
        // 淡忘机制就形同虚设喵。
        val decisions = listOf(
            decision(1, "很久以前的事", recalled = false, reason = RecallSkipReason.FADING),
        )
        val out = MemoryReranker.applyJudgement(decisions, relevantIds = setOf(1L), limit = 6)
        assertFalse("已淡忘的不该被救回来", out[0].recalled)
    }

    @Test
    fun `冷却中的记忆不能被模型翻案`() {
        // 冷却的作用是刚提过的别反复念叨喵。若模型能推翻它，
        // 同一条记忆会每轮都被捞回来——那是另一种坏掉喵。
        val decisions = listOf(
            decision(1, "刚说过的事", recalled = false, reason = RecallSkipReason.COOLING_DOWN),
        )
        val out = MemoryReranker.applyJudgement(decisions, relevantIds = setOf(1L), limit = 6)
        assertFalse("冷却中的不该被救回来", out[0].recalled)
    }

    @Test
    fun `永久人设免一切判定`() {
        // 人设必须永远在最前，模型说无关也不算数喵。
        val decisions = listOf(
            decision(1, "你是小语", recalled = true, reason = null, permanent = true),
        )
        val out = MemoryReranker.applyJudgement(decisions, relevantIds = emptySet(), limit = 6)
        assertTrue("人设不该被模型剔除", out[0].recalled)
    }

    @Test
    fun `救回来的条数不会超过注入额度`() {
        val decisions = (1..5).map {
            decision(it.toLong(), "记忆$it", recalled = false, reason = RecallSkipReason.LOST_ROLL)
        }
        val out = MemoryReranker.applyJudgement(decisions, relevantIds = setOf(1L, 2L, 3L, 4L, 5L), limit = 2)
        assertEquals("最多只能注入 2 条", 2, out.count { it.recalled })
    }

    @Test
    fun `没被模型提到的候选保持原状`() {
        // 已唤醒的、模型没提的，不该被误伤喵。
        val decisions = listOf(
            decision(1, "已经醒了的", recalled = true, reason = null),
        )
        val out = MemoryReranker.applyJudgement(decisions, relevantIds = setOf(1L), limit = 6)
        assertTrue(out[0].recalled)
    }

    // ---------------------------------------------------------- 提示词

    @Test
    fun `候选清单带上编号与正文`() {
        val decisions = listOf(
            decision(1, "他喜欢喝美式", recalled = false, reason = null),
            decision(2, "他养了一只猫", recalled = false, reason = null),
        )
        val text = MemoryReranker.buildUserContent("今天好累", decisions)
        assertTrue("要带上用户说的话：$text", text.contains("今天好累"))
        assertTrue("要带编号：$text", text.contains("1.") && text.contains("2."))
        assertTrue("要带正文：$text", text.contains("他喜欢喝美式") && text.contains("他养了一只猫"))
    }

    @Test
    fun `候选清单不会泄露内部统计数字`() {
        // 把重要度或鲜活度告诉模型会诱导它按数字排序，
        // 那就退化成了统计排序本来的样子，白花一次请求喵。
        val decisions = listOf(decision(1, "他喜欢喝美式", recalled = false, reason = null))
        val text = MemoryReranker.buildUserContent("今天好累", decisions)
        assertFalse("不该出现相似度：$text", text.contains("0.5"))
        assertFalse("不该出现概率：$text", text.contains("0.2"))
    }

    @Test
    fun `候选过多时只送前若干条`() {
        // 排在很后面的本来也进不了注入额度，送过去只是白烧 token 与延迟喵。
        val decisions = (1..30).map { decision(it.toLong(), "记忆$it", false, null) }
        val text = MemoryReranker.buildUserContent("查询", decisions)
        assertTrue(
            "该包含第 ${MemoryReranker.MAX_CANDIDATES} 条",
            text.contains("记忆${MemoryReranker.MAX_CANDIDATES}"),
        )
        assertFalse(
            "不该包含第 ${MemoryReranker.MAX_CANDIDATES + 1} 条",
            text.contains("记忆${MemoryReranker.MAX_CANDIDATES + 1}."),
        )
    }

    // ---------------------------------------------------------- 越过词面门槛

    @Test
    fun `模型判相关可以越过词面门槛`() {
        // 用户报的另一半症状是「要么不注入」喵：他说「今天好累」，
        // 而记忆是「他最近总加班」——词面零重叠，相似度门槛把它挡掉了喵。
        // 但语义上这正该被想起来。这是统计检索做不到、必须靠模型补的一段喵。
        val gated = decision(
            id = 1L,
            text = "他最近总加班",
            recalled = false,
            reason = RecallSkipReason.IRRELEVANT,
        )
        val out = MemoryReranker.applyJudgement(listOf(gated), relevantIds = setOf(1L), limit = 6)
        assertTrue("被词面门槛挡掉、但语义相关的该被救回来", out[0].recalled)
        assertNull("救回来之后不该还标着不相关", out[0].reason)
    }

    @Test
    fun `越过门槛的也算被救回`() {
        // 「救回几条」这个数字在自检页上是判断精排有没有在工作的唯一依据喵。
        // 之前它靠 `roll == 0.0` 统计，把「越过骰子」那类全漏掉了，
        // 显示出来是个偏小的数，还会让人以为精排没在工作喵。
        val gated = decision(1L, "他最近总加班", false, RecallSkipReason.IRRELEVANT)
        val lostRoll = decision(2L, "他喜欢喝美式", false, RecallSkipReason.LOST_ROLL, roll = 0.77)
        val rescued = MemoryReranker.applyJudgement(
            decisions = listOf(gated, lostRoll),
            relevantIds = setOf(1L, 2L),
            limit = 6,
        )
        val before = setOf<Long>()
        val count = rescued.count { it.recalled && it.memory.id !in before }
        assertEquals("两条都该算被救回", 2, count)
    }

    @Test
    fun `送进模型的清单按相似度重排`() {
        // 传进来的顺序是综合得分，而综合分里重要度(0.25)加鲜活度(0.20)
        // 比相似度(0.30)还重——不重排的话，12 个名额会被「分数高但无关」的占满，
        // 真正相关的那条压根没被送给模型看，精排于是永远救不回东西喵。
        val captured = java.util.concurrent.atomic.AtomicReference<String>("")
        val fake = UtilityLlm { request ->
            captured.set(request.userContent)
            "[1]"
        }
        val highScoreButIrrelevant = decision(
            id = 1L, text = "用户喜欢喝美式咖啡",
            recalled = false, reason = RecallSkipReason.IRRELEVANT, similarity = 0.0,
        )
        val mostRelevant = decision(
            id = 2L, text = "他最近总加班",
            recalled = false, reason = RecallSkipReason.IRRELEVANT, similarity = 0.9,
        )
        kotlinx.coroutines.runBlocking {
            MemoryReranker.rerank(
                decisions = listOf(highScoreButIrrelevant, mostRelevant),
                query = "今天好累",
                recalledCount = 0,
                limit = 6,
                config = UtilityLlmConfig(enabled = true),
                llm = fake,
                mainEndpoint = "https://example.com/v1/chat/completions",
                mainModel = "m",
                mainApiKey = "k",
            )
        }
        val content = captured.get()
        val relevantAt = content.indexOf("他最近总加班")
        val irrelevantAt = content.indexOf("用户喜欢喝美式咖啡")
        assertTrue("两条都该被送到模型面前：$content", relevantAt >= 0 && irrelevantAt >= 0)
        assertTrue(
            "相似度高的那条该排在前面（先出现），实际内容：$content",
            relevantAt < irrelevantAt,
        )
    }
}
