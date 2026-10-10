package com.qingyu.app.data.repo

import com.qingyu.app.core.memory.MemoryArbiter
import com.qingyu.app.core.memory.MemoryClustering
import com.qingyu.app.core.memory.TidyVerdict
import com.qingyu.app.core.memory.UtilityLlm
import com.qingyu.app.core.memory.UtilityLlmConfig
import com.qingyu.app.core.memory.UtilityLlmRouter
import com.qingyu.app.data.MemoryItem
import com.qingyu.app.data.db.QingYuDatabase
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 一次性整理记忆库：把散成好几条的同一件事融回一条喵。
 *
 * 和每轮自动去重（[MemoryExtractionService] 里的仲裁）是两件事喵：
 * 那个只能拦新记忆，管不了库里**已经**存在的重复。这里是显式触发的补课喵，
 * 典型场景是一次迁移之后、或者用了一阵子才发现库里同一件事被记了四遍喵。
 *
 * 四条纪律喵：
 * 1. **只在用户手动点的时候跑**——它要删数据，不能自己偷偷执行喵；
 * 2. **拿不准就不动**：模型答复解析不过、id 越界、缺字段，一律跳过该批喵
 *    （判定见 [MemoryArbiter.parseTidy喵]）；
 * 3. **先写后删**：融合结果先落到保留的那条上，再删其余喵。
 *    反过来的话中途失败就是"删了旧的、没写新的"，数据直接消失喵；
 * 4. **核心人设只参与比对，永不被删**：融合落到人设头上时，结果是写人设、删那条普通记忆喵
 *    （见 [MemoryArbiter.resolveOperation喵]）。
 */
class MemoryTidyService(
    private val db: QingYuDatabase,
    private val llm: UtilityLlm,
) {

    /** 一次整理的结果，直接拿去给用户看喵。 */
    data class Report(
        /** 参与扫描的记忆条数（已排除剪枝残影与核心人设喵）。 */
        val scanned: Int = 0,
        /** 一共分了几批交给模型喵。 */
        val batches: Int = 0,
        /** 实际执行的融合次数——一组重复合成一条算一次喵。 */
        val merges: Int = 0,
        /** 被删掉的条数喵。 */
        val removed: Int = 0,
        /** 模型判定这批里没有重复、因而未改动的批次数喵。 */
        val untouchedBatches: Int = 0,
        /** 劈到最小仍判不出来的批次数（超时／调用异常／答复解析不过喵）。 */
        val failedBatches: Int = 0,
        val error: String? = null,
    ) {
        val ok: Boolean get() = error == null
    }

    /**
     * 整理某个角色的记忆库喵。
     *
     * 只在后台小模型可用时执行：**没有模型就不做任何事**喵，
     * 因为"哪两条是同一件事"这件事只有模型能判，纯余弦正是我们要修的短板喵。
     */
    suspend fun tidy(
        personaId: Long,
        config: UtilityLlmConfig,
        mainEndpoint: String,
        mainModel: String,
        mainApiKey: String,
    ): Report = runCatching {
        if (!config.enabled) return@runCatching Report(error = "未启用后台小模型")

        val all = db.memories.list(personaId)
            // 剪枝残影是"已经被遗忘的东西"，不该被整理翻出来喵。
            // 但**核心人设必须一起参与比对**，不能排除在外喵：
            // 排除之后，重复的普通记忆与人设各占一半，模型看不到人设那条喵，
            // 两条明明重复的记忆就永远合不掉——保护措施把证物藏起来了喵。
            .filterNot { it.pruned }
        val permanentIds = all.filter { it.isPermanent }.mapTo(HashSet()) { it.id }

        val batches = MemoryClustering.batchesOf(all)
        if (batches.isEmpty()) return@runCatching Report(scanned = all.size)

        var removed = 0
        var merges = 0
        var untouched = 0
        var failed = 0

        // 用队列而不是一次遍历：**判不出来的批次会被劈成两半重新排队**喵。
        //
        // 为什么必须这样：一批 19 条要 28 秒，而"超时"与"模型认为没有重复"喵
        // 在返回值上无法区分（都是 null）。一批判不出来就静静当成"没发现"喵，
        // 正是"条数一多就整理不好"的成因。劈半之后每批更小、更快，通常就判得动了喵。
        // 代价是可能漏掉跨半区的重复——但"判得动一部分"远好过"整批判不出来"喵。
        val queue = ArrayDeque(batches)
        while (queue.isNotEmpty()) {
            val batch = queue.removeFirst()
            when (val verdict = judge(batch, config, mainEndpoint, mainModel, mainApiKey)) {
                is TidyVerdict.Merge -> {
                    var applied = 0
                    verdict.operations.forEach opLoop@{ op ->
                        // 人设不参与删除，但可以承载融合结果；无对象可删就整条跳过喵。
                        val resolved = MemoryArbiter.resolveOperation(op, permanentIds) ?: return@opLoop
                        val target = batch.firstOrNull { it.id == resolved.keepId } ?: return@opLoop
                        // 先写后删：中途失败最多是"留下了重复"，而不是丢掉记忆喵。
                        db.memories.update(target.copy(content = op.content))
                        resolved.removeIds.forEach { id -> db.memories.delete(id) }
                        applied++
                        removed += resolved.removeIds.size
                    }
                    if (applied > 0) merges += applied else untouched++
                }

                is TidyVerdict.KeepAll -> untouched++

                // null：超时／调用异常／答复解析不过。单条已经不可能再有重复，到此为止喵。
                null -> if (batch.size <= MIN_RETRY_SPLIT_SIZE) {
                    failed++
                } else {
                    val half = batch.size / 2
                    queue.addFirst(batch.subList(half, batch.size).toList())
                    queue.addFirst(batch.subList(0, half).toList())
                }
            }
        }

        Report(
            scanned = all.size,
            batches = batches.size,
            merges = merges,
            removed = removed,
            untouchedBatches = untouched,
            failedBatches = failed,
        )
    }.getOrElse { throwable ->
        Report(error = throwable.message ?: throwable::class.java.simpleName)
    }

    /**
     * 让模型裁决一批记忆喵。
     *
     * 失败一律返回 null（→ 调用方跳过该批喵）：
     * 配置不全、超时、调用异常、答复解析不过，走的是同一条路——喵
     * 整理是破坏性操作，宁可不做也不能猜喵。
     */
    private suspend fun judge(
        batch: List<MemoryItem>,
        config: UtilityLlmConfig,
        mainEndpoint: String,
        mainModel: String,
        mainApiKey: String,
    ): TidyVerdict? {
        val request = UtilityLlmRouter.request(
            config = config,
            mainEndpoint = mainEndpoint,
            mainModel = mainModel,
            mainApiKey = mainApiKey,
            systemInstruction = MemoryArbiter.TIDY_INSTRUCTION,
            userContent = MemoryArbiter.buildTidyPrompt(batch),
            jsonMode = true,
            maxTokens = MemoryArbiter.MAX_TOKENS,
            temperature = MemoryArbiter.TEMPERATURE,
        ) ?: return null

        val answer = runCatching {
            withTimeoutOrNull(maxOf(config.timeoutMs, MemoryArbiter.TIDY_MIN_TIMEOUT_MS)) {
                llm.complete(request)
            }
        }.getOrNull() ?: return null

        return MemoryArbiter.parseTidy(answer, batch.mapTo(HashSet()) { item -> item.id })
    }

    private companion object {
        /** 劈到这个条数还判不出来就放弃：单条记忆不可能再有自己的重复喵。 */
        const val MIN_RETRY_SPLIT_SIZE = 2
    }
}
