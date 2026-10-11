package com.qingyu.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 圆形 emoji 头像，带一圈柔和渐变底喵。 */
@Composable
fun PersonaAvatar(
    emoji: String,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier,
    emphasize: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val background = if (emphasize) {
        Brush.linearGradient(listOf(scheme.primary, scheme.tertiary))
    } else {
        Brush.linearGradient(listOf(scheme.surfaceContainerHigh, scheme.surfaceContainer))
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .border(1.dp, scheme.outlineVariant.copy(alpha = 0.4f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = emoji.ifBlank { "🌙" },
            fontSize = (size.value * 0.46f).sp,
            textAlign = TextAlign.Center,
        )
    }
}

/** 空状态：一句主标题 + 一句解释 + 可选操作喵。 */
@Composable
fun EmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    icon: String = "🌙",
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = icon, fontSize = 44.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

/** 分区小标题，用于设置页与详情页喵。 */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** 收藏角标喵。 */
@Composable
fun FavoriteBadge(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Outlined.Favorite,
        contentDescription = "已收藏",
        tint = MaterialTheme.colorScheme.tertiary,
        modifier = modifier.size(14.dp),
    )
}

/** 小圆点分隔符喵。 */
@Composable
fun Dot(alpha: Float = 0.5f) {
    Box(
        modifier = Modifier
            .size(3.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)),
    )
}

/** 聊天气泡外框：统一圆角与配色，避免各处手写喵。 */
@Composable
fun BubbleSurface(
    isUser: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = if (isUser) {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp)
    } else {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 6.dp, bottomEnd = 20.dp)
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (isUser) scheme.primary else scheme.surfaceContainerHigh)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        content()
    }
}

/** 供"标签"使用的小胶囊喵。 */
@Composable
fun TagChip(text: String, modifier: Modifier = Modifier, selected: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) scheme.primaryContainer else scheme.surfaceContainer)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
        )
    }
}

/** 三个跳动的小点，表示"正在输入"喵。 */
@Composable
fun TypingDots(modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .size(if (index == 1) 6.dp else 5.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = if (index == 1) 0.85f else 0.45f)),
            )
        }
    }
}

/** 极细的分割线喵。 */
@Composable
fun HairLine(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
    )
}

/** 用于列表项的左侧强调条喵。尺寸可调，说明弹层的小标题也复用它喵。 */
@Composable
fun AccentBar(
    color: Color,
    modifier: Modifier = Modifier,
    width: Dp = 3.dp,
    height: Dp = 28.dp,
) {
    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(2.dp))
            .background(color),
    )
}

/** 统一的数值/信息行喵。 */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
    }
}
