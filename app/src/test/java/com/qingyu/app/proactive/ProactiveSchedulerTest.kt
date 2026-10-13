package com.qingyu.app.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「让角色主动找你」的调度参数喵。
 *
 * 这里钉的都是**数值边界**——它们错了不会崩、只会让功能悄悄变得不可用喵：
 * 间隔算错会让角色每隔几分钟骚扰一次（白烧 token），
 * 或让闹钟永远不到点喵。
 */
class ProactiveSchedulerTest {

    @Test
    fun `间隔下限是十五分钟`() {
        // 用户把滑块拉到最小也不该几分钟弹一次：那是骚扰，
        // 而且每次都要真发一次请求、花他自己的 token喵。
        assertEquals(15, ProactiveScheduler.MIN_INTERVAL_MINUTES)
    }

    @Test
    fun `动作名带包名前缀`() {
        // 广播动作必须带包名喵：不带的话可能撞上别的应用的同名动作，
        // 而这是个 exported=false 的接收者，撞上了排查起来会很莫名喵。
        assertTrue(
            "动作名应当以包名开头：${ProactiveScheduler.ACTION_PROACTIVE}",
            ProactiveScheduler.ACTION_PROACTIVE.startsWith("com.qingyu.app."),
        )
    }
}
