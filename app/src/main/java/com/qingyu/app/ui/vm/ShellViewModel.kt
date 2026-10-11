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

    private val _state = MutableStateFlow(ShellUiState())
    val state: StateFlow<ShellUiState> = _state.asStateFlow()

    fun navigate(screen: AppScreen) = _state.update { it.copy(screen = screen) }

    fun back() = _state.update { it.copy(screen = AppScreen.Home) }

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
