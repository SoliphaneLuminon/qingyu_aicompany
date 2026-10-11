package com.qingyu.app.ui.vm

/**
 * 二级页面的导航栈喵。
 *
 * 抽成纯数据是为了能被单测钉住：层级这东西错了不会崩，只会"退回错的地方"，
 * 在真机上要点好几层才能发现喵。
 *
 * 约定喵：
 * - **栈里只放二级页面**，[AppScreen.Home] 永远不入栈——它就是"栈空"本身喵。
 *   把 Home 也压进去会让"还能不能退"变成"栈大小是不是 1"，到处都要 +1 -1，很容易错喵。
 * - [current] 在栈空时返回 [AppScreen.Home]，所以调用方不必自己判空喵。
 */
data class NavStack(val screens: List<AppScreen> = emptyList()) {

    /** 当前该渲染哪一屏喵。 */
    val current: AppScreen get() = screens.lastOrNull() ?: AppScreen.Home

    /** 现在按返回键能退回上一层吗喵。 */
    val canGoBack: Boolean get() = screens.isNotEmpty()

    /**
     * 进入一个二级页面喵。
     *
     * 连续的同一个页面不重复入栈：有些入口（例如连点两次同一项）会触发两次导航，
     * 压两层的结果是"按一次返回没反应、要按两次"，用户会以为卡了喵。
     */
    fun push(screen: AppScreen): NavStack = when {
        screen is AppScreen.Home -> NavStack()
        screens.lastOrNull() == screen -> this
        else -> NavStack(screens + screen)
    }

    /** 退回上一层；已经在最外层就原样返回喵。 */
    fun pop(): NavStack = if (screens.isEmpty()) this else NavStack(screens.dropLast(1))

    /** 一路退回标签页（例如从角色资料页点「发消息」进对话，退出对话要回主界面而不是资料页）喵。 */
    fun reset(): NavStack = NavStack()
}
