package com.qingyu.app.core.memory

import com.qingyu.app.data.MemoryItem

/**
 * 把"可能是同一件事"的记忆聚成组，供一次性整理使用喵。
 *
 * 为什么用连通分量而不是两两结对：同一条事实往往散成 3~4 条喵
 * （「23:58 与苏婉聊天询问时间」那一簇就有 4 条，两两相似度 0.29 ~ 0.75喵）。
 * 结对只能一条一条地删；连通分量让模型一次看完，一次融合成一条喵，
 * 也避免了"A 融进 B、C 又融进 B"这种重复劳动喵。
 *
 * 纯函数，不碰数据库，能被单元测试直接钉住（见 MemoryClusteringTest喵）。
 */
object MemoryClustering {

    /**
     * 聚类的相似度门槛喵。
     *
     * 比 [MemoryArbiter.CANDIDATE_FLOOR]（0.30，只是"给模型看一眼"）高一截喵，
     * 原因是**连锁传染**：A~B、B~C 各自够像，不代表 A 和 C 是同一件事喵
     * （「家乡是长沙」~「想回长沙」~「哥哥在长沙」完全可能串成一坨喵）。
     * 门槛太低会把不同事实连成一大片，而这一层是要删数据的——喵
     * 宁可漏聚几组，也不许错聚喵。
     */
    const val CLUSTER_FLOOR = 0.45

    /** 一个簇最多几条。超出的按 id 截断，避免一次丢给模型几十条喵。 */
    const val MAX_CLUSTER_SIZE = 6

    /**
     * 分批的粒度喵。
     *
     * 直接决定成本（N 条记忆 ≈ ceil(N/BATCH_SIZE) 次模型调用）、判定难度喵，
     * 以及**单次调用要等多久**。实测（同一台手机的配置喵）：
     * 19 条一批要 28 秒、10 条 4.8 秒、5 条 2.7 秒——批越大越慢喵，
     * 而模型调用的超时是有上限的，所以批不能开大喵。
     *
     * 12 条是"单次调用够快（几秒级）"与"调用次数不至于太多"之间的折中喵；
     * 万一还是判不出来，`MemoryTidyService` 会把那一批劈半重试喵。
     */
    const val BATCH_SIZE = 12

    /**
     * 分批时"必须同批"的门槛喵。
     *
     * 低门槛在这里**不会**导致误合——最终判定由模型做，分组只负责保证喵
     * "词面上很像的两条别被拆到两批里去"。所以它比 [CLUSTER_FLOOR] 低得多喵。
     */
    const val BATCH_LINK_FLOOR = 0.30

    /**
     * 返回全部 size >= 2 的簇喵。
     *
     * 每个簇内按 id 升序、簇之间按簇内最小 id 升序，结果稳定可复现喵
     * （同一个库跑两次得到完全一样的分组，便于排查与测试喵）。
     */
    fun clustersOf(
        memories: List<MemoryItem>,
        floor: Double = CLUSTER_FLOOR,
        maxSize: Int = MAX_CLUSTER_SIZE,
    ): List<List<MemoryItem>> {
        if (memories.size < 2) return emptyList()

        val parent = IntArray(memories.size) { it }

        fun find(x: Int): Int {
            var root = x
            while (parent[root] != root) root = parent[root]
            var cur = x
            while (parent[cur] != cur) {
                val next = parent[cur]
                parent[cur] = root
                cur = next
            }
            return root
        }

        // 让根永远取分量里的最小下标，分组成果才与遍历顺序无关喵。
        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra == rb) return
            if (ra < rb) parent[rb] = ra else parent[ra] = rb
        }

        val terms = memories.map { MemoryTextIndex.document(it.id, it.content).terms }
        for (i in memories.indices) {
            if (terms[i].isEmpty()) continue
            for (j in i + 1 until memories.size) {
                if (terms[j].isEmpty()) continue
                if (MemoryTextIndex.similarity(terms[i], terms[j]) >= floor) union(i, j)
            }
        }

        return memories.indices
            .groupBy { find(it) }
            .values
            .map { group -> group.map { memories[it] }.sortedBy { memory -> memory.id } }
            .filter { it.size >= 2 }
            .map { it.take(maxSize.coerceAtLeast(1)) }
            .sortedBy { it.first().id }
    }

    /**
     * 把记忆切成若干批，交给模型逐批找重复喵。
     *
     * 为什么不直接把 [clustersOf] 的结果拿去问模型：**词法聚类认不出"换一套说法"的同一件事**喵。
     * 实测那两条夫妻记忆——喵
     * 「用户是挽夏的丈夫，两人以老公老婆互称喵」
     * 「用户称呼挽夏为老婆，挽夏称呼用户为老公，两人是夫妻关系喵」
     * ——余弦只有 0.327，被 0.45 的聚类门槛挡在外面，于是整理时回一句"没有重复"喵，
     * 而模型**连看都没看到这两条**喵。
     *
     * 词法能做的只有一件事：把词面上很像的记忆拴在一起别拆散。判定必须交给模型喵。
     *
     * @return 每一批都不超过 [batchSize] 条；[linkFloor] 连成的组一定整组留在同一批里喵。
     */
    fun batchesOf(
        memories: List<MemoryItem>,
        batchSize: Int = BATCH_SIZE,
        linkFloor: Double = BATCH_LINK_FLOOR,
    ): List<List<MemoryItem>> {
        if (memories.isEmpty()) return emptyList()
        val size = batchSize.coerceAtLeast(1)

        // 组内不得拆分，所以上限直接取一批的容量；组的规模本来也不会超过它喵。
        val groups = clustersOf(memories, floor = linkFloor, maxSize = size)
        val groupedIds = HashSet<Long>()
        groups.forEach { group -> group.forEach { item -> groupedIds.add(item.id) } }

        val units = ArrayList<List<MemoryItem>>(memories.size)
        groups.forEach { units.add(it) }
        memories.filterNot { it.id in groupedIds }.sortedBy { it.id }.forEach { units.add(listOf(it)) }

        val batches = ArrayList<List<MemoryItem>>()
        var current = ArrayList<MemoryItem>()
        units.forEach { unit ->
            if (current.isNotEmpty() && current.size + unit.size > size) {
                batches.add(current)
                current = ArrayList()
            }
            current.addAll(unit)
        }
        if (current.isNotEmpty()) batches.add(current)
        return batches
    }
}
