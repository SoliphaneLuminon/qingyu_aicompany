package com.qingyu.app.core.memory

import kotlin.math.roundToLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 遗忘曲线与冷却隔离的回归测试喵。
 *
 * 这些数值是刻意定死的规则，一旦有人"顺手优化"了系数喵，
 * 角色的记忆节奏就会整体漂移，而且是那种"说不上哪里怪"的漂移——喵
 * 所以每个常量都钉一条用例喵。
 */
class MemoryDecayTest {

    @Test
    fun `普通记忆衰减速度是用户改过的两倍`() {
        assertEquals(0.05, MemoryDecay.LAMBDA_NORMAL, 0.0)
        assertEquals(0.025, MemoryDecay.LAMBDA_USER_EDITED, 0.0)
        assertEquals(0.0, MemoryDecay.LAMBDA_PERMANENT, 0.0)
    }

    @Test
    fun `人设节点不衰减`() {
        val before = 0.9
        val after = MemoryDecay.decayed(
            vividness = before,
            deltaTurns = 500,
            recallCount = 0,
            lambda = MemoryDecay.lambdaFor(isUserEdited = false, isPermanent = true),
        )
        assertEquals(before, after, 0.0)
    }

    @Test
    fun `衰减公式按 R 加一放慢`() {
        val lambda = MemoryDecay.LAMBDA_NORMAL
        // R = 0：分母为 1喵
        val first = MemoryDecay.decayed(1.0, deltaTurns = 10, recallCount = 0, lambda = lambda)
        // R = 9：分母为 10，同样过了 10 轮但衰减明显更慢喵
        val practiced = MemoryDecay.decayed(1.0, deltaTurns = 10, recallCount = 9, lambda = lambda)
        assertTrue(practiced > first)
    }

    @Test
    fun `没有经过时间就不衰减`() {
        val after = MemoryDecay.decayed(0.8, deltaTurns = 0, recallCount = 3, lambda = MemoryDecay.LAMBDA_NORMAL)
        assertEquals(0.8, after, 0.0)
    }

    @Test
    fun `鲜活度被夹在 0 到 1 之间且量化到百万分之一`() {
        val after = MemoryDecay.decayed(0.123456, deltaTurns = 1, recallCount = 0, lambda = MemoryDecay.LAMBDA_NORMAL)
        // 量化精度是 1e-6：既保证同一状态两次计算得到完全相等的值喵，
        // 又不会像三位小数那样把 0.049787 抬成 0.050 而在剪枝阈值上改变判断喵。
        val quantized = (after * 1_000_000.0).roundToLong() / 1_000_000.0
        assertEquals(quantized, after, 0.0)
        assertTrue(after in 0.0..1.0)
    }

    @Test
    fun `冷却期内被屏蔽`() {
        assertTrue(MemoryDecay.coolingDown(currentTurn = 25, lastRecalledTurn = 10, isPermanent = false))
    }

    @Test
    fun `刚过冷却期就放行`() {
        assertFalse(MemoryDecay.coolingDown(currentTurn = 30, lastRecalledTurn = 10, isPermanent = false))
    }

    @Test
    fun `人设节点跳过冷却`() {
        assertFalse(MemoryDecay.coolingDown(currentTurn = 11, lastRecalledTurn = 10, isPermanent = true))
    }

    @Test
    fun `从未被唤醒过的记忆不冷却`() {
        assertFalse(
            MemoryDecay.coolingDown(
                currentTurn = 5,
                lastRecalledTurn = MemoryDecay.NEVER_RECALLED_TURN,
                isPermanent = false,
            ),
        )
    }

    @Test
    fun `人设节点百分百唤醒`() {
        assertEquals(1.0, MemoryDecay.recallProbability(0.0, 0.0, isUserEdited = false, isPermanent = true), 0.0)
    }

    @Test
    fun `概率上限是 0_5`() {
        val p = MemoryDecay.recallProbability(
            emotionalWeight = 1.0,
            vividness = 1.0,
            isUserEdited = true,
            isPermanent = false,
        )
        assertEquals(0.5, p, 0.0001)
    }

    @Test
    fun `概率公式等于基数加情绪与鲜活度`() {
        val p = MemoryDecay.recallProbability(
            emotionalWeight = 0.5,
            vividness = 0.5,
            isUserEdited = false,
            isPermanent = false,
        )
        // 0.1 + 0.2 * 0.5 + 0.1 * 0.5 = 0.25
        assertEquals(0.25, p, 0.0001)
    }

    // --------------------------------------------------------------- 按天衰减

    @Test
    fun `按天衰减 3_5 天后鲜活度约 0_705`() {
        val after = MemoryDecay.decayedByDays(
            vividness = 1.0,
            deltaDays = 3.5,
            recallCount = 0,
            lambda = MemoryDecay.LAMBDA_PER_DAY_NORMAL,
        )
        // exp(-0.10 * 3.5 / 1) = exp(-0.35) ≈ 0.705
        assertEquals(0.705, after, 0.001)
    }

    @Test
    fun `按天衰减没经过时间就不衰减`() {
        val after = MemoryDecay.decayedByDays(
            vividness = 0.8,
            deltaDays = 0.0,
            recallCount = 3,
            lambda = MemoryDecay.LAMBDA_PER_DAY_NORMAL,
        )
        assertEquals(0.8, after, 0.0)
    }

    @Test
    fun `按天衰减 λ 为零永久节点不衰减`() {
        val before = 0.9
        val after = MemoryDecay.decayedByDays(
            vividness = before,
            deltaDays = 365.0,
            recallCount = 0,
            lambda = MemoryDecay.lambdaForDays(isUserEdited = false, isPermanent = true),
        )
        assertEquals(before, after, 0.0)
    }

    @Test
    fun `按天衰减 R 越大衰减越慢`() {
        val lambda = MemoryDecay.LAMBDA_PER_DAY_NORMAL
        // R = 0, resistance = 1
        val first = MemoryDecay.decayedByDays(vividness = 1.0, deltaDays = 7.0, recallCount = 0, lambda = lambda)
        // R = 9, resistance = 10，同过 7 天但衰减明显更慢喵
        val practiced = MemoryDecay.decayedByDays(vividness = 1.0, deltaDays = 7.0, recallCount = 9, lambda = lambda)
        assertTrue(practiced > first)
    }

    @Test
    fun `按天衰减 lambda_day_normal 是 day_normal 的两倍`() {
        // 编辑过的记忆衰减更慢喵
        assertEquals(MemoryDecay.LAMBDA_PER_DAY_NORMAL, MemoryDecay.LAMBDA_PER_DAY_USER_EDITED * 2.0, 0.0001)
    }

    // --------------------------------------------------------------- 剪枝阈值

    @Test
    fun `剪枝阈值 0_05 远低于唤醒阈值 0_2`() {
        assertTrue(MemoryDecay.PRUNE_VIVIDNESS_THRESHOLD < MemoryDecay.VIVIDNESS_FLOOR)
    }

    @Test
    fun `苏醒后鲜活度在阈值之上`() {
        assertTrue(MemoryDecay.REAWAKEN_VIVIDNESS > MemoryDecay.VIVIDNESS_FLOOR)
        assertTrue(MemoryDecay.REAWAKEN_VIVIDNESS < 1.0)
    }

    @Test
    fun `剪枝阈值不会把鲜活度正常的记忆误判`() {
        val v = MemoryDecay.decayedByDays(1.0, 0.5, 0, MemoryDecay.LAMBDA_PER_DAY_NORMAL)
        assertTrue(v > MemoryDecay.PRUNE_VIVIDNESS_THRESHOLD)
    }

    @Test
    fun `长时间不碰的记忆会跌到剪枝阈值以下`() {
        // 30 天不碰，R=0：半衰期 7 天左右，30 天已经很淡了喵
        val v = MemoryDecay.decayedByDays(1.0, 30.0, 0, MemoryDecay.LAMBDA_PER_DAY_NORMAL)
        assertTrue(v < MemoryDecay.PRUNE_VIVIDNESS_THRESHOLD)
    }

    // --------------------------------------------------------------- 多因子综合得分

    @Test
    fun `recencyFactor 从未召回时为 1_0`() {
        assertEquals(1.0, MemoryDecay.recencyFactor(MemoryDecay.NEVER_RECALLED_TURN, 100), 0.0)
    }

    @Test
    fun `recencyFactor 刚出冷却时约 0_5`() {
        // lastRecalledTurn=1, currentTurn=22 → gap=21 → 21/40 ≈ 0.525
        val factor = MemoryDecay.recencyFactor(lastRecalledTurn = 1, currentTurn = 22)
        assertEquals(0.525, factor, 0.001)
    }

    @Test
    fun `recencyFactor 冷却内也可以被算但由冷却机制屏蔽`() {
        val factor = MemoryDecay.recencyFactor(lastRecalledTurn = 5, currentTurn = 10)
        // gap=5, 5/40 = 0.125 — 数值正确，但冷却机制会提前挡住喵
        assertTrue(factor < 0.2)
    }

    @Test
    fun `compositeScore 永久节点返回 2_0 永在最前`() {
        val score = MemoryDecay.compositeScore(
            similarity = 0.0, importance = 1, vividness = 0.0, emotionalWeight = 0.0,
            lastRecalledTurn = 0, currentTurn = 1, isPermanent = true,
        )
        assertEquals(2.0, score, 0.0)
    }

    @Test
    fun `compositeScore 满分记忆 ≈ 1_0`() {
        val score = MemoryDecay.compositeScore(
            similarity = 1.0, importance = 10, vividness = 1.0, emotionalWeight = 1.0,
            lastRecalledTurn = MemoryDecay.NEVER_RECALLED_TURN, currentTurn = 100,
            isPermanent = false,
        )
        // 0.30*1.0 + 0.25*1.0 + 0.20*1.0 + 0.15*1.0 + 0.10*1.0 = 1.0
        assertEquals(1.0, score, 0.001)
    }

    @Test
    fun `compositeScore 高相似低重要 vs 低相似高重要`() {
        val highSimLowImp = MemoryDecay.compositeScore(
            similarity = 0.9, importance = 2, vividness = 0.5, emotionalWeight = 0.3,
            lastRecalledTurn = 10, currentTurn = 30, isPermanent = false,
        )
        val lowSimHighImp = MemoryDecay.compositeScore(
            similarity = 0.3, importance = 9, vividness = 0.5, emotionalWeight = 0.3,
            lastRecalledTurn = 10, currentTurn = 30, isPermanent = false,
        )
        // 高相似低重要: 0.30*0.9 + 0.25*0.2 + 0.20*0.5 + 0.15*0.3 + 0.10*0.5 = 0.27+0.05+0.10+0.045+0.05 = 0.515喵
        // 低相似高重要: 0.30*0.3 + 0.25*0.9 + 0.20*0.5 + 0.15*0.3 + 0.10*0.5 = 0.09+0.225+0.10+0.045+0.05 = 0.51喵
        // 高相似略胜，但差距很小——这正是设计意图：重要度可以部分补偿相似度的不足喵
        assertTrue(highSimLowImp > lowSimHighImp)
        assertTrue(Math.abs(highSimLowImp - lowSimHighImp) < 0.02)
    }
}
