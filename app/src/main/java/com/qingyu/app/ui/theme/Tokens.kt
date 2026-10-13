package com.qingyu.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * 全应用的**间距**令牌喵。
 *
 * **为什么需要它**喵：改之前统计过一遍，项目里光圆角就有 10 种数值
 * （2/3/6/8/10/12/14/16/18/22 dp），alpha 有 16 种喵。
 * 每一个单看都"差不多合理"，但并排放在一起就会觉得哪里不对劲——
 * 那正是"不够统一、不够有质感"的来源喵。
 *
 * 令牌不是把数字变小，而是把它们**收敛到一档可数的刻度上**喵。
 * 以后要调整体疏密，改这里一处即可，而不是满项目找 `12.dp`喵。
 *
 * 刻度取 4 的倍数（外加 2dp 与 6dp 两个特例）喵：
 * 4dp 网格是移动端最通用的密度节奏，能在各种屏幕上保持整数像素对齐喵。
 */
object Spacing {
    /** 2dp：极小缝隙，例如图标与角标之间喵。 */
    val hairline = 2.dp

    /** 4dp：元素内部的紧凑间隙喵。 */
    val xxs = 4.dp

    /** 6dp：图标与紧邻文字之间喵。 */
    val xs = 6.dp

    /** 8dp：行内元素之间喵。 */
    val sm = 8.dp

    /** 12dp：列表行内边距、卡片内边距喵。 */
    val md = 12.dp

    /** 16dp：页面左右边距（全应用统一用这一档）喵。 */
    val lg = 16.dp

    /** 20dp：卡片内边距的宽松档喵。 */
    val xl = 20.dp

    /** 24dp：区块之间喵。 */
    val xxl = 24.dp

    /** 32dp：空状态等大留白喵。 */
    val huge = 32.dp
}

/**
 * 全应用的**圆角**令牌喵。
 *
 * 名字按**用途**而不是按大小取喵：`card` 比 `medium` 更能表达
 * "这个圆角是给卡片用的"，改起来也不会因为数值变动而名不副实喵。
 *
 * **这是全应用唯一的圆角真源**喵：[androidx.compose.material3.Shapes]
 * 在 [Type.kt] 里从它派生，所以 `MaterialTheme.shapes.medium` 与
 * `Radius.card` 拿到的是同一个值喵。
 *
 * 改之前这里有个隐患值得记下喵：`Shapes` 原本自己写了一套数
 * （8/12/18/24/32），而这一套是另一套（4/8/12/16/20/28）——
 * 于是"卡片圆角"在代码里同时存在 16dp 与 18dp 两种，
 * 组件用哪个 API 会得到不同结果，而且极难排查喵。
 */
object Radius {
    /** 4dp：角标、极小的胶囊喵。 */
    val badge = RoundedCornerShape(4.dp)

    /** 8dp：小控件——标签、小按钮喵。 */
    val chip = RoundedCornerShape(8.dp)

    /** 12dp：中等控件——输入框、小卡片喵。 */
    val small = RoundedCornerShape(12.dp)

    /** 16dp：卡片、面板喵。 */
    val card = RoundedCornerShape(16.dp)

    /** 20dp：大卡片、对话框喵。 */
    val large = RoundedCornerShape(20.dp)

    /** 28dp：底部弹层、大面板的顶角喵。 */
    val sheet = RoundedCornerShape(28.dp)

    /** 完全圆形：头像、圆形按钮喵。 */
    val full = RoundedCornerShape(percent = 50)

    /** 胶囊：像未读徽标那种"数字多长都能包住"的形状喵。 */
    val pill = RoundedCornerShape(percent = 50)
}

/**
 * 全应用的**透明度**令牌喵。
 *
 * 改之前有 16 种取值（0.06 / 0.08 / 0.12 / 0.4 / 0.45 / 0.5 / 0.55 / 0.6 /
 * 0.65 / 0.7 / 0.75 / 0.78 / 0.8 / 0.85 / 0.9 / 0.92）喵。
 * 其中 0.4 与 0.45、0.7 与 0.75 这种差距，**肉眼分不出来但会让界面显得脏**——
 * 因为同一个界面里出现了"看起来该一样却微妙地不一样"的灰喵。
 *
 * 收敛成五档，每档对应一个明确用途喵。
 */
object Alpha {
    /** 0.06：极淡的底色填充（选中态背景）喵。 */
    const val faint = 0.06f

    /** 0.12：淡色填充、分隔感强的底色喵。 */
    const val subtle = 0.12f

    /** 0.38：禁用态（Material 规范的 disabled 值）喵。 */
    const val disabled = 0.38f

    /** 0.60：次级文字、说明文字喵。 */
    const val secondary = 0.60f

    /** 0.80：正文的弱化版本、图标默认色喵。 */
    const val muted = 0.80f

    /** 0.92：接近不透明，用于压在图片上的文字喵。 */
    const val onMedia = 0.92f
}

/**
 * 全应用的**动效时长**令牌喵。
 *
 * 只在"必须是有限时长"的场合用（例如无限循环动画的一个周期）喵；
 * 位移与状态切换一律走 [Motion] 里的弹簧——弹簧没有固定时长，
 * 它的减速曲线才是"平滑有质感"的来源喵。
 */
object Duration {
    /** 120ms：极短的反馈，例如按下高亮喵。 */
    const val instant = 120

    /** 240ms：常规过渡喵。 */
    const val quick = 240

    /** 900ms：呼吸、脉冲这类循环动画的一个周期喵。 */
    const val breath = 900
}
