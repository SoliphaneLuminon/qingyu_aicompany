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
    /** 待确认删除的角色；非空时界面弹二次确认喵。 */
    val pendingDelete: Persona? = null,
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
                // 头像文件不跟着复制：两个角色共用同一个文件的话，
                // 删掉其中一个就会把文件也删了，另一个的头像跟着消失喵。
                // 副本退回 emoji，用户想换再传一张喵。
                avatarImage = "",
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

    // ------------------------------------------------------------ 头像

    /**
     * 裁剪页确认后：把位图落盘，并写进当前草稿喵。
     *
     * 只改草稿、不立刻写库：用户可能接着点「取消」，
     * 那时库里不该多出一个从没被确认过的头像喵。
     */
    fun applyCroppedAvatar(bitmap: android.graphics.Bitmap) {
        viewModelScope.launch {
            val result = runCatching { container.avatarStore.save(bitmap) }
            result.fold(
                onSuccess = { name ->
                    // 换头像时顺手删掉草稿里那个旧文件，否则每换一次都会留一个孤儿喵。
                    val previous = _state.value.draft.avatarImage
                    if (previous.isNotBlank() && previous != name) {
                        // 只有当没有别的角色在用它时才删喵。
                        val stillUsed = db.personas.list().any { it.avatarImage == previous }
                        if (!stillUsed) container.avatarStore.delete(previous)
                    }
                    _state.update { it.copy(draft = it.draft.copy(avatarImage = name)) }
                },
                onFailure = { error ->
                    _state.update { it.copy(toast = error.message ?: "保存头像失败") }
                },
            )
            bitmap.recycle()
        }
    }

    /** 退回 emoji 头像喵。 */
    fun clearAvatar() {
        _state.update { it.copy(draft = it.draft.copy(avatarImage = "")) }
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
            // 头像文件不归数据库管，删角色时要顺手清掉，否则它会永远留在私有目录里喵。
            // 先确认没有别的角色还在用同一个文件（复制角色曾共用过，老库里可能仍有）喵。
            if (persona.avatarImage.isNotBlank()) {
                val stillUsed = db.personas.list().any { it.avatarImage == persona.avatarImage }
                if (!stillUsed) container.avatarStore.delete(persona.avatarImage)
            }
            _state.update { it.copy(toast = "已删除角色及其对话") }
            reload()
        }
    }

    /**
     * 请求删除：只把待删角色挂到状态上，由界面弹二次确认喵。
     *
     * 删除会连带清掉聊天记录与记忆，是不可恢复的，所以不能点了就删喵。
     */
    fun askDelete(persona: Persona) = _state.update { it.copy(pendingDelete = persona) }

    /** 取消删除确认喵。 */
    fun cancelDelete() = _state.update { it.copy(pendingDelete = null) }

    /** 确认删除喵。 */
    fun confirmDelete() {
        val target = _state.value.pendingDelete ?: return
        _state.update { it.copy(pendingDelete = null) }
        delete(target)
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
