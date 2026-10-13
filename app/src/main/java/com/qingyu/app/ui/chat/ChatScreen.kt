package com.qingyu.app.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.KeyboardVoice
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhoneInTalk
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.TimeZone
import com.qingyu.app.ui.theme.Motion

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
    onBack: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenChatInfo: () -> Unit,
    /** 首启引导里那句「去设置」跳到哪；顶栏的「⋯」不再是它喵。 */
    onOpenSettings: () -> Unit,
    /** 打开实时语音通话页喵。 */
    onStartCall: () -> Unit = {},
    /** 打开表情管理页喵。 */
    onOpenStickerManager: () -> Unit = {},
    modifier: Modifier = Modifier,
    /** 软键盘当前占据的高度；0 表示键盘收起喵。 */
    imeBottomInset: Dp = 0.dp,
    /**
     * 系统底部让位（导航栏 / 手势条）喵。
     *
     * 本页是 edge-to-edge 且 `contentWindowInsets` 显式置零，没人替它避让系统栏喵；
     * 不处理的话输入栏会压在手势条下面，按钮点不到喵。
     */
    systemBottomInset: Dp = 0.dp,
    /**
     * 外壳已经为底部让出的高度，例如底部标签栏喵。
     *
     * 键盘高度是**相对屏幕底**量的，而本页可用区域的底边已经在内侧被让掉一截喵
     * （外壳 Scaffold 的 innerPadding）。两者直接用就会重复让位喵：
     * 输入栏会比键盘顶再高出一个标签栏的高度，中间空出一条喵。
     */
    reservedBottomInset: Dp = 0.dp,
) {
    // 键盘与系统栏取**较大者**而不是相加喵：
    // 键盘弹起时它本身已盖住导航栏，相加会多让出一截空白；
    // 取较大者两种情形都对——有键盘让给键盘，没键盘让给手势条喵。
    val bottomGap = (maxOf(imeBottomInset, systemBottomInset) - reservedBottomInset)
        .coerceAtLeast(0.dp)
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) viewModel.attachImage(uri) }

    // 表情：从相册选一张 / 从文件选一个压缩包喵。
    val stickerImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) viewModel.importStickerFromUri(uri) }
    val stickerZipPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) viewModel.importStickersFromZip(uri) }

    // 录音权限：按住说话时才申请，而不是一进聊天页就弹窗——
    // 用户还没表达要用语音，先要权限会显得突兀喵。
    val audioPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.startRecording()
        } else {
            viewModel.showBanner("没有麦克风权限，按住说话用不了")
        }
    }

    // **拆成两半算**喵，这是进聊天框与发消息掉帧的主因之一：
    // 原来整条时间线只挂了一个 remember，键里带着 state.stream——
    // 而流式期间 stream **每来一个 token 就是一个新对象**，于是每个 token 都要
    // 重扫全部消息、重算日期分隔，一次 O(消息数) 的重建喵。
    //
    // 历史那半只跟 messages 有关、流式那半只跟 stream 有关，各算各的、各自缓存喵。
    val historyItems = remember(state.messages, showReasoning) {
        buildHistoryItems(state.messages, showReasoning)
    }
    // 已落库 id 的集合：流式那半用它判"这个气泡是不是已经落库了"喵。
    // 用集合是 O(1)，而它只在消息变化时重建一次（不是每个 token）喵。
    val messageIds = remember(state.messages) {
        state.messages.mapTo(HashSet(state.messages.size)) { it.id }
    }
    val lastMessage = state.messages.lastOrNull()
    val streamItems = remember(state.stream, showReasoning, messageIds, lastMessage) {
        buildStreamItems(
            stream = state.stream,
            showReasoning = showReasoning,
            messageIds = messageIds,
            lastRole = lastMessage?.role,
            lastContent = lastMessage?.content.orEmpty(),
        )
    }
    val timeline = if (streamItems.isEmpty()) historyItems else historyItems + streamItems

    // 「刚到的消息」的分界线喵：进入这段对话那一刻的最大消息 id。
    // 比它大的都是这次进来之后才到的（自己发的、对方回的、表情、思考），
    // 只有这些做入场动画；历史消息就地画出来、不淡入也不位移喵。
    val baselineId = remember(state.conversation?.id) {
        state.messages.maxOfOrNull { it.id } ?: 0L
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

    // **只有"新来了一条"才自动滚到底**喵。
    //
    // 原来键里挂着 `timeline.lastOrNull()`——那是一个每次重建都会换身份的对象，
    // 而流式期间时间线每个 token 都在重建，于是这个 effect 每个 token 重启一次、
    // 每次都把上一次的滚动动画打断重来：屏幕一直在被拽，帧也就一直掉喵。
    // 现在键换成**最后一条的稳定 key**（字符串），内容增长不会重启它喵。
    //
    // **进入对话时不用动画滚**喵：刚进来时列表还没排过版（`visibleItemsInfo` 是空的），
    // 原来的代码会走 `animateScrollToItem` 从**顶部**一路动画滚到最后一条——
    // 一段 300 条的对话就是滚过 300 项，`dumpsys gfxinfo` 里的
    // `Slow issue draw commands` 正是这么来的喵。
    // 进对话该"直接就位"，而不是让用户看着它从最早的消息滚下来喵。
    var landed by remember(state.conversation?.id) { mutableStateOf(false) }
    val lastItemKey = timeline.lastOrNull()?.let(::timelineKey)
    LaunchedEffect(timeline.size, lastItemKey, landed) {
        if (timeline.isEmpty()) return@LaunchedEffect
        if (!landed) {
            // 第一次定位：**瞬时跳**到底部，一帧到位、不做动画喵。
            runCatching { listState.scrollToItem(timeline.lastIndex) }
            landed = true
            return@LaunchedEffect
        }
        // 之后才是"新消息来了"：用户自己往上翻看历史时**绝不硬拽回去**喵，
        // 只在本来就在底部附近（最后两条可见）时才跟随喵。
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@LaunchedEffect
        if (lastVisible >= info.totalItemsCount - 2) {
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
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回聊天列表")
                        }
                    },
                    title = {
                        val persona = state.persona
                        val topTyping = state.topBarTyping || state.stream.generating
                        if (topTyping) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PersonaAvatar(
                                        emoji = persona?.avatarEmoji ?: "🌙",
                                        avatarImage = persona?.avatarImage.orEmpty(),
                                        size = 34.dp,
                                        emphasize = true,
                                    )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = "对方正在输入中…",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PersonaAvatar(
                                        emoji = persona?.avatarEmoji ?: "🌙",
                                        avatarImage = persona?.avatarImage.orEmpty(),
                                        size = 34.dp,
                                        emphasize = true,
                                    )
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
                        // 三个点不再是下拉菜单，而是进入「聊天信息」独立页面喵——
                        // 菜单项一多就该有独立页面，那里还能顺手放上角色头像与资料入口喵。
                        IconButton(onClick = onOpenChatInfo) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "聊天信息")
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
                    onInputFocusChanged = viewModel::onInputFocusChanged,
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
                    onToggleStickerPanel = viewModel::toggleStickerPanel,
                    onTogglePlusPanel = viewModel::togglePlusPanel,
                    onPickSticker = viewModel::sendSticker,
                    onImportSticker = { stickerImagePicker.launch("image/*") },
                    onOpenStickerManager = onOpenStickerManager,
                    onOpenRedPacket = viewModel::openRedPacket,
                    onVoiceHoldStart = {
                        // 先问权限再开录；已经有权限就直接开始喵。
                        if (viewModel.hasAudioPermission()) {
                            viewModel.startRecording()
                        } else {
                            audioPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onVoiceHoldEnd = { viewModel.stopRecordingAndSend() },
                    onStartCall = onStartCall,
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
                            key = { item -> timelineKey(item) },
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
                            // **只留位移过渡**喵：
                            // - 淡出必须关掉（fadeOutSpec = null）：默认淡出会把已经移出列表的
                            //   气泡继续留在屏幕上慢慢变透明，用户看到的是「原消息钉在另一层、
                            //   融进背景」，一直拖到流式结束才消失喵；
                            // - 淡入也关掉：入场动画由 MessageRow 按"是不是刚到的消息"决定，
                            //   两套叠在一起会重复变淡，还白多一层开销喵。
                            // 位移用**临界阻尼**弹簧，与入场动画同一套手感（平滑、不回弹）喵。
                            val itemModifier = Modifier.animateItem(
                                fadeInSpec = null,
                                fadeOutSpec = null,
                                placementSpec = spring(
                                    dampingRatio = 1f,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                            )
                            when (item) {
                                is TimelineItem.Day -> DaySeparator(
                                    text = item.label,
                                    modifier = itemModifier.padding(vertical = 10.dp),
                                )

                                is TimelineItem.Msg -> MessageRow(
                                    message = item.message,
                                    persona = state.persona,
                                    showAvatar = item.showAvatar,
                                    // 只有进入这段对话之后**新到的**消息才做入场动画喵：
                                    // 历史消息就地画出来，不淡入、不位移、也不挂渲染层喵。
                                    animateIn = item.message.id > baselineId,
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
                                    // 流式气泡永远是"刚到的"，入场动画照给喵。
                                    animateIn = true,
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
        PersonaAvatar(
                        emoji = persona?.avatarEmoji ?: "🌙",
                        avatarImage = persona?.avatarImage.orEmpty(),
                        size = 72.dp,
                        emphasize = true,
                    )
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
    onInputFocusChanged: (Boolean) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPickImage: () -> Unit,
    onRemoveAttachment: (com.qingyu.app.data.Attachment) -> Unit,
    onToggleSearch: () -> Unit,
    onSpeakToggle: () -> Unit,
    onRetry: () -> Unit,
    onToggleStickerPanel: () -> Unit,
    onTogglePlusPanel: () -> Unit,
    onPickSticker: (com.qingyu.app.data.Sticker) -> Unit,
    onImportSticker: () -> Unit,
    onOpenStickerManager: () -> Unit,
    onOpenRedPacket: () -> Unit,
    onVoiceHoldStart: () -> Unit,
    onVoiceHoldEnd: () -> Unit,
    onStartCall: () -> Unit,
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
                AnimatedVisibility(visible = state.stream.searching || state.importingImage || state.importingStickers) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        BreathingHint(
                            text = when {
                                state.importingStickers -> "正在导入表情…"
                                state.importingImage -> "正在处理图片…"
                                else -> "正在联网检索…"
                            },
                        )
                    }
                }

                AnimatedVisibility(visible = state.pendingAttachments.isNotEmpty()) {
                    PendingAttachmentsRow(
                        attachments = state.pendingAttachments,
                        onRemove = onRemoveAttachment,
                    )
                }

                AnimatedVisibility(visible = state.recording) {
                    RecordingIndicator(startedAt = state.recordingStartedAt)
                }
            }

            // 输入行按微信排：语音 | 输入框 | 表情 | ＋喵。
            //
            // 顺序不是随意的：语音与表情都是"另一种表达方式"，紧贴输入框两侧，
            // 手指从输入框出发左右各挪一格就能碰到；＋ 是"别的功能"，
            // 放最右边与它收纳的联网/通话/红包一致——都不属于"说话"本身喵。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 6.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                // 最左：按住说话。松手即发送，不必再点一次发送键喵。
                HoldToTalkButton(
                    recording = state.recording,
                    onHoldStart = onVoiceHoldStart,
                    onHoldEnd = onVoiceHoldEnd,
                )

                TextField(
                    value = state.input,
                    onValueChange = onInputChange,
                    modifier = Modifier
                        .weight(1f)
                        // 输入框开合要上报：用户发完消息又点回输入框时，
                        // 回复要往后推一推，别把他正打的字打断在中间喵。
                        .onFocusChanged { onInputFocusChanged(it.isFocused) },
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

                IconButton(onClick = onToggleStickerPanel) {
                    Icon(
                        imageVector = Icons.Outlined.EmojiEmotions,
                        contentDescription = "表情",
                        tint = if (state.stickerPanelOpen) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }

                // 有内容可发时，＋ 让位给发送键：微信也是这个行为，
                // 打完字最想点的就是发送，不该还要先收起一个面板喵。
                if (state.canSend) {
                    CircleActionButton(
                        icon = Icons.AutoMirrored.Outlined.Send,
                        contentDescription = "发送",
                        container = MaterialTheme.colorScheme.primary,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        onClick = onSend,
                    )
                } else {
                    IconButton(onClick = onTogglePlusPanel) {
                        Icon(
                            imageVector = if (state.plusPanelOpen) Icons.Outlined.Close else Icons.Outlined.AddCircleOutline,
                            contentDescription = "更多",
                            tint = if (state.plusPanelOpen) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }

            // 表情面板与 ＋ 面板互斥，同时最多开一个喵。
            AnimatedVisibility(visible = state.stickerPanelOpen) {
                StickerPanel(
                    stickers = state.stickers,
                    onPick = onPickSticker,
                    onImport = onImportSticker,
                    onManage = onOpenStickerManager,
                )
            }

            AnimatedVisibility(visible = state.plusPanelOpen) {
                PlusPanel(
                    searchEnabled = state.searchEnabled,
                    onToggleSearch = onToggleSearch,
                    onPickImage = onPickImage,
                    onStartCall = onStartCall,
                    onRedPacket = onOpenRedPacket,
                    onSpeakToggle = onSpeakToggle,
                    speaking = speaking,
                    onStop = onStop,
                )
            }
        }
    }
}

/**
 * 按住说话按钮喵。
 *
 * 用 `detectTapGestures` 的 `onPress` 而不是 `onLongClick`：这里要的是
 * **按住期间一直录、松手立刻停**喵。`onLongClick` 只在跨过阈值时触发一次，
 * 拿不到"松开"那一刻；而 `onPress` 的回调在整个按压期间挂起，
 * 返回时正好就是松手或取消，语义严丝合缝喵。
 *
 * 用 `onPress` 也顺带解决了另一个坑：手写 `awaitPointerEventScope` 循环时，
 * 协程捕获的是**进入那一刻**的 `recording` 值，之后状态变了它也看不到，
 * 于是会反复触发开始/结束。`onPress` 每次按压都是一次新的挂起，不存在这个问题喵。
 */
@Composable
private fun HoldToTalkButton(
    recording: Boolean,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
) {
    val scale by animateFloatAsState(
        targetValue = if (recording) 1.12f else 1f,
        animationSpec = Motion.snappy,
        label = "holdTalkScale",
    )
    val tint = if (recording) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .padding(bottom = 2.dp)
            .size(48.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(
                if (recording) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    Color.Transparent
                },
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onHoldStart()
                        // 挂起直到松手或被取消，两种情况都该停止录音喵。
                        tryAwaitRelease()
                        onHoldEnd()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (recording) Icons.Outlined.Mic else Icons.Outlined.KeyboardVoice,
            contentDescription = "按住说话",
            tint = tint,
        )
        // 录音中给一圈外环，明确"正在录"这件事不是猜的喵。
        if (recording) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, tint.copy(alpha = 0.55f), CircleShape),
            )
        }
    }
}

/** 录音中的提示条：秒数 + 松手发送喵。 */
@Composable
private fun RecordingIndicator(startedAt: Long) {
    var elapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(startedAt) {
        while (true) {
            elapsed = System.currentTimeMillis() - startedAt
            delay(200)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.error),
        )
        Text(
            text = "正在录音 %.1f 秒，松手发送".format(elapsed / 1000.0),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 表情面板：网格 + 导入入口喵。 */
@Composable
private fun StickerPanel(
    stickers: List<com.qingyu.app.data.Sticker>,
    onPick: (com.qingyu.app.data.Sticker) -> Unit,
    onImport: () -> Unit,
    onManage: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (stickers.isEmpty()) "还没有表情" else "${stickers.size} 个表情",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onImport) { Text("导入") }
            TextButton(onClick = onManage) { Text("管理") }
        }
        if (stickers.isEmpty()) {
            Text(
                text = "从相册选一张图，或导入一个压缩包喵",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                gridItems(stickers, key = { it.id }) { sticker ->
                    StickerCell(sticker = sticker, onClick = { onPick(sticker) })
                }
            }
        }
    }
}

/** 一个表情格子：图 + 名字喵。 */
@Composable
private fun StickerCell(
    sticker: com.qingyu.app.data.Sticker,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, sticker.id, sticker.filePath) {
        value = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(sticker.filePath)?.asImageBitmap() }.getOrNull()
        }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            val image = bitmap
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = sticker.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(56.dp),
                )
            } else {
                Text("🖼", style = MaterialTheme.typography.titleMedium)
            }
        }
        Text(
            text = sticker.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 「＋」面板喵。
 *
 * 收纳的是"不属于打字"的功能：联网搜索、语音通话、红包，以及发图片喵。
 * 这些原来挤在输入行里，把输入框压得很窄；收进面板之后输入行只剩四格，
 * 每一格都够大、不会误触喵。
 */
@Composable
private fun PlusPanel(
    searchEnabled: Boolean,
    onToggleSearch: () -> Unit,
    onPickImage: () -> Unit,
    onStartCall: () -> Unit,
    onRedPacket: () -> Unit,
    onSpeakToggle: () -> Unit,
    speaking: Boolean,
    onStop: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            PlusAction(
                icon = if (searchEnabled) Icons.Outlined.Public else Icons.Outlined.Language,
                label = if (searchEnabled) "联网已开" else "联网搜索",
                highlight = searchEnabled,
                onClick = onToggleSearch,
            )
            PlusAction(
                icon = Icons.Outlined.AddPhotoAlternate,
                label = "图片",
                onClick = onPickImage,
            )
            PlusAction(
                icon = Icons.Outlined.PhoneInTalk,
                label = "语音通话",
                onClick = onStartCall,
            )
            PlusAction(
                icon = Icons.Outlined.CardGiftcard,
                label = "红包",
                onClick = onRedPacket,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            PlusAction(
                icon = if (speaking) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                label = if (speaking) "停止朗读" else "朗读",
                onClick = onSpeakToggle,
            )
            if (speaking) {
                PlusAction(
                    icon = Icons.Outlined.Stop,
                    label = "停止生成",
                    onClick = onStop,
                )
            }
        }
    }
}

/** ＋ 面板里的一个圆形动作喵。 */
@Composable
private fun PlusAction(
    icon: ImageVector,
    label: String,
    highlight: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (highlight) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (highlight) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

/**
 * 时间线的**历史那一半**：消息本体 + 跨天日期分隔喵。
 *
 * 只依赖消息列表，所以流式期间每个 token 都不会把它重算一遍喵
 * （原来整条时间线是一个 remember，键里带 stream，于是每 token 都要
 * 重扫全部消息、重建日期分隔——这是发消息时掉帧的主因之一喵）。
 */
private fun buildHistoryItems(
    messages: List<ChatMessage>,
    showReasoning: Boolean,
): List<TimelineItem> {
    val items = ArrayList<TimelineItem>(messages.size + 4)
    var lastRole: MessageRole? = null
    // **`now` 只取一次**喵：原来每条消息都调 TimeUtil.now()，
    // 一天之内虽然结果一样，但那是一次系统调用乘以消息条数喵。
    val now = TimeUtil.now()
    var lastDayKey = Long.MIN_VALUE
    var lastDayLabel: String? = null

    messages.forEach { message ->
        // 关掉"显示思考过程"时整条不画，也就**不该占一天的分隔**喵。
        if (!showReasoning && message.role == MessageRole.REASONING) return@forEach

        val key = dayKey(message.timestamp, now)
        if (key != lastDayKey) {
            // 分隔标签只在跨天时才需要算（那天大概率是"今天"或"昨天"，
            // 连 TimeUtil.smart 都不会走到）喵。
            lastDayLabel = dayLabel(key, now)
            lastDayKey = key
            items.add(TimelineItem.Day(lastDayLabel))
            lastRole = null
        }
        val showAvatar = message.role != MessageRole.REASONING && lastRole != message.role
        items.add(TimelineItem.Msg(message, showAvatar))
        lastRole = message.role
    }
    return items
}

/**
 * 时间线的**流式那一半**：还在长的思考块 + 还没落库的乐观气泡喵。
 *
 * 只依赖 `stream`（外加两个用来判重的轻量入参），条目数恒为 0~2，
 * 所以每个 token 重算它也不痛喵。
 *
 * @param messageIds 已落库消息的 id 集合；用集合判重是 O(1)，
 *   原先是在消息列表里线性扫一遍，token 级重算时那是 O(消息数) 喵
 * @param lastRole / lastContent 最后一条消息的形态，用来判断思考块是不是已经落库了喵
 */
private fun buildStreamItems(
    stream: ChatStreamState,
    showReasoning: Boolean,
    messageIds: Set<Long>,
    lastRole: MessageRole?,
    lastContent: String,
): List<TimelineItem> {
    val items = ArrayList<TimelineItem>(2)

    val reasoning = stream.reasoning.trim()
    if (showReasoning && stream.generating && reasoning.isNotEmpty()) {
        val alreadyShown = lastRole == MessageRole.REASONING && lastContent == reasoning
        if (!alreadyShown) {
            items.add(TimelineItem.Reason(reasoning, true))
        }
    }

    stream.bubbles.forEachIndexed { index, bubble ->
        val isLast = index == stream.bubbles.lastIndex
        val alreadySaved = bubble.id > 0 && bubble.id in messageIds
        if (!alreadySaved) {
            items.add(TimelineItem.Bubble(bubble, streaming = stream.generating && isLast))
        }
    }

    return items
}

/**
 * 一条时间线在 LazyColumn 里的稳定 key 喵。
 *
 * 抽出来是因为它**有两个用处**：当 `items(key = …)`，以及判断
 * "最后一条是不是换了"（自动滚到底的触发条件）喵。
 * 两处各写一遍的话，改了一处忘了另一处，滚动就会莫名其妙地不跟手喵。
 */
private fun timelineKey(item: TimelineItem): String = when (item) {
    is TimelineItem.Day -> "day-${item.label}"
    is TimelineItem.Msg -> "msg-${item.message.id}"
    is TimelineItem.Bubble -> "bubble-${item.bubble.id}-${item.bubble.timestamp}"
    // **流式思考块用一个固定 key** 喵：它的文字每来一个 token 就变一次，
    // 若把文字算进 key，每个 token 都会「删掉旧的、插入新的」——
    // 叠加动画就是一堆半透明残影摞在背景上，而它们会一直堆到
    // 模型想完、不再有新 token 为止喵。
    // 同一时刻只会有一个实时思考块，固定 key 不会撞车喵。
    is TimelineItem.Reason -> "reason-streaming"
}

/**
 * 算出这条消息该归到哪一天，返回一个**可直接比较**的日期键喵。
 *
 * 原来这里对每条消息都调 `sameDay`（各建两个 `Calendar`）、再退回
 * `TimeUtil.smart`（内部还要建 `SimpleDateFormat`）喵。
 * 一段 300 条的对话就是**上千次对象分配加格式化**，而且全在主线程的组合阶段——
 * 这正是 `dumpsys gfxinfo` 里 `Slow UI thread` 的来源喵。
 *
 * 现在改成：`Calendar` 只建一次（`now` 那一刻），每条消息只做几次整数除法喵。
 * 键直接用于比较，所以"同一天"的判定也从对象比较变成了整数比较喵。
 */
private fun dayKey(timestamp: Long, now: Long): Long {
    // 用"距 epoch 的天数"当键：本地时区的偏移量按同一条消息的时间算，
    // 这样跨时区、跨夏令时都不会把两天并成一天喵。
    val offset = TimeZone.getDefault().getOffset(timestamp)
    return (timestamp + offset) / 86_400_000L
}

private fun dayLabel(key: Long, now: Long): String {
    val todayKey = dayKey(now, now)
    return when (key) {
        todayKey -> "今天"
        todayKey - 1 -> "昨天"
        else -> TimeUtil.smart(key * 86_400_000L).substringBefore(' ')
    }
}