package com.qingyu.app.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回复前的等待规则喵。
 *
 * 这套规则错了会变成"永远不回"或"根本不等"，两种都很难在真机上复现，
 * 所以把每个分支都钉住喵。
 */
class ReplyGateTest {

    private val sentAt = 1_000_000L

    /** 基础窗口刚结束的时刻喵。 */
    private val baseEnd = sentAt + ReplyGate.BASE_WAIT_MS

    @Test
    fun `基础窗口内永远不回`() {
        // 这是最要紧的一条：这段时间是用来合并连发消息的，
        // 输入框开没开都得等满，否则发完一条就被抢答喵。
        assertFalse(
            "还没到基础窗口就该等",
            ReplyGate.isReady(baseEnd - 1L, sentAt, inputOpenedAt = null, inputFocused = false),
        )
        assertFalse(
            "输入框开着也一样要等满基础窗口",
            ReplyGate.isReady(baseEnd - 1L, sentAt, inputOpenedAt = null, inputFocused = true),
        )
    }

    @Test
    fun `没重新点开输入框时到点就回`() {
        assertTrue(
            "到点该回",
            ReplyGate.isReady(baseEnd, sentAt, inputOpenedAt = null, inputFocused = false),
        )
        assertTrue(
            "输入框一直开着、但从没重新点开，也按基础窗口回",
            ReplyGate.isReady(baseEnd, sentAt, inputOpenedAt = null, inputFocused = true),
        )
    }

    @Test
    fun `重新点开输入框后推迟到点开之后 5 秒`() {
        // 发完 1 秒后又点开输入框喵。
        val openedAt = sentAt + 1_000L
        assertFalse(
            "基础窗口结束也不该回：他刚点开输入框",
            ReplyGate.isReady(baseEnd, sentAt, inputOpenedAt = openedAt, inputFocused = true),
        )
        assertFalse(
            "还差一点",
            ReplyGate.isReady(openedAt + ReplyGate.EXTENDED_WAIT_MS - 1L, sentAt, openedAt, inputFocused = true),
        )
        assertTrue(
            "点开满 5 秒就该回",
            ReplyGate.isReady(openedAt + ReplyGate.EXTENDED_WAIT_MS, sentAt, openedAt, inputFocused = true),
        )
    }

    @Test
    fun `用户一直打字也不会永远不回`() {
        // 到点就回，不等他打完——否则碰上一直在改字的人就永远不回了喵。
        val openedAt = sentAt + 500L
        assertTrue(
            "超过点开后 5 秒必须回",
            ReplyGate.isReady(
                openedAt + ReplyGate.EXTENDED_WAIT_MS + 1L, sentAt, openedAt, inputFocused = true,
            ),
        )
    }

    @Test
    fun `点开过又把输入框关掉就立刻回`() {
        // 关掉＝放弃继续打，不该再让他干等喵。
        val openedAt = sentAt + 1_000L
        assertTrue(
            "基础窗口已过且输入框关着，该立刻回",
            ReplyGate.isReady(baseEnd, sentAt, openedAt, inputFocused = false),
        )
    }

    @Test
    fun `点开时刻早于基础窗口结束时不会提前回`() {
        // 点开得很早（例如发完立刻又点），基础窗口仍要先等满喵。
        val openedAt = sentAt + 100L
        assertFalse(
            "基础窗口没满就不该回",
            ReplyGate.isReady(baseEnd - 1L, sentAt, openedAt, inputFocused = true),
        )
    }

    @Test
    fun `分段醒来不会一口气睡过基础窗口`() {
        val delay = ReplyGate.nextCheckDelayMs(
            now = sentAt,
            sentAt = sentAt,
            inputOpenedAt = null,
            inputFocused = true,
        )
        assertEquals("应当正好睡到基础窗口结束", ReplyGate.BASE_WAIT_MS, delay)
    }

    @Test
    fun `已经过了所有时刻就不再等`() {
        val delay = ReplyGate.nextCheckDelayMs(
            now = baseEnd + ReplyGate.EXTENDED_WAIT_MS + 1L,
            sentAt = sentAt,
            inputOpenedAt = sentAt + 100L,
            inputFocused = true,
        )
        assertEquals("都过去了就不该再等", 0L, delay)
    }

    @Test
    fun `输入框关着时只睡到基础窗口结束`() {
        // 关着的输入框只可能被"重新点开"改变，而那要先过基础窗口，
        // 所以不必频繁轮询喵。
        val delay = ReplyGate.nextCheckDelayMs(
            now = sentAt,
            sentAt = sentAt,
            inputOpenedAt = sentAt + 100L,
            inputFocused = false,
        )
        assertEquals("只需睡到基础窗口结束", ReplyGate.BASE_WAIT_MS, delay)
    }

    @Test
    fun `两个时长就是产品定的那两个数`() {
        assertEquals("基础等待是 3 秒", 3_000L, ReplyGate.BASE_WAIT_MS)
        assertEquals("点开输入框后再等 5 秒", 5_000L, ReplyGate.EXTENDED_WAIT_MS)
    }
}
