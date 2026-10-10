package com.qingyu.app.core.memory

import com.qingyu.app.data.MemoryItem

/** 一条记忆为什么没被想起来。调试页与测试都看得懂喵。 */
enum class RecallSkipReason(val label: String) {
    /** 已淡忘：鲜活度低于阈值喵。 */
    FADING("已淡忘"),

    /** 冷却中：近 20 轮内刚被唤醒过喵。 */
    COOLING_DOWN("冷却中"),

    /** 概率没中。正常情况，不是错误喵。 */
    LOST_ROLL("概率未中"),
}

/** 本轮对一条记忆的判定结果。不管醒没醒都留档，方便排查"为什么没想起来"喵。 */
data class RecallDecision(
    val memory: MemoryItem,
    /** 与当前查询的相似度，0..1喵。 */
    val similarity: Double,
    /** 本轮唤醒概率，0..1喵。 */
    val probability: Double,
    /** 掷出的骰子，0..1；因硬性规则出局时为 0喵。 */
    val roll: Double,
    /** 最终是否被唤醒喵。 */
    val recalled: Boolean,
    /** 没被唤醒的原因喵。 */
    val reason: RecallSkipReason?,
)

/**
 * 检索与唤醒的运行时参数喵。
 *
 * 全部可关：任何一个开关关掉都得到一个确定且可解释的行为喵，
 * 而不是"看起来好像不灵了"喵。
 */
data class RecallConfig(
    /** 一次最多注入几条喵。 */
    val limit: Int = 6,
    /** 硬性冷却轮数喵。 */
    val cooldownTurns: Int = MemoryDecay.COOLDOWN_TURNS,
    /** 鲜活度阈值喵。 */
    val vividnessFloor: Double = MemoryDecay.VIVIDNESS_FLOOR,
    /** 关闭后鲜活度不再衰减（"永不遗忘"模式喵）。 */
    val decayEnabled: Boolean = true,
    /** 关闭后只要相关就注入，不再掷骰子（"什么都能想起来"模式喵）。 */
    val probabilityEnabled: Boolean = true,
    /** 最低重要度门槛：低于此值的记忆不进入候选池。0 = 不过滤喵。 */
    val minImportance: Int = 0,
    /** 是否启用小模型 Reranker 对唤醒候选做交叉比对喵。 */
    val rerankerEnabled: Boolean = false,
)

/**
 * 记忆检索的三段管道喵：
 *
 * **1. 粗筛**（[MemoryTextIndex.rank] / 未来可换为 Embedding 向量检索喵）
 *   关键词倒排索引 + 标量过滤 → 候选面从全集缩到 Top-N喵。
 *
 * **2. 精筛**（本文件 `evaluate`喵）
 *   多因子综合得分排序（相似度 × 重要度 × 鲜活度 × 情绪 × 间隔喵），
 *   再经淡忘/冷却硬性屏蔽与概率唤醒，选出最终注入的 K 条喵。
 *
 * **3. 注入**（上层 `MemoryDirector.prepare`喵）
 *   人设永远在最前；精选记忆按得分降序；剪枝残影走独立配额喵。
 *
 * Reranker 是可选的第四插片：开启后把精筛的 Top-M 候选交给小模型做喵
 * 交叉比对，用模型的判断替代纯统计排序。关闭时退回到多因子打分喵。
 */
object MemoryRecall {

    // -- 公开入口 ----------------------------------------------------------------喵

    /** 直接用正文建索引——适合记忆量小或还没建过索引的老版本喵。 */
    fun evaluate(
        memories: List<MemoryItem>,
        query: String,
        currentTurn: Int,
        config: RecallConfig = RecallConfig(),
    ): List<RecallDecision> {
        val enabled = memories.filter { it.enabled }
        if (enabled.isEmpty()) return emptyList()
        val documents = enabled.associate { item ->
            item.id to MemoryTextIndex.document(item.id, item.searchText).terms
        }
        return evaluate(enabled, documents, query, currentTurn, config)
    }

    /** 用已持久化的索引来判定——线上走这条喵。 */
    fun evaluate(
        memories: List<MemoryItem>,
        documents: Map<Long, Map<String, Double>>,
        query: String,
        currentTurn: Int,
        config: RecallConfig = RecallConfig(),
        reranker: ((List<RecallDecision>) -> List<RecallDecision>)? = null,
    ): List<RecallDecision> {
        val enabled = memories.filter { it.enabled }
        if (enabled.isEmpty()) return emptyList()

        val limit = config.limit.coerceAtLeast(1)

        // 1. 永久人设：免一切判定，按重要度排在最前喵。
        val permanent = enabled
            .filter { it.isPermanent }
            .sortedWith(compareByDescending<MemoryItem> { it.importance }.thenBy { it.id })
            .take(limit)
            .map { item -> permanentDecision(item) }

        val budget = (limit - permanent.size).coerceAtLeast(0)
        if (budget == 0) return permanent

        val ordinary = enabled.filterNot { it.isPermanent }
        if (ordinary.isEmpty()) return permanent

        // 2. 查询无实质内容时不召回普通记忆：空输入不该触发"随机想起"喵。
        val queryWeights = MemoryTextIndex.weigh(MemoryTextIndex.tokenize(query))
        if (queryWeights.isEmpty()) return permanent

        // 3. 粗筛 → 余弦 → 多因子综合排序喵。
        val scored = ordinary.mapNotNull { item ->
            val doc = documents[item.id] ?: return@mapNotNull null
            val sim = MemoryTextIndex.similarity(queryWeights, doc)
            val score = MemoryDecay.compositeScore(
                similarity = sim,
                importance = item.importance,
                vividness = item.vividness,
                emotionalWeight = item.emotionalWeight,
                lastRecalledTurn = item.lastRecalledTurn,
                currentTurn = currentTurn,
                isPermanent = false,
            )
            Triple(item, sim, score)
        }.sortedByDescending { it.third }  // 按综合得分降序喵

        // 3. 精筛判定：按得分顺序逐条过淡忘/冷却/概率，直到预算填满喵。
        val decisions = ArrayList<RecallDecision>(budget * 2)  // 略大，因为筛掉率约 50%喵
        for ((item, sim, _) in scored) {
            val d = decide(item, sim, currentTurn, config)
            decisions.add(d)
            if (d.recalled && decisions.count { it.recalled } >= budget) break
        }

        // 4. [可选] Reranker 二次精排：拿已唤醒的候选交小模型交叉比对喵。
        val finalCandidates = if (reranker != null && decisions.any { it.recalled }) {
            reranker(decisions.filter { it.recalled })
        } else {
            decisions
        }

        return permanent + finalCandidates
    }

    /** 最终注入系统的记忆：保留被唤醒的，人设在前喵。 */
    fun recalled(decisions: List<RecallDecision>): List<MemoryItem> =
        decisions.filter { it.recalled }.map { it.memory }

    // -- 内部判定 ----------------------------------------------------------------喵

    private fun permanentDecision(item: MemoryItem) = RecallDecision(
        memory = item,
        similarity = 1.0,
        probability = MemoryDecay.PERMANENT_PROBABILITY,
        roll = 0.0,
        recalled = true,
        reason = null,
    )

    /** 单条记忆的硬性屏蔽 + 概率唤醒喵。 */
    private fun decide(
        item: MemoryItem,
        similarity: Double,
        currentTurn: Int,
        config: RecallConfig,
    ): RecallDecision {
        val probability = MemoryDecay.recallProbability(
            emotionalWeight = item.emotionalWeight,
            vividness = item.vividness,
            isUserEdited = item.isUserEdited,
            isPermanent = item.isPermanent,
        )

        val hardBlock = when {
            config.decayEnabled && item.vividness < config.vividnessFloor -> RecallSkipReason.FADING
            MemoryDecay.coolingDown(
                currentTurn = currentTurn,
                lastRecalledTurn = item.lastRecalledTurn,
                isPermanent = item.isPermanent,
                cooldownTurns = config.cooldownTurns,
            ) -> RecallSkipReason.COOLING_DOWN
            else -> null
        }

        val roll = when {
            hardBlock != null -> 0.0
            !config.probabilityEnabled -> 0.0
            else -> Dice.roll(item.id, currentTurn)
        }

        val pass = hardBlock == null && Dice.hit(roll, probability, config.probabilityEnabled)
        return RecallDecision(
            memory = item,
            similarity = similarity,
            probability = if (config.probabilityEnabled) probability else 1.0,
            roll = roll,
            recalled = pass,
            reason = if (pass) null else (hardBlock ?: RecallSkipReason.LOST_ROLL),
        )
    }

    /**
     * 按 (记忆 id, 轮次) 播种的随机数喵。
     *
     * 刻意不用全局随机源：同一条记忆在同一轮"想不想得起来"必须是同一个答案喵。
     * 这样重新生成、重新进入会话不会让记忆忽有忽无，测试也能钉住具体数值喵。
     */
    private object Dice {

        fun roll(memoryId: Long, turn: Int): Double =
            java.util.Random(seed(memoryId, turn)).nextDouble()

        fun hit(roll: Double, probability: Double, enabled: Boolean): Boolean =
            if (!enabled) true else roll < probability

        private fun seed(memoryId: Long, turn: Int): Long {
            var hash = 0x5DEECE66L
            hash = mix(hash + memoryId)
            hash = mix(hash + turn.toLong())
            return hash
        }

        private fun mix(value: Long): Long {
            var x = value
            x = x xor (x ushr 16)
            x *= 0x7FEB352DL
            x = x xor (x ushr 15)
            x *= 0x846CA68BL
            x = x xor (x ushr 16)
            return x
        }
    }
}
