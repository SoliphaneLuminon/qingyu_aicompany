package com.qingyu.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 排版与形状：全项目中文字体的字号/行高，以及控件圆角。[QingYuTheme] 统一应用喵。 */
private fun style(
    size: Float,
    lineHeight: Float,
    weight: FontWeight = FontWeight.Normal,
    letterSpacing: Float = 0f,
) = TextStyle(
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
)

/** 中文界面下比 Material 默认行高更松一点，长段落读起来不挤喵。 */
val QingYuTypography = Typography(
    displaySmall = style(32f, 42f, FontWeight.SemiBold, -0.4f),
    headlineMedium = style(26f, 36f, FontWeight.SemiBold, -0.3f),
    headlineSmall = style(22f, 32f, FontWeight.SemiBold, -0.2f),
    titleLarge = style(19f, 28f, FontWeight.SemiBold, -0.1f),
    titleMedium = style(16f, 24f, FontWeight.SemiBold),
    titleSmall = style(14f, 20f, FontWeight.Medium),
    bodyLarge = style(16f, 25f),
    bodyMedium = style(14f, 22f),
    bodySmall = style(12.5f, 18f),
    labelLarge = style(14f, 20f, FontWeight.Medium),
    labelMedium = style(12f, 16f, FontWeight.Medium),
    labelSmall = style(11f, 15f, FontWeight.Medium, 0.2f),
)

/**
 * 偏圆润的形状，符合"陪伴"类应用的气质喵。
 *
 * **它从 [Radius] 派生，不自己写数值**喵。改之前这里是独立的五个数
 * （8/12/18/24/32），而 [Radius] 是另一套（4/8/12/16/20/28）——
 * 于是"卡片圆角"在代码里同时存在 16dp 与 18dp 两种，
 * 组件用 `MaterialTheme.shapes.medium` 还是 `Radius.card` 会得到**不同**的结果喵。
 *
 * 那种"两套真源"的问题不会立刻显形，但会慢慢让界面显得不齐——
 * 而它极难排查，因为每一处单看都"差不多合理"喵。
 *
 * 现在只有一处真源：要调整体圆角，改 [Radius] 即可喵。
 */
val QingYuShapes = Shapes(
    extraSmall = Radius.badge,
    small = Radius.chip,
    medium = Radius.card,
    large = Radius.large,
    extraLarge = Radius.sheet,
)
