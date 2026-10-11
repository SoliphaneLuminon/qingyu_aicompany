package com.qingyu.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qingyu.app.AppContainer
import com.qingyu.app.data.MessageRole
import com.qingyu.app.ui.chat.ChatInfoScreen
import com.qingyu.app.ui.chat.CallScreen
import com.qingyu.app.ui.chat.ChatScreen
import com.qingyu.app.ui.chat.RedPacketDialog
import com.qingyu.app.ui.chat.StickerManagerScreen
import com.qingyu.app.ui.contacts.ContactsScreen
import com.qingyu.app.ui.contacts.PersonaDetailScreen
import com.qingyu.app.ui.debug.DebugScreen
import com.qingyu.app.ui.favorites.FavoritesScreen
import com.qingyu.app.ui.home.HomeScreen
import com.qingyu.app.ui.memory.MemoryScreen
import com.qingyu.app.ui.modules.ModulesScreen
import com.qingyu.app.ui.personas.AvatarCropScreen
import com.qingyu.app.ui.personas.PersonaEditorDialog
import com.qingyu.app.ui.search.SearchScreen
import com.qingyu.app.ui.settings.SettingsScreen
import com.qingyu.app.ui.theme.LocalDarkTheme
import com.qingyu.app.ui.theme.QyBottomBarDark
import com.qingyu.app.ui.theme.QyBottomBarLight
import com.qingyu.app.ui.vm.AppScreen
import com.qingyu.app.ui.vm.ChatUiState
import com.qingyu.app.ui.vm.ChatViewModel
import com.qingyu.app.ui.vm.DataViewModel
import com.qingyu.app.ui.vm.PersonasViewModel
import com.qingyu.app.ui.vm.ShellViewModel

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
 * 读取系统底部（导航栏 / 手势条）让位高度喵。
 *
 * 对话覆盖层现在叠在 Scaffold **外面**，拿不到 Scaffold 的 innerPadding，
 * 而它又是 edge-to-edge 的，所以系统栏高度得自己量、自己传给聊天页，
 * 否则输入栏会压在手势条下面点不到喵。
 */
@Composable
private fun rememberSystemBottomInset(): Dp =
    with(LocalDensity.current) { WindowInsets.systemBars.getBottom(this).toDp() }

/**
 * 二级页面（搜索 / 模块 / 调试 / 聊天信息 / 角色资料）的入场动画喵。
 *
 * 这几页在导航上是"提前 return"的独立分支，没有常驻容器可以承载进出场，
 * 所以只做入场：一点点向右推进，给出"进入了新一屏"的方向感喵。
 *
 * **为什么不用 `AnimatedVisibility(visible = false → true)`**喵：
 * 它初始不可见时一个子项都不组合，会先露出一帧**空的窗口背景**，
 * 用户看到的就是"白屏闪一下再出现内容"喵。
 * 这里改成内容**始终组合**、只把位移从右推到原位；
 * 外面再垫一层不透明的主题底色，滑动过程中露出的也是应用自己的颜色，
 * 而不是窗口背景——两处一起才彻底没有那一帧白喵。
 */
@Composable
private fun ScreenEnter(content: @Composable () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
        )
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // 只推移不到一屏宽的十二分之一，太多反而像卡顿喵。
                    translationX = (1f - progress.value) * (size.width / 12f)
                },
        ) {
            content()
        }
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

    // 头像：相册选图 → 裁剪页 → 落盘。
    // 相册与裁剪分两步，是因为裁剪需要一张能自由缩放平移的位图，
    // 而系统相册只给一个 Uri；中间的 Uri 就暂存在这里喵。
    var croppingAvatarUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val avatarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) croppingAvatarUri = uri }

    // 表情：相册单张 / 压缩包整包。两处入口（输入框面板与表情管理页）共用这两个启动器喵。
    val stickerImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) chatViewModel.importStickerFromUri(uri) }
    val stickerZipPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) chatViewModel.importStickersFromZip(uri) }

    // 通话页里的麦克风权限：与聊天页共用一个申请入口，避免两处逻辑漂移喵。
    val audioPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) chatViewModel.startRecording() else chatViewModel.showBanner("没有麦克风权限")
    }

    // 通话页回显"上一句听成了什么"：识别错了要看得见，
    // 否则用户只会觉得"它答非所问"，却不知道是识别环节出的问题喵。
    var callLastUserText by remember { mutableStateOf("") }
    LaunchedEffect(chatState.messages.size, chatState.recording) {
        chatState.messages.lastOrNull { it.role == MessageRole.USER }?.let { callLastUserText = it.content }
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

    // 系统手势条 / 导航栏高度：对话覆盖层在 Scaffold 外面，得自己量喵。
    val systemBottom = rememberSystemBottomInset()

    // 当前是否停在某个角色的对话里（主界面 → 对话 是盖在上面的一层）喵。
    val inChat = chatState.persona != null

    /**
     * 对话覆盖层的进出场进度喵。
     *
     * 用一个 0..1 的进度量而不是布尔可见性，是为了避开两件事喵：
     * - 布尔 + `AnimatedVisibility` 在"不可见"那一帧**不组合子项**，
     *   露出的会是窗口背景（近白），也就是用户看到的"白屏一下"
     * - 退场时 [ChatViewModel.closeChat] 会立刻清空 [chatState]，
     *   若直接用 `persona != null` 当可见性，动画没播完内容就没了，
     *   会滑出一张空白页
     *
     * 所以这里显式推进度：进场 0→1，退场 1→0，播完才把覆盖层卸掉；
     * 期间渲染用 [lastChatState] 里那份「最后一次打开时的快照」顶替喵。
     */
    val chatProgress = remember { Animatable(0f) }
    // 覆盖层是否还挂着（进度退到 0 且动画结束后才卸），它是真正的"要不要渲染"喵。
    var chatMounted by remember { mutableStateOf(false) }
    val lastChatState = remember { mutableStateOf<ChatUiState?>(null) }

    LaunchedEffect(inChat) {
        if (inChat) {
            chatMounted = true
            chatProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
            )
        } else {
            chatProgress.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 260, easing = FastOutLinearInEasing),
            )
            // 动画收尾再卸掉，并丢掉快照，免得下次打开先闪一下上一个人的消息喵。
            chatMounted = false
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
            onPickAvatar = { avatarPicker.launch("image/*") },
            onClearAvatar = { personaEditor.clearAvatar() },
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

        is AppScreen.StickerManager -> {
            ScreenEnter {
                StickerManagerScreen(
                    stickers = chatState.stickers,
                    importing = chatState.importingStickers,
                    onBack = back,
                    onImportImage = { stickerImagePicker.launch("image/*") },
                    onImportZip = { stickerZipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*")) },
                    onRename = { sticker, name -> chatViewModel.renameSticker(sticker, name) },
                    onDelete = { sticker -> chatViewModel.deleteSticker(sticker) },
                )
            }
            return
        }

        is AppScreen.Call -> {
            val persona = chatState.persona
            if (persona == null) {
                // 对话已经关了（例如通话中途挂断后退回），直接退回列表喵。
                shellViewModel.back()
            } else {
                // 通话期间置上标记：回复要无条件念出来，不能依赖聊天页那两个朗读开关喵。
                // 离开这一屏（含返回键、挂断）时由 DisposableEffect 清掉，不会残留喵。
                DisposableEffect(Unit) {
                    chatViewModel.setCallState(inCall = true, status = "通话中")
                    onDispose { chatViewModel.setCallState(inCall = false, status = "") }
                }
                CallScreen(
                    persona = persona,
                    recording = chatState.recording,
                    thinking = chatState.stream.generating,
                    speaking = speaking,
                    lastUserText = callLastUserText,
                    onHoldStart = {
                        if (chatViewModel.hasAudioPermission()) {
                            chatViewModel.startRecording()
                        } else {
                            audioPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onHoldEnd = { chatViewModel.stopRecordingAndSend() },
                    onHangUp = {
                        chatViewModel.cancelRecording()
                        chatViewModel.stopSpeaking()
                        back()
                    },
                )
            }
            return
        }

        AppScreen.Home -> Unit
    }

    // 最外层垫一层不透明底色：任何一帧、任何页面切换的空隙里露出来的
    // 都应该是应用自己的颜色，而不是窗口背景（近白，深色主题下就是"白屏闪一下"的来源）喵。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.surface,
            bottomBar = {
                // 键盘弹出时收起底部标签栏：它本来就被键盘完全遮住喵，
                // 但 Scaffold 仍会为它预留约 80dp。聊天页的输入栏上移了「整个键盘高度喵」，
                // 两边一叠加，输入栏就会停在键盘上方约一个标签栏高的位置——那条空白正是它喵。
                if (!imeVisible) {
                    // 底栏用"介于普通对话与置顶对话之间"的那层底色喵：
                    // 浅色下 普通白 → 底栏 #F7F7F7 → 置顶 #F2F2F2，
                    // 暗色下 普通 #191919 → 底栏 #202020 → 置顶 #262626，
                    // 三层明度依次递进，一眼能分出"列表 / 底栏 / 置顶"喵。
                    NavigationBar(
                        containerColor = if (LocalDarkTheme.current) QyBottomBarDark else QyBottomBarLight,
                    ) {
                        HomeTab.entries.forEach { item ->
                            NavigationBarItem(
                                selected = tab == item,
                                onClick = {
                                    // 从别的标签切回"聊天"时，回到列表而不是上次那段对话喵。
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
            // consumeWindowInsets 是关键的一步喵。
            //
            // 外层 Scaffold 已经把状态栏高度与底栏高度算进了 innerPadding，
            // 而内层各页自己的 TopAppBar 默认又会加一次 `statusBarsPadding`、
            // 内容区也会再让一次导航栏——两边各重复一遍，界面上下就多出一圈空白喵。
            //
            // Material3 的约定是：被父布局 consume 掉的 insets 会从子组件的
            // `contentWindowInsets` 里自动排除（见 Scaffold.kt 的注释）。
            // 所以这里声明"这些我已经用掉了"，内层就不再重复让位——
            // 标题栏自然上移、底栏自然下移，中间内容区随之变大喵。
            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding),
            ) {
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
                            loading = chatState.loading,
                            onOpenChat = { persona -> chatViewModel.openPersona(persona) },
                            onOpenSearch = {
                                shellViewModel.navigate(AppScreen.Search(0L))
                            },
                            onHideChat = { persona ->
                                chatViewModel.hideChatOf(persona)
                            },
                            onTogglePin = { persona, pinned ->
                                chatViewModel.setPinned(persona, pinned)
                            },
                            onAddContact = { personaEditor.createNew() },
                            onImportChat = { importLauncher.launch(arrayOf("application/json", "text/plain", "*")) },
                            snackbarHostState = homeSnackbar,
                        )

                        HomeTab.CONTACTS -> ContactsScreen(
                            personas = chatState.allPersonas,
                            loading = chatState.loading,
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
        }

        // ---------------------------------------------------------------- 对话覆盖层
        // 它必须是 Scaffold 的**兄弟**，不能放在 content 里喵。
        //
        // Material3 的 Scaffold 先摆 body、**后摆 bottomBar**，也就是标签栏压在正文之上喵。
        // 之前放在 content 里时，五个标签的导航栏正好盖住聊天页底部的输入栏，
        // 表现就是"进了对话找不到输入框"（键盘一弹出标签栏收起，输入框才露出来）喵。
        // 放到外层之后它才真的盖住整屏、含底部标签栏，与设计意图一致喵。
        //
        // 退场时 chatState 已被 closeChat 清空（persona 立刻置空），
        // 直接渲染会看到空白滑出去，所以留一份最后一次的快照顶替到动画播完喵。
        SideEffect {
            if (chatState.persona != null) lastChatState.value = chatState
        }
        val renderState = if (chatState.persona != null) {
            chatState
        } else {
            lastChatState.value ?: chatState
        }

        if (chatMounted) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // 从右侧推入：progress 1 时归位，0 时整屏在右边喵。
                        translationX = size.width * (1f - chatProgress.value)
                    },
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
                    onStartCall = {
                        chatViewModel.togglePlusPanelOff()
                        shellViewModel.navigate(AppScreen.Call)
                    },
                    onOpenStickerManager = {
                        chatViewModel.toggleStickerPanelOff()
                        shellViewModel.navigate(AppScreen.StickerManager)
                    },
                    // 它已经盖住了整个屏幕（含底部标签栏），没有人替它让位，
                    // 键盘与系统手势条都要由它自己避让喵。
                    reservedBottomInset = 0.dp,
                    imeBottomInset = imeBottom,
                    systemBottomInset = systemBottom,
                )
            }
        }
    }

    // ---------------------------------------------------------------- 头像裁剪
    // 放在最外层（连角色编辑弹窗之上）：裁剪是"必须给出结果才能继续"的一步，
    // 底下露出的是相册选的原图，弹窗留在后面不影响，回来还能接着改别的字段喵。
    croppingAvatarUri?.let { uri ->
        AvatarCropScreen(
            source = uri,
            onCancel = { croppingAvatarUri = null },
            onConfirm = { bitmap ->
                personaEditor.applyCroppedAvatar(bitmap)
                croppingAvatarUri = null
            },
        )
    }

    // ---------------------------------------------------------------- 发红包
    // 与裁剪页同级放在最外层：它可能在对话里触发（聊天页盖着主界面），
    // 若放在 Scaffold 里面会被对话覆盖层压住、根本点不到喵。
    if (chatState.redPacketOpen) {
        RedPacketDialog(
            onDismiss = { chatViewModel.closeRedPacket() },
            onSend = { amount, wish -> chatViewModel.sendRedPacket(amount, wish) },
        )
    }
}
