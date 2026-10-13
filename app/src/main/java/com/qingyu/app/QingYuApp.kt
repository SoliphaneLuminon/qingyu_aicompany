package com.qingyu.app

import android.app.Application
import com.qingyu.app.proactive.ProactiveScheduler

/**
 * 轻语 应用入口喵。
 *
 * 目前只做最轻量的初始化（崩溃兜底 + 前后台追踪），真正的依赖装配在
 * [AppContainer] 中按需构建喵，这样单元测试与 Compose 预览都不需要完整启动应用喵。
 */
class QingYuApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        val container = AppContainer.get(this)
        // 前后台追踪必须在这里挂上、且只挂一次喵：
        // 它决定"回复跑完要不要弹系统通知"，晚注册就会漏掉早于它的那次切换喵。
        registerActivityLifecycleCallbacks(container.appVisibility)
        // 通知频道建早一点喵：真正要发通知时往往已经在后台，
        // 那时再建频道会多一次不必要的系统调用，也容易漏掉首次通知喵。
        container.replyNotifier.ensureChannels()
        // 每次启动都按当前设置重排一次「角色主动找你」的闹钟喵。
        // 放在这里而不是只放在设置页，是因为用户可能升级应用、或改了系统时间，
        // 而闹钟是"排一次管很久"的东西，漏排一次就静默失效好几小时喵。
        ProactiveScheduler(this).reschedule()
    }

    companion object {
        lateinit var instance: QingYuApp
            private set
    }
}
