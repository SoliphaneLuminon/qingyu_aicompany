package com.qingyu.app.ui.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 返回键的层级规则喵。
 *
 * 层级错了不会崩，只会"退回错的地方"——在真机上要点好几层才发现，
 * 所以每一条规则都钉住喵。
 */
class NavStackTest {

    @Test
    fun `空栈就是主界面`() {
        val stack = NavStack()
        assertEquals("空栈应当停在主界面", AppScreen.Home, stack.current)
        assertFalse("空栈时不该能再退", stack.canGoBack)
    }

    @Test
    fun `压入二级页后当前就是它`() {
        val stack = NavStack().push(AppScreen.Modules)
        assertEquals("当前应当是刚压入的那一页", AppScreen.Modules, stack.current)
        assertTrue("压入后应当能退回", stack.canGoBack)
    }

    @Test
    fun `压入主界面等于清空栈`() {
        // Home 是"栈空"本身，不该作为一层存在，否则判断"能不能退"要处处 +1 -1喵。
        val stack = NavStack().push(AppScreen.Modules).push(AppScreen.Home)
        assertEquals("压入主界面后应当回到主界面", AppScreen.Home, stack.current)
        assertFalse("并且不该还能退", stack.canGoBack)
    }

    @Test
    fun `连续压入同一页不会压两层`() {
        // 连点两次同一项会触发两次导航；压两层的结果是"按一次返回没反应"喵。
        val stack = NavStack().push(AppScreen.Modules).push(AppScreen.Modules)
        assertEquals("只应当有一层", 1, stack.screens.size)
        assertEquals("当前仍是这一页", AppScreen.Modules, stack.current)
    }

    @Test
    fun `参数不同的同一类页面会各占一层`() {
        // 从甲的资料页进乙的资料页是两次真实导航，必须能逐层退回喵。
        val stack = NavStack()
            .push(AppScreen.PersonaDetail(1L))
            .push(AppScreen.PersonaDetail(2L))
        assertEquals("两个不同角色应当各占一层", 2, stack.screens.size)
        assertEquals("当前是乙", AppScreen.PersonaDetail(2L), stack.current)
        assertEquals("退回一层应当是甲", AppScreen.PersonaDetail(1L), stack.pop().current)
    }

    @Test
    fun `逐层退回`() {
        val stack = NavStack()
            .push(AppScreen.Modules)
            .push(AppScreen.Debug)
            .push(AppScreen.ChatInfo)
        assertEquals("当前是最后一层", AppScreen.ChatInfo, stack.current)
        assertEquals("退一层到调试", AppScreen.Debug, stack.pop().current)
        assertEquals("再退一层到模块", AppScreen.Modules, stack.pop().pop().current)
        assertEquals("退到底就是主界面", AppScreen.Home, stack.pop().pop().pop().current)
    }

    @Test
    fun `空栈再退还是空栈`() {
        // 返回键在最后一层按下去应当交给系统去退出应用，而不是崩或死循环喵。
        val stack = NavStack().pop()
        assertEquals("空栈再退还是主界面", AppScreen.Home, stack.current)
        assertFalse("并且仍然不该能退", stack.canGoBack)
    }

    @Test
    fun `一路退回标签页`() {
        // 从角色资料页点「发消息」进对话后，退出对话该回主界面而不是回资料页喵。
        val stack = NavStack()
            .push(AppScreen.PersonaDetail(1L))
            .push(AppScreen.ChatInfo)
        val reset = stack.reset()
        assertEquals("清空后应当回到主界面", AppScreen.Home, reset.current)
        assertFalse("清空后不该还能退", reset.canGoBack)
    }

    @Test
    fun `典型的钻取路径`() {
        // 通讯录 → 角色资料 → 聊天信息 → 角色资料（从名片点进去）
        val stack = NavStack()
            .push(AppScreen.PersonaDetail(7L))
            .push(AppScreen.ChatInfo)
            .push(AppScreen.PersonaDetail(7L))
        assertEquals("应当有三层", 3, stack.screens.size)
        assertEquals("退一层回聊天信息", AppScreen.ChatInfo, stack.pop().current)
        assertEquals("再退一层回角色资料", AppScreen.PersonaDetail(7L), stack.pop().pop().current)
    }

    @Test
    fun `push 与 pop 不会改动原对象`() {
        // 不可变，避免调用方拿着旧引用看到被改过的栈喵。
        val base = NavStack().push(AppScreen.Modules)
        val pushed = base.push(AppScreen.Debug)
        val popped = base.pop()
        assertEquals("原栈应当保持一层", 1, base.screens.size)
        assertEquals("push 产生新栈", 2, pushed.screens.size)
        assertEquals("pop 也产生新栈", 0, popped.screens.size)
    }
}
