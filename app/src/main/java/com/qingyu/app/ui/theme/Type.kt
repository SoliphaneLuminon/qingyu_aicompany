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

/** 偏圆润的形状，符合"陪伴"类应用的气质喵。 */
val QingYuShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)
