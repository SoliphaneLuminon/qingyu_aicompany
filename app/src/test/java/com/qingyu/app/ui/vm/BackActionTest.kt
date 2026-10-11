package com.qingyu.app.ui.vm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 返回键的优先级喵。
 *
 * 这几条规则决定"按一下返回会去哪"，错了不会崩、只会退错地方，
 * 所以把每种组合都钉住喵。
 */
class BackActionTest {

    @Test
    fun `有二级页面时先退二级页面`() {
        // 在对话里点「⋯」进聊天信息页：对话还开着，但最上面是聊天信息页喵。
        assertEquals(
            "该退回对话，而不是把整段对话关掉",
            BackAction.POP_SCREEN,
            decideBackAction(screen = AppScreen.ChatInfo, inChat = true, tabIsHome = true),
        )
    }

    @Test
    fun `二级页面优先于标签页`() {
        assertEquals(
            "在设置标签里进了模块页，返回该回设置而不是切到聊天标签",
            BackAction.POP_SCREEN,
            decideBackAction(screen = AppScreen.Modules, inChat = false, tabIsHome = false),
        )
    }

    @Test
    fun `没有二级页面时退对话覆盖层`() {
        assertEquals(
            "对话盖在标签栏之上，返回该退回列表",
            BackAction.CLOSE_CHAT,
            decideBackAction(screen = AppScreen.Home, inChat = true, tabIsHome = true),
        )
    }

    @Test
    fun `对话优先于标签页`() {
        // 从通讯录点「发消息」会同时进对话并把标签切到「聊天」，
        // 但万一停在别的标签，返回仍该先退对话喵。
        assertEquals(
            "该先退对话",
            BackAction.CLOSE_CHAT,
            decideBackAction(screen = AppScreen.Home, inChat = true, tabIsHome = false),
        )
    }

    @Test
    fun `非聊天标签先回聊天标签`() {
        assertEquals(
            "在通讯录按返回该回聊天列表",
            BackAction.GO_HOME_TAB,
            decideBackAction(screen = AppScreen.Home, inChat = false, tabIsHome = false),
        )
    }

    @Test
    fun `退无可退才退出应用`() {
        assertEquals(
            "在主界面且没有对话时才交给系统退出",
            BackAction.EXIT,
            decideBackAction(screen = AppScreen.Home, inChat = false, tabIsHome = true),
        )
    }
}
