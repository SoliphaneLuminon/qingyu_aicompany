package com.qingyu.app.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PushPin
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
import com.qingyu.app.ui.components.UnreadBadge
import com.qingyu.app.ui.theme.LocalDarkTheme
import com.qingyu.app.ui.theme.QyChatRowNormalDark
import com.qingyu.app.ui.theme.QyChatRowNormalLight
import com.qingyu.app.ui.theme.QyChatRowPinnedDark
import com.qingyu.app.ui.theme.QyChatRowPinnedLight
import com.qingyu.app.ui.theme.Motion
import com.qingyu.app.ui.theme.Alpha

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
    onTogglePin: (Persona, Boolean) -> Unit,
    onAddContact: () -> Unit,
    onImportChat: () -> Unit,
    /**
     * 首屏数据是否还在加载喵。
     *
     * 必需：列表初始为空，若不加区分就会先闪一下「还没有对话」再变成真实列表，
     * 看起来像"打开时白了一下又显示"喵。加载中时留白（底色由外壳铺好）比闪错内容好喵。
     */
    loading: Boolean = false,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    modifier: Modifier = Modifier,
) {
    var plusMenu by remember { mutableStateOf(false) }
    var hideTarget by remember { mutableStateOf<ChatListEntry?>(null) }
    // 长按弹出的操作菜单挂在哪个条目上（null = 没弹）喵。
    var menuFor by remember { mutableStateOf<ChatListEntry?>(null) }

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
        if (entries.isEmpty() && loading) {
            // 加载中就先留白：底色已由外壳铺成主题色，不会闪白，
            // 也不会闪一下"还没有对话"这种错误内容喵。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        } else if (entries.isEmpty()) {
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
                // 上下各留一点：列表贴到标题栏与底栏时显得挤，
                // 上下让出这段留白之后内容区看起来更舒展喵。
                contentPadding = PaddingValues(top = 6.dp, bottom = 20.dp),
            ) {
                items(entries, key = { it.persona.id }) { entry ->
                    // 外层 Box 只为给长按菜单当锚点：菜单要**贴着这一行**弹出喵。
                    // 放到 Scaffold 末尾的话会锚在屏幕角落，离手指很远喵。
                    // DropdownMenu 内部是 Popup，不受列表裁剪，靠底部也能完整显示喵。
                    //
                    // 里面必须再套一个 Column 来叠行与分隔线——Box 是**层叠**布局，
                    // 直接把两样放进 Box 会让分隔线画在行的上面而不是下面喵。
                    // 显式给 placementSpec喵：裸的 animateItem() 会用 Compose 的默认弹簧，
                    // 那与聊天页列表的位移手感**不一样**（默认是欠阻尼，会轻微回弹）喵。
                    // 两个列表页的让位动画必须同一套，否则切来切去会觉得"哪里不对劲"喵。
                    Box(
                        modifier = Modifier.animateItem(
                            fadeInSpec = null,
                            fadeOutSpec = null,
                            placementSpec = Motion.placement,
                        ),
                    ) {
                        Column {
                            ChatRow(
                                entry = entry,
                                onClick = { onOpenChat(entry.persona) },
                                onLongClick = { menuFor = entry },
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 76.dp),
                                thickness = 0.6.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = Alpha.disabled),
                            )
                        }
                        if (menuFor?.persona?.id == entry.persona.id) {
                            ChatRowMenu(
                                pinned = entry.conversation?.pinned == true,
                                onTogglePin = {
                                    onTogglePin(entry.persona, entry.conversation?.pinned != true)
                                    menuFor = null
                                },
                                onDelete = {
                                    hideTarget = entry
                                    menuFor = null
                                },
                                onDismiss = { menuFor = null },
                            )
                        }
                    }
                }
            }
        }
    }

    // 「删除对话」的语义是**从主界面移除**，不是删角色、也不删记录，必须说清楚喵。
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
 * 长按一行弹出的操作菜单喵。
 *
 * 只有两项：置顶 / 取消置顶、删除对话喵。
 * 「删除对话」在这里是**从主界面移除**（可恢复），与资料页里的「删除联系人」
 * 是两件完全不同的事，所以文案必须写全「对话」二字，别让用户误以为删了人喵。
 */
@Composable
private fun ChatRowMenu(
    pinned: Boolean,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(if (pinned) "取消置顶" else "置顶该对话") },
            leadingIcon = {
                Icon(
                    // 未置顶用描边图钉、已置顶用实心图钉：一眼看出当前状态喵。
                    imageVector = if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                    contentDescription = null,
                    tint = if (pinned) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            },
            onClick = onTogglePin,
        )
        DropdownMenuItem(
            text = { Text("删除对话") },
            leadingIcon = {
                Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error)
            },
            onClick = onDelete,
        )
    }
}

/**
 * 列表里的一行：头像 + 名字 + 最后一句 + 时间喵。
 *
 * 置顶的行用更浅（浅色模式）／更亮（暗色模式）的底色区分，
 * 深色浅色两套方向相反，所以色值来自主题常量而不是就地叠 alpha 喵。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(
    entry: ChatListEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val pinned = entry.conversation?.pinned == true
    val isDark = LocalDarkTheme.current
    val normal = if (isDark) QyChatRowNormalDark else QyChatRowNormalLight
    val pinnedColor = if (isDark) QyChatRowPinnedDark else QyChatRowPinnedLight

    // **按下底色必须真的接上 interaction source**喵。
    // 原来这里写的是 `var pressed by remember { mutableStateOf(false) }`，
    // 而它**从来没有被赋过 true**——于是下面那个 `pressed ->` 分支
    // 是永远走不到的死代码，会话行按下去只有一层涟漪、没有底色变化喵。
    //
    // 这种"看起来实现了、实际永不执行"的写法比完全没写更糟喵：
    // 读代码的人会以为这里已经有按压反馈，于是不会再去加喵。
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(
        targetValue = when {
            pressed -> MaterialTheme.colorScheme.surfaceContainerHigh
            pinned -> pinnedColor
            else -> normal
        },
        animationSpec = Motion.color,
        label = "chatRowBg",
    )
    val persona = entry.persona
    val conversation = entry.conversation

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .combinedClickable(
                // 涟漪关掉喵：整行已经有底色变化了，再叠一层涟漪
                // 在大块区域上会显得"糊"，而且底色变化本身更贴合列表行的语义喵。
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = "更多操作",
            )
            // 纵向多给 2dp：上下平移让内容区变大之后，
            // 行与行之间再松一点才不显得挤喵。
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 未读红点贴在头像右上角喵：微信就是这个位置，扫一眼就能看到喵。
        // 往右上各偏 6dp，让它压住头像边缘而不是缩在里面——缩在里面会显得像头像的一部分喵。
        Box {
            PersonaAvatar(persona = persona, size = 48.dp, emphasize = true)
            UnreadBadge(
                count = conversation?.unread ?: 0,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 6.dp, y = (-6).dp),
            )
        }
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
