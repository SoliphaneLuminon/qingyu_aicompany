package com.qingyu.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qingyu.app.AppContainer
import com.qingyu.app.ui.chat.ChatScreen
import com.qingyu.app.ui.debug.DebugScreen
import com.qingyu.app.ui.favorites.FavoritesScreen
import com.qingyu.app.ui.home.HomeScreen
import com.qingyu.app.ui.memory.MemoryScreen
import com.qingyu.app.ui.modules.ModulesScreen
import com.qingyu.app.ui.search.SearchScreen
import com.qingyu.app.ui.settings.SettingsScreen
import com.qingyu.app.ui.vm.AppScreen
import com.qingyu.app.ui.vm.ChatViewModel
import com.qingyu.app.ui.vm.PersonasViewModel
import com.qingyu.app.ui.vm.ShellViewModel

/** 主界面标签喵。第一个是角色列表，也就是打开应用看到的那一屏喵。 */
enum class HomeTab(val label: String) {
    HOME("聊天"),
    FAVORITES("收藏"),
    MEMORY("记忆"),
    SETTINGS("设置"),
}

/**
 * 读取软键盘（IME）当前占据的底部高度；0 表示键盘收起喵。
 *
 * 只认 Compose 官方的 [WindowInsets.ime]：它由 Compose 自己的 inset 分发驱动喵。
 * 不要在这里自己挂 `ViewCompat.setOnApplyWindowInsetsListener` —— 那会与 Compose喵
 * 内部的监听互相覆盖，谁后注册谁生效，取到 0 值（键盘弹出但输入栏不动就是这样来的喵）。
 */
@Composable
private fun rememberImeBottomInset(): Dp =
    with(LocalDensity.current) { WindowInsets.ime.getBottom(this).toDp() }

/**
 * 二级页面（搜索 / 模块 / 调试）的入场动画喵。
 *
 * 这几页在导航上是"提前 return"的独立分支，没有常驻容器可以承载进出场，
 * 所以只做入场：淡入 + 一点点向右推进，给出"进入了新一屏"的方向感喵。
 * 位移刻意压得很小（宽度的 1/14），太大反而显得卡顿喵。
 */
@Composable
private fun ScreenEnter(content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(durationMillis = 180)) +
            slideInHorizontally(
                animationSpec = tween(durationMillis = 240),
                initialOffsetX = { width -> width / 14 },
            ),
        exit = fadeOut(animationSpec = tween(durationMillis = 120)),
    ) {
        content()
    }
}

/**
 * 应用外壳喵。
 *
 * 导航结构照微信来：**首页就是角色列表**，点一个角色进入它那段对话，
 * 返回键回到列表喵。一个角色固定对应一段对话，所以没有"新建对话 / 切换对话"这一层喵。
 *
 * 导航用状态机而不是 Navigation 组件：全局只有 4 个底部标签 + 3 个二级页，喵
 * 用状态表达比维护 nav graph 更省心喵。
 */
@Composable
fun QingYuAppRoot(container: AppContainer) {
    val application = LocalContext.current.applicationContext as android.app.Application
    val chatViewModel: ChatViewModel = viewModel(factory = ChatViewModel.factory(application))
    val shellViewModel: ShellViewModel = viewModel(factory = ShellViewModel.factory(application))
    // 角色编辑器由首页与角色页共用，实例提到这里，避免两处各持一份列表状态喵。
    val personaEditor: PersonasViewModel = viewModel(factory = PersonasViewModel.factory(application))

    val chatState by chatViewModel.state.collectAsStateWithLifecycle()
    val shellState by shellViewModel.state.collectAsStateWithLifecycle()
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val speechState by container.speech.state.collectAsStateWithLifecycle()
    val speaking = speechState.speaking || speechState.preparing

    var tab by remember { mutableStateOf(HomeTab.HOME) }

    // 键盘高度在这里统一测量一次，向下传给聊天页；键盘可见时底部标签栏让位喵。
    val imeBottom = rememberImeBottomInset()
    val imeVisible = imeBottom > 0.dp

    // 当前是否停在某个角色的对话里（首页 → 对话 是同一标签下的一层）喵。
    val inChat = chatState.persona != null

    /**
     * 收藏页与记忆页要按角色取数据，但它们不一定是在对话里打开的喵。
     *
     * 优先级：正在聊的这个角色 → 上次聊过的（还在的话）→ 列表第一个 → 0喵。
     * 以前 bootstrap 会自动打开上次的角色，所以这两个页面总有值；
     * 改成"首页是角色列表"之后不再自动进对话，必须在这里补上回退，
     * 否则一进来记忆页是空的，看起来像坏了喵。
     */
    val activePersonaId = chatState.persona?.id
        ?: settings.lastPersonaId.takeIf { id -> chatState.personas.any { it.id == id } }
        ?: chatState.personas.firstOrNull()?.id
        ?: 0L

    // 系统返回键：在对话里就退回列表，而不是直接退出应用喵。
    BackHandler(enabled = inChat) { chatViewModel.closeChat() }

    // 回到列表这一屏时重算一次列表，保证预览与排序是最新的喵。
    LaunchedEffect(inChat, tab) {
        if (!inChat || tab == HomeTab.HOME) shellViewModel.refresh()
    }

    val back: () -> Unit = { shellViewModel.back() }

    when (val screen = shellState.screen) {
        is AppScreen.Search -> {
            ScreenEnter {
                SearchScreen(
                    conversationId = screen.conversationId.takeIf { it > 0L }
                        ?: (chatState.conversation?.id ?: 0L),
                    onBack = back,
                )
            }
            return
        }

        is AppScreen.Modules -> {
            ScreenEnter { ModulesScreen(onBack = back) }
            return
        }

        is AppScreen.Debug -> {
            ScreenEnter { DebugScreen(state = chatState, onBack = back) }
            return
        }

        AppScreen.Home -> Unit
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        bottomBar = {
            // 键盘弹出时收起底部标签栏：它本来就被键盘完全遮住喵，
            // 但 Scaffold 仍会为它预留约 80dp。聊天页的输入栏上移了「整个键盘高度喵」，
            // 两边一叠加，输入栏就会停在键盘上方约一个标签栏高的位置——那条空白正是它喵。
            if (!imeVisible) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    HomeTab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = {
                                // 从别的标签切回"聊天"时，回到角色列表而不是上次那段对话喵。
                                if (item == HomeTab.HOME && tab != HomeTab.HOME) chatViewModel.closeChat()
                                tab = item
                            },
                            icon = {
                                Icon(
                                    imageVector = when (item) {
                                        HomeTab.HOME -> Icons.Outlined.ChatBubbleOutline
                                        HomeTab.FAVORITES -> Icons.Outlined.FavoriteBorder
                                        HomeTab.MEMORY -> Icons.Outlined.Psychology
                                        HomeTab.SETTINGS -> Icons.Outlined.Settings
                                    },
                                    contentDescription = item.label,
                                )
                            },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        // 外壳已经为底部让出的高度（标签栏 / 系统导航栏），交给聊天页做减法喵。
        val reservedBottom = innerPadding.calculateBottomPadding()
        // 聊天页自带输入栏与背景色，不吃外壳的 innerPadding（它自己算底部留白）喵。
        if (tab == HomeTab.HOME && inChat) {
            ChatScreen(
                state = chatState,
                viewModel = chatViewModel,
                speaking = speaking,
                showReasoning = settings.showReasoning,
                onBack = { chatViewModel.closeChat() },
                onOpenSettings = { tab = HomeTab.SETTINGS },
                onOpenSearch = {
                    shellViewModel.navigate(AppScreen.Search(chatState.conversation?.id ?: 0L))
                },
                onOpenDebug = { shellViewModel.navigate(AppScreen.Debug) },
                onOpenModules = { shellViewModel.navigate(AppScreen.Modules) },
                imeBottomInset = imeBottom,
                reservedBottomInset = reservedBottom,
            )
        } else {
            Box(modifier = Modifier.padding(innerPadding)) {
                // 底部标签切换时做一次交叉淡入淡出：不引入滑动是因为四个页面宽度都占满，
                // 横向位移反而会让人以为可以左右滑，淡入更符合"换了一屏"的直觉喵。
                Crossfade(
                    targetState = tab,
                    animationSpec = tween(durationMillis = 220),
                    label = "homeTab",
                ) { current ->
                    when (current) {
                        HomeTab.HOME -> HomeScreen(
                            state = chatState,
                            viewModel = chatViewModel,
                            personaEditor = personaEditor,
                            onOpenChat = { persona -> chatViewModel.openPersona(persona) },
                            onOpenSettings = { tab = HomeTab.SETTINGS },
                        )

                        HomeTab.FAVORITES -> FavoritesScreen(
                            container = container,
                            personaId = activePersonaId,
                        )

                        HomeTab.MEMORY -> MemoryScreen(
                            personaId = activePersonaId,
                        )

                        HomeTab.SETTINGS -> SettingsScreen(container = container)
                    }
                }
            }
        }
    }
}
