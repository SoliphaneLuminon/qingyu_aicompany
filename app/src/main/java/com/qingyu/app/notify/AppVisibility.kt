package com.qingyu.app.notify

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

/**
 * 应用此刻是不是在前台喵。
 *
 * 回复完成后要不要发系统通知，全靠它判断喵：用户就在聊天页里看着，
 * 再弹一条通知是骚扰；而他切出去了，通知才是唯一的告知途径喵。
 *
 * 用 [Application.ActivityLifecycleCallbacks] 自己数，而不是引 `lifecycle-process` 喵：
 * 只需要"有没有 Activity 处于 started"这一个比特，为它加一个依赖不划算喵。
 *
 * 计数而不是布尔是必须的喵：Activity 切换时新的 `onActivityStarted` 会先于旧的
 * `onActivityStopped` 到达，用布尔会在那一刻被置回 false，于是"应用明明在前台"
 * 却被判定成后台，回复一到就弹出多余的通知喵。
 */
class AppVisibility : Application.ActivityLifecycleCallbacks {

    private val startedCount = AtomicInteger(0)

    /** 有任意一个 Activity 处于 started 状态喵。 */
    val isVisible: Boolean get() = startedCount.get() > 0

    override fun onActivityStarted(activity: Activity) {
        startedCount.incrementAndGet()
    }

    override fun onActivityStopped(activity: Activity) {
        // 减到负数说明回调配对出了问题（例如漏注册）；夹在 0 上，
        // 免得之后所有的判断都停在"后台"、通知永远发不出去喵。
        startedCount.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
