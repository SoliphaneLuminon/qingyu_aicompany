package com.qingyu.app.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qingyu.app.core.TimeUtil
import com.qingyu.app.data.ChatMessage
import com.qingyu.app.data.MessageRole
import com.qingyu.app.data.Persona
import com.qingyu.app.data.repo.ChatStreamState
import com.qingyu.app.ui.components.PersonaAvatar
import com.qingyu.app.ui.theme.LocalDarkTheme
import com.qingyu.app.ui.theme.QyChatBackgroundDark
import com.qingyu.app.ui.theme.QyChatBackgroundLight
import com.qingyu.app.ui.vm.ChatUiState
import com.qingyu.app.ui.vm.ChatViewModel
import java.util.Calendar

sealed interface TimelineItem {
    data class Day(val label: String) : TimelineItem
    data class Msg(val message: ChatMessage, val showAvatar: Boolean) : TimelineItem
    data class Bubble(val bubble: ChatStreamState.Bubble, val streaming: Boolean) : TimelineItem
    data class Reason(val text: String, val streaming: Boolean) : TimelineItem
}

/**
 * 聊天页喵。
 *
 * 键盘相关的两个高度都由外壳 [com.qingyu.app.ui.QingYuAppRoot] 测量后传入喵，
 * 这里只做一次减法。原因见 [reservedBottomInset] 的说明喵。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    viewModel: ChatViewModel,
    speaking: Boolean,
    showReasoning: Boolean,
    onOpenDrawer: () -> Unit,
    onOpenPersonas: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenDebug: () -> Unit,
    modifier: Modifier = Modifier,
    /** 软键盘当前占据的高度；0 表示键盘收起喵。 */
    imeBottomInset: Dp = 0.dp,
    /**
     * 外壳已经为底部让出的高度，例如底部标签栏喵。
     *
     * 键盘高度是**相对屏幕底**量的，而本页可用区域的底边已经在内侧被让掉一截喵
     * （外壳 Scaffold 的 innerPadding）。两者直接用就会重复让位喵：
     * 输入栏会比键盘顶再高出一个标签栏的高度，中间空出一条喵。
     */
    reservedBottomInset: Dp = 0.dp,
) {
    // 真正还需要抬升的量：键盘高度减去外壳已经让掉的那一段，下限为 0喵。
    val bottomGap = (imeBottomInset - reservedBottomInset).coerceAtLeast(0.dp)
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) viewModel.attachImage(uri) }

    val timeline = remember(state.messages, state.stream, showReasoning) {
        buildTimeline(state).filter { it !is TimelineItem.Reason || showReasoning }
    }

    LaunchedEffect(state.banner) {
        state.banner?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissBanner()
        }
    }

    if (state.needsSetup) {
        WelcomeDialog(
            onOpenSettings = onOpenSettings,
            onDismiss = { viewModel.markSetupSeen() },
        )
    }

    state.previewImage?.let { attachment ->
        ImageViewerDialog(
            attachment = attachment,
            onDismiss = { viewModel.closeImagePreview() },
        )
    }

    LaunchedEffect(timeline.size, timeline.lastOrNull()) {
        if (timeline.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(timeline.lastIndex) }
        }
    }

    // 外层 Box 的背景色设为输入栏的 surface 色喵。
    //    - 键盘收起：Scaffold 填满屏幕，Box 背景被遮住，输入栏贴屏幕底部喵。
    //    - 键盘弹出：Scaffold 上移 imeBottomInset，露出下方这段 Box 背景色喵，
    //               与输入栏的 surface 色完全连成一体，视觉上毫无断层喵。
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = bottomGap),
            containerColor = if (LocalDarkTheme.current) QyChatBackgroundDark else QyChatBackgroundLight,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    modifier = Modifier.statusBarsPadding(),
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                    navigationIcon = {
                        IconButton(onClick = onOpenDrawer) {
                            Icon(Icons.Outlined.Menu, contentDescription = "会话")
                        }
                    },
                    title = {
                        val persona = state.persona
                        val topTyping = state.topBarTyping || state.stream.generating
                        if (topTyping) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PersonaAvatar(emoji = persona?.avatarEmoji ?: "🌙", size = 34.dp, emphasize = true)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = "对方正在输入中…",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PersonaAvatar(emoji = persona?.avatarEmoji ?: "🌙", size = 34.dp, emphasize = true)
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = persona?.name ?: "轻语",
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = state.conversation?.title ?: persona?.tagline.orEmpty(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenSearch) {
                            Icon(Icons.Outlined.Search, contentDescription = "搜索")
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = "更多")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("新对话") },
                                    leadingIcon = { Icon(Icons.Outlined.Add, null) },
                                    onClick = {
                                        viewModel.newConversation()
                                        menuOpen = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("收藏这段对话") },
                                    leadingIcon = { Icon(Icons.Outlined.FavoriteBorder, null) },
                                    onClick = {
                                        viewModel.favoriteConversation()
                                        menuOpen = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("切换角色") },
                                    onClick = {
                                        onOpenPersonas()
                                        menuOpen = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("接口与设置") },
                                    onClick = {
                                        onOpenSettings()
                                        menuOpen = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("上下文调试") },
                                    onClick = {
                                        onOpenDebug()
                                        menuOpen = false
                                    },
                                )
                            }
                        }
                    },
                )
            },
            snackbarHost = {
                SnackbarHost(snackbarHostState) { data ->
                    Snackbar(snackbarData = data, shape = RoundedCornerShape(14.dp))
                }
            },
            bottomBar = {
                ChatInputBar(
                    state = state,
                    speaking = speaking,
                    onInputChange = viewModel::onInputChange,
                    onSend = viewModel::send,
                    onStop = viewModel::stopGenerating,
                    onPickImage = { imagePicker.launch("image/*") },
                    onRemoveAttachment = viewModel::removePendingAttachment,
                    onToggleSearch = viewModel::toggleSearch,
                    onSpeakToggle = {
                        if (speaking) {
                            viewModel.stopSpeaking()
                        } else {
                            state.messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.let(viewModel::speak)
                        }
                    },
                    onRetry = viewModel::retryLast,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                when {
                    state.loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                    timeline.isEmpty() -> EmptyChatHint(
                        persona = state.persona,
                        modifier = Modifier.align(Alignment.Center),
                    )

                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 12.dp, bottom = 16.dp),
                    ) {
                        items(
                            items = timeline,
                            key = { item ->
                                when (item) {
                                    is TimelineItem.Day -> "day-${item.label}"
                                    is TimelineItem.Msg -> "msg-${item.message.id}"
                                    is TimelineItem.Bubble -> "bubble-${item.bubble.id}-${item.bubble.timestamp}"
                                    is TimelineItem.Reason -> "reason-${item.text.hashCode()}"
                                }
                            },
                            // 新消息插进来时，后面的项平滑让位而不是瞬移；
                            // 删除时同理，整段列表不会"跳"一下喵。
                            contentType = { item ->
                                when (item) {
                                    is TimelineItem.Day -> "day"
                                    is TimelineItem.Msg -> "msg"
                                    is TimelineItem.Bubble -> "bubble"
                                    is TimelineItem.Reason -> "reason"
                                }
                            },
                        ) { item ->
                            // 每项单独的插入/位移过渡喵。
                            val itemModifier = Modifier.animateItem()
                            when (item) {
                                is TimelineItem.Day -> DaySeparator(
                                    text = item.label,
                                    modifier = itemModifier.padding(vertical = 10.dp),
                                )

                                is TimelineItem.Msg -> MessageRow(
                                    message = item.message,
                                    persona = state.persona,
                                    showAvatar = item.showAvatar,
                                    onRegenerate = { viewModel.regenerate(item.message.id) },
                                    onToggleFavorite = { viewModel.toggleFavorite(item.message) },
                                    onDelete = { viewModel.deleteMessage(item.message) },
                                    onSpeak = { viewModel.speak(item.message) },
                                    onOpenImage = { viewModel.openImagePreview(it) },
                                    modifier = itemModifier,
                                )

                                is TimelineItem.Bubble -> MessageRow(
                                    message = ChatMessage(
                                        id = item.bubble.id,
                                        conversationId = state.conversation?.id ?: 0L,
                                        role = MessageRole.ASSISTANT,
                                        content = item.bubble.text,
                                        timestamp = item.bubble.timestamp,
                                    ),
                                    persona = state.persona,
                                    streaming = item.streaming,
                                    showAvatar = true,
                                    modifier = itemModifier,
                                )

                                is TimelineItem.Reason -> Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 4.dp),
                                ) {
                                    Spacer(Modifier.width(38.dp))
                                    ReasoningBlock(
                                        text = item.text,
                                        durationMs = state.stream.reasoningDurationMs,
                                        streaming = item.streaming,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 首次打开时的引导喵。 */
@Composable
private fun WelcomeDialog(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("先接上你的模型") },
        text = {
            Column {
                Text(
                    text = "轻语不代理你的对话：请求从这台手机直接发给你填的接口，" +
                            "聊天记录只存在本机数据库里。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "到「设置 → 接口」填写 OpenAI 兼容的地址、Key 与模型名即可开始。" +
                            "DeepSeek、Ollama、LM Studio、各类中转都行。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.Button(onClick = {
                onDismiss()
                onOpenSettings()
            }) { Text("去设置") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("先看看") }
        },
    )
}

@Composable
private fun EmptyChatHint(persona: Persona?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PersonaAvatar(emoji = persona?.avatarEmoji ?: "🌙", size = 72.dp, emphasize = true)
        Spacer(Modifier.height(16.dp))
        Text(text = persona?.name ?: "轻语", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            text = persona?.tagline.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(18.dp))
        Text(
            text = "说点什么，开始这段对话",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatInputBar(
    state: ChatUiState,
    speaking: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPickImage: () -> Unit,
    onRemoveAttachment: (com.qingyu.app.data.Attachment) -> Unit,
    onToggleSearch: () -> Unit,
    onSpeakToggle: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                AnimatedVisibility(visible = state.stream.searching || state.importingImage) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        BreathingHint(text = if (state.importingImage) "正在处理图片…" else "正在联网检索…")
                    }
                }

                AnimatedVisibility(visible = state.pendingAttachments.isNotEmpty()) {
                    PendingAttachmentsRow(
                        attachments = state.pendingAttachments,
                        onRemove = onRemoveAttachment,
                    )
                }

                AnimatedVisibility(visible = state.lastFailedInput != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "上次没有发出去",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "重试",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.error)
                                    .clickable(onClick = onRetry)
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 6.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                IconButton(onClick = onPickImage, enabled = !state.importingImage) {
                    Icon(
                        imageVector = Icons.Outlined.AddPhotoAlternate,
                        contentDescription = "发送图片",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextField(
                    value = state.input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("说点什么…") },
                    maxLines = 5,
                    shape = RoundedCornerShape(22.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                )
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onToggleSearch) {
                    Icon(
                        imageVector = if (state.searchEnabled) Icons.Outlined.Public else Icons.Outlined.Language,
                        contentDescription = if (state.searchEnabled) "联网已开启" else "联网已关闭",
                        tint = if (state.searchEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        },
                    )
                }
                IconButton(onClick = onSpeakToggle) {
                    Icon(
                        imageVector = if (speaking) {
                            Icons.AutoMirrored.Outlined.VolumeOff
                        } else {
                            Icons.AutoMirrored.Outlined.VolumeUp
                        },
                        contentDescription = "朗读",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                CircleActionButton(
                    icon = Icons.AutoMirrored.Outlined.Send,
                    contentDescription = "发送",
                    container = if (state.canSend) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    tint = if (state.canSend) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    },
                    enabled = state.canSend,
                    onClick = onSend,
                )
            }
        }
    }
}

/** 待发送图片的横向预览条，每张右上角可以删掉喵。 */
@Composable
private fun PendingAttachmentsRow(
    attachments: List<com.qingyu.app.data.Attachment>,
    onRemove: (com.qingyu.app.data.Attachment) -> Unit,
) {
    androidx.compose.foundation.lazy.LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(attachments, key = { it.id.let { id -> if (id > 0) id else it.localPath.hashCode().toLong() } }) { attachment ->
            Box {
                AttachmentThumbnail(
                    attachment = attachment,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.65f))
                        .clickable { onRemove(attachment) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "移除",
                        tint = Color.White,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CircleActionButton(
    icon: ImageVector,
    contentDescription: String,
    container: Color,
    tint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(container)
            .alpha(if (enabled) 1f else 0.85f)
            .clickable(
                enabled = enabled,
                indication = null,
                interactionSource = interaction,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, tint = tint)
    }
}

/** 把消息与流式气泡合并成一条时间线，并在跨天处插入日期喵。 */
private fun buildTimeline(state: ChatUiState): List<TimelineItem> {
    val items = ArrayList<TimelineItem>(state.messages.size + state.stream.bubbles.size + 4)

    var lastRole: MessageRole? = null
    var lastDay: String? = null

    state.messages.forEach { message ->
        val day = dayLabel(message.timestamp)
        if (day != lastDay) {
            items.add(TimelineItem.Day(day))
            lastDay = day
            lastRole = null
        }
        val showAvatar = message.role != MessageRole.REASONING && lastRole != message.role
        items.add(TimelineItem.Msg(message, showAvatar))
        lastRole = message.role
    }

    val reasoning = state.stream.reasoning.trim()
    if (state.stream.generating && reasoning.isNotEmpty()) {
        val alreadyShown = state.messages.lastOrNull()?.let {
            it.role == MessageRole.REASONING && it.content == reasoning
        } ?: false
        if (!alreadyShown) {
            items.add(TimelineItem.Reason(reasoning, true))
        }
    }

    state.stream.bubbles.forEachIndexed { index, bubble ->
        val isLast = index == state.stream.bubbles.lastIndex
        val alreadySaved = bubble.id > 0 && state.messages.any { it.id == bubble.id }
        if (!alreadySaved) {
            items.add(TimelineItem.Bubble(bubble, streaming = state.stream.generating && isLast))
        }
    }

    return items
}

private fun dayLabel(timestamp: Long): String {
    val now = TimeUtil.now()
    return when {
        sameDay(timestamp, now) -> "今天"
        sameDay(timestamp, now - 86_400_000L) -> "昨天"
        else -> TimeUtil.smart(timestamp).substringBefore(' ')
    }
}

private fun sameDay(a: Long, b: Long): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a }
    val cb = Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
            ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}