package com.qingyu.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import com.qingyu.app.data.ThemeMode

/**
 * 主题入口：把 [Color.kt] / [Type.kt] 的色板、字体、形状组装成 Material 3 主题喵。
 *
 * 结构喵：
 * - [LightColors] / [DarkColors]：两套微信色板的 Material 3 落位喵。
 *   具体色值在 [Color.kt] 里维护，这里只做"哪个色值落在哪个语义槽"的映射喵；
 *   改配色请改 [Color.kt]，不要在这里写 `Color(0xFF...)`喵
 * - [LocalDarkTheme]：当前是否暗色的 CompositionLocal，供气泡等需要喵
 *   "明暗两套颜色"的组件读取喵
 * - [QingYuTheme]：唯一入口，按 App 主题模式（跟随系统/强制浅/强制暗）选色板喵
 *
 * 注意：不采用壁纸动态取色——高仿微信需要固定的品牌绿 #07C160喵，
 * 不能被 Android 12+ 的 Material You 壁纸取色盖掉喵。
 */

private val LightColors = lightColorScheme(
    primary = QyPrimary,
    onPrimary = QyOnPrimary,
    primaryContainer = QyPrimaryContainer,
    onPrimaryContainer = QyOnPrimaryContainer,
    secondary = QySecondary,
    secondaryContainer = QySecondaryContainer,
    onSecondaryContainer = QyOnSecondaryContainer,
    tertiary = QyTertiary,
    tertiaryContainer = QyTertiaryContainer,
    onTertiaryContainer = QyOnTertiaryContainer,
    background = QySurface,
    onBackground = QyOnSurface,
    surface = QySurface,
    onSurface = QyOnSurface,
    surfaceVariant = QySurfaceContainerHigh,
    onSurfaceVariant = QyOnSurfaceVariant,
    surfaceContainer = QySurfaceContainer,
    surfaceContainerHigh = QySurfaceContainerHigh,
    outline = QyOutline,
    outlineVariant = QyOutlineVariant,
    error = QyError,
    onError = QyOnError,
)

private val DarkColors = darkColorScheme(
    primary = QyPrimaryDarkTheme,
    onPrimary = QyOnPrimaryDark,
    primaryContainer = QyPrimaryContainerDark,
    onPrimaryContainer = QyOnPrimaryContainerDark,
    secondary = ColorTokens.secondaryDark,
    secondaryContainer = QyPrimaryContainerDark,
    onSecondaryContainer = QyOnPrimaryContainerDark,
    tertiary = QyTertiaryDark,
    tertiaryContainer = QyTertiaryContainerDark,
    onTertiaryContainer = QyOnTertiaryContainerDark,
    background = QySurfaceDark,
    onBackground = QyOnSurfaceDark,
    surface = QySurfaceDark,
    onSurface = QyOnSurfaceDark,
    surfaceVariant = QySurfaceContainerHighDark,
    onSurfaceVariant = QyOnSurfaceVariantDark,
    surfaceContainer = QySurfaceContainerDark,
    surfaceContainerHigh = QySurfaceContainerHighDark,
    outline = QyOutlineDark,
    outlineVariant = QyOutlineDark,
    error = ColorTokens.errorDark,
    onError = QyOnPrimaryDark,
)

/** 暗色下两个没有对应 Qy* 常量的语义槽，集中放这里避免散落成魔法值喵。 */
private object ColorTokens {
    val secondaryDark = androidx.compose.ui.graphics.Color(0xFF9AE6BC)
    val errorDark = androidx.compose.ui.graphics.Color(0xFFFA8A8A)
}

/**
 * 当前是否处于暗色。跟随 App 手动选的主题（SYSTEM/LIGHT/DARK喵），
 * 而不是 `isSystemInDarkTheme()`——后者在用户手动强制浅色/暗色时会判断错喵。
 */
val LocalDarkTheme = staticCompositionLocalOf { false }

/**
 * 轻语主题（高仿微信喵）。
 *
 * 固定使用微信色板（品牌绿 #07C160 / 白·浅灰表面），浅色与暗色两套喵。
 * [dynamicColor] 参数保留仅为不破坏调用方；微信需要固定的品牌绿，壁纸取色被忽略喵。
 */
@Composable
fun QingYuTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    // 高仿微信需要固定的品牌绿，不能用壁纸取色盖掉它——不采用动态取色喵。
    val colors = if (dark) DarkColors else LightColors

    CompositionLocalProvider(LocalDarkTheme provides dark) {
        MaterialTheme(
            colorScheme = colors,
            typography = QingYuTypography,
            shapes = QingYuShapes,
            content = content,
        )
    }
}
