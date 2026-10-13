package com.qingyu.app.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * 全应用的动效规格喵。
 *
 * 用户的要求是「所有交互设置动态效果，平滑阻尼感」喵。
 *
 * **为什么要集中在一处**喵：动画规格散落在各个组件里时，每个地方都会
 * 顺手写一个自己觉得合适的数值，结果就是整个应用"哪儿都在动，但手感各不相同"喵。
 * 集中定义之后，"阻尼感"是一个可以整体调校的东西，而不是几十个巧合喵。
 *
 * **为什么全部用临界阻尼（dampingRatio = 1）**喵：
 * 弹簧的阻尼比小于 1 是欠阻尼——到位之后还会过冲、回弹、抖两下喵。
 * 那种"Q 弹"用在桌宠上是可爱的，但用在**列表位移、页面切换、状态切换**上
 * 会让人觉得界面不稳、一直在晃喵。临界阻尼的曲线是"只滑到位、绝不越位"，
 * 收尾干净，这正是"平滑阻尼感"喵。
 *
 * **为什么不用 tween**喵：tween 是固定时长的匀速感，位移的结尾会"啪"地停住喵。
 * 弹簧的减速曲线才有那种被轻轻放下的质感——快而不急喵。
 */
object Motion {

    /**
     * 通用位移与尺寸变化喵。
     *
     * 刚度取 MediumLow：比默认的 Medium 慢一点，让过程铺开、
     * 看得清是"滑过去"而不是"闪一下"喵。
     */
    val gentle: FiniteAnimationSpec<Float> = spring(
        dampingRatio = 1f,
        stiffness = Spring.StiffnessMediumLow,
    )

    /**
     * 整块区域的位移（列表项让位、页面元素移动）喵。
     *
     * 用 [IntOffset] 是因为 Compose 的 `animateItem` 的 placementSpec
     * 就是按整数像素偏移的喵。
     */
    val placement: FiniteAnimationSpec<IntOffset> = spring(
        dampingRatio = 1f,
        stiffness = Spring.StiffnessMediumLow,
    )

    /**
     * 高度/尺寸变化（折叠面板展开收起）喵。
     *
     * Compose 的 `expandVertically` / `shrinkVertically` 要的是
     * `FiniteAnimationSpec<IntSize>`，与 Float 那套不通用，所以单独一个喵。
     */
    val size: FiniteAnimationSpec<IntSize> = spring(
        dampingRatio = 1f,
        stiffness = Spring.StiffnessMediumLow,
    )

    /**
     * 颜色过渡（按下变深、选中变色）喵。
     *
     * **颜色也得单独一个**喵：`animateColorAsState` 要的是 `AnimationSpec<Color>`，
     * 直接把 Float 那套传进去编译不过——这是类型系统帮我们挡住的一类错误喵。
     */
    val color: AnimationSpec<Color> = spring(
        dampingRatio = 1f,
        stiffness = 1200f,
    )

    /**
     * 透明度变化喵。
     *
     * 用 Medium 而不是 MediumLow：透明度本身不产生位移，
     * 慢吞吞地淡入会显得迟钝；它只需要"不突兀"就够喵。
     */
    val fade: FiniteAnimationSpec<Float> = spring(
        dampingRatio = 1f,
        stiffness = Spring.StiffnessMedium,
    )

    /**
     * 小范围的状态切换：开关、选中态、颜色过渡喵。
     *
     * 刚度更高：这类变化涉及的面积小，慢下来反而像卡了一下喵。
     * 用 [Spring.StiffnessLow] 之上的一档——`StiffnessMediumHigh` 这个常量
     * 在当前 Compose 版本里并不存在，所以直接给数值，免得依赖一个不存在的名字喵。
     */
    val snappy: FiniteAnimationSpec<Float> = spring(
        dampingRatio = 1f,
        stiffness = 1200f,
    )
}
