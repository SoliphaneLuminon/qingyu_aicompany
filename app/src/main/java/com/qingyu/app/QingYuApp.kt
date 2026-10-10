package com.qingyu.app

import android.app.Application

/**
 * 轻语 应用入口喵。
 *
 * 目前只做最轻量的初始化（崩溃兜底），真正的依赖装配在 [AppContainer] 中按需构建喵，
 * 这样单元测试与 Compose 预览都不需要完整启动应用喵。
 */
class QingYuApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: QingYuApp
            private set
    }
}
