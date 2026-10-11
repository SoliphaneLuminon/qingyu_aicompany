package com.qingyu.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 一个设置项的说明：用法 / 调整影响 / 建议参数喵。
 *
 * 三段是刻意这样分的喵：
 * - [usage] 回答"这是干什么的"，看不懂名字时才需要读它喵
 * - [impact] 回答"改大了会怎样"，这是最容易被忽略、也最容易调坏的一段喵
 * - [suggestion] 给出可直接照抄的推荐值，省去反复试错喵
 *
 * 三段都可为空；全空时 [HelpDot] 不显示，避免出现点了没内容的问号喵。
 */
data class HelpContent(
    val usage: String = "",
    val impact: String = "",
    val suggestion: String = "",
) {
    val isEmpty: Boolean get() = usage.isBlank() && impact.isBlank() && suggestion.isBlank()

    companion object {
        /** 只有一句用法时的简写喵。 */
        fun of(usage: String): HelpContent = HelpContent(usage = usage)

        /** 有用法与影响时的简写喵。 */
        fun of(usage: String, impact: String): HelpContent = HelpContent(usage = usage, impact = impact)
    }
}

/**
 * 圆形边框包起来的「?」喵。
 *
 * 点开弹出该设置的说明喵。刻意画成"文字 + 圆形描边"而不用现成图标：
 * 需求就是这个形状，自己画能保证任何字号下圆框都正好裹住问号喵。
 *
 * [content] 为空时不渲染任何东西，所以调用方可以无条件挂上它喵。
 */
@Composable
fun HelpDot(
    content: HelpContent?,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
) {
    if (content == null || content.isEmpty) return
    HelpDotButton(title = null, content = content, modifier = modifier, size = size)
}

@Composable
private fun HelpDotButton(
    title: String?,
    content: HelpContent,
    modifier: Modifier,
    size: Dp,
) {
    var open by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme

    // 按下去轻轻一缩，给一点"这是个按钮"的手感喵。
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 900f),
        label = "helpDotScale",
    )

    Box(
        modifier = modifier
            .size(size)
            .scale(scale)
            .clip(CircleShape)
            .border(1.dp, scheme.onSurfaceVariant.copy(alpha = 0.55f), CircleShape)
            .clickable(interactionSource = interaction, indication = null) { open = true },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "?",
            fontSize = (size.value * 0.68f).sp,
            fontWeight = FontWeight.Bold,
            color = scheme.onSurfaceVariant,
            // 一行文字的高度里，基线以下还留着下行字母（g/p/y）的空间，而「?」没有下行部分，
            // 所以它按文本框居中时会**整体偏下**、在圆框里显得压底喵。
            // 这里按字号比例往上挪一点，让字形本身的视觉中心对齐圆心喵。
            modifier = Modifier.offset(y = -(size * 0.08f)),
        )
    }

    if (open) {
        HelpDialog(title = title, content = content, onDismiss = { open = false })
    }
}

/** 说明弹层：三段各自成块，空段自动省略喵。 */
@Composable
fun HelpDialog(
    title: String?,
    content: HelpContent,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title ?: "说明", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                HelpSection("用法", content.usage)
                HelpSection("调整影响", content.impact)
                HelpSection("建议参数", content.suggestion)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}

/** 说明弹层里的一段：左侧一道主色竖条 + 小标题，标题只在有内容时出现喵。 */
@Composable
private fun HelpSection(heading: String, body: String) {
    if (body.isBlank()) return
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.padding(bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AccentBar(color = scheme.primary, height = 13.dp, width = 3.dp)
            Spacer(Modifier.width(7.dp))
            Text(text = heading, style = MaterialTheme.typography.labelLarge, color = scheme.primary)
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
    }
}

/**
 * 输入框的标签行：左边标签文字，右边跟一个问号喵。
 *
 * 直接塞进 `OutlinedTextField(label = ...)` 里用喵。
 */
@Composable
fun LabelWithHelp(label: String, content: HelpContent?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = label)
        if (content != null && !content.isEmpty) {
            Spacer(Modifier.width(6.dp))
            HelpDot(content = content, size = 15.dp)
        }
    }
}

/** 一行小标题 + 问号，用于给整组控件挂说明喵。 */
@Composable
fun RowLabelWithHelp(
    label: String,
    content: HelpContent?,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f),
        )
        if (content != null && !content.isEmpty) {
            HelpDot(content = content, size = 15.dp)
        }
        if (trailing != null) {
            Spacer(Modifier.width(4.dp))
            trailing()
        }
    }
}
