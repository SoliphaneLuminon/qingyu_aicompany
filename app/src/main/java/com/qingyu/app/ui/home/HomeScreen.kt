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
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qingyu.app.core.TimeUtil
import com.qingyu.app.data.ChatListEntry
import com.qingyu.app.data.Persona
import com.qingyu.app.ui.components.EmptyState
import com.qingyu.app.ui.components.PersonaAvatar

/**
 * 主界面：对话列表喵。
 *
 * 布局照微信：一行一段对话，左边头像、中间名字与最后一句、右边时间，
 * 行间用极细分割线而不是卡片留白——微信是连续列表，卡片化会显得松散喵。
 *
 * 分工喵：**这里管对话，通讯录管角色**。所以
 * - 只列出**有对话且没被移除**的角色；从没聊过的人属于通讯录，不占主界面喵
 * - 长按菜单里是「删除对话」（= 从主界面移除，角色与记录都留着）喵
 * - 右上角是搜索与加号：加号里只有「添加联系人」和「从备份导入聊天记录」喵
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    entries: List<ChatListEntry>,
    onOpenChat: (Persona) -> Unit,
    onOpenSearch: () -> Unit,
    onHideChat: (Persona) -> Unit,
    onAddContact: () -> Unit,
    onImportChat: () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    modifier: Modifier = Modifier,
) {
    var plusMenu by remember { mutableStateOf(false) }
    var hideTarget by remember { mutableStateOf<ChatListEntry?>(null) }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                title = { Text("轻语") },
                actions = {
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Outlined.Search, contentDescription = "搜索")
                    }
                    Box {
                        IconButton(onClick = { plusMenu = true }) {
                            Icon(Icons.Outlined.Add, contentDescription = "更多")
                        }
                        DropdownMenu(expanded = plusMenu, onDismissRequest = { plusMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("添加联系人") },
                                leadingIcon = { Icon(Icons.Outlined.PersonAdd, null) },
                                onClick = {
                                    plusMenu = false
                                    onAddContact()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("从备份导入聊天记录") },
                                leadingIcon = { Icon(Icons.Outlined.ChatBubbleOutline, null) },
                                onClick = {
                                    plusMenu = false
                                    onImportChat()
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                EmptyState(
                    title = "还没有对话",
                    description = "去「通讯录」添加一个联系人，\n或者点右上角的 ＋ 直接添加。",
                    icon = "💬",
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(entries, key = { it.persona.id }) { entry ->
                    // animateItem：对话被移除后重新回来、或新对话出现时平滑让位，
                    // 而不是"啪"地插进列表里喵。
                    Column(modifier = Modifier.animateItem()) {
                        ChatRow(
                            entry = entry,
                            onClick = { onOpenChat(entry.persona) },
                            onLongClick = { hideTarget = entry },
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
    }

    // 长按：只给「删除对话」，语义是**从主界面移除**，不是删角色、也不删记录喵。
    hideTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { hideTarget = null },
            title = { Text("删除这段对话？") },
            text = {
                Text(
                    "「${entry.persona.name}」会从主界面移除，" +
                        "但聊天记录、角色与它记住的事都会保留。\n\n" +
                        "从通讯录点进去，或者再发一条消息，它就会回来。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onHideChat(entry.persona)
                    hideTarget = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { hideTarget = null }) { Text("取消") }
            },
        )
    }
}

/**
 * 列表里的一行：头像 + 名字 + 最后一句 + 时间喵。
 *
 * 主界面只列有对话的角色，所以这里一定拿得到 conversation；
 * 万一为空（并发下刚被清空）就退化成一句提示，不显示空白喵。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(
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
        PersonaAvatar(emoji = persona.avatarEmoji, size = 48.dp, emphasize = true)
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
                text = conversation?.lastMessagePreview.orEmpty().ifBlank { "开始聊天吧" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = TimeUtil.smart(entry.sortTime),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
