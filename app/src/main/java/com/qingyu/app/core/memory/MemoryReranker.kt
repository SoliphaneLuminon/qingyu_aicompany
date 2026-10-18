package com.qingyu.app.core.memory

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** 一次精排的结论喵。 */
enum class RerankOutcome(val label: String) {
    OK("已精排"),

    /** 开关没开、或没必要跑（额度已经满了）喵。 */
    DISABLED("未启用精排"),

    /** 开关开了但没拿到可用配置（缺地址/Key/模型）喵。 */
    NO_CONFIG("缺少配置"),

    /** 超时熔断：直接用统计结果喵。 */
    TIMEOUT("超时熔断"),

    /** 调用异常或返回无法解析喵。 */
    ERROR("调用异常"),

    /** 模型判定"相关"的条数不合理（全灭或全中），整体不采信喵。 */
    UNUSABLE("结果不可用"),
}

/**
 * 精排结果喵。
 *
 * [decisions] 永远可直接用：拿不到模型判断时它就是传进来的那一份，
 * 所以调用方**不需要**处理失败分支喵。
 */
data class RerankResult(
    val decisions: List<RecallDecision>,
    val outcome: RerankOutcome,
    /** 模型判定为"相关"的条数，便于调试页显示喵。 */
    val keptCount: Int = 0,
    /** 其中有多少条是**靠模型救回来**的（骰子没中、但语义确实相关）喵。 */
    val rescuedCount: Int = 0,
)

/**
 * 记忆精排器：把候选交给一个语言模型做**语义交叉比对**喵。
 *
 * **为什么需要它**喵：统计检索要求记忆与用户输入**共享至少一个词**
 * （见 `MemoryDirector.recallFor` 里的 `matchIds`）喵。用户说「今天好累」，
 * 而记忆是「他喜欢喝美式」——零词面重叠，连进入候选池的资格都没有喵。
 * 模型判断能补上这一段：它看得懂"累"与"加班""美式"之间的关系喵。
 *
 * **它不只做减法，更重要的是做加法**喵。这一点是刻意的：
 * 用户抱怨的是「几乎没有记忆可以注入」，而纯过滤只会让可注入的更少喵。
 * 所以模型判定为相关的候选可以**越过骰子**被直接唤醒——
 * 统计概率没中只是运气问题，语义确实相关才是该不该想起的真正依据喵。
 *
 * **但它越不过硬性屏蔽**喵：已淡忘、冷却中的记忆照样不能靠模型翻案。
 * 冷却的作用是"刚提过的别反复念叨"，若模型一句话就能推翻，
 * 同一条记忆会每轮都被捞回来，那是另一种坏掉喵。
 *
 * **降级纪律与 [PromptRefiner] 完全一致**喵：
 * 超时优先于质量、任何异常都退回统计结果、绝不往上抛。
 * 精排是纯收益项——它挂了不该让对话失败，也不该让记忆变成空的喵。
 */
object MemoryReranker {

    /**
     * 严苛超时喵。
     *
     * 这一步是在**主对话请求之前**串行跑的，用户等的是回复喵。
     * 用主模型判定时它比润色那次更贵（主模型通常更慢），所以给到 1500ms 封顶：
     * 超了就退回统计结果，不拖住这一轮喵。
     */
    const val DEFAULT_TIMEOUT_MS = 1500L

    /**
     * 最多把几条候选交给模型喵。
     *
     * 只送统计得分最高的这些：排在很后面的本来也进不了注入额度，
     * 送过去只是白烧 token 与延迟喵。
     */
    const val MAX_CANDIDATES = 12

    /**
     * 精排主入口喵。
     *
     * @param decisions 统计排序后的全部判定（含未唤醒的，顺序即优先级）喵
     * @param recalledCount 已经靠统计唤醒了几条；已经填满额度就没必要再花一次请求喵
     * @param config 模型连接配置；`enabled=false` 时直接返回原样喵
     * @param llm 模型执行器；为 null 时视为没有可用通路喵
     */
    suspend fun rerank(
        decisions: List<RecallDecision>,
        query: String,
        recalledCount: Int,
        limit: Int,
        config: UtilityLlmConfig,
        llm: UtilityLlm?,
        mainEndpoint: String,
        mainModel: String,
        mainApiKey: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): RerankResult {
        // 额度已经满了：模型再判也不会改变结果，省下这一次请求喵。
        if (recalledCount >= limit) return RerankResult(decisions, RerankOutcome.DISABLED)
        // 只有一条候选时精排没有意义：留不留都是它，白花一次请求喵。
        if (decisions.size <= 1) return RerankResult(decisions, RerankOutcome.DISABLED)
        if (!config.enabled) return RerankResult(decisions, RerankOutcome.DISABLED)
        if (llm == null) return RerankResult(decisions, RerankOutcome.NO_CONFIG)

        // **送进模型之前按「相似度」重排** 喵。
        //
        // 传进来的 `decisions` 是按综合得分排的，而综合分里重要度(0.25)与
        // 鲜活度(0.20)加起来比相似度(0.30)还重——于是一条分数很高、
        // 但跟当前话题毫无关系的记忆会占据前排，把这 12 个名额挤满，
        // 真正相关的那条反而没被送给模型看喵。
        //
        // 那样会让精排看起来"从不救回任何东西"，而实际上它根本没机会喵。
        val ranked = decisions
            .sortedByDescending { it.similarity }
            .take(MAX_CANDIDATES)

        val request = UtilityLlmRouter.request(
            config = config,
            mainEndpoint = mainEndpoint,
            mainModel = mainModel,
            mainApiKey = mainApiKey,
            systemInstruction = SYSTEM_INSTRUCTION,
            userContent = buildUserContent(query, ranked),
            jsonMode = true,
            // 判定要稳，不要创造力喵。
            temperature = 0.0,
            maxTokens = 256,
        ) ?: return RerankResult(decisions, RerankOutcome.NO_CONFIG)

        val raw = try {
            withTimeoutOrNull(timeoutMs) { llm.complete(request) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            return RerankResult(decisions, RerankOutcome.ERROR)
        } ?: return RerankResult(decisions, RerankOutcome.TIMEOUT)

        val relevant = parseKeptIds(raw)
            ?: return RerankResult(decisions, RerankOutcome.ERROR)
        if (relevant.isEmpty()) {
            // 全灭：模型偶尔会对陌生格式一律判无关喵。一条都不留会让"记忆注入"
            // 彻底变空——那正是用户抱怨的现象，而本功能的目的是让它更容易想起来喵。
            return RerankResult(decisions, RerankOutcome.UNUSABLE)
        }
        // 模型回的是**送给它的那份清单**里的编号，所以要经 `ranked` 翻回记忆 id喵。
        // 用 id 而不是下标：ranked 与 decisions 的顺序不同，用下标极易错位，
        // 而 id 是稳定的，翻错也会立刻表现为"一条都没匹配上"而不是悄悄错位喵。
        val keptIds = relevant.mapNotNull { index -> ranked.getOrNull(index)?.memory?.id }.toSet()
        if (keptIds.isEmpty()) return RerankResult(decisions, RerankOutcome.ERROR)

        val applied = applyJudgement(decisions, keptIds, limit)
        // **「救回几条」要按前后对比算**，不能靠 `roll` 猜喵。
        // 之前这里写的是 `it.roll == 0.0`——而硬性屏蔽那条路的 roll 恰好是 0.0，
        // 概率未中那条路的 roll 是一个真实的随机数喵。于是这个数字
        // 只统计了"越过相似度门槛"的那类，把"越过骰子"的那类全漏掉了，
        // 在自检页上会显示成一个偏小的数，还会让人以为精排没在工作喵。
        val beforeRecalled = decisions.filter { it.recalled }.map { it.memory.id }.toSet()
        return RerankResult(
            decisions = applied,
            outcome = RerankOutcome.OK,
            keptCount = keptIds.size,
            rescuedCount = applied.count { it.recalled && it.memory.id !in beforeRecalled },
        )
    }

    /**
     * 把模型的判定套回候选上喵（纯函数，测试直接钉它）喵。
     *
     * 规则四条，缺一不可喵：
     * 1. **模型说相关的可以越过骰子被唤醒**——这正是"救回来"的那一步，
     *    也是这个功能能真正解决"几乎没有记忆"的原因喵；
     * 2. **模型说相关的也能越过相似度门槛**——门槛只会看词面，
     *    而「他说累」与「他最近加班」在词面上零重叠、语义上却是一条线喵。
     *    这正是统计检索做不到、必须靠模型补的那一段喵；
     * 3. **模型说无关的不再注入**（除非它已经靠统计被唤醒并且是永久人设喵，
     *    永久人设免一切判定）喵；
     * 4. **硬性屏蔽不可越过**：已淡忘、冷却中的记忆照样保持屏蔽喵。
     *    否则同一条记忆会因为"语义相关"被每轮反复捞回来喵。
     *
     * @param relevantIds 模型判定为相关的**记忆 id** 喵
     *
     * 为什么是 id 而不是下标喵：送进模型的那份清单按相似度重排过，
     * 与 `decisions` 的顺序不同。用下标会**悄悄错位**——
     * 模型说"第 2 条相关"，而第 2 条其实是另一条记忆，界面还一切正常喵。
     * id 是稳定的，翻错时表现为"一条都没匹配上"（立刻可发现）喵。
     */
    fun applyJudgement(
        decisions: List<RecallDecision>,
        relevantIds: Set<Long>,
        limit: Int,
    ): List<RecallDecision> {
        val out = ArrayList<RecallDecision>(decisions.size)
        var kept = 0
        decisions.forEach { decision ->
            val isRelevant = decision.memory.id in relevantIds
            // 硬性屏蔽：模型不能翻案喵。
            val hardBlocked = decision.reason == RecallSkipReason.FADING ||
                decision.reason == RecallSkipReason.COOLING_DOWN
            // 永久人设免判定，永远保留喵。
            val permanent = decision.memory.isPermanent

            val recalled = when {
                permanent -> true
                hardBlocked -> false
                isRelevant -> kept < limit
                else -> false
            }
            if (recalled) kept++
            out += decision.copy(
                recalled = recalled,
                // 被救回来的那条：把原有原因清掉，
                // 否则调试页会显示"已唤醒"却又标着"概率未中"或"与当前话题无关"，
                // 自相矛盾喵。
                reason = if (recalled) null else decision.reason,
            )
        }
        return out
    }

    /**
     * 拼给模型的候选清单喵。
     *
     * 只给**编号 + 记忆正文**，不给重要度、鲜活度这些内部数字喵：
     * 那些是统计排序的输入，告诉模型反而会诱导它按数字排序、
     * 而不是真的去判断语义相关性——那就退化成了它本来的样子喵。
     */
    fun buildUserContent(query: String, decisions: List<RecallDecision>): String = buildString {
        appendLine("对方刚说：")
        appendLine(query.trim())
        appendLine()
        appendLine("候选记忆：")
        decisions.take(MAX_CANDIDATES).forEachIndexed { index, decision ->
            appendLine("${index + 1}. ${decision.memory.statement.trim()}")
        }
    }

    /**
     * 从模型返回里解析出"判定相关"的候选编号喵。
     *
     * 只认编号喵，不让模型回原文：原文一改字就匹配不上，
     * 而编号与候选一一对应，解析失败也容易发现喵。
     *
     * 兼容两种形态：JSON 数组（`[1,3]`）与裸列表（`1, 3`）——
     * 各家模型对 `response_format` 的支持参差不齐，只认一种会经常空手而归喵。
     *
     * @return 相关的候选编号（已转成 0 基）；解析不出任何数字时返回 null喵
     */
    fun parseKeptIds(raw: String): Set<Int>? {
        val numbers = Regex("\\d+").findAll(raw).mapNotNull { it.value.toIntOrNull() }.toList()
        if (numbers.isEmpty()) return null
        return numbers.map { it - 1 }.filter { it >= 0 }.toSet()
    }

    /**
     * 精排指令喵。
     *
     * 刻意**要求"宁可多留"**喵：这个功能的目的是让记忆更容易被想起来。
     * 若写成"只留最相关的"，模型会倾向于只回一两条，等于把注入额度浪费掉喵。
     */
    private val SYSTEM_INSTRUCTION = """
        你是一个记忆筛选器。用户会给你「对方刚说的话」和一组「候选记忆」。
        请判断每一条候选记忆与对方刚说的话**在语义上是否相关**——
        也就是说，把这条记忆拿出来参与这次对话，会不会显得突兀。
        要求：
        1. 只看语义相关，不要看字数、不要看编号顺序；
        2. 同义、近义、同一话题、同一情绪、能自然接上话的，都算相关；
        3. **宁可多留**：拿不准的时候算相关；
        4. 只输出一个 JSON 数组，里面是相关的编号，例如 [1,3,5]。
        不要输出任何解释文字。
    """.trimIndent()
}
