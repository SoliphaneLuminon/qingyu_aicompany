package com.qingyu.app.core.memory

import com.qingyu.app.data.MemoryCategory
import com.qingyu.app.data.MemoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 疑似重复聚类的回归测试喵。
 *
 * 夹具全部取自真实记忆库——这是刻意的：阈值该定多少、会不会串味喵，
 * 只有拿真实数据量过才算数，编几个句子自娱自乐没意义喵。
 */
class MemoryClusteringTest {

    private fun memory(id: Long, content: String) = MemoryItem(
        id = id,
        personaId = 1L,
        category = MemoryCategory.EVENT,
        content = content,
    )

    /** 真实的一簇：同一件事被记了 4 遍，两两相似度 0.29 ~ 0.75喵。 */
    private val clockCluster = listOf(
        memory(5L, "用户在某天深夜23:58左右主动与苏婉聊天，询问时间。"),
        memory(29L, "用户在23:58左右主动联系苏婉，询问时间并告知自己刚回来，表示很累。"),
        memory(30L, "用户在接近午夜（23:58）时与苏婉聊天，并询问对方能否看到时间。"),
        memory(31L, "用户在深夜23:58左右与苏婉聊天，并询问时间。"),
    )

    /** 只是都提到"长沙"，事实各不相同——绝不能被串成一簇喵。 */
    private val changshaTrap = listOf(
        memory(19L, "用户目前生活在福州，表示四周都是山，与长沙不同，有点想回长沙。"),
        memory(36L, "用户在福州，家乡是长沙"),
        memory(38L, "用户的哥哥叫阿昭，是长沙人，会从长沙给用户发照片。"),
    )

    private val spicyPair = listOf(
        memory(42L, "用户喜欢吃辣，林挽夏记得这一点。"),
        memory(44L, "用户喜欢吃辣，林挽夏记得用户爱吃辣。"),
    )

    @Test
    fun `同一件事散成的多条会被聚成一个簇`() {
        val clusters = MemoryClustering.clustersOf(clockCluster)
        assertEquals(1, clusters.size)
        assertEquals(listOf(5L, 29L, 30L, 31L), clusters.first().map { it.id })
    }

    @Test
    fun `换了个说法的重复对也会成簇`() {
        val clusters = MemoryClustering.clustersOf(spicyPair)
        assertEquals(1, clusters.size)
        assertEquals(listOf(42L, 44L), clusters.first().map { it.id })
    }

    @Test
    fun `只是共享词汇的不同事实不会被串在一起`() {
        // 这三条两两相似度都低于 0.45，所以一条都不该聚起来喵。
        // 这正是把门槛从 0.30 抬到 0.45 要防的事：串起来就会被当成同一件事融合掉喵。
        assertTrue(MemoryClustering.clustersOf(changshaTrap).isEmpty())
    }

    @Test
    fun `门槛调低就会把弱关联也串进来`() {
        // 反过来钉住"为什么不能偷懒用候选门槛 0.30"喵：
        // 「家乡是长沙」~「想回长沙」~「哥哥在长沙」会被连成一坨，然后被融成一条假事实喵。
        val loose = MemoryClustering.clustersOf(changshaTrap, floor = 0.30)
        assertEquals(1, loose.size)
        assertEquals(listOf(19L, 36L, 38L), loose.first().map { it.id })
    }

    @Test
    fun `不相干的记忆不产生簇`() {
        assertTrue(MemoryClustering.clustersOf(listOf(clockCluster[0], spicyPair[0])).isEmpty())
    }

    @Test
    fun `少于两条时没有簇`() {
        assertTrue(MemoryClustering.clustersOf(listOf(clockCluster[0])).isEmpty())
        assertTrue(MemoryClustering.clustersOf(emptyList()).isEmpty())
    }

    @Test
    fun `簇内按 id 升序且簇间按最小 id 升序`() {
        val shuffled = clockCluster.reversed() + spicyPair.reversed()
        val clusters = MemoryClustering.clustersOf(shuffled)
        assertEquals(2, clusters.size)
        // 输入顺序被打乱，输出依然稳定：先 {5,...} 再 {42,44喵}
        assertEquals(listOf(5L, 29L, 30L, 31L), clusters[0].map { it.id })
        assertEquals(listOf(42L, 44L), clusters[1].map { it.id })
    }

    @Test
    fun `单簇大小有上限`() {
        // 造一个大簇：全部由"用户在深夜23:58与苏婉聊天询问时间"变体组成喵
        val big = (1..9).map { index ->
            memory(index.toLong(), "用户在某天深夜23:58左右主动与苏婉聊天，询问时间（第${index}次记录）。")
        }
        val clusters = MemoryClustering.clustersOf(big)
        assertEquals(1, clusters.size)
        assertTrue(
            "单簇应被截断到 ${MemoryClustering.MAX_CLUSTER_SIZE}：${clusters.first().size}",
            clusters.first().size <= MemoryClustering.MAX_CLUSTER_SIZE,
        )
    }

    // ------------------------------------------------------------ 分批（一次性整理用）

    private val husband = memory(101L, "用户是挽夏的丈夫，两人以老公老婆互称")
    private val wife = memory(102L, "用户称呼挽夏为老婆，挽夏称呼用户为老公，两人是夫妻关系")
    private val others = listOf(
        memory(103L, "用户在智能俱乐部学习AI相关技术，目标是成为AI认知领域前沿的一批人。"),
        memory(104L, "用户喜欢吃辣，林挽夏记得这一点。"),
    )

    @Test
    fun `换一套说法的同一件事聚不成簇但会被拴进同一批`() {
        // 整理时回一句"没有发现同一件事"，而这两条明明是一件事喵。
        // 根因就是余弦只有 0.327，够不上聚类门槛 0.45——词法聚类根本看不见它们喵，
        // 于是模型连看都没看到。这个用例把那条分界线钉死喵。
        assertTrue(
            "改成能成簇说明聚类门槛被动过了：${MemoryClustering.clustersOf(listOf(husband, wife))}",
            MemoryClustering.clustersOf(listOf(husband, wife)).isEmpty(),
        )

        // 分批走的是低门槛（BATCH_LINK_FLOOR），保证这两条一定同批 => 模型一定看得到喵。
        val batches = MemoryClustering.batchesOf(listOf(husband, wife) + others, batchSize = 2)
        assertTrue(
            "夫妻两条被拆到不同批了：${batches.map { batch -> batch.map { it.id } }}",
            batches.any { batch -> batch.map { it.id }.containsAll(listOf(husband.id, wife.id)) },
        )
    }

    @Test
    fun `每批不超过上限且不丢条目`() {
        val many = (1..17).map { index ->
            memory(index.toLong(), "用户在智能俱乐部学习AI相关技术（第${index}次记录）。")
        }
        val batches = MemoryClustering.batchesOf(many, batchSize = 5)
        assertTrue(batches.isNotEmpty())
        batches.forEach { batch -> assertTrue("批过大：${batch.size}", batch.size <= 5) }
        // 分批只负责划分，绝不能丢条目喵
        assertEquals(17, batches.sumOf { it.size })
        assertEquals(many.map { it.id }.toSet(), batches.flatten().map { it.id }.toSet())
    }

    @Test
    fun `不相干的记忆按 id 顺序分摊且结果稳定`() {
        val items = listOf(
            memory(31L, "用户在深夜23:58左右与苏婉聊天，并询问时间。"),
            memory(7L, "用户的哥哥叫阿昭，是长沙人。"),
            memory(19L, "用户目前生活在福州，表示四周都是山。"),
        )
        val first = MemoryClustering.batchesOf(items, batchSize = 2)
        val second = MemoryClustering.batchesOf(items.reversed(), batchSize = 2)
        // 输入顺序换一下，切出来的批次必须完全一样喵
        assertEquals(
            first.map { batch -> batch.map { it.id } },
            second.map { batch -> batch.map { it.id } },
        )
        // 没有词法关联时按 id 升序排喵
        assertEquals(listOf(7L, 19L, 31L), first.flatten().map { it.id })
        assertTrue(first.all { it.size <= 2 })
    }

    @Test
    fun `空库不产生批次`() {
        assertTrue(MemoryClustering.batchesOf(emptyList()).isEmpty())
    }
}
