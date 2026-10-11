package com.qingyu.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import com.qingyu.app.ui.chat.ChatInfoScreen
import com.qingyu.app.ui.chat.ChatScreen
import com.qingyu.app.ui.contacts.ContactsScreen
import com.qingyu.app.ui.contacts.PersonaDetailScreen
import com.qingyu.app.ui.debug.DebugScreen
import com.qingyu.app.ui.favorites.FavoritesScreen
import com.qingyu.app.ui.home.HomeScreen
import com.qingyu.app.ui.memory.MemoryScreen
import com.qingyu.app.ui.modules.ModulesScreen
import com.qingyu.app.ui.personas.PersonaEditorDialog
import com.qingyu.app.ui.search.SearchScreen
import com.qingyu.app.ui.settings.SettingsScreen
import com.qingyu.app.ui.vm.AppScreen
import com.qingyu.app.ui.vm.ChatUiState
import com.qingyu.app.ui.vm.ChatViewModel
import com.qingyu.app.ui.vm.DataViewModel
import com.qingyu.app.ui.vm.PersonasViewModel
import com.qingyu.app.ui.vm.ShellViewModel
import kotlinx.coroutines.delay

/** 对话退场动画时长，与下面 [AnimatedVisibility] 的 exit 保持一致喵。 */
private const val CHAT_EXIT_MS = 260L

/**
 * 底部标签喵。
 *
 * 分工照微信：**「聊天」管对话（主界面），「通讯录」管角色**喵。
 * 一条对话可以不存在主界面里（被移除了），但角色永远在通讯录里，所以两者必须分开喵。
 */
enum class HomeTab(val label: String) {
    HOME("聊天"),
    CONTACTS("通讯录"),
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
    // 角色编辑器由主界面与通讯录共用，实例提到这里，避免两处各持一份列表状态喵。
    val personaEditor: PersonasViewModel = viewModel(factory = PersonasViewModel.factory(application))
    // 导入聊天备份走这里（走系统文件选择器）喵。
    val dataViewModel: DataViewModel = viewModel(factory = DataViewModel.factory(application))

    val chatState by chatViewModel.state.collectAsStateWithLifecycle()
    val shellState by shellViewModel.state.collectAsStateWithLifecycle()
    val dataState by dataViewModel.state.collectAsStateWithLifecycle()
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val speechState by container.speech.state.collectAsStateWithLifecycle()
    val speaking = speechState.speaking || speechState.preparing
    val homeSnackbar = remember { SnackbarHostState() }

    var tab by remember { mutableStateOf(HomeTab.HOME) }

    // 「从备份导入聊天记录」：拉系统文件选择器，选中的备份交给 DataViewModel 解析喵。
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) dataViewModel.importChat(uri)
    }

    // 导入结果通过主界面的 snackbar 反馈喵。
    LaunchedEffect(dataState.message) {
        dataState.message?.let {
            homeSnackbar.showSnackbar(it)
            dataViewModel.clearMessage()
            chatViewModel.refreshPersonas()
        }
    }

    // 键盘高度在这里统一测量一次，向下传给聊天页；键盘可见时底部标签栏让位喵。
    val imeBottom = rememberImeBottomInset()
    val imeVisible = imeBottom > 0.dp

    // 当前是否停在某个角色的对话里（主界面 → 对话 是盖在上面的一层）喵。
    val inChat = chatState.persona != null

    /**
     * 对话覆盖层的可见性喵。
     *
     * 它比 [inChat] 多活一小会儿：关闭对话时 [chatState] 立刻被清空，
     * 若直接用 `inChat` 驱动，退场动画还没播完内容就没了，会看到一张空白页滑出去喵。
     * 所以这里用 `snapTo` 立即置为 true、关闭时延迟到动画播完再置 false，
     * 期间用 [lastChatState] 顶替着渲染喵。
     */
    var showChat by remember { mutableStateOf(false) }
    val lastChatState = remember { mutableStateOf<ChatUiState?>(null) }
    LaunchedEffect(inChat) {
        if (inChat) {
            showChat = true
        } else {
            delay(CHAT_EXIT_MS)
            showChat = false
            lastChatState.value = null
        }
    }

    /**
     * 收藏页与记忆页要按角色取数据，但它们不一定是在对话里打开的喵。
     *
     * 优先级：正在聊的这个角色 → 上次聊过的（还在的话）→ 通讯录第一个 → 0喵。
     * 以前 bootstrap 会自动打开上次的角色，所以这两个页面总有值；
     * 改成"主界面是对话列表"之后不再自动进对话，必须在这里补上回退，
     * 否则一进来记忆页是空的，看起来像坏了喵。
     */
    val activePersonaId = chatState.persona?.id
        ?: settings.lastPersonaId.takeIf { id -> chatState.allPersonas.any { it.id == id } }
        ?: chatState.allPersonas.firstOrNull()?.id
        ?: 0L

    // 系统返回键：在对话里就退回列表，而不是直接退出应用喵。
    BackHandler(enabled = inChat) { chatViewModel.closeChat() }

    // 回到列表这一屏时重算一次列表，保证预览与排序是最新的喵。
    LaunchedEffect(inChat, tab) {
        if (!inChat || tab == HomeTab.HOME) shellViewModel.refresh()
    }

    // 角色被改动（新建 / 编辑 / 复制 / 删除）后，主界面与通讯录都要重算喵。
    LaunchedEffect(personaEditor.state.value.personas, personaEditor.state.value.editorOpen) {
        chatViewModel.refreshPersonas()
    }

    val back: () -> Unit = { shellViewModel.back() }

    // 角色编辑器与删除确认由根部统一渲染：主界面、通讯录、资料页三处都可能触发它们，
    // 放在这里才能保证只有一份实例、状态不会各说各话喵。
    val editorState by personaEditor.state.collectAsStateWithLifecycle()
    if (editorState.editorOpen) {
        PersonaEditorDialog(
            draft = editorState.draft,
            isNew = editorState.editing == null,
            onChange = personaEditor::onDraftChange,
            onDismiss = { personaEditor.closeEditor() },
            onSave = { personaEditor.save() },
        )
    }
    editorState.pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { personaEditor.cancelDelete() },
            title = { Text("删除联系人「${target.name}」？") },
            text = { Text("这段对话、聊天记录，以及它记住的事都会一起删除，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    personaEditor.confirmDelete()
                    // 删掉的正是当前打开的那个角色时，退回列表喵。
                    if (chatState.persona?.id == target.id) {
                        chatViewModel.closeChat()
                        if (shellState.screen !is AppScreen.Home) shellViewModel.back()
                    }
                    chatViewModel.refreshPersonas()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { personaEditor.cancelDelete() }) { Text("取消") }
            },
        )
    }

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

        is AppScreen.ChatInfo -> {
            val persona = chatState.persona
            if (persona == null) {
                // 对话已经关了（例如清空后返回），直接退回喵。
                shellViewModel.back()
            } else {
                ScreenEnter {
                    ChatInfoScreen(
                        persona = persona,
                        conversation = chatState.conversation,
                        onBack = back,
                        onOpenPersona = {
                            shellViewModel.navigate(AppScreen.PersonaDetail(persona.id))
                        },
                        onFavoriteConversation = { chatViewModel.favoriteConversation() },
                        onOpenModules = { shellViewModel.navigate(AppScreen.Modules) },
                        onOpenDebug = { shellViewModel.navigate(AppScreen.Debug) },
                        onOpenMemory = {
                            chatViewModel.closeChat()
                            tab = HomeTab.MEMORY
                            back()
                        },
                        onHideChat = {
                            chatViewModel.hideCurrentChat()
                            back()
                        },
                        onClearMessages = { chatViewModel.clearCurrentMessages() },
                    )
                }
            }
            return
        }

        is AppScreen.PersonaDetail -> {
            val persona = chatState.allPersonas.firstOrNull { it.id == screen.personaId }
            if (persona == null) {
                shellViewModel.back()
            } else {
                ScreenEnter {
                    PersonaDetailScreen(
                        persona = persona,
                        conversation = chatState.conversationByPersona[persona.id],
                        onBack = back,
                        onOpenChat = {
                            chatViewModel.openPersona(persona)
                            tab = HomeTab.HOME
                            back()
                        },
                        onEdit = { personaEditor.edit(persona) },                        onOpenMemory = {
                            chatViewModel.closeChat()
                            tab = HomeTab.MEMORY
                            back()
                        },
                        onDelete = { personaEditor.askDelete(persona) },
                    )
                }
            }
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
                                        HomeTab.CONTACTS -> Icons.Outlined.Contacts
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
        // 外壳已经为底部让出的高度（标签栏 / 系统导航栏），交给各页面做减法喵。
        val reservedBottom = innerPadding.calculateBottomPadding()
        Box(modifier = Modifier.padding(innerPadding)) {
            // 底部标签切换时做一次交叉淡入淡出：不引入滑动是因为页面宽度都占满，
            // 横向位移反而会让人以为可以左右滑，淡入更符合"换了一屏"的直觉喵。
            Crossfade(
                targetState = tab,
                animationSpec = tween(durationMillis = 220),
                label = "homeTab",
            ) { current ->
                when (current) {
                    HomeTab.HOME -> HomeScreen(
                        entries = chatState.chatList,
                        onOpenChat = { persona -> chatViewModel.openPersona(persona) },
                        onOpenSearch = {
                            shellViewModel.navigate(AppScreen.Search(0L))
                        },
                        onHideChat = { persona ->
                            chatViewModel.hideChatOf(persona)
                        },
                        onAddContact = { personaEditor.createNew() },
                        onImportChat = { importLauncher.launch(arrayOf("application/json", "text/plain", "*")) },
                        snackbarHostState = homeSnackbar,
                    )

                    HomeTab.CONTACTS -> ContactsScreen(
                        personas = chatState.allPersonas,
                        onOpenPersona = { persona ->
                            shellViewModel.navigate(AppScreen.PersonaDetail(persona.id))
                        },
                        onAddContact = { personaEditor.createNew() },
                        onOpenChat = { persona -> chatViewModel.openPersona(persona) },
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

        // ---------------------------------------------------------------- 对话覆盖层
        // 对话不是"标签页里的一屏"，而是像微信那样**从右侧推入、盖住整个屏幕**
        // （包括底部标签栏）喵。所以它不放在 Scaffold 的 content 里，
        // 而是叠在最外层，这样推进/退回时列表与标签栏都保持原样被它盖住喵。
        //
        // 退场时 chatState 已经被 closeChat 清空（persona 立刻置空），
        // 直接渲染会看到"空白页滑出去"。所以留一份最后一次的快照，
        // 在退场期间顶替它；动画播完就丢掉喵。
        //
        // 只在"确实打开了某个角色"时更新快照：ChatUiState 是 data class、
        // 比较是结构相等，写回同值不会触发重组，所以这里不会打转喵。
        SideEffect {
            if (chatState.persona != null) lastChatState.value = chatState
        }
        // 关键：**开着的时候用实时状态，关掉之后（persona 没了）用快照**。
        // 若写成 `if (showChat) chatState else 快照`，退场动画期间 showChat 仍是 true，
        // 拿到的正是被清空的那份，反而会滑出一张空白页喵。
        val renderState = if (chatState.persona != null) {
            chatState
        } else {
            lastChatState.value ?: chatState
        }

        AnimatedVisibility(
            visible = showChat,
            enter = slideInHorizontally(
                animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                initialOffsetX = { width -> width },
            ) + fadeIn(animationSpec = tween(durationMillis = 160)),
            exit = slideOutHorizontally(
                animationSpec = tween(durationMillis = 260, easing = FastOutLinearInEasing),
                targetOffsetX = { width -> width },
            ) + fadeOut(animationSpec = tween(durationMillis = 140)),
        ) {
            ChatScreen(
                state = renderState,
                viewModel = chatViewModel,
                speaking = speaking,
                showReasoning = settings.showReasoning,
                onBack = { chatViewModel.closeChat() },
                onOpenSearch = {
                    shellViewModel.navigate(AppScreen.Search(chatState.conversation?.id ?: 0L))
                },
                onOpenChatInfo = { shellViewModel.navigate(AppScreen.ChatInfo) },
                onOpenSettings = { tab = HomeTab.SETTINGS },
                // 它盖住了整个屏幕、也盖住了底部标签栏，所以没有别人替它让位，
                // 键盘高度要完整由它自己吃掉喵。
                reservedBottomInset = 0.dp,
                imeBottomInset = imeBottom,
            )
        }
    }
}
