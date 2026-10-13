package com.qingyu.app.data.repo

import com.qingyu.app.core.TimeUtil
import com.qingyu.app.core.memory.BioRhythm
import com.qingyu.app.core.memory.MemoryDecay
import com.qingyu.app.core.memory.MemoryRecall
import com.qingyu.app.core.memory.MemoryReranker
import com.qingyu.app.core.memory.MemoryTextIndex
import com.qingyu.app.core.memory.RecallConfig
import com.qingyu.app.core.memory.RecallDecision
import com.qingyu.app.core.memory.RerankOutcome
import com.qingyu.app.core.memory.TemporalContext
import com.qingyu.app.core.memory.UtilityLlm
import com.qingyu.app.data.AppSettings
import com.qingyu.app.data.MemoryItem
import com.qingyu.app.data.db.QingYuDatabase
import com.qingyu.app.data.rerankConfig

/** 一次记忆准备的结果喵。 */
data class MemoryContext(
    /** 本轮轮次，从 1 开始；0 表示没有推进（未启用注入喵）。 */
    val turn: Int,
    /** 最终注入系统提示词的记忆，人设在前喵。 */
    val memories: List<MemoryItem>,
    /** 全部候选的判定明细（含被屏蔽的原因），调试页会展示喵。 */
    val decisions: List<RecallDecision>,
    /** 时空与生物感知快照喵。 */
    val temporal: TemporalContext,
    /** 本轮新剪枝的记忆 id，供后续异步小模型优化压缩喵。 */
    val prunedIds: List<Long> = emptyList(),
    /**
     * 本轮精排的结论喵。
     *
     * 它是"为什么记忆没注入"这个问题的**第一手答案**喵：
     * 没有它，用户看到记忆是空的，无法区分"没有相关记忆""精排挂了""开关没开"喵。
     */
    val rerankOutcome: RerankOutcome = RerankOutcome.DISABLED,
    /** 靠模型判定**越过骰子**被救回来的条数喵。 */
    val rescuedCount: Int = 0,
)

/**
 * 记忆系统的运行时编排喵。
 *
 * 把"推进轮次 → 衰减 → 粗召回 → 打分 → 冷却/淡忘屏蔽 → 概率唤醒 → 回写"喵
 * 这一整套动作收在一处，上层只拿 [MemoryContext喵]。
 *
 * 这里**不做任何网络请求**：是否记得住、想不想得起来必须是本地的确定性行为喵，
 * 网络一抖就"失忆"是不可以接受的。提炼（写记忆）才走网络，而且是异步的喵。
 */
class MemoryDirector(
    private val db: QingYuDatabase,
    /**
     * 精排用的模型执行器喵。
     *
     * **它接的是"小模型"那条通路，但默认配置指向主模型**喵
     * （见 [com.qingyu.app.data.rerankConfig] 与 `UtilityProvider.MAIN`）——
     * 用户的要求就是"先用主模型判定，但预留小模型接口"喵。
     * 以后要换成真正的后台小模型，改设置里的 provider 即可，
     * 这里一行都不用动喵。
     */
    private val rerankerLlm: UtilityLlm? = null,
    /** 主模型的连接信息：provider = MAIN 时精排复用它喵。 */
    private val mainEndpoint: () -> String = { "" },
    private val mainModel: () -> String = { "" },
    private val mainApiKey: () -> String = { "" },
) {

    /**
     * 准备好本轮要注入的上下文喵。
     *
     * @param query 当前用户输入，用来找相关记忆喵。
     */
    suspend fun prepare(
        personaId: Long,
        conversationId: Long,
        query: String,
        settings: AppSettings,
        now: Long = TimeUtil.now(),
    ): MemoryContext {
        // 1. 轮次必须先推进：衰减与冷却都拿它当时间轴喵，
        //    顺序反了会让"上次唤醒的轮"比"当前轮"还新，冷却判定直接失效喵。
        val advance = db.memories.advanceTurn(conversationId, personaId, now)

        // 2. 时空与生物感知只看系统时间，跟记忆本身无关喵。
        val temporal = if (settings.memoryTemporalEnabled) {
            BioRhythm.snapshot(now, advance.previousAt)
        } else {
            TemporalContext.blank()
        }

        if (!settings.injectMemories) {
            return MemoryContext(advance.turn, emptyList(), emptyList(), temporal)
        }

        // 3. 衰减：每条记忆各自用 `now - updated_at` 做时间衰减喵。
        //    衰减过程中如果鲜活度跌破剪枝阈值，会就地压缩为残影并返回 id喵。
        val decayOutcome = if (settings.memoryDecayEnabled) {
            db.memories.applyDecay(personaId, now)
        } else {
            null
        }

        val decisions = recallFor(personaId, query, advance.turn, settings)
        // 4. 精排：拿模型做语义交叉比对，把"统计没选中但语义相关"的救回来喵。
        val reranked = rerank(decisions, query, settings)
        val recalled = MemoryRecall.recalled(reranked.decisions)
        // 5. 只给"真的想起来了"的记忆刷新冷却起点——被屏蔽的不算出现过喵。
        if (recalled.isNotEmpty()) {
            db.memories.markRecalled(recalled.map { it.id }, advance.turn, now)
        }

        return MemoryContext(
            turn = advance.turn,
            memories = recalled,
            decisions = reranked.decisions,
            temporal = temporal,
            prunedIds = decayOutcome?.prunedIds.orEmpty(),
            rerankOutcome = reranked.outcome,
            rescuedCount = reranked.rescuedCount,
        )
    }

    /**
     * 跑一次精排喵。
     *
     * 抽成私有方法是因为 [prepare] 与 [preview] **必须走同一套**喵：
     * 自检页显示的东西一旦不等于真正发出去的东西，那一页就失去了意义喵。
     */
    private suspend fun rerank(
        decisions: List<RecallDecision>,
        query: String,
        settings: AppSettings,
    ): com.qingyu.app.core.memory.RerankResult {
        if (decisions.isEmpty()) {
            return com.qingyu.app.core.memory.RerankResult(
                decisions,
                RerankOutcome.DISABLED,
            )
        }
        val config = settings.rerankConfig()
        return MemoryReranker.rerank(
            decisions = decisions,
            query = query,
            recalledCount = decisions.count { it.recalled },
            limit = configFor(settings).limit,
            config = config,
            llm = rerankerLlm,
            mainEndpoint = mainEndpoint(),
            mainModel = mainModel(),
            mainApiKey = mainApiKey(),
        )
    }
    /**
     * 只读预览：和 [prepare] 用**同一套**检索与唤醒规则，但一步都不写库喵。
     *
     * 调试页必须走这里。不能让自检页自己去读 `enabledFor()`（默认上限 40喵），
     * 否则"自检页显示的记忆"和"实际发出去的记忆"根本不是同一批——喵
     * 自检页看起来有十几条，真正注入的只有 6 条，越查越糊涂喵。
     *
     * 与真实请求的唯一差异：预览不推进轮次，所以骰子种子与冷却窗口喵
     * 比真实那一轮早一格（真实轮次会先 `advanceTurn`）。规则一致、时相差一轮喵。
     */
    suspend fun preview(
        personaId: Long,
        conversationId: Long,
        query: String,
        settings: AppSettings,
    ): MemoryContext {
        val temporal = peek(conversationId, settings)
        if (!settings.injectMemories) {
            return MemoryContext(turn = 0, memories = emptyList(), decisions = emptyList(), temporal = temporal)
        }
        // 用当前已落库的轮次，不推进它喵。
        val turn = db.memories.turnOf(conversationId).coerceAtLeast(1)
        val decisions = recallFor(personaId, query, turn, settings)
        // 预览也走精排喵：否则自检页显示的记忆与实际注入的会不一样，
        // 而"预览"这个功能的全部价值就在于**忠实**喵。
        val reranked = rerank(decisions, query, settings)
        return MemoryContext(
            turn = turn,
            memories = MemoryRecall.recalled(reranked.decisions),
            decisions = reranked.decisions,
            temporal = temporal,
            rerankOutcome = reranked.outcome,
            rescuedCount = reranked.rescuedCount,
        )
    }

    /**
     * 检索 + 唤醒判定的唯一实现，[prepare] 与 [preview] 共用喵。
     *
     * 刻意抽出来：这两条路径一旦各写一份，就会悄悄长歪——喵
     * 自检页显示的东西不再等于真正发出去的东西，而这种偏差没有任何测试能兜住喵。
     */
    private suspend fun recallFor(
        personaId: Long,
        query: String,
        currentTurn: Int,
        settings: AppSettings,
    ): List<RecallDecision> {
        val items = db.memories.enabledFor(personaId, limit = CANDIDATE_LIMIT)
            .filter { settings.memoryMinImportance == 0 || it.importance >= settings.memoryMinImportance }
        if (items.isEmpty()) return emptyList()

        val queryTerms = MemoryTextIndex.weigh(MemoryTextIndex.tokenize(query))
        val corpus = if (queryTerms.isEmpty()) {
            items
        } else {
            // 粗召回：先按共同 term 收窄，再交给余弦打分喵。
            val matched = db.memories.matchIds(personaId, queryTerms.keys)
            if (matched.isEmpty()) emptyList() else items.filter { it.id in matched }
        }
        if (corpus.isEmpty()) return emptyList()

        // 用持久化的索引打分；索引缺失的行（例如还没重写过的老数据）退回现算喵。
        val index = db.memories.indexFor(personaId, corpus.map { it.id })
        val documents = corpus.associate { item ->
            item.id to (index[item.id] ?: MemoryTextIndex.document(item.id, item.searchText).terms)
        }

        return MemoryRecall.evaluate(
            memories = corpus,
            documents = documents,
            query = query,
            currentTurn = currentTurn,
            config = configFor(settings),
        )
    }

    /**
     * 只读的时空快照：用**系统当前时间**，不推进轮次、不写库、不动记忆喵。
     *
     * 调试页拿它自检"此刻算出来是几点、多久没见"——走 [prepare] 会顺手推进轮次喵、
     * 触发衰减，那是真实对话的副作用，预览不能干这个喵。
     */
    suspend fun peek(conversationId: Long, settings: AppSettings): TemporalContext {
        if (!settings.memoryTemporalEnabled) return TemporalContext.blank()
        val lastChatAt = if (conversationId > 0L) db.memories.lastActiveAt(conversationId) else 0L
        return BioRhythm.snapshot(TimeUtil.now(), lastChatAt)
    }

    /** 把设置翻译成检索参数；数值在这里统一夹紧，后面就不用各处防御喵。 */
    fun configFor(settings: AppSettings): RecallConfig = RecallConfig(
        limit = settings.memoryInjectLimit.coerceIn(1, MAX_INJECT_LIMIT),
        cooldownTurns = settings.memoryCooldownTurns.coerceAtLeast(0),
        vividnessFloor = MemoryDecay.VIVIDNESS_FLOOR,
        decayEnabled = settings.memoryDecayEnabled,
        probabilityEnabled = settings.memoryProbabilityEnabled,
        minImportance = settings.memoryMinImportance.coerceIn(0, 10),
        rerankerEnabled = settings.memoryRerankerEnabled,
    )

    private companion object {
        /** 粗召回的候选上限；再多也没意义，注入额度通常是个位数喵。 */
        const val CANDIDATE_LIMIT = 200

        /** 一轮最多注入几条，防止有人把滑杆拉爆喵。 */
        const val MAX_INJECT_LIMIT = 40
    }
}
