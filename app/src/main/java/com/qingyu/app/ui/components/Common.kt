package com.qingyu.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.BitmapFactory
import com.qingyu.app.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 角色头像喵。
 *
 * 有自定义头像（[avatarImage] 非空且文件还在）就画那张图，否则退回 emoji 喵。
 * 两者共用一个入口，是因为界面上十几个地方都要显示头像；
 * 让每处自己判断"该画图还是画字"，迟早会漏掉一两处、出现同一角色两个样子喵。
 *
 * 图片解码放在 [produceState] 里按文件名缓存：头像会被列表滚动反复重组，
 * 每次都重新解码 512×512 的 PNG 会明显掉帧喵。
 */
@Composable
fun PersonaAvatar(
    emoji: String,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier,
    emphasize: Boolean = false,
    /** 自定义头像的文件名；空串表示用 emoji 喵。 */
    avatarImage: String = "",
) {
    val scheme = MaterialTheme.colorScheme
    val background = if (emphasize) {
        Brush.linearGradient(listOf(scheme.primary, scheme.tertiary))
    } else {
        Brush.linearGradient(listOf(scheme.surfaceContainerHigh, scheme.surfaceContainer))
    }

    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, avatarImage) {
        value = if (avatarImage.isBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                val file = AppContainer.get(context).avatarStore.fileOf(avatarImage)
                file?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() }.getOrNull() }
            }
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .border(1.dp, scheme.outlineVariant.copy(alpha = 0.4f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            // 裁成圆形：头像框本身是圆的，图却是方的，不裁就会溢出圆角喵。
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape),
            )
        } else {
            Text(
                text = emoji.ifBlank { "🌙" },
                fontSize = (size.value * 0.46f).sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 直接按角色画头像的便捷重载喵。 */
@Composable
fun PersonaAvatar(
    persona: com.qingyu.app.data.Persona,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier,
    emphasize: Boolean = false,
) = PersonaAvatar(
    emoji = persona.avatarEmoji,
    size = size,
    modifier = modifier,
    emphasize = emphasize,
    avatarImage = persona.avatarImage,
)

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

/** 微信那种红喵。定成常量而不是就地写字面量，免得几处各写一个略微不同的红喵。 */
private val UnreadRed = Color(0xFFFA5151)

/**
 * 红点里要显示的文案喵；返回 null 表示**不该画**这个徽标喵。
 *
 * 抽成纯函数是为了能钉住边界（见 `UnreadBadgeTest`）喵：0 不画、99 显示 99、
 * 100 显示 99+喵。过了 99 就不再报具体数字——三位数会把圆点撑得很大，
 * 而且"99+"传达的信息已经够了喵。
 */
fun unreadBadgeLabel(count: Int): String? = when {
    count <= 0 -> null
    count > 99 -> "99+"
    else -> count.toString()
}

/**
 * 微信式未读红点：红底、白字、居中喵。
 *
 * 条数为 0 时**整个不画**——留一个空红圈比不画更难看喵。
 */
@Composable
fun UnreadBadge(count: Int, modifier: Modifier = Modifier) {
    val label = unreadBadgeLabel(count) ?: return
    // 一位数用正圆，两位数以上拉成胶囊喵：正圆会把"12"这种两位数的边缘切掉喵。
    val shape = if (label.length <= 1) CircleShape else RoundedCornerShape(50)
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
            .clip(shape)
            .background(UnreadRed)
            .padding(horizontal = 5.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
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
