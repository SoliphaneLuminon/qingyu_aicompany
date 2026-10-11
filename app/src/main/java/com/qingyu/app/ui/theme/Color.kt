package com.qingyu.app.ui.theme

import androidx.compose.ui.graphics.Color

// 轻语主色板：高仿微信配色喵。
// 品牌主色用微信绿 #07C160；界面走"白 / 浅灰表面 + 微信绿点缀"喵，
// 浅色与暗色两套都对齐微信的视觉语言喵。
// 语义色槽沿用 Material 3 命名，让现有组件无需改引用就能吃到新配色喵。

// ---------------------------------------------------------------- 浅色
val QyPrimary = Color(0xFF07C160)          // 微信绿喵
val QyOnPrimary = Color(0xFFFFFFFF)
val QyPrimaryContainer = Color(0xFFE7F9EF) // 浅绿选区 / 激活态底色喵
val QyOnPrimaryContainer = Color(0xFF0A7A43)

val QySecondary = Color(0xFF07C160)
val QySecondaryContainer = Color(0xFFE7F9EF)
val QyOnSecondaryContainer = Color(0xFF0A7A43)

val QyTertiary = Color(0xFF576B95)         // 微信链接蓝灰调喵
val QyTertiaryContainer = Color(0xFFE8EEF7)
val QyOnTertiaryContainer = Color(0xFF2E3F5E)

val QySurface = Color(0xFFFFFFFF)          // 页面 / 卡片：白喵
val QySurfaceDim = Color(0xFFF2F2F2)
val QySurfaceContainer = Color(0xFFF7F7F7) // 列表 / 面板：浅灰喵
val QySurfaceContainerHigh = Color(0xFFEDEDED)
val QyOnSurface = Color(0xFF111111)        // 主文字喵
val QyOnSurfaceVariant = Color(0xFF888888) // 次级文字喵
val QyOutline = Color(0xFFD9D9D9)
val QyOutlineVariant = Color(0xFFE5E5E5)   // 分隔线喵
val QyError = Color(0xFFFA5151)            // 微信红喵
val QyOnError = Color(0xFFFFFFFF)

// ---------------------------------------------------------------- 暗色
val QyPrimaryDarkTheme = Color(0xFF07C160)
val QyOnPrimaryDark = Color(0xFFFFFFFF)
val QyPrimaryContainerDark = Color(0xFF0E4F2E)
val QyOnPrimaryContainerDark = Color(0xFF9AE6BC)

val QySurfaceDark = Color(0xFF191919)
val QySurfaceContainerDark = Color(0xFF1F1F1F)
val QySurfaceContainerHighDark = Color(0xFF2A2A2A)
val QyOnSurfaceDark = Color(0xFFFFFFFF)
val QyOnSurfaceVariantDark = Color(0xFF7F7F7F)
val QyOutlineDark = Color(0xFF3A3A3A)
val QyTertiaryDark = Color(0xFF8A9CC3)
val QyOnTertiaryContainerDark = Color(0xFFD6E1F7)
val QyTertiaryContainerDark = Color(0xFF2C3D5E)

// ---------------------------------------------------------------- 聊天气泡
// 微信聊天页：浅色背景是经典灰 #EDEDED，暗色是 #191919喵。
// 自己=绿色气泡（浅色用 #95EC69 微信浅绿，暗色用 #07C160），对方=白 / 深灰气泡喵。
val QyChatBackgroundLight = Color(0xFFEDEDED)
val QyChatBackgroundDark = Color(0xFF191919)
val QyUserBubbleLight = Color(0xFF95EC69)
val QyUserBubbleDark = Color(0xFF07C160)
val QyPetBubble = Color(0xFFFFFFFF)   // 对方气泡浅色喵
val QyPetBubbleDark = Color(0xFF2E2E2E)

// ---------------------------------------------------------------- 列表层级
// 列表页三层底色，明度次序固定为：普通 → 底部标签栏 → 置顶。
//
// 浅色下"变浅"是**更灰**（白底上略暗才看得出层次）：
//   普通 #FFFFFF → 底栏 #F7F7F7 → 置顶 #F2F2F2
// 暗色下"变浅"是**更亮**：
//   普通 #191919 → 底栏 #202020 → 置顶 #262626
//
// 两套方向相反，所以不能共用一个 alpha 叠加，必须各写一个色值喵。
val QyChatRowNormalLight = Color(0xFFFFFFFF)
val QyBottomBarLight = Color(0xFFF7F7F7)
val QyChatRowPinnedLight = Color(0xFFF2F2F2)

val QyChatRowNormalDark = Color(0xFF191919)
val QyBottomBarDark = Color(0xFF202020)
val QyChatRowPinnedDark = Color(0xFF262626)
