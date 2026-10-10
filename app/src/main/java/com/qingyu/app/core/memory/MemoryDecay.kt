package com.qingyu.app.core.memory

import kotlin.math.exp
import kotlin.math.roundToLong

/**
 * 遗忘曲线与唤醒控制。全部是纯函数，不依赖 Android，也不需要网络喵。
 *
 * 这是"计算与状态解耦"的落点：模型只负责接话，**记不记得住、什么时候想起来**喵
 * 完全由这里的确定性算术决定。随机数也是按 (记忆 id, 轮次) 播种的喵，
 * 同一个输入永远得到同一个结果，方便测试与复盘喵。
 *
 * 三条规则喵：
 * 1. 艾宾浩斯衰减 `V(t) = V0 * exp(-lambda * Δt / (R + 1))`喵
 *    —— 分母里的 R 是历史被唤醒次数：被想起得越多，记忆越"抗衰减"喵，
 *    这就是间隔重复效应，公式本身已经表达了，不需要额外的强化项喵。
 * 2. 硬性冷却：普通记忆被唤醒后 20 轮内强制屏蔽，避免同一个梗反复念叨喵。
 * 3. 概率唤醒 `P = min(0.5, base + 0.2 * E + 0.1 * V)`；永久人设恒为 1.0喵。
 */
object MemoryDecay {

    /** 普通记忆的衰减系数（每回合，保留给测试与向后兼容喵）。 */
    const val LAMBDA_NORMAL = 0.05

    /** 用户手动改过的记忆：衰减减半（每回合喵）。 */
    const val LAMBDA_USER_EDITED = 0.025

    /** 普通记忆的衰减系数（每天）。半衰期约一周喵。 */
    const val LAMBDA_PER_DAY_NORMAL = 0.10

    /** 用户手动改过的记忆：每天衰减减半喵。 */
    const val LAMBDA_PER_DAY_USER_EDITED = 0.05

    /** 伴侣人设 / 永久节点：永不衰减喵。 */
    const val LAMBDA_PERMANENT = 0.0

    /** 硬性冷却轮数喵。 */
    const val COOLDOWN_TURNS = 20

    /** 鲜活度低于此值的记忆不再参与唤醒喵。 */
    const val VIVIDNESS_FLOOR = 0.2

    /** 鲜活度跌破此阈值触发剪枝——压成残影，只留核心名词喵。 */
    const val PRUNE_VIVIDNESS_THRESHOLD = 0.05

    /** 剪枝记忆被唤醒后恢复到什么鲜活度——"第二春"但不是全新喵。 */
    const val REAWAKEN_VIVIDNESS = 0.5

    /** 普通记忆的唤醒概率基数喵。 */
    const val BASE_PROBABILITY = 0.1

    /** 用户改过的记忆的唤醒概率基数喵。 */
    const val BASE_PROBABILITY_USER_EDITED = 0.25

    /** 唤醒概率上限喵。 */
    const val MAX_PROBABILITY = 0.5

    const val EMOTION_COEFFICIENT = 0.2

    const val VIVIDNESS_COEFFICIENT = 0.1

    /** 永久人设享有 100% 优先唤醒特权喵。 */
    const val PERMANENT_PROBABILITY = 1.0

    /**
     * 从未被唤醒过时的轮次标记喵。
     *
     * 不能用 0：新建一条记忆时当前就在第 0 轮附近喵，
     * 若记录成 0，冷却判定 `turn - 0 < 20` 会立刻把它屏蔽掉 20 轮喵，
     * 表现就是"刚记住的事反而想不起来"喵。
     */
    const val NEVER_RECALLED_TURN = -100

    /** 永久 > 用户改过 > 普通，决定 lambda（回合制，保留给测试和向后兼容喵）。 */
    fun lambdaFor(isUserEdited: Boolean, isPermanent: Boolean): Double = when {
        isPermanent -> LAMBDA_PERMANENT
        isUserEdited -> LAMBDA_USER_EDITED
        else -> LAMBDA_NORMAL
    }

    /** 永久 > 用户改过 > 普通，决定 lambda（按天喵）。 */
    fun lambdaForDays(isUserEdited: Boolean, isPermanent: Boolean): Double = when {
        isPermanent -> LAMBDA_PERMANENT
        isUserEdited -> LAMBDA_PER_DAY_USER_EDITED
        else -> LAMBDA_PER_DAY_NORMAL
    }

    /**
     * `V(t) = V0 * exp(-lambda * Δt / (R + 1))` —— 回合制版，仅测试与向后兼容保留喵。
     */
    fun decayed(vividness: Double, deltaTurns: Int, recallCount: Int, lambda: Double): Double {
        if (lambda <= 0.0 || deltaTurns <= 0) return clamp(vividness)
        val resistance = (recallCount.coerceAtLeast(0) + 1).toDouble()
        return clamp(vividness * exp(-lambda * deltaTurns / resistance))
    }

    /**
     * `V(t) = V0 * exp(-lambda * Δt / (R + 1))` —— 按天版，线上衰减走这里喵。
     *
     * @param deltaDays 自 `updated_at` 以来经过的天数；<= 0 不衰减喵。
     */
    fun decayedByDays(vividness: Double, deltaDays: Double, recallCount: Int, lambda: Double): Double {
        if (lambda <= 0.0 || deltaDays <= 0.0) return clamp(vividness)
        val resistance = (recallCount.coerceAtLeast(0) + 1).toDouble()
        return clamp(vividness * exp(-lambda * deltaDays / resistance))
    }

    /**
     * 是否处在硬性冷却里喵。
     *
     * 人设节点跳过这一层——否则最重要的设定反而会因为"刚提过"而消失喵。
     */
    fun coolingDown(
        currentTurn: Int,
        lastRecalledTurn: Int,
        isPermanent: Boolean,
        cooldownTurns: Int = COOLDOWN_TURNS,
    ): Boolean {
        if (isPermanent || cooldownTurns <= 0) return false
        if (lastRecalledTurn <= NEVER_RECALLED_TURN) return false
        return currentTurn - lastRecalledTurn < cooldownTurns
    }

    /**
     * `P = min(0.5, base + 0.2 * E + 0.1 * V)`
     *
     * 情绪权重 E 的系数比鲜活度 V 大一倍：一件事"让人有感觉"比"记得清楚"喵
     * 更容易被顺口提起。永久节点直接返回 1.0喵。
     */
    fun recallProbability(
        emotionalWeight: Double,
        vividness: Double,
        isUserEdited: Boolean,
        isPermanent: Boolean,
    ): Double {
        if (isPermanent) return PERMANENT_PROBABILITY
        val base = if (isUserEdited) BASE_PROBABILITY_USER_EDITED else BASE_PROBABILITY
        val raw = base + EMOTION_COEFFICIENT * emotionalWeight + VIVIDNESS_COEFFICIENT * vividness
        return clamp(raw.coerceAtMost(MAX_PROBABILITY))
    }

    /** 新鲜写入的记忆统一从这里出发喵。 */
    fun initialVividness(): Double = 1.0

    /**
     * 距上次想起越久越"成熟"的反向新鲜度偏置喵。
     *
     * 冷却把最近 20 轮刚提过的记忆硬性挡住；冷却之外再多给一层薄弱的喵
     * anti-recency bias——很久没被想起的记忆在排序上略占先手，避免喵，
     * "最近提过的几件事把其他一切全盖死"喵。
     *
     * 返回 0..1；从未被唤醒过 = 1.0（最成熟），刚出冷却（~21 轮）= 约 0.5喵。
     */
    fun recencyFactor(lastRecalledTurn: Int, currentTurn: Int): Double {
        if (lastRecalledTurn <= NEVER_RECALLED_TURN) return 1.0
        val gap = (currentTurn - lastRecalledTurn).coerceAtLeast(0).toDouble()
        return (gap / 40.0).coerceAtMost(1.0)
    }

    /**
     * 多因子综合得分——决定记忆在候选池里的排序，不决定最终是否注入喵。
     *
     * 五个因子及其权重喵：
     * - 相似度 (0.30)：这条记忆与当前用户输入的相关程度喵
     * - 重要度 (0.25)：1..10，越高越优先喵
     * - 鲜活度 (0.20)：残余记忆度，越新鲜越优先喵
     * - 情绪分量 (0.15)：有感情的记忆略占便宜喵
     * - 间隔成熟度 (0.10)：久未被想起的记忆略优先，增加轮换喵
     *
     * 永久节点固定返回 2.0，永在最前喵。
     */
    fun compositeScore(
        similarity: Double,
        importance: Int,
        vividness: Double,
        emotionalWeight: Double,
        lastRecalledTurn: Int,
        currentTurn: Int,
        isPermanent: Boolean,
    ): Double {
        if (isPermanent) return 2.0
        val impNorm = importance.coerceIn(1, 10).toDouble() / 10.0  // 0.1..1.0
        val recency = recencyFactor(lastRecalledTurn, currentTurn)  // 0.0..1.0
        return similarity * 0.30 +
               impNorm * 0.25 +
               vividness * 0.20 +
               emotionalWeight * 0.15 +
               recency * 0.10
    }

    /**
     * 收进 0..1，并按百万分之一量化喵。
     *
     * 量化是为了让"同一状态的两次计算"拿到完全相等的值，便于比较与落库喵。
     * 精度取到 1e-6 而不是三位小数：`exp(-0.10 * 30) = 0.049787`喵
     * 会被 0.001 的量化抬成 0.050，正好撞上剪枝阈值 0.05喵，
     * 于是"30 天该淡到阈值以下"这条判断在边界上莫名其妙地不成立喵。
     */
    private fun clamp(value: Double): Double =
        (value.coerceIn(0.0, 1.0) * 1_000_000.0).roundToLong() / 1_000_000.0
}
