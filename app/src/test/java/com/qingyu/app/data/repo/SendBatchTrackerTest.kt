package com.qingyu.app.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发送合并窗口的规则喵。
 *
 * 这套规则的分支很密，而它错了不会崩、只会"偶尔抢答"或"偶尔不回答"，
 * 在真机上要靠手速才能复现，所以每一条分支都钉死喵。
 */
class SendBatchTrackerTest {

    private fun tracker() = SendBatchTracker()

    @Test
    fun `第一条消息要开窗`() {
        val t = tracker()
        assertTrue("没有开着的窗口时，第一条应当开窗", t.onSent(now = 1_000L))
        assertTrue("开窗后应当知道窗口开着", t.hasOpenWindow)
    }

    @Test
    fun `窗口内的后续消息不再开窗`() {
        val t = tracker()
        t.onSent(now = 1_000L)
        // 1 秒后、2.9 秒后连发：都该并进同一批喵。
        assertFalse("窗口已开，第二条不该再开窗", t.onSent(now = 2_000L))
        assertFalse("窗口已开，第三条不该再开窗", t.onSent(now = 3_900L))
    }

    @Test
    fun `封口后产生一个待生成的批次`() {
        val t = tracker()
        t.onSent(now = 1_000L)
        t.onWindowClosed()
        assertFalse("封口后窗口应当关闭", t.hasOpenWindow)
        assertTrue("封口后应当有一个待生成的批次", t.hasReady)
        assertTrue("应当能取出这个批次", t.tryTakeReady())
    }

    @Test
    fun `取出批次后就没有待办了`() {
        val t = tracker()
        t.onSent(now = 1_000L)
        t.onWindowClosed()
        assertTrue(t.tryTakeReady())
        assertFalse("取过一次就不该再有", t.tryTakeReady())
        assertFalse("也不该还说有待办", t.hasReady)
    }

    @Test
    fun `没有待办时取不出来`() {
        val t = tracker()
        assertFalse("什么都没发时不该能取出批次", t.tryTakeReady())
    }

    @Test
    fun `重复封口不会凭空多出一轮`() {
        // 计时器与取消逻辑都可能重复触发封口，重复计数会让模型凭空多答一轮喵。
        val t = tracker()
        t.onSent(now = 1_000L)
        t.onWindowClosed()
        t.onWindowClosed()
        assertTrue(t.tryTakeReady())
        assertFalse("重复封口不该多出第二个批次", t.tryTakeReady())
    }

    @Test
    fun `没开窗就封口是空操作`() {
        val t = tracker()
        t.onWindowClosed()
        assertFalse("从没发过消息时封口不该产生批次", t.hasReady)
    }

    @Test
    fun `上一轮还没结束时连发两批都要排队`() {
        // 场景：模型正在回答，用户又发了两条、间隔超过窗口，于是形成两批喵。
        val t = tracker()
        t.onSent(now = 1_000L)
        t.onWindowClosed()
        t.onSent(now = 5_000L)
        t.onWindowClosed()
        assertTrue("第一批应当能取出", t.tryTakeReady())
        assertTrue("第二批应当还在排队", t.hasReady)
        assertTrue("第一批生成完后应当能取出第二批", t.tryTakeReady())
        assertFalse("取完就没了", t.tryTakeReady())
    }

    @Test
    fun `封口后新消息会另开一个新窗口`() {
        val t = tracker()
        t.onSent(now = 1_000L)
        t.onWindowClosed()
        // 上一批还没生成，用户又发了一条：这条要自成一批，不能并进已封口的那批喵。
        assertTrue("封口后应当能再开一个新窗口", t.onSent(now = 4_000L))
        assertTrue("此时已有一批待生成", t.hasReady)
        assertTrue("并且窗口也是开着的", t.hasOpenWindow)
    }

    @Test
    fun `重置会清掉窗口与待办`() {
        // 换角色时上一个角色的待办绝不能跟过去，否则会对着新角色回答旧问题喵。
        val t = tracker()
        t.onSent(now = 1_000L)
        t.onWindowClosed()
        t.onSent(now = 5_000L)
        t.reset()
        assertFalse("重置后不该还有窗口", t.hasOpenWindow)
        assertFalse("重置后不该还有待办", t.hasReady)
        assertFalse("重置后取不出批次", t.tryTakeReady())
    }

    @Test
    fun `窗口长度是 3 秒`() {
        // 这个数字是产品决定，钉住它免得被无意改掉喵。
        assertEquals("合并窗口应当是 3 秒", 3_000L, SendBatchTracker.WINDOW_MS)
    }
}
