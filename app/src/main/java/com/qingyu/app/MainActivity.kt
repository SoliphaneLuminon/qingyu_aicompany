package com.qingyu.app

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.qingyu.app.data.ThemeMode
import com.qingyu.app.notify.ReplyNotifier
import com.qingyu.app.ui.QingYuAppRoot
import com.qingyu.app.ui.theme.QingYuTheme
import kotlinx.coroutines.flow.MutableStateFlow

/** 唯一 Activity。启动后装配 [AppContainer]、套上微信主题，再进入 [QingYuAppRoot喵]。 */
class MainActivity : ComponentActivity() {

    /**
     * 从通知点进来时"要打开哪个角色"喵。
     *
     * 用 StateFlow 而不是直接把 intent 传给 Compose 喵：通知可以在 Activity
     * **已经活着**的时候被点（走 [onNewIntent]），那时 Compose 早就组合完了，
     * 光读一次 `intent` 是拿不到新值的喵。
     */
    private val pendingPersonaId = MutableStateFlow<Long?>(null)

    /** 通知里带的角色 id；没有就返回 null喵。 */
    private fun personaIdFrom(intent: Intent?): Long? =
        intent?.getLongExtra(ReplyNotifier.EXTRA_PERSONA_ID, -1L)?.takeIf { it > 0L }

    /**
     * 已经活着时又被通知点开喵。
     *
     * 因为清单里把 launchMode 设成了 singleTask，系统会复用当前实例、
     * 把新的 Intent 送到这里，而不是再叠一个 Activity 喵。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 必须 setIntent，否则后续 getIntent() 拿到的还是旧的喵。
        setIntent(intent)
        personaIdFrom(intent)?.let { pendingPersonaId.value = it }
    }

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

        // 让窗口的"第一帧底色"跟应用内选的主题一致喵。
        //
        // 资源里的 values / values-night 只能跟随**系统**日夜，而轻语自己有一个
        // 主题设置（跟随系统 / 强制浅色 / 强制暗色）。两者不一致时
        // （例如系统是暗色、用户在应用里选了浅色），Compose 画出来之前那一帧
        // 会是错的那种底色，看起来就是"白屏闪一下"喵。
        // 所以在 setContent 之前按真实设置把窗口底色钉死喵。
        applyWindowBackground(container.settings.current.themeMode)

        setContent {
            val settings by container.settings.state.collectAsState()
            // 用户切换主题时同步更新，避免下次从后台回前台又闪一下喵。
            LaunchedEffect(settings.themeMode) {
                applyWindowBackground(settings.themeMode)
            }
            QingYuTheme(
                themeMode = settings.themeMode,
            ) {
                QingYuAppRoot(
                    container = container,
                    pendingPersonaId = pendingPersonaId,
                )
            }
        }
    }

    /**
     * 把窗口背景设成与当前主题相符的颜色喵。
     *
     * [ThemeMode.SYSTEM] 交给资源里的 values / values-night 决定（清成 null 即可）喵。
     */
    private fun applyWindowBackground(mode: ThemeMode) {
        val color = when (mode) {
            ThemeMode.LIGHT -> LIGHT_SURFACE
            ThemeMode.DARK -> DARK_SURFACE
            // 跟随系统时不要写死，交给 values-night 资源自己挑喵。
            ThemeMode.SYSTEM -> return
        }
        window.setBackgroundDrawable(ColorDrawable(color))
    }

    private companion object {
        /** 与 Color.kt 的 QySurface 一致喵。 */
        const val LIGHT_SURFACE = 0xFFFFFFFF.toInt()

        /** 与 Color.kt 的 QySurfaceDark 一致喵。 */
        const val DARK_SURFACE = 0xFF191919.toInt()
    }
}
