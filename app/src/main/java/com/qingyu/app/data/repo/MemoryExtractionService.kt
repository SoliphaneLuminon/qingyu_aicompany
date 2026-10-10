package com.qingyu.app.data.repo

import com.qingyu.app.core.memory.Arbitration
import com.qingyu.app.core.memory.MemoryArbiter
import com.qingyu.app.core.memory.MemoryDedup
import com.qingyu.app.core.memory.MemoryTripleValidator
import com.qingyu.app.core.memory.UtilityLlm
import com.qingyu.app.core.memory.UtilityLlmConfig
import com.qingyu.app.core.memory.UtilityLlmRouter
import com.qingyu.app.data.MemoryItem
import com.qingyu.app.data.db.QingYuDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 后台可控提炼（Async Extractor喵）。
 *
 * 唯一的写入口在这里，纪律有三条喵：
 * 1. **提炼是副产品，不是主流程** —— 失败就静默，绝不把异常抛回对话喵；
 * 2. **校验不过的不要** —— 模型返回的 JSON 必须在 [MemoryTripleValidator] 里过关喵，
 *    字段类型不合法直接丢弃，不尝试修补，更不污染数据库喵；
 * 3. **重复的不写** —— 同一角色下 content 一模一样的跳过喵。
 *
 * 注意：这里发的是网络请求，但**只在生成结束之后异步跑**喵，
 * 所以它拖不慢任何一次回复（见 [launchAsync喵]）。
 */
class MemoryExtractionService(
    private val db: QingYuDatabase,
    private val llm: UtilityLlm,
) {

    /** 一次提炼的结果喵。 */
    data class Outcome(
        val stored: List<MemoryItem> = emptyList(),
        /** 模型原始回复，排查问题时才用喵。 */
        val raw: String = "",
        /** 通过强类型校验的条数喵。 */
        val valid: Int = 0,
        /** 校验通过但没写进去的条数（通常是重复的喵）。 */
        val discarded: Int = 0,
        val error: String? = null,
    ) {
        val ok: Boolean get() = error == null
    }

    /**
     * 提炼一段对话里的长期记忆并落库喵。
     *
     * @return 不抛异常；失败信息在 [Outcome.error喵]。
     */
    suspend fun extractAndStore(
        personaId: Long,
        transcript: String,
        config: UtilityLlmConfig,
        mainEndpoint: String,
        mainModel: String,
        mainApiKey: String,
    ): Outcome = runCatching {
        if (!config.enabled) return@runCatching Outcome(error = "未启用自动提炼")
        if (transcript.isBlank()) return@runCatching Outcome(error = "没有可提炼的对话")

        val request = UtilityLlmRouter.request(
            config = config,
            mainEndpoint = mainEndpoint,
            mainModel = mainModel,
            mainApiKey = mainApiKey,
            systemInstruction = INSTRUCTION,
            userContent = transcript,
            jsonMode = true,
            maxTokens = MAX_TOKENS,
            temperature = TEMPERATURE,
        ) ?: return@runCatching Outcome(error = "后台小模型配置不完整")

        val raw = llm.complete(request)
        val parsed = MemoryTripleValidator.parse(raw)

        // 已有记忆只拉一次：余弦预筛要跟全量比一遍，融合还要按 id 就地回写喵。
        //
        // 只跟"还没被剪枝"的记忆比：被剪枝等于已经遗忘，用户再提起时应该能重新记住喵，
        // 而不是因为库里的残影被判成"记过了"而永远回不来喵。
        val existing = db.memories.list(personaId).filterNot { it.pruned }.toMutableList()

        val stored = ArrayList<MemoryItem>(parsed.size)
        parsed.forEach { memory ->
            val item = memory.toItem(personaId)
            // 一模一样的话直接跳过：模型经常每轮都提炼出同一条喵。
            if (db.memories.exists(personaId, item.statement)) return@forEach

            // 余弦预筛 → 小模型仲裁。模型不可用/超时/答非所问都会退回余弦阈值喵，
            // 也就是这一层不存在时的老行为喵。
            val verdict = arbitrate(item.statement, existing, config, mainEndpoint, mainModel, mainApiKey)
            val mergeTarget = (verdict as? Arbitration.Merge)
                ?.let { merge -> existing.firstOrNull { it.id == merge.targetId } }

            when {
                // 判重：丢掉新的这条，已有那条不动喵。
                verdict is Arbitration.Skip -> Unit

                // 融合：把已有那条改写得更全面。新记忆**不单独入库**——喵
                // 它的事实已经被并进去了，再存一条就还是重复喵。
                verdict is Arbitration.Merge && mergeTarget != null -> {
                    val merged = mergeTarget.copy(content = verdict.content)
                    db.memories.update(merged)
                    val at = existing.indexOfFirst { it.id == mergeTarget.id }
                    if (at >= 0) existing[at] = merged
                }

                else -> {
                    val id = db.memories.insert(item)
                    val saved = item.copy(id = id)
                    stored.add(saved)
                    // 同一批内部也要互相比对：模型一次吐出两条几乎相同的情况并不少见喵。
                    existing.add(saved)
                }
            }
        }

        Outcome(
            stored = stored,
            raw = raw,
            valid = parsed.size,
            discarded = parsed.size - stored.size,
        )
    }.getOrElse { throwable ->
        Outcome(error = throwable.message ?: throwable::class.java.simpleName)
    }

    /**
     * 丢到后台跑，成功失败都不影响当前对话喵。
     *
     * @param scope 必须是应用级 scope，否则界面一销毁提炼就跟着没了喵。
     */
    fun launchAsync(
        scope: CoroutineScope,
        personaId: Long,
        transcript: String,
        config: UtilityLlmConfig,
        mainEndpoint: String,
        mainModel: String,
        mainApiKey: String,
    ) {
        if (!config.enabled) return
        scope.launch {
            runCatching {
                extractAndStore(
                    personaId = personaId,
                    transcript = transcript,
                    config = config,
                    mainEndpoint = mainEndpoint,
                    mainModel = mainModel,
                    mainApiKey = mainApiKey,
                )
            }
        }
    }

    /**
     * 用小模型压缩剪枝残影：把机械压缩产物（`obj` 或 `subject`喵）
     * 交给模型提炼为更自然的 5–10 字核心词喵。
     *
     * 异步跑、不阻塞主流程。失败就退回到机械压缩（不丢数据喵）。
     */
    fun launchPruneCompression(
        scope: CoroutineScope,
        prunedIds: List<Long>,
        config: UtilityLlmConfig,
        mainEndpoint: String,
        mainModel: String,
        mainApiKey: String,
    ) {
        if (prunedIds.isEmpty() || !config.enabled) return
        scope.launch {
            prunedIds.forEach { id ->
                runCatching {
                    compressOne(db, llm, id, config, mainEndpoint, mainModel, mainApiKey)
                }
            }
        }
    }

    private suspend fun compressOne(
        db: QingYuDatabase,
        llm: UtilityLlm,
        memoryId: Long,
        config: UtilityLlmConfig,
        mainEndpoint: String,
        mainModel: String,
        mainApiKey: String,
    ) {
        val row = db.memories.pruneCandidate(memoryId) ?: return
        val request = UtilityLlmRouter.request(
            config = config,
            mainEndpoint = mainEndpoint,
            mainModel = mainModel,
            mainApiKey = mainApiKey,
            systemInstruction = "你是一个记忆压缩器。输出一段极简的核心词（5–10 字），不要解释。",
            userContent = "原始记忆：${row.originalContent}\n机械压缩：${row.compressedText}\n请给出更好的压缩：",
            jsonMode = false,
            maxTokens = 64,
            temperature = 0.1,
        ) ?: return
        val result = runCatching { llm.complete(request) }.getOrNull()?.trim()?.take(20) ?: return
        if (result.isBlank() || result == row.compressedText) return
        db.memories.enhancePrune(memoryId, result)
    }

    /**
     * 判定一条新记忆该怎么处理喵。
     *
     * 两段式：余弦预筛挑出"可能重复"的候选 → 小模型判 KEEP / SKIP / MERGE喵。
     *
     * **任何一步出问题都退回纯余弦阈值**（见 [fallbackByCosine]），也就是这一层不存在时的行为喵：
     * 仲裁是提升项，不该因为它自己挂了就让整轮记忆收录失败喵。
     */
    private suspend fun arbitrate(
        statement: String,
        existing: List<MemoryItem>,
        config: UtilityLlmConfig,
        mainEndpoint: String,
        mainModel: String,
        mainApiKey: String,
    ): Arbitration {
        val candidates = MemoryArbiter.candidatesOf(statement, existing)
        // 没有任何一条像它：连模型都不用叫。绝大多数新记忆都走这条路，等于零额外开销喵。
        if (candidates.isEmpty()) return Arbitration.Keep

        val request = UtilityLlmRouter.request(
            config = config,
            mainEndpoint = mainEndpoint,
            mainModel = mainModel,
            mainApiKey = mainApiKey,
            systemInstruction = MemoryArbiter.INSTRUCTION,
            userContent = MemoryArbiter.buildPrompt(statement, candidates),
            jsonMode = true,
            maxTokens = MemoryArbiter.MAX_TOKENS,
            temperature = MemoryArbiter.TEMPERATURE,
        )

        val answer = request?.let { req ->
            runCatching {
                withTimeoutOrNull(maxOf(config.timeoutMs, MemoryArbiter.ARBITER_MIN_TIMEOUT_MS)) {
                    llm.complete(req)
                }
            }.getOrNull()
        }

        val verdict = answer?.let { MemoryArbiter.parse(it, candidates.mapTo(HashSet()) { c -> c.id }) }
        return verdict ?: fallbackByCosine(statement, existing)
    }

    /** 仲裁不可用时的兜底：沿用纯余弦阈值，取最近的那条候选作为 target喵。 */
    private fun fallbackByCosine(statement: String, existing: List<MemoryItem>): Arbitration {
        val nearest = MemoryArbiter.candidatesOf(statement, existing).firstOrNull()
            ?: return Arbitration.Keep
        return if (nearest.similarity >= MemoryDedup.DUPLICATE_THRESHOLD) {
            Arbitration.Skip(nearest.id, "余弦兜底")
        } else {
            Arbitration.Keep
        }
    }

    companion object {

        /** 提炼一次的输出上限喵。 */
        const val MAX_TOKENS = 1024

        const val TEMPERATURE = 0.1

        /**
         * 提炼指令喵。
         *
         * 同时要三元组和整句：三元组给检索和后续结构化用喵，
         * `content` 保证老字段不为空（记忆页、导出、提示词注入都在读它喵）。
         */
        val INSTRUCTION: String = """
            从下面的聊天记录里提炼**值得长期记住**的事实。宁缺毋滥——拿不准"以后还会不会用到"的就不要。

            输出格式：纯 JSON 数组，不要任何解释或代码块标记。每个元素：
            {"subject":"主语（通常是"用户"或"我"）","predicate":"关系或动作","object":"客体","content":"一句完整、具体的中文陈述（必填）","context":"发生场景，可为空字符串","emotional_weight":0到1之间的数字（这件事的情绪分量）,"importance":1到10的整数（判据见下面的"重要度怎么给"，务必按锚点给）,"category":"PROFILE|PREFERENCE|EVENT|RELATION|RULE"}

            规则：
            1. 只提取"稳定的、未来还用得上的"信息：身份标签、固定喜好、重要经历、人际关系、明确约定。
            2. 不要提取：一次性寒暄、临时情绪（"今天有点累"）、笼统感受（"心情还不错"）、缺少具体细节的表述（"用户喜欢喝咖啡"——太泛。必须是"用户每天早上喝一杯不加糖的美式咖啡"这种）。一句话的测试：如果这句话脱离上下文仍能独立传达一件具体事实，就可以；否则不出。
            3. 拿不准的宁可不要。输出 [] 是完全可以的，比灌一堆垃圾强。
            4. 最多 8 条。
            5. subject、predicate、object、content 必须是字符串，不能为 null。
            6. emotional_weight 和 importance 必须是数字，不能写成字符串。

            重要度怎么给——这一项最容易被给歪，请严格按锚点，不要一律往高给：
            - 10：不可变更的身份与约定。定了就不再变、且直接决定关系基调。
              例："用户是挽夏的丈夫，两人以老公老婆互称"、"你是 AI，住在手机里"。
            - 8~9：长期稳定、会反复影响互动的核心事实。
              例：家乡、职业方向、对角色做过的郑重承诺。
            - 6~7：稳定但影响面窄的偏好或关系。
              例："用户喜欢吃辣"、"哥哥叫阿昭"。
            - 4~5：一次性的、值得记住的具体事件。
              例：某天深夜的一次对话、某次约定。
            - 1~3：边缘细节。
              例：具体到分钟的时点、当时的天气、顺口一提的小事。

            两条纪律：
            - **默认给 5**，只有明确踩到上面某个锚点才往上加。绝大多数日常偏好落在 4~6。
            - 一次提炼里 7 分以上的通常不超过 1~2 条。若你给出的条目**超过一半都在 7 以上**，
              说明尺度松了，请重新按锚点校准再输出。
        """.trimIndent()
    }
}
