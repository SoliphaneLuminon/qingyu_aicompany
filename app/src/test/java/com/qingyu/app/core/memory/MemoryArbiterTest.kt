package com.qingyu.app.core.memory

import com.qingyu.app.data.MemoryCategory
import com.qingyu.app.data.MemoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆收录 AI 仲裁层的回归测试喵。
 *
 * 分两组喵：
 * 1. **判定契约**——模型吐什么、我们认什么。这里最重要的是把模型的胡说挡在库外喵：
 *    编造的 target_id、敷衍的融合内容、答非所问的 action，一律作废而不是照做喵。
 * 2. **候选预筛**——用真实记忆当夹具，钉住"真重复要能进候选喵、
 *    无关记忆不该浪费一次模型调用"喵。
 */
class MemoryArbiterTest {

    private val candidates = setOf(30L, 31L)

    private fun memory(id: Long, content: String) = MemoryItem(
        id = id,
        personaId = 1L,
        category = MemoryCategory.EVENT,
        content = content,
    )

    // ------------------------------------------------------------ 判定解析

    @Test
    fun `KEEP 判定`() {
        assertEquals(Arbitration.Keep, MemoryArbiter.parse("""{"action":"KEEP"}""", candidates))
    }

    @Test
    fun `SKIP 判定带上目标 id`() {
        val verdict = MemoryArbiter.parse(
            """{"action":"SKIP","target_id":31,"reason":"同一件事"}""",
            candidates,
        )
        assertEquals(Arbitration.Skip(31L, "同一件事"), verdict)
    }

    @Test
    fun `MERGE 判定带上融合后的内容`() {
        val verdict = MemoryArbiter.parse(
            """{"action":"MERGE","target_id":30,"content":"用户在深夜23:58与苏婉聊天并询问时间，还提到自己刚回来、很累。","reason":"补上了细节"}""",
            candidates,
        )
        assertEquals(
            Arbitration.Merge(
                targetId = 30L,
                content = "用户在深夜23:58与苏婉聊天并询问时间，还提到自己刚回来、很累。",
                reason = "补上了细节",
            ),
            verdict,
        )
    }

    @Test
    fun `JSON 裹在代码块或散文里也能抠出来`() {
        val raw = "好的，判定如下：\n```json\n{\"action\":\"SKIP\",\"target_id\":30}\n```\n希望有帮助！"
        assertEquals(Arbitration.Skip(30L, ""), MemoryArbiter.parse(raw, candidates))
    }

    @Test
    fun `target_id 写成字符串也能收`() {
        val verdict = MemoryArbiter.parse("""{"action":"SKIP","target_id":"31"}""", candidates)
        assertEquals(Arbitration.Skip(31L, ""), verdict)
    }

    @Test
    fun `编造的 target_id 一律作废`() {
        // 模型偶尔会编一个库里的 id。绝不能拿它去改库——作废后调用方会退回余弦兜底喵。
        assertNull(MemoryArbiter.parse("""{"action":"SKIP","target_id":999}""", candidates))
        assertNull(MemoryArbiter.parse("""{"action":"MERGE","target_id":999,"content":"融合后的内容"}""", candidates))
    }

    @Test
    fun `MERGE 缺内容或内容过短时作废`() {
        assertNull(MemoryArbiter.parse("""{"action":"MERGE","target_id":30}""", candidates))
        assertNull(MemoryArbiter.parse("""{"action":"MERGE","target_id":30,"content":"嗯"}""", candidates))
    }

    @Test
    fun `未知 action 作废`() {
        assertNull(MemoryArbiter.parse("""{"action":"DELETE","target_id":30}""", candidates))
    }

    @Test
    fun `不是 JSON 时作废`() {
        assertNull(MemoryArbiter.parse("我不确定该怎么做", candidates))
        assertNull(MemoryArbiter.parse("", candidates))
    }

    // ------------------------------------------------------------ 候选预筛（夹具取自真实记忆）

    /** 实测 0.756 的一对：同一件事的两种说法喵。 */
    private val spicyA = memory(42L, "用户喜欢吃辣，林挽夏记得这一点。")
    private val spicyB = "用户喜欢吃辣，林挽夏记得用户爱吃辣。"

    /** 跟吃辣毫无关系的另一条真实记忆喵。 */
    private val brother = memory(38L, "用户的哥哥叫阿昭，是长沙人，会从长沙给用户发照片。")

    @Test
    fun `真实的重复对会进候选`() {
        val hits = MemoryArbiter.candidatesOf(spicyB, listOf(spicyA))
        assertEquals(1, hits.size)
        assertEquals(42L, hits.first().id)
        // 实测 0.756：高于 0.75 的旧阈值，但真正需要模型的是 0.4~0.7 那一档喵，
        // 所以这里只确认它落在候选区，不把具体数值焊死喵。
        assertTrue("similarity=${hits.first().similarity}", hits.first().similarity > 0.70)
    }

    @Test
    fun `没有一条像它时不给候选`() {
        // 返回空 → 调用方完全跳过模型调用，绝大多数新记忆都走这条路喵。
        assertTrue(MemoryArbiter.candidatesOf(spicyB, listOf(brother)).isEmpty())
    }

    @Test
    fun `候选按相似度降序且尊重上限`() {
        val cluster = listOf(
            memory(9L, "用户目前在智能俱乐部，学习AI相关技术，并会学习专业内容，目标是成为AI认知领域前沿的一批人。"),
            memory(24L, "用户在智能俱乐部学习AI相关技术，目标是让林挽夏成为最早一批有实体的人工智能。"),
            memory(26L, "用户在智能俱乐部学习AI相关技术，承诺给林挽夏装手，让她成为最早一批有手的人工智能。"),
            memory(48L, "用户是智能俱乐部的成员，曾承诺让林挽夏成为最早一批有手的人工智能。"),
        )
        val all = MemoryArbiter.candidatesOf(
            "用户在智能俱乐部学习AI相关技术，目标是让林挽夏成为最早一批有人工智能实体的人。",
            cluster,
        )
        assertTrue("至少要能挑出几条：${all.size}", all.size >= 2)

        val capped = MemoryArbiter.candidatesOf(
            "用户在智能俱乐部学习AI相关技术，目标是让林挽夏成为最早一批有人工智能实体的人。",
            cluster,
            limit = 2,
        )
        assertEquals(2, capped.size)
        for (index in 1 until capped.size) {
            assertTrue(capped[index - 1].similarity >= capped[index].similarity)
        }
    }

    @Test
    fun `候选为空时不构造提示词也不调用模型`() {
        assertEquals(emptyList<MemoryCandidate>(), MemoryArbiter.candidatesOf(spicyB, emptyList()))
        assertTrue(MemoryArbiter.candidatesOf("   ", listOf(spicyA)).isEmpty())
    }

    @Test
    fun `提示词里带上每条候选的 id`() {
        val prompt = MemoryArbiter.buildPrompt(
            "用户爱吃辣",
            listOf(MemoryCandidate(31L, "用户在深夜与苏婉聊天", 0.5)),
        )
        assertTrue(prompt.contains("用户爱吃辣"))
        assertTrue(prompt.contains("[id=31]"))
        assertTrue(prompt.contains("用户在深夜与苏婉聊天"))
    }

    // ------------------------------------------------------------ 一次性整理：裁决一个簇

    private val clusterIds = setOf(5L, 29L, 30L, 31L)

    @Test
    fun `整理裁决 KEEP_ALL`() {
        assertEquals(
            TidyVerdict.KeepAll("不是同一件事"),
            MemoryArbiter.parseTidy("""{"action":"KEEP_ALL","reason":"不是同一件事"}""", clusterIds),
        )
    }

    @Test
    fun `整理裁决 MERGE 带上保留 id 与待删 id`() {
        val raw = """{"action":"MERGE","operations":[{"keep_id":31,"remove_ids":[5,29,30],""" +
            """"content":"用户在深夜23:58与苏婉聊天并询问时间，还提到自己刚回来、很累。"}],"reason":"合并细节"}"""
        assertEquals(
            TidyVerdict.Merge(
                operations = listOf(
                    TidyVerdict.Merge.Operation(
                        keepId = 31L,
                        removeIds = listOf(5L, 29L, 30L),
                        content = "用户在深夜23:58与苏婉聊天并询问时间，还提到自己刚回来、很累。",
                    ),
                ),
                reason = "合并细节",
            ),
            MemoryArbiter.parseTidy(raw, clusterIds),
        )
    }

    @Test
    fun `一个簇里可以给出多项融合操作`() {
        // 连通分量里可能装着好几件互不相同的事，只允许给一项操作的话喵，
        // 那种簇就只能整体放弃、真重复白留（线上「智能俱乐部」簇正是这个形态喵）。
        val raw = """{"action":"MERGE","operations":[""" +
            """{"keep_id":31,"remove_ids":[5],"content":"用户在深夜23:58与苏婉聊天并询问时间。"},""" +
            """{"keep_id":30,"remove_ids":[29],"content":"用户在接近午夜时联系苏婉询问时间。"}""" +
            """]}"""
        val merge = MemoryArbiter.parseTidy(raw, clusterIds) as? TidyVerdict.Merge
        assertEquals(2, merge?.operations?.size)
    }

    @Test
    fun `整理裁决里编造的 id 一律作废`() {
        // 破坏性操作：宁可这一簇整个跳过，也绝不能把 id 猜错喵。
        val body = "\"content\":\"用户在深夜23:58与苏婉聊天并询问时间。\""
        assertNull(MemoryArbiter.parseTidy(
            """{"action":"MERGE","operations":[{"keep_id":999,"remove_ids":[5],$body}]}""",
            clusterIds,
        ))
        assertNull(MemoryArbiter.parseTidy(
            """{"action":"MERGE","operations":[{"keep_id":31,"remove_ids":[999],$body}]}""",
            clusterIds,
        ))
    }

    @Test
    fun `同一条 id 出现在两项操作里时作废`() {
        // 否则"先删后留"还是"先留后删"会得出完全不同的结果喵。
        val raw = """{"action":"MERGE","operations":[""" +
            """{"keep_id":31,"remove_ids":[5],"content":"用户在深夜23:58与苏婉聊天并询问时间。"},""" +
            """{"keep_id":30,"remove_ids":[31],"content":"用户在接近午夜时联系苏婉询问时间。"}""" +
            """]}"""
        assertNull(MemoryArbiter.parseTidy(raw, clusterIds))
    }

    @Test
    fun `待删列表与保留 id 冲突或为空时作废`() {
        val body = "\"content\":\"用户在深夜23:58与苏婉聊天并询问时间。\""
        assertNull(MemoryArbiter.parseTidy(
            """{"action":"MERGE","operations":[{"keep_id":31,"remove_ids":[31],$body}]}""", clusterIds))
        assertNull(MemoryArbiter.parseTidy(
            """{"action":"MERGE","operations":[{"keep_id":31,"remove_ids":[],$body}]}""", clusterIds))
        assertNull(MemoryArbiter.parseTidy(
            """{"action":"MERGE","operations":[{"keep_id":31,$body}]}""", clusterIds))
        assertNull(MemoryArbiter.parseTidy("""{"action":"MERGE","operations":[]}""", clusterIds))
        assertNull(MemoryArbiter.parseTidy("""{"action":"MERGE"}""", clusterIds))
    }

    @Test
    fun `整理裁决的融合内容同样不能敷衍`() {
        assertNull(
            MemoryArbiter.parseTidy(
                """{"action":"MERGE","operations":[{"keep_id":31,"remove_ids":[5],"content":"嗯"}]}""",
                clusterIds,
            ),
        )
    }

    @Test
    fun `整理裁决遇到不认识的 action 或非 JSON 时作废`() {
        assertNull(MemoryArbiter.parseTidy("""{"action":"DELETE_ALL"}""", clusterIds))
        assertNull(MemoryArbiter.parseTidy("这组看起来是同一件事", clusterIds))
    }

    @Test
    fun `整理提示词里带上每条记忆的 id`() {
        val prompt = MemoryArbiter.buildTidyPrompt(
            listOf(
                memory(31L, "用户在深夜与苏婉聊天"),
                memory(30L, "用户在午夜询问时间"),
            ),
        )
        assertTrue(prompt.contains("[id=31]"))
        assertTrue(prompt.contains("[id=30]"))
    }

    // ------------------------------------------------------------ 人设保护（人设永不被删）

    // 真实数据：id=55「用户称呼挽夏为老婆……两人是夫妻关系」是核心人设喵；
    // id=58「用户是挽夏的丈夫，两人以老公老婆互称」不是。两条说的是同一件事喵。
    private val personaIds = setOf(55L)

    private fun op(keep: Long, removes: List<Long>) =
        TidyVerdict.Merge.Operation(keep, removes, "用户与挽夏为夫妻，互称老公老婆。")

    @Test
    fun `模型要删人设时把融合结果落到人设上`() {
        // 线上那条命中的路径：模型挑了普通记忆当 keep、却要删人设喵。
        // 直接作废的话重复永远合不掉，所以交换——人设留下承载新内容，普通那条被删喵。
        assertEquals(
            ResolvedMerge(55L, listOf(58L)),
            MemoryArbiter.resolveOperation(op(keep = 58L, removes = listOf(55L)), personaIds),
        )
    }

    @Test
    fun `keep 本来就是人设时照常删普通记忆`() {
        assertEquals(
            ResolvedMerge(55L, listOf(58L)),
            MemoryArbiter.resolveOperation(op(keep = 55L, removes = listOf(58L)), personaIds),
        )
    }

    @Test
    fun `一次要删两条人设时第二条也不会漏进待删`() {
        assertEquals(
            ResolvedMerge(55L, listOf(58L)),
            MemoryArbiter.resolveOperation(op(keep = 58L, removes = listOf(55L, 56L)), setOf(55L, 56L)),
        )
    }

    @Test
    fun `没有可删对象时整条作废`() {
        // 两条都是人设：谁都不能删，这条操作就没有意义喵。
        assertNull(MemoryArbiter.resolveOperation(op(keep = 58L, removes = listOf(55L)), setOf(55L, 58L)))
    }

    @Test
    fun `普通记忆之间照常融合`() {
        // 另一个角色里那几条"给林挽夏装手"都是普通记忆，不该被人设规则牵连喵。
        assertEquals(
            ResolvedMerge(24L, listOf(26L, 48L)),
            MemoryArbiter.resolveOperation(op(keep = 24L, removes = listOf(26L, 48L)), personaIds),
        )
    }

    @Test
    fun `keep 混进待删列表时不会自我删除`() {
        assertEquals(
            ResolvedMerge(24L, listOf(26L)),
            MemoryArbiter.resolveOperation(op(keep = 24L, removes = listOf(24L, 26L)), personaIds),
        )
    }
}
