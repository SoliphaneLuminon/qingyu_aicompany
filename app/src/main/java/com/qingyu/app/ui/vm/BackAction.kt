package com.qingyu.app.ui.vm

/**
 * 按一次返回键该做什么喵。
 *
 * 抽成纯函数是因为"层级"错了不会崩，只会退错地方——而且它取决于三处状态的组合
 * （当前在哪一屏、对话覆盖层开没开、停在哪个标签页），在真机上要一层层点才能验喵。
 */
enum class BackAction {
    /** 从二级页面退回上一层喵。 */
    POP_SCREEN,

    /** 关掉盖在最上面的对话覆盖层喵。 */
    CLOSE_CHAT,

    /** 从别的标签页回到「聊天」标签喵。 */
    GO_HOME_TAB,

    /** 已经无处可退，交给系统退出应用喵。 */
    EXIT,
}

/**
 * 返回键的优先级喵。
 *
 * 顺序不是随便定的，它对应界面上"盖在最上面的是什么"喵：
 *
 * 1. **二级页面优先于对话覆盖层**喵。在对话里点「⋯」进聊天信息页时，
 *    对话还开着（`persona` 非空），但屏幕上最上面的是聊天信息页——
 *    这时按返回该退回对话，而不是把整段对话关掉喵。
 * 2. **对话覆盖层优先于标签页**喵。对话是盖在整个标签栏之上的一层，
 *    退回列表比切换标签更符合"退一层"的直觉喵。
 * 3. **非「聊天」标签先回「聊天」**喵。微信里在通讯录按返回是回聊天列表，
 *    不是退出应用喵。
 * 4. 都退无可退才交给系统退出喵。
 *
 * @param screen 当前二级页面（[AppScreen.Home] 表示没有二级页面）喵
 * @param inChat 对话覆盖层是否开着喵
 * @param tabIsHome 当前是否停在「聊天」标签喵
 */
fun decideBackAction(
    screen: AppScreen,
    inChat: Boolean,
    tabIsHome: Boolean,
): BackAction = when {
    screen !is AppScreen.Home -> BackAction.POP_SCREEN
    inChat -> BackAction.CLOSE_CHAT
    !tabIsHome -> BackAction.GO_HOME_TAB
    else -> BackAction.EXIT
}
