package com.qingyu.app.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.qingyu.app.AppContainer
import com.qingyu.app.data.Conversation
import com.qingyu.app.data.Persona
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 应用级导航目标。用状态而不是 Navigation 库：页面少、参数简单，少一个依赖少一处坑喵。 */
sealed interface AppScreen {
    data object Home : AppScreen
    data object Personas : AppScreen
    data class Search(val conversationId: Long) : AppScreen
    data object Modules : AppScreen
    data object Debug : AppScreen
}

data class ShellUiState(
    val screen: AppScreen = AppScreen.Home,
    val drawerOpen: Boolean = false,
    val conversations: List<Conversation> = emptyList(),
    val personas: List<Persona> = emptyList(),
)

class ShellViewModel(application: Application) : AndroidViewModel(application) {

    private val container = AppContainer.get(application)

    private val _state = MutableStateFlow(ShellUiState())
    val state: StateFlow<ShellUiState> = _state.asStateFlow()

    fun openDrawer() = _state.update { it.copy(drawerOpen = true) }

    fun closeDrawer() = _state.update { it.copy(drawerOpen = false) }

    fun navigate(screen: AppScreen) = _state.update { it.copy(screen = screen, drawerOpen = false) }

    fun back() = _state.update { it.copy(screen = AppScreen.Home) }

    fun refresh() {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    conversations = container.database.conversations.list(),
                    personas = container.database.personas.list(),
                )
            }
        }
    }

    fun renameConversation(conversationId: Long, title: String) {
        viewModelScope.launch {
            container.database.conversations.rename(conversationId, title)
            refresh()
        }
    }

    fun setPinned(conversationId: Long, pinned: Boolean) {
        viewModelScope.launch {
            container.database.conversations.setPinned(conversationId, pinned)
            refresh()
        }
    }

    fun deleteConversation(conversationId: Long) {
        viewModelScope.launch {
            container.database.conversations.delete(conversationId)
            refresh()
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
