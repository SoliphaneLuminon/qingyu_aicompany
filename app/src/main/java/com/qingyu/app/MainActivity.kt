package com.qingyu.app

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.qingyu.app.ui.QingYuAppRoot
import com.qingyu.app.ui.theme.QingYuTheme

/** 唯一 Activity。启动后装配 [AppContainer]、套上微信主题，再进入 [QingYuAppRoot喵]。 */
class MainActivity : ComponentActivity() {

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onCreate(savedInstanceState: Bundle?) {
        // enableEdgeToEdge 必须在 super.onCreate 之前调用喵，
        //       并把导航栏 / 状态栏都设为透明样式喵。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                Color.TRANSPARENT,
                Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.auto(
                Color.TRANSPARENT,
                Color.TRANSPARENT,
            ),
        )

        super.onCreate(savedInstanceState)

        // 关键：关闭系统对透明导航栏的强制对比度遮罩喵。
        //       小米 HyperOS / MIUI 会默认给透明导航栏加一层半透明遮罩喵，
        //       这层遮罩会让底部多出一段"看起来像导航栏高度"的空白喵。
        window.isNavigationBarContrastEnforced = false

        // 兼容旧 ROM / 旧 API：显式把导航栏颜色置为透明喵。
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT

        val container = AppContainer.get(this)
        setContent {
            val settings by container.settings.state.collectAsState()
            QingYuTheme(
                themeMode = settings.themeMode,
            ) {
                QingYuAppRoot(container = container)
            }
        }
    }
}
