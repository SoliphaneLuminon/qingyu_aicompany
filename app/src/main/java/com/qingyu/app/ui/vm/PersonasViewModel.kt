package com.qingyu.app.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.qingyu.app.AppContainer
import com.qingyu.app.core.TimeUtil
import com.qingyu.app.data.Persona
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 角色页：UI 状态与控制器——角色卡（Persona）的 CRUD 与编辑喵。
 *
 * 角色卡包含人格设定、情境、说话示例、开场白以及模型/温度覆盖配置喵；
 * [PersonasUiState] 持有列表与编辑弹窗草稿（`editorOpen` / `editing` / `draft`喵）。
 * 内置角色在首次启动时由 [SeedPersonas] 写入，用户可复制/编辑/删除喵。
 */
data class PersonasUiState(
    val loading: Boolean = true,
    val personas: List<Persona> = emptyList(),
    val editorOpen: Boolean = false,
    val editing: Persona? = null,
    val draft: Persona = Persona(name = "", createdAt = 0L, updatedAt = 0L),
    val toast: String? = null,
)

class PersonasViewModel(application: Application) : AndroidViewModel(application) {

    private val container = AppContainer.get(application)
    private val db = container.database

    private val _state = MutableStateFlow(PersonasUiState())
    val state: StateFlow<PersonasUiState> = _state.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            _state.update { it.copy(loading = false, personas = db.personas.list()) }
        }
    }

    fun createNew() {
        _state.update {
            it.copy(
                editorOpen = true,
                editing = null,
                draft = Persona(
                    name = "",
                    avatarEmoji = "🌙",
                    tagline = "",
                    systemPrompt = "你是……（写下他是谁、和用户什么关系、说话什么风格）",
                    greeting = "",
                    createdAt = TimeUtil.now(),
                    updatedAt = TimeUtil.now(),
                ),
            )
        }
    }

    fun edit(persona: Persona) {
        _state.update { it.copy(editorOpen = true, editing = persona, draft = persona) }
    }

    fun duplicate(persona: Persona) {
        viewModelScope.launch {
            val copy = persona.copy(
                id = 0L,
                name = "${persona.name} 副本",
                builtIn = false,
                createdAt = TimeUtil.now(),
                updatedAt = TimeUtil.now(),
            )
            val id = db.personas.insert(copy)
            _state.update { it.copy(toast = "已复制角色") }
            reload()
            db.personas.get(id)?.let { edit(it) }
        }
    }

    fun closeEditor() = _state.update { it.copy(editorOpen = false, editing = null) }

    fun onDraftChange(transform: (Persona) -> Persona) {
        _state.update { it.copy(draft = transform(it.draft)) }
    }

    fun save() {
        val current = _state.value
        val draft = current.draft
        if (draft.name.isBlank()) {
            _state.update { it.copy(toast = "先给角色起个名字") }
            return
        }
        viewModelScope.launch {
            if (current.editing == null) {
                db.personas.insert(draft.copy(createdAt = TimeUtil.now(), updatedAt = TimeUtil.now()))
            } else {
                db.personas.update(draft.copy(updatedAt = TimeUtil.now()))
            }
            _state.update { it.copy(editorOpen = false, editing = null, toast = "已保存") }
            reload()
        }
    }

    fun delete(persona: Persona) {
        viewModelScope.launch {
            db.personas.delete(persona.id)
            _state.update { it.copy(toast = "已删除角色及其对话") }
            reload()
        }
    }

    fun clearToast() = _state.update { it.copy(toast = null) }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    PersonasViewModel(application) as T
            }
    }
}
