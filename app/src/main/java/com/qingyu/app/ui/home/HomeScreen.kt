package com.qingyu.app.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qingyu.app.core.TimeUtil
import com.qingyu.app.data.ChatListEntry
import com.qingyu.app.data.Persona
import com.qingyu.app.ui.components.EmptyState
import com.qingyu.app.ui.components.PersonaAvatar
import com.qingyu.app.ui.personas.PersonaEditorDialog
import com.qingyu.app.ui.vm.ChatUiState
import com.qingyu.app.ui.vm.ChatViewModel
import com.qingyu.app.ui.vm.PersonasViewModel

/**
 * 首页：角色列表喵。
 *
 * 布局照微信来：一行一个角色，左边头像、中间名字与最后一句、右边时间，
 * 行与行之间用极细分割线而不是卡片留白——微信是连续列表，卡片化会显得松散喵。
 *
 * 点一行直接进这个角色的对话（一个角色就是一段对话，不再有"新建对话"喵）；
 * 长按弹菜单做编辑 / 删除，与微信长按会话的操作位置一致喵。
 *
 * [viewModel] 与 [personaEditor] 都由根部注入：自己在这里 `viewModel()` 取一份，
 * 会与根持有的是同一个实例，看起来没问题，但两处各自维护列表状态时极易不同步——
 * 原来"新建角色后看不到"的 bug 正是这么来的，所以这里只读状态、不自己持有喵。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: ChatUiState,
    viewModel: ChatViewModel,
    personaEditor: PersonasViewModel,
    onOpenChat: (Persona) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var menuFor by remember { mutableStateOf<ChatListEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<Persona?>(null) }
    val editorState by personaEditor.state.collectAsStateWithLifecycle()

    // 编辑器改动过角色（新建 / 编辑 / 复制）或关掉弹窗后，把首页列表重算一遍喵。
    // 这一步就是"新建角色后立刻能看到"的保证——只刷新角色页那份列表是不够的喵。
    LaunchedEffect(editorState.personas, editorState.editorOpen) {
        viewModel.refreshPersonas()
    }

    LaunchedEffect(state.banner) {
        state.banner?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissBanner()
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                title = { Text("轻语") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "设置")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { personaEditor.createNew() }) {
                Icon(Icons.Outlined.Add, contentDescription = "新建角色")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        if (state.chatList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                EmptyState(
                    title = "还没有角色",
                    description = "点右下角的 ＋ 创建一个。\n一个角色就是一段对话，建好就能直接聊。",
                    icon = "💬",
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(bottom = 88.dp),
            ) {
                items(state.chatList, key = { it.persona.id }) { entry ->
                    ChatListRow(
                        entry = entry,
                        onClick = { onOpenChat(entry.persona) },
                        onLongClick = { menuFor = entry },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 76.dp),
                        thickness = 0.6.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                    )
                }
            }
        }
    }

    menuFor?.let { entry ->
        DropdownMenu(expanded = true, onDismissRequest = { menuFor = null }) {
            DropdownMenuItem(
                text = { Text("编辑角色") },
                leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                onClick = {
                    personaEditor.edit(entry.persona)
                    menuFor = null
                },
            )
            DropdownMenuItem(
                text = { Text("删除角色", color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error)
                },
                onClick = {
                    deleteTarget = entry.persona
                    menuFor = null
                },
            )
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除「${target.name}」？") },
            text = { Text("这段对话与它记住的事都会一起删除，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePersona(target)
                    deleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }

    if (editorState.editorOpen) {
        PersonaEditorDialog(
            draft = editorState.draft,
            isNew = editorState.editing == null,
            onChange = personaEditor::onDraftChange,
            onDismiss = { personaEditor.closeEditor() },
            onSave = { personaEditor.save() },
        )
    }
}

/**
 * 列表里的一行：头像 + 名字 + 最后一句 + 时间喵。
 *
 * 没聊过的角色也照常列出，副标题显示角色简介而不是留空——
 * 留白会让人以为出错了，简介顺便说明这个人是谁喵。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatListRow(
    entry: ChatListEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val background by animateColorAsState(
        targetValue = if (pressed) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surface
        },
        animationSpec = tween(120),
        label = "chatRowBg",
    )
    val persona = entry.persona
    val conversation = entry.conversation

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = "更多操作",
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PersonaAvatar(
            emoji = persona.avatarEmoji,
            size = 48.dp,
            emphasize = !entry.neverChatted,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = persona.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = if (entry.neverChatted) {
                    persona.tagline.ifBlank { "还没聊过，点一下开始" }
                } else {
                    conversation?.lastMessagePreview.orEmpty().ifBlank { "开始聊天吧" }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (entry.neverChatted) "" else TimeUtil.smart(entry.sortTime),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
