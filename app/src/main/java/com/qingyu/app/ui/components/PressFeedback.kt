package com.qingyu.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import com.qingyu.app.ui.theme.Motion

/**
 * 统一的**按压反馈**喵。
 *
 * 改之前统计过：全项目 39 处 `.clickable`，而只有 5 个文件自己做了按压反馈喵。
 * 其余 34 处点下去**没有任何视觉回应**——这正是"手感不够精致"最直接的来源喵。
 * 用户按下去的那一刻得不到确认，界面就显得"木"喵。
 *
 * **为什么是"轻微缩放 + 原生涟漪"两层**喵：
 * - **涟漪**（Material 的 indication）负责"我点到你了"的即时确认，
 *   它是瞬时的、零延迟的喵
 * - **缩放**负责"这东西被按下去了"的物理感，它是渐变的、有阻尼的喵
 *
 * 只有涟漪会显得单薄（尤其在大块卡片上，涟漪扩散到边缘时按压已经结束了）喵；
 * 只有缩放又缺少"点中了"的即时性喵。两层叠起来才像真的按到了东西喵。
 *
 * **缩放幅度刻意很小（0.97）**喵：这个应用的气质是"安静的陪伴"，
 * 按一下弹一下会显得轻浮喵。0.97 是"能感觉到但不会注意到"的幅度喵。
 *
 * 缩放的动画走 [Motion.snappy]（临界阻尼）喵：
 * 按下去要**跟手**，松手要**稳稳回位**，都不能有回弹抖动喵。
 */
fun Modifier.pressable(
    /** 缩放到的比例；默认 0.97喵。传 1f 表示只要涟漪、不要缩放喵。 */
    pressedScale: Float = 0.97f,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = Motion.snappy,
        label = "pressScale",
    )
    this
        .graphicsLayer {
            // 只有真要缩放时才挂渲染层喵：常驻一个恒为 1f 的 graphicsLayer
            // 是白开销，而列表里这样的行可能有几十个喵。
            if (pressedScale != 1f) {
                scaleX = scale
                scaleY = scale
            }
        }
        .clickable(
            interactionSource = interaction,
            // 用主题当前的 indication，不写死 ripple 颜色——
            // 这样浅色/暗色下自动是对的喵。
            indication = LocalIndication.current,
            onClick = onClick,
        )
}

/**
 * 与 [pressable] 同款，但支持长按喵（会话行要长按出菜单）喵。
 */
fun Modifier.pressableCombined(
    pressedScale: Float = 0.97f,
    onLongClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = Motion.snappy,
        label = "pressScale",
    )
    this
        .graphicsLayer {
            if (pressedScale != 1f) {
                scaleX = scale
                scaleY = scale
            }
        }
        .combinedClickable(
            interactionSource = interaction,
            indication = LocalIndication.current,
            onLongClickLabel = onLongClickLabel,
            onLongClick = onLongClick,
            onClick = onClick,
        )
}

/**
 * 一行列表项的**统一按压底色**喵。
 *
 * 列表行不适合用缩放（整行缩放会让文字边缘发虚）喵，
 * 所以它们用"按下时底色变深"这一种反馈喵。
 *
 * 抽出来是因为改之前每个列表页各写一遍，用的还是不同的动画规格与不同的颜色喵。
 *
 * @param pressed 由调用方从自己的 interaction source 读出来喵
 * @param base 未按下时的底色喵
 * @param pressedColor 按下时的底色；默认取主题的 surfaceContainerHigh喵
 */
@Composable
fun pressedRowColor(
    pressed: Boolean,
    base: androidx.compose.ui.graphics.Color,
    pressedColor: androidx.compose.ui.graphics.Color? = null,
): androidx.compose.ui.graphics.Color {
    val target = if (pressed) {
        pressedColor ?: androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        base
    }
    val color by androidx.compose.animation.animateColorAsState(
        targetValue = target,
        animationSpec = Motion.color,
        label = "pressedRowColor",
    )
    return color
}

/**
 * **图片与表情**这类内容的按压反馈：只做极轻微的缩放，**不挂涟漪**喵。
 *
 * 为什么和 [pressable] 分开喵：图片上盖一层涟漪会挡住内容本身，
 * 而表情包/缩略图的辨识度全靠画面，涟漪扩散的那一瞬间反而看不清点了哪张喵。
 *
 * 缩放也只给 0.96（比通用的 0.97 略深一点）喵：图片是大块面，
 * 同样的比例在大块面上更不容易被察觉喵。
 */
fun Modifier.pressableImage(
    pressedScale: Float = 0.96f,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = Motion.snappy,
        label = "pressImageScale",
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interaction,
            indication = null,
            onClick = onClick,
        )
}

/**
 * 胶囊标签（分类筛选、模型选择这类）的按压反馈喵。
 *
 * 标签很小，缩放看不出来，所以用**透明度**：按下时略暗一点喵。
 * 这是最轻的一种反馈，正合适——标签本来就是轻量操作喵。
 */
fun Modifier.pressableChip(onClick: () -> Unit): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val dim by animateFloatAsState(
        targetValue = if (pressed) 0.72f else 1f,
        animationSpec = Motion.snappy,
        label = "pressChipDim",
    )
    this
        .graphicsLayer { alpha = dim }
        .clickable(
            interactionSource = interaction,
            indication = null,
            onClick = onClick,
        )
}
