package com.qingyu.app.ui.chat

import android.graphics.BitmapFactory
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingyu.app.core.TimeUtil
import com.qingyu.app.ui.theme.LocalDarkTheme
import com.qingyu.app.ui.theme.QyPetBubble
import com.qingyu.app.ui.theme.QyPetBubbleDark
import com.qingyu.app.ui.theme.QyUserBubbleDark
import com.qingyu.app.ui.theme.QyUserBubbleLight
import com.qingyu.app.data.ChatMessage
import com.qingyu.app.data.MessageKind
import com.qingyu.app.data.MessageRole
import com.qingyu.app.data.Persona
import com.qingyu.app.data.RedPacket
import com.qingyu.app.ui.components.PersonaAvatar
import com.qingyu.app.ui.components.TypingDots
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.qingyu.app.ui.theme.Motion

/** 思考过程折叠块。默认折叠，只显示"思考了 2.4 秒"喵。 */
@Composable
fun ReasoningBlock(
    text: String,
    durationMs: Long,
    streaming: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val label = when {
        streaming -> "正在思考…"
        durationMs > 0 -> "思考了 ${"%.1f".format(durationMs / 1000.0)} 秒"
        else -> "思考过程"
    }
    Column(
        modifier = modifier
            .widthIn(max = 300.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surfaceContainer)
            .clickable(enabled = text.isNotBlank()) { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "💭", fontSize = 12.sp)
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (text.isNotBlank()) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (expanded && text.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = text.trim(),
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = scheme.onSurfaceVariant,
            )
        } else if (streaming && text.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = text.trim().takeLast(60),
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = scheme.onSurfaceVariant.copy(alpha = 0.75f),
                maxLines = 2,
            )
        }
    }
}

/** 一条消息。点击气泡弹出操作菜单喵。 */
@Composable
fun MessageRow(
    message: ChatMessage,
    persona: Persona?,
    streaming: Boolean = false,
    showAvatar: Boolean = true,
    /** 这一条是不是**刚到的**消息；只有它才做入场动画喵。 */
    animateIn: Boolean = true,
    showReasoning: Boolean = true,
    onRegenerate: () -> Unit = {},
    onToggleFavorite: () -> Unit = {},
    onDelete: () -> Unit = {},
    onSpeak: () -> Unit = {},
    onOpenImage: (com.qingyu.app.data.Attachment) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val isUser = message.role == MessageRole.USER
    val scheme = MaterialTheme.colorScheme
    val isDark = LocalDarkTheme.current
    // 微信气泡：自己=浅绿/鲜绿，对方=白/深灰。文字在同主题下都取深色/浅色对比较高的一种喵。
    val bubbleColor = if (isUser) {
        if (isDark) QyUserBubbleDark else QyUserBubbleLight
    } else {
        if (isDark) QyPetBubbleDark else QyPetBubble
    }
    val bubbleTextColor = if (isUser && !isDark) Color(0xFF111111) else Color.White
    var menuOpen by remember { mutableStateOf(false) }
    val copyText = com.qingyu.app.ui.components.rememberCopyText()

    // 新出现的消息淡入 + 轻微上浮 + 从自己那一侧滑进来：
    // 自己发的从右边来、对方的从左边来，方向感与气泡位置一致喵。
    // 必须放在下面那个提前 return 之前——否则用户切换"显示思考过程"时，
    // 本函数的 remember 调用次数会变，Compose 的插槽对不上会直接崩喵。
    //
    // **只有刚到的消息才走这套**喵（见 [animateIn]）：原来每条消息第一次进组合
    // 都从 alpha=0 起跑，于是每次打开对话，可视区几十条会同时淡入 + 位移——
    // 既白掉一批帧，观感上也像"整段历史刚被重放了一遍"喵。
    val appear = remember(message.id) { Animatable(if (animateIn) 0f else 1f) }
    LaunchedEffect(message.id) {
        if (animateIn) appear.animateTo(1f, Motion.gentle)
    }

    // 用户在设置里关掉思考展示时，reasoning 消息整条不渲染喵。
    if (message.role == MessageRole.REASONING && !showReasoning) return

    // 不做入场动画的消息**不挂 graphicsLayer**喵：那一层是为动画开的独立渲染层，
    // 历史消息常驻几十条时它就是纯开销喵。
    val rowModifier = if (animateIn) {
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = appear.value
                translationY = (1f - appear.value) * 12f
                // 位移压得很小：气泡本身就在屏幕一侧，滑太远会显得晃喵。
                translationX = (1f - appear.value) * (if (isUser) 26f else -26f)
            }
            .padding(horizontal = 12.dp, vertical = 4.dp)
    } else {
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    }

    Row(
        modifier = rowModifier,
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!isUser) {
            if (showAvatar) {
                PersonaAvatar(
                    emoji = persona?.avatarEmoji ?: "🌙",
                    avatarImage = persona?.avatarImage.orEmpty(),
                    size = 30.dp,
                )
                Spacer(Modifier.width(8.dp))
            } else {
                Spacer(Modifier.width(38.dp))
            }
        }

        Box {
            Column(
                horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
                modifier = Modifier.widthIn(max = 300.dp),
            ) {
                if (message.role == MessageRole.REASONING) {
                    ReasoningBlock(
                        text = message.content,
                        durationMs = message.reasoningDuration,
                        streaming = false,
                    )
                } else {
                    val shape = if (isUser) {
                        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp)
                    } else {
                        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 6.dp, bottomEnd = 20.dp)
                    }
                    // 有图时把图片放在气泡外：图片本身不需要气泡底色，压在一起会显得脏喵
                    Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
                        when (message.kind) {
                            // 表情包：只画图，**不画名字**喵。
                            // 名字是给模型看的文字那一半，摆进气泡里会让用户看到
                            // "开心"两个字压在图上，而微信里表情就是一张图喵。
                            MessageKind.STICKER -> StickerBubble(
                                message = message,
                                onOpen = onOpenImage,
                            )

                            // 红包：画成一张能看出金额与留言的卡片喵。
                            // 金额取自 payload 而不是解析 content，文案怎么改都不影响显示喵。
                            MessageKind.RED_PACKET -> {
                                val packet = RedPacket.fromPayload(message.payload)
                                if (packet != null) {
                                    RedPacketBubble(
                                        amount = packet.amount,
                                        wish = packet.wish,
                                        modifier = Modifier.padding(bottom = 2.dp),
                                    )
                                } else {
                                    // 老数据或被截断的 payload：退化成一句纯文本，不显示空白喵。
                                    Text(
                                        text = message.content,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = bubbleTextColor,
                                        modifier = Modifier
                                            .clip(shape)
                                            .background(bubbleColor)
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                    )
                                }
                            }

                            MessageKind.TEXT -> {
                                if (message.attachments.isNotEmpty()) {
                                    MessageImages(
                                        attachments = message.attachments,
                                        onOpen = onOpenImage,
                                        modifier = Modifier.padding(bottom = 4.dp),
                                    )
                                }
                            }
                        }
                        // 表情与红包都不再画文字气泡：内容已经在卡片/图里表达完了喵。
                        val drawTextBubble = message.kind == MessageKind.TEXT &&
                            (message.content.isNotBlank() || message.error != null || streaming)
                        if (drawTextBubble) {
                            Column(
                                modifier = Modifier
                                    .clip(shape)
                                    .background(bubbleColor)
                                    .clickable { menuOpen = true }
                                    .animateContentSize()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                            ) {
                                if (message.content.isNotBlank()) {
                                    Text(
                                        text = message.content,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = bubbleTextColor,
                                    )
                                }
                                if (streaming) {
                                    Spacer(Modifier.height(6.dp))
                                    TypingDots(tint = bubbleTextColor)
                                }
                                if (message.error != null) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = message.error,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = scheme.error,
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
                ) {
                    if (message.favorite) {
                        Icon(
                            imageVector = Icons.Outlined.Favorite,
                            contentDescription = null,
                            tint = scheme.tertiary,
                            modifier = Modifier.size(11.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        text = TimeUtil.clock(message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }

                if (message.searchResults.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    SearchSources(hits = message.searchResults)
                }
            }

            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("复制") },
                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                    onClick = {
                        copyText(message.content)
                        menuOpen = false
                    },
                )
                if (!isUser && message.role != MessageRole.REASONING) {
                    DropdownMenuItem(
                        text = { Text("朗读") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.VolumeUp, null) },
                        onClick = {
                            onSpeak()
                            menuOpen = false
                        },
                    )
                    // 只有真落过库的消息才给「重新生成」喵：流式过程中那条乐观气泡的 id 是 -1，
                    // 拿它去重新生成，引擎查不到目标就不会删旧内容，于是**再生成一条**
                    // ——用户拿到的是两条回复，而不是把原来那条换掉喵。
                    if (message.id > 0) {
                        DropdownMenuItem(
                            text = { Text("重新生成") },
                            leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                            onClick = {
                                onRegenerate()
                                menuOpen = false
                            },
                        )
                    }
                }
                if (message.role != MessageRole.REASONING) {
                    DropdownMenuItem(
                        text = { Text(if (message.favorite) "取消收藏" else "收藏") },
                        leadingIcon = {
                            Icon(
                                if (message.favorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                                null,
                            )
                        },
                        onClick = {
                            onToggleFavorite()
                            menuOpen = false
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("删除") },
                    leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                    onClick = {
                        onDelete()
                        menuOpen = false
                    },
                )
            }
        }

        if (isUser) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(scheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "我", style = MaterialTheme.typography.labelMedium, color = scheme.onPrimaryContainer)
            }
        }
    }
}

/**
 * 表情包气泡喵。
 *
 * 只画图，不画名字喵。名字是这条消息**给模型看的那一半**（作为 content 进入上下文），
 * 对用户来说表情就是一张图——把"开心"两个字压在图上，反而像在看文件名喵。
 * 名字该出现的地方是表情管理页，那里才是给它命名的地方喵。
 */
@Composable
private fun StickerBubble(
    message: ChatMessage,
    onOpen: (com.qingyu.app.data.Attachment) -> Unit,
) {
    val attachment = message.attachments.firstOrNull()
    if (attachment == null) {
        // 没有附件（例如导入的历史里只剩名字）：退化成文字，不显示空白喵。
        Text(
            text = message.content.ifBlank { "[表情]" },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, attachment.localPath) {
        value = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(attachment.localPath)?.asImageBitmap() }.getOrNull()
        }
    }
    Box(
        modifier = Modifier
            .size(120.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onOpen(attachment) },
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = message.content,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text("🖼", style = MaterialTheme.typography.headlineMedium)
        }
    }
}

/** 日期分隔小标签喵。 */
@Composable
fun DaySeparator(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

/** 正在生成时的"呼吸"提示条喵。 */
@Composable
fun BreathingHint(text: String, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "breath")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "alpha",
    )
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}
