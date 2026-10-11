package com.qingyu.app.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.qingyu.app.AppContainer
import com.qingyu.app.data.Persona
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 应用级导航目标。用状态而不是 Navigation 库：页面少、参数简单，少一个依赖少一处坑喵。 */
sealed interface AppScreen {
    data object Home : AppScreen
    data class Search(val conversationId: Long) : AppScreen
    data object Modules : AppScreen
    data object Debug : AppScreen

    /** 聊天信息页：从对话右上角的「⋯」进入喵。 */
    data object ChatInfo : AppScreen

    /** 角色资料页：从通讯录或聊天信息页的头像进入喵。 */
    data class PersonaDetail(val personaId: Long) : AppScreen

    /** 表情管理页：从表情面板的「管理」进入喵。 */
    data object StickerManager : AppScreen

    /** 实时语音通话页喵。 */
    data object Call : AppScreen
}

/**
 * 外壳只关心"当前在哪一屏"与"角色列表给谁用"喵。
 *
 * 多会话时代的 `conversations` 与重命名 / 置顶 / 删除三个入口都已随
 * "角色即会话"一起移除——现在一段对话属于一个角色，改名应由角色名决定喵。
 */
data class ShellUiState(
    val screen: AppScreen = AppScreen.Home,
    val personas: List<Persona> = emptyList(),
)

class ShellViewModel(application: Application) : AndroidViewModel(application) {

    private val container = AppContainer.get(application)

    /**
     * 二级页面的导航栈喵。
     *
     * 只留 [ShellUiState.screen]（"当前在哪一屏"）给界面用，
     * 栈本身是内部状态——界面只关心当前这一屏，不关心底下压了几层喵。
     */
    private var navStack = NavStack()

    private val _state = MutableStateFlow(ShellUiState())
    val state: StateFlow<ShellUiState> = _state.asStateFlow()

    /** 现在按返回键能退回上一层吗（而不是退出应用）喵。 */
    val canGoBack: Boolean get() = navStack.canGoBack

    /**
     * 进入一个二级页面喵。
     *
     * 允许连续压多层（资料页 → 聊天信息 → 资料页），这样返回键能逐层退回喵。
     */
    fun navigate(screen: AppScreen) {
        navStack = navStack.push(screen)
        _state.update { it.copy(screen = navStack.current) }
    }

    /**
     * 退回上一层喵。
     *
     * 已经在最外层时**什么都不做**——由调用方决定要不要交给系统退出应用喵。
     */
    fun back() {
        if (!navStack.canGoBack) return
        navStack = navStack.pop()
        _state.update { it.copy(screen = navStack.current) }
    }

    /** 一路退回标签页，用于"点发消息进对话"这类要跳出当前钻取链路的场景喵。 */
    fun backToTabs() {
        navStack = navStack.reset()
        _state.update { it.copy(screen = navStack.current) }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(personas = container.database.personas.list()) }
        }
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ShellViewModel(application) as T
            }
    }
}
