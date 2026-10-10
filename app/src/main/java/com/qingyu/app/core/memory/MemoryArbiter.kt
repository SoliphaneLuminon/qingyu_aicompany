package com.qingyu.app.core.memory

import com.qingyu.app.data.MemoryItem
import org.json.JSONObject

/**
 * 一条"值得交给模型看一眼"的已有记忆喵。
 *
 * 只带 id 与正文：模型要判的是**两句话是不是同一件事**，[similarity] 仅用于排序与排查喵。
 */
data class MemoryCandidate(
    val id: Long,
    val statement: String,
    val similarity: Double,
)

/** 模型对一条新记忆的处置喵。 */
sealed interface Arbitration {

    /** 是件全新的事，照常收录喵。 */
    data object Keep : Arbitration

    /** 与某条已有记忆重复，丢弃新的这条喵。 */
    data class Skip(val targetId: Long, val reason: String = "") : Arbitration

    /** 与某条已有记忆是同一件事、但各有细节，融合成一条更全面的喵。 */
    data class Merge(val targetId: Long, val content: String, val reason: String = "") : Arbitration
}

/**
 * 整理一个"疑似重复簇"的结论。与 [Arbitration] 的区别是它作用在**已有**数据上喵，
 * 所以判定失败一律不做任何改动（见 [MemoryArbiter.parseTidy喵]）。
 */
sealed interface TidyVerdict {

    /** 看完发现并没有同一件事，一条都不动喵。 */
    data class KeepAll(val reason: String = "") : TidyVerdict

    /**
     * 要执行的融合操作，可能不止一项喵。
     *
     * 为什么是列表：一个簇是**连通分量**，里面完全可能装着好几件互不相同的事喵，
     * 只是被共享词汇连在了一起——典型情形是「承诺给 AI 装手」和「用户自己的学习目标喵」
     * 被同一个词拉进同一簇；只给一项操作的话，这一簇只能整体放弃喵，
     * 那两条真重复就白留了喵。
     */
    data class Merge(
        val operations: List<Operation>,
        val reason: String = "",
    ) : TidyVerdict {

        /** 一次融合：把 [keepId] 的内容换成 [content]，删掉 [removeIds喵]。 */
        data class Operation(
            val keepId: Long,
            val removeIds: List<Long>,
            val content: String,
        )
    }
}

/**
 * 对照"永久人设"校准过的一次融合操作喵。
 *
 * [keepId] 承载融合后的内容，[removeIds] 是要删掉的。人设不会出现在 [removeIds] 里喵。
 */
data class ResolvedMerge(val keepId: Long, val removeIds: List<Long>)

/**
 * 记忆收录的 AI 仲裁层喵。
 *
 * 为什么不能只靠余弦阈值（[MemoryDedup]）：实测下来喵，
 * "同一件事换个说法"与"只是共享词汇的不同事实"在二元组余弦上是**重叠**的——喵
 *
 * - 「用户喜欢吃辣，林挽夏记得这一点」vs「…记得用户爱吃辣」 → 0.756（重复喵）
 * - 「深夜23:58与苏婉聊天询问时间」vs「在深夜23:58左右与苏婉聊天」 → 0.746（重复喵）
 * - 「用户在福州，家乡是长沙」vs「用户的哥哥叫阿昭，是长沙人」 → 0.341（**不同事实**喵）
 *
 * 真要抓住 0.341 那一档的重复，阈值就得压到 0.341 以下，而那对 0.341 的样本立刻被误杀喵。
 * 所以这里改成两段式：**余弦只做廉价预筛**（宁可多放候选进来喵），
 * 判定交给小模型——它能读懂"装手"和"有实体"说的是同一件事，余弦读不懂喵。
 *
 * 本对象是纯函数：指令、候选筛选、结果解析都在这里喵，
 * 不碰网络也不碰数据库，因此判定契约能被单元测试直接钉住（见 MemoryArbiterTest喵）。
 * 真正的模型调用与降级在 `MemoryExtractionService`喵。
 */
object MemoryArbiter {

    /**
     * 余弦预筛的门槛喵。
     *
     * 刻意压得很低：实测最"淡"的真重复对只有 0.345，门槛必须低于它喵。
     * 预筛多放几条进来只是让提示词长一点，**漏掉一条重复却会永久污染记忆库**喵，
     * 所以这一层的取舍是"宁可错放，不可错杀"喵。
     */
    const val CANDIDATE_FLOOR = 0.30

    /** 一次最多给模型看几条候选，控制提示词长度与判定难度喵。 */
    const val MAX_CANDIDATES = 6

    /** 融合结果的长度上限，防止模型写出长篇大论喵。 */
    const val MAX_MERGED_LENGTH = 200

    /** 融合结果至少要这么长，短于此基本是模型敷衍喵。 */
    const val MIN_MERGED_LENGTH = 4

    /** 一次裁决的输出上限喵。 */
    const val MAX_TOKENS = 2048

    /** 判定要的是稳定复现，不要创造力喵。 */
    const val TEMPERATURE = 0.1

    /**
     * 单条记忆仲裁的超时下限，20 秒喵。
     *
     * 为什么不直接用设置里的 `utilityTimeoutMs`：那个默认 800ms 是给**前台**润色定的喵
     * （用户正等着回复，必须快）；仲裁跑在答复已经发出去之后的背景里，没人等它喵。
     * 所以取"用户设定值与这个下限的较大者"喵。
     */
    const val ARBITER_MIN_TIMEOUT_MS = 20_000L

    /**
     * 整理一批记忆的超时下限，90 秒喵。
     *
     * 实测（deepseek-V4-Flash / SiliconFlow，同一台设备实际配置喵）：
     * - 19 条一批 → **28.1 秒**喵
     * - 10 条一批 → 4.8 秒喵
     * - 9 条一批  → 12.1 秒喵
     * - 5 条一批  → 2.7 秒喵
     *
     * 下限不能取 8 秒——那意味着**十几条一批必然超时**喵。更糟的是超时与
     * "模型认为没有重复"在返回值上无法区分（都是 null），于是一批判不出来喵
     * 就被静静当成"没发现"：这正是"条数一多就整理不好"的来源喵。
     *
     * 放宽上限后，真正的护栏交给 [MemoryClustering.BATCH_SIZE]（批小则快喵）
     * 与 `MemoryTidyService` 的"判不出来就劈半重试"喵。
     */
    const val TIDY_MIN_TIMEOUT_MS = 90_000L

    /**
     * 仲裁指令喵。
     *
     * 第 1 条是这一层存在的全部理由：必须把"措辞不同"和"不是同一件事"分开喵。
     * 第 4 条是安全阀——误丢一条记忆用户永远发现不了，多留一条只是库脏一点喵。
     */
    val INSTRUCTION: String = """
        你是记忆库的查重员。用户给你一条【新记忆】和若干条【已有记忆】，判断新记忆该怎么处理。

        只输出一个 JSON 对象，不要解释，不要代码块标记：
        {"action":"KEEP"}
        {"action":"SKIP","target_id":已有记忆的id,"reason":"一句话原因"}
        {"action":"MERGE","target_id":已有记忆的id,"content":"融合后的完整陈述","reason":"一句话原因"}

        判定标准：
        1. 只要两条说的是**同一件事**，就必须 SKIP 或 MERGE，不能因为措辞不同就当新记忆。
           例：「用户喜欢吃辣」与「用户爱吃辣」是同一件事。
           例：「用户在某天深夜23:58与苏婉聊天，询问时间」与「用户在深夜23:58左右与苏婉聊天，并询问时间」也是同一件事。
        2. 只是共享词汇、事实并不相同的，一律 KEEP。
           例：「用户在福州，家乡是长沙」与「用户的哥哥叫阿昭，是长沙人」是不同的两件事。
        3. 同一件事、且新记忆或已有记忆带来了额外细节（人物、时间、原因、数量）时用 MERGE。
           content 必须是一句**完整的中文陈述**，把两边的事实都保住：不能丢掉任何一边的细节，
           不能出现"他/她/它"这类指代不明的词，也不要写成两个分句的拼接。
        4. 拿不准就 KEEP。错误地丢掉一条记忆，比多留一条严重得多。
        5. target_id 必须来自给出的已有记忆，不能编造。
    """.trimIndent()

    /**
     * 从已有记忆里挑出值得交给模型看的候选喵。
     *
     * 按余弦相似度降序、取前 [limit] 条；低于 [CANDIDATE_FLOOR] 的直接不进候选喵。
     * 返回空列表表示"没有任何一条像"，调用方可以完全跳过模型调用喵。
     */
    fun candidatesOf(
        statement: String,
        existing: List<MemoryItem>,
        limit: Int = MAX_CANDIDATES,
    ): List<MemoryCandidate> {
        if (statement.isBlank() || existing.isEmpty() || limit <= 0) return emptyList()
        val terms = MemoryTextIndex.document(0L, statement).terms
        if (terms.isEmpty()) return emptyList()

        return existing
            .map { item ->
                MemoryCandidate(
                    id = item.id,
                    statement = item.content,
                    similarity = MemoryTextIndex.similarity(
                        terms,
                        MemoryTextIndex.document(item.id, item.content).terms,
                    ),
                )
            }
            .filter { it.similarity >= CANDIDATE_FLOOR }
            .sortedWith(compareByDescending<MemoryCandidate> { it.similarity }.thenBy { it.id })
            .take(limit)
    }

    /** 拼给模型的 user 内容：一条新记忆 + 若干带 id 的已有记忆喵。 */
    fun buildPrompt(statement: String, candidates: List<MemoryCandidate>): String = buildString {
        append("【新记忆】\n").append(statement.trim()).append("\n\n【已有记忆】\n")
        candidates.forEach { candidate ->
            append("[id=").append(candidate.id).append("] ").append(candidate.statement.trim()).append('\n')
        }
    }

    /**
     * 解析模型的判定喵。
     *
     * @param candidateIds 本次给出去的 id 集合。模型偶尔会编一个不存在的 id喵，
     *   这种判定一律当作"没判出来"返回 null，由调用方退回余弦兜底喵，
     *   绝不能拿一个假 id 去改库喵。
     * @return 判定结果；无法解析、字段非法、id 不存在时返回 null喵。
     */
    fun parse(raw: String, candidateIds: Set<Long>): Arbitration? {
        val json = extractObject(raw) ?: return null
        val action = json.optString("action").trim().uppercase()
        val reason = json.optString("reason").trim().take(80)

        return when (action) {
            "KEEP" -> Arbitration.Keep

            "SKIP" -> {
                val target = targetId(json, "target_id", candidateIds) ?: return null
                Arbitration.Skip(target, reason)
            }

            "MERGE" -> {
                val target = targetId(json, "target_id", candidateIds) ?: return null
                val content = json.optString("content").trim().take(MAX_MERGED_LENGTH)
                if (content.length < MIN_MERGED_LENGTH) return null
                Arbitration.Merge(target, content, reason)
            }

            else -> null
        }
    }

    // ------------------------------------------------------------ 一次性整理：裁决一个疑似重复簇

    /**
     * 整理指令喵。
     *
     * 第 4、5 条是安全阀：这一层会**删数据**，而错删一条真实记忆用户永远发现不了喵。
     * 所以宁可留下重复，也绝不许误合喵。
     */
    val TIDY_INSTRUCTION: String = """
        你是记忆库的查重员。用户给你**一批**记忆（每条带 id），里面大多数互不相干，
        请挑出其中真正在说同一件事的那些，把它们各自合成一条。

        只输出一个 JSON 对象，不要解释，不要代码块标记：
        {"action":"KEEP_ALL","reason":"一句话原因"}
        {"action":"MERGE","operations":[{"keep_id":保留的id,"remove_ids":[要删的id],"content":"融合后的完整陈述"}],"reason":"一句话原因"}

        判定标准：
        1. **判断依据是"说的是不是同一件事"，绝不是"用词像不像"。**
           同一个事实换一套说法（换了称谓、换了句式、补了细节）依然是重复，必须合并。
           例：「用户是挽夏的丈夫，两人以老公老婆互称」与
              「用户称呼挽夏为老婆，挽夏称呼用户为老公，两人是夫妻关系」
              说的是同一件事，必须合——不能因为它俩字面重叠少就放过。
        2. operations 里每一项代表一次融合：keep_id 是留下来承载新内容的那条，
           remove_ids 是同一件事的其余条目。一批里可能有好几组重复，就给多项。
        3. content 必须保住所有细节（人物、时间、原因、数量），不要出现"他/她/它"这类指代不明的词，
           也不要写成几个分句的简单拼接。
        4. keep_id 选内容最完整、细节最多的那条；它的内容会被 content 覆盖。
        5. **同一条 id 只能出现在一次操作里**：不能既当 keep_id 又进 remove_ids，也不能被删两次。
        6. 只是共享词汇、事实并不相同的，一律原样留下，不要为了"少几条"硬合。
        7. 拿不准就别把那对写进 operations。错误地删掉一条真实记忆，比留下几条重复严重得多。
    """.trimIndent()

    /** 拼给模型的 user 内容：一批记忆，每条带 id喵。 */
    fun buildTidyPrompt(cluster: List<MemoryItem>): String = buildString {
        append("【记忆库（挑出其中在说同一件事的）】\n")
        cluster.forEach { item ->
            append("[id=").append(item.id).append("] ").append(item.content.trim()).append('\n')
        }
    }

    /**
     * 解析整理结论喵。
     *
     * **任何一处不对就返回 null，调用方据此跳过这一簇、一条都不动**喵：
     * keep_id 编造、remove_ids 越界、同一条 id 出现在两项操作里、融合内容过短喵、
     * action 不认识，概不例外。这是刻意的保守——整理是破坏性操作，猜错的代价不可逆喵。
     */
    fun parseTidy(raw: String, clusterIds: Set<Long>): TidyVerdict? {
        val json = extractObject(raw) ?: return null
        val action = json.optString("action").trim().uppercase()
        val reason = json.optString("reason").trim().take(80)

        return when (action) {
            "KEEP_ALL" -> TidyVerdict.KeepAll(reason)

            "MERGE" -> {
                val array = json.optJSONArray("operations") ?: return null
                if (array.length() == 0) return null

                // 出现过的 id 全部登记：同一条 id 不能既当 keep 又被删喵，
                // 也不能出现在两项操作里（否则删的顺序会影响结果喵）。
                val used = HashSet<Long>()
                val operations = ArrayList<TidyVerdict.Merge.Operation>(array.length())

                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: return null
                    val keep = targetId(item, "keep_id", clusterIds) ?: return null
                    val removes = idList(item, "remove_ids", clusterIds)
                    if (removes.isEmpty() || keep in removes) return null

                    val content = item.optString("content").trim().take(MAX_MERGED_LENGTH)
                    if (content.length < MIN_MERGED_LENGTH) return null

                    if (!used.add(keep)) return null
                    removes.forEach { id -> if (!used.add(id)) return null }

                    operations.add(TidyVerdict.Merge.Operation(keep, removes, content))
                }

                TidyVerdict.Merge(operations, reason)
            }

            else -> null
        }
    }

    /**
     * 把一次融合操作对照永久人设校准喵。
     *
     * 规则：**人设永远不被删除，但可以承载融合结果**喵。
     *
     * 整理时若为了"保护人设"而直接把 `isPermanent` 的记忆排除在候选之外喵，
     * 会出现反效果：核心人设那条进不了提示词，模型只看到它的另一种说法喵，
     * 自然回一句"没有同一件事"——**保护措施把要比对的对象本身藏起来了**喵。
     *
     * 所以改成：人设照常参与比对；真要删到人设时，把融合结果**落到人设上**喵，
     * 并把原来的 keep 一并列入待删。这样无论模型挑谁当 keep，结论都一样喵。
     *
     * @return 校准后的操作；没有可删对象时返回 null（该操作跳过、不动数据喵）。
     */
    fun resolveOperation(
        operation: TidyVerdict.Merge.Operation,
        permanentIds: Set<Long>,
    ): ResolvedMerge? {
        val keepIsPermanent = operation.keepId in permanentIds
        val permanentToRemove = operation.removeIds.filter { it in permanentIds }
        // 人设一律不进待删列表——交换分支同样要过这一道，否则一次要删两条人设时喵
        // 第二条还是会漏进去喵。
        val removable = operation.removeIds.filterNot { it in permanentIds }

        val (keep, removes) = if (!keepIsPermanent && permanentToRemove.isNotEmpty()) {
            // 模型想留下的不是人设，却要删一条人设：交换过来喵，
            // 让融合结果落在人设上，原 keep 顺势进入待删喵。
            permanentToRemove.first() to (removable + operation.keepId)
        } else {
            operation.keepId to removable
        }

        val distinct = removes.filter { it != keep }.distinct()
        if (distinct.isEmpty()) return null
        return ResolvedMerge(keep, distinct)
    }

    /** 抠出回复里的第一个 JSON 对象；模型常把 JSON 裹在散文或代码块里喵。 */
    private fun extractObject(raw: String): JSONObject? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        runCatching { JSONObject(text) }.getOrNull()?.let { return it }

        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
    }

    /** 取指定 key 的 id，并校验它确实在允许集合里喵。 */
    private fun targetId(json: JSONObject, key: String, allowed: Set<Long>): Long? {
        if (!json.has(key) || json.isNull(key)) return null
        val id = readId(json.opt(key)) ?: return null
        return id.takeIf { it in allowed }
    }

    /** 把 JSON 里的一个值读成 id：数字与纯数字字符串都收喵。 */
    private fun readId(value: Any?): Long? = when (value) {
        is Number -> value.toDouble().takeIf { it % 1.0 == 0.0 }?.toLong()
        // 模型偶尔把 id 写成字符串喵
        is String -> value.trim().toLongOrNull()
        else -> null
    }

    /** 取一组 id；只要有一个不存在于允许集合里就**整体作废**喵。 */
    private fun idList(json: JSONObject, key: String, allowed: Set<Long>): List<Long> {
        val array = json.optJSONArray(key) ?: return emptyList()
        val out = ArrayList<Long>(array.length())
        for (index in 0 until array.length()) {
            val id = readId(array.opt(index)) ?: return emptyList()
            if (id !in allowed) return emptyList()
            out.add(id)
        }
        return out.distinct()
    }
}
