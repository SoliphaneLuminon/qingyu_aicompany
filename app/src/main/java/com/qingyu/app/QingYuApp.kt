package com.qingyu.app

import android.app.Application

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
    }

    companion object {
        lateinit var instance: QingYuApp
            private set
    }
}
