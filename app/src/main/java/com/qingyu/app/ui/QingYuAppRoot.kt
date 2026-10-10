package com.qingyu.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qingyu.app.AppContainer
import com.qingyu.app.core.TimeUtil
import com.qingyu.app.data.Conversation
import com.qingyu.app.data.Persona
import com.qingyu.app.ui.chat.ChatScreen
import com.qingyu.app.ui.debug.DebugScreen
import com.qingyu.app.ui.favorites.FavoritesScreen
import com.qingyu.app.ui.memory.MemoryScreen
import com.qingyu.app.ui.modules.ModulesScreen
import com.qingyu.app.ui.personas.PersonasScreen
import com.qingyu.app.ui.search.SearchScreen
import com.qingyu.app.ui.settings.SettingsScreen
import com.qingyu.app.ui.vm.AppScreen
import com.qingyu.app.ui.vm.ChatViewModel
import com.qingyu.app.ui.vm.ShellViewModel
import kotlinx.coroutines.launch

/** 主界面标签喵。 */
enum class HomeTab(val label: String) {
    CHAT("聊天"),
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
 * 应用外壳喵。
 *
 * 导航用状态机而不是 Navigation 组件：全局只有 5 个页面，其中 4 个是底部标签喵，
 * 一个抽屉装着会话列表，用状态表达比维护 nav graph 更省心喵。
 */
@Composable
fun QingYuAppRoot(container: AppContainer) {
    val application = LocalContext.current.applicationContext as android.app.Application
    val chatViewModel: ChatViewModel = viewModel(factory = ChatViewModel.factory(application))
    val shellViewModel: ShellViewModel = viewModel(factory = ShellViewModel.factory(application))

    val chatState by chatViewModel.state.collectAsStateWithLifecycle()
    val shellState by shellViewModel.state.collectAsStateWithLifecycle()
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val speechState by container.speech.state.collectAsStateWithLifecycle()
    val speaking = speechState.speaking || speechState.preparing

    var tab by remember { mutableStateOf(HomeTab.CHAT) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // 键盘高度在这里统一测量一次，向下传给聊天页；键盘可见时底部标签栏让位喵。
    val imeBottom = rememberImeBottomInset()
    val imeVisible = imeBottom > 0.dp

    // 聊天页每次回到前台都同步一次会话列表与角色列表喵。
    LaunchedEffect(chatState.conversation?.id, tab) {
        shellViewModel.refresh()
    }

    val back: () -> Unit = {
        shellViewModel.back()
        chatViewModel.refreshPersonas()
    }

    when (val screen = shellState.screen) {
        is AppScreen.Personas -> {
            PersonasScreen(
                onBack = back,
                onPickPersona = { personaId ->
                    val persona = shellState.personas.firstOrNull { it.id == personaId }
                    if (persona != null) chatViewModel.openPersona(persona)
                    tab = HomeTab.CHAT
                    back()
                },
            )
            return
        }

        is AppScreen.Search -> {
            SearchScreen(
                conversationId = screen.conversationId.takeIf { it > 0L } ?: (chatState.conversation?.id ?: 0L),
                onBack = back,
            )
            return
        }

        is AppScreen.Modules -> {
            ModulesScreen(onBack = back)
            return
        }

        is AppScreen.Debug -> {
            DebugScreen(state = chatState, onBack = back)
            return
        }

        AppScreen.Home -> Unit
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ConversationDrawer(
                conversations = shellState.conversations,
                personas = shellState.personas,
                currentConversationId = chatState.conversation?.id ?: 0L,
                currentPersonaId = chatState.persona?.id ?: 0L,
                onClose = { scope.launch { drawerState.close() } },
                onPickConversation = {
                    chatViewModel.switchConversation(it)
                    scope.launch { drawerState.close() }
                },
                onPickPersona = {
                    chatViewModel.openPersona(it)
                    tab = HomeTab.CHAT
                    scope.launch { drawerState.close() }
                },
                onNewConversation = {
                    chatViewModel.newConversation()
                    scope.launch { drawerState.close() }
                },
                onRename = shellViewModel::renameConversation,
                onPin = shellViewModel::setPinned,
                onDelete = shellViewModel::deleteConversation,
                onManagePersonas = {
                    scope.launch { drawerState.close() }
                    shellViewModel.navigate(AppScreen.Personas)
                },
                onOpenModules = {
                    scope.launch { drawerState.close() }
                    shellViewModel.navigate(AppScreen.Modules)
                },
            )
        },
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.surface,
            bottomBar = {
                // 键盘弹出时收起底部标签栏：它本来就被键盘完全遮住喵，
                // 但 Scaffold 仍会为它预留约 80dp。聊天页的输入栏上移了「整个键盘高度喵」，
                // 两边一叠加，输入栏就会停在键盘上方约一个标签栏高的位置——那条空白正是它喵。
                if (!imeVisible && (tab != HomeTab.CHAT || !drawerState.isOpen)) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        HomeTab.entries.forEach { item ->
                            NavigationBarItem(
                                selected = tab == item,
                                onClick = { tab = item },
                                icon = {
                                    Icon(
                                        imageVector = when (item) {
                                            HomeTab.CHAT -> Icons.Outlined.ChatBubbleOutline
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
            Box(modifier = Modifier.padding(innerPadding)) {
                when (tab) {
                    HomeTab.CHAT -> ChatScreen(
                        state = chatState,
                        viewModel = chatViewModel,
                        speaking = speaking,
                        showReasoning = settings.showReasoning,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onOpenPersonas = { shellViewModel.navigate(AppScreen.Personas) },
                        onOpenSettings = { tab = HomeTab.SETTINGS },
                        onOpenSearch = { shellViewModel.navigate(AppScreen.Search(chatState.conversation?.id ?: 0L)) },
                        onOpenDebug = { shellViewModel.navigate(AppScreen.Debug) },
                        imeBottomInset = imeBottom,
                        reservedBottomInset = reservedBottom,
                    )

                    HomeTab.FAVORITES -> FavoritesScreen(
                        container = container,
                        personaId = chatState.persona?.id ?: 0L,
                    )

                    HomeTab.MEMORY -> MemoryScreen(
                        personaId = chatState.persona?.id ?: 0L,
                    )

                    HomeTab.SETTINGS -> SettingsScreen(container = container)
                }
            }
        }
    }
}

/** 会话抽屉：上半部分是角色，下半部分是当前角色的会话喵。 */
@Composable
private fun ConversationDrawer(
    conversations: List<Conversation>,
    personas: List<Persona>,
    currentConversationId: Long,
    currentPersonaId: Long,
    onClose: () -> Unit,
    onPickConversation: (Long) -> Unit,
    onPickPersona: (Persona) -> Unit,
    onNewConversation: () -> Unit,
    onRename: (Long, String) -> Unit,
    onPin: (Long, Boolean) -> Unit,
    onDelete: (Long) -> Unit,
    onManagePersonas: () -> Unit,
    onOpenModules: () -> Unit,
) {
    var renaming by remember { mutableStateOf<Conversation?>(null) }
    var deleting by remember { mutableStateOf<Conversation?>(null) }

    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 20.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "轻语",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onNewConversation) {
                    Icon(Icons.Outlined.Add, contentDescription = "新对话")
                }
            }

            Text(
                text = "角色",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 6.dp),
            )
            Column {
                personas.take(5).forEach { persona ->
                    DrawerRow(
                        selected = persona.id == currentPersonaId,
                        emoji = persona.avatarEmoji,
                        title = persona.name,
                        subtitle = persona.tagline,
                        onClick = { onPickPersona(persona) },
                    )
                }
                DrawerRow(
                    selected = false,
                    emoji = "➕",
                    title = "管理角色",
                    subtitle = "新建、编辑、复制角色卡",
                    onClick = onManagePersonas,
                )
                DrawerRow(
                    selected = false,
                    emoji = "🧩",
                    title = "模块",
                    subtitle = "安装 JS 插件扩展轻语",
                    onClick = onOpenModules,
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            )

            Text(
                text = "对话",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 20.dp, bottom = 6.dp),
            )

            if (conversations.isEmpty()) {
                Text(
                    text = "还没有对话",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
                ) {
                    items(conversations, key = { it.id }) { conversation ->
                        ConversationRow(
                            conversation = conversation,
                            selected = conversation.id == currentConversationId,
                            onClick = { onPickConversation(conversation.id) },
                            onRename = { renaming = conversation },
                            onPin = { onPin(conversation.id, !conversation.pinned) },
                            onDelete = { deleting = conversation },
                        )
                    }
                }
            }
        }
    }

    renaming?.let { conversation ->
        var text by remember(conversation.id) { mutableStateOf(conversation.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名对话") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(conversation.id, text)
                    renaming = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } },
        )
    }

    deleting?.let { conversation ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除这段对话？") },
            text = { Text("「${conversation.title}」及其 ${conversation.messageCount} 条消息会被永久删除。") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(conversation.id)
                    deleting = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun DrawerRow(
    selected: Boolean,
    emoji: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) scheme.primaryContainer else scheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = emoji)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: Conversation,
    selected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) scheme.primaryContainer else scheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (conversation.pinned) {
                    Icon(
                        imageVector = Icons.Outlined.PushPin,
                        contentDescription = null,
                        modifier = Modifier
                            .width(12.dp)
                            .height(12.dp),
                        tint = scheme.primary,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = conversation.title.ifBlank { "未命名对话" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "${conversation.messageCount} 条 · ${TimeUtil.relative(conversation.updatedAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onPin) {
            Icon(
                imageVector = Icons.Outlined.PushPin,
                contentDescription = "置顶",
                modifier = Modifier.width(16.dp).height(16.dp),
                tint = if (conversation.pinned) scheme.primary else scheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRename) {
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = "重命名",
                modifier = Modifier.width(16.dp).height(16.dp),
                tint = scheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = "删除",
                modifier = Modifier.width(16.dp).height(16.dp),
                tint = scheme.error,
            )
        }
    }
}
