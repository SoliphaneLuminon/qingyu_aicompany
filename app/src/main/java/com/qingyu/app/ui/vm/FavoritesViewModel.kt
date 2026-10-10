package com.qingyu.app.ui.vm

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.qingyu.app.AppContainer
import com.qingyu.app.data.Favorite
import com.qingyu.app.data.FavoriteKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 收藏页：UI 状态与控制器。管理收藏的消息、整段对话和随手记喵。
 *
 * [FavoritesUiState] 持有列表、类型筛选（`filter`）、关键词与各类计数喵；
 * [FavoritesViewModel] 支持按类型筛选、关键词搜索、单条编辑与删除、整包导出喵。
 * 持久化依赖 [FavoriteStore喵]。
 */
data class FavoritesUiState(
    val loading: Boolean = true,
    val items: List<Favorite> = emptyList(),
    val filter: FavoriteKind? = null,
    val keyword: String = "",
    val toast: String? = null,
    val editing: Favorite? = null,
    val counts: Map<FavoriteKind, Int> = emptyMap(),
)

class FavoritesViewModel(application: Application) : AndroidViewModel(application) {

    private val container = AppContainer.get(application)
    private val db = container.database

    private val _state = MutableStateFlow(FavoritesUiState())
    val state: StateFlow<FavoritesUiState> = _state.asStateFlow()

    init {
        reload()
    }

    fun setFilter(kind: FavoriteKind?) {
        _state.update { it.copy(filter = kind) }
        reload()
    }

    fun setKeyword(value: String) {
        _state.update { it.copy(keyword = value) }
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            val current = _state.value
            val items = db.favorites.list(current.filter, current.keyword)
            val counts = FavoriteKind.entries.associateWith { db.favorites.count(it) }
            _state.update { it.copy(loading = false, items = items, counts = counts) }
        }
    }

    fun delete(favorite: Favorite) {
        viewModelScope.launch {
            db.favorites.delete(favorite.id)
            _state.update { it.copy(toast = "已删除") }
            reload()
        }
    }

    /** 新建一条随手记喵。 */
    fun addNote(title: String, content: String) {
        viewModelScope.launch {
            db.favorites.insert(
                Favorite(
                    kind = FavoriteKind.NOTE,
                    title = title.ifBlank { content.take(16) },
                    content = content,
                    createdAt = com.qingyu.app.core.TimeUtil.now(),
                ),
            )
            _state.update { it.copy(toast = "已加入收藏空间") }
            reload()
        }
    }

    fun update(favorite: Favorite) {
        viewModelScope.launch {
            db.favorites.update(favorite)
            _state.update { it.copy(editing = null, toast = "已保存") }
            reload()
        }
    }

    fun startEdit(favorite: Favorite) = _state.update { it.copy(editing = favorite) }

    fun cancelEdit() = _state.update { it.copy(editing = null) }

    fun exportAll() {
        viewModelScope.launch {
            val uri = container.archive.exportFavorites()
            _state.update {
                it.copy(toast = if (uri == null) "收藏空间是空的" else "已导出到 下载/轻语 目录")
            }
        }
    }

    /** 导出单条收藏为文本喵。 */
    fun exportOne(favorite: Favorite) {
        viewModelScope.launch {
            val text = buildString {
                appendLine("# ${favorite.title}")
                appendLine()
                if (favorite.personaName.isNotBlank()) appendLine("角色：${favorite.personaName}")
                if (favorite.tags.isNotEmpty()) appendLine("标签：${favorite.tags.joinToString("、")}")
                appendLine()
                appendLine(favorite.content)
                if (favorite.detail.isNotBlank()) {
                    appendLine()
                    appendLine("---")
                    appendLine()
                    appendLine(favorite.detail)
                }
            }
            val uri: Uri? = container.archive.exportPlainText(
                "收藏_${favorite.title.take(16)}_${com.qingyu.app.core.TimeUtil.fileStamp()}.md",
                text,
            )
            _state.update { it.copy(toast = if (uri == null) "导出失败" else "已导出到 下载/轻语 目录") }
        }
    }

    fun clearToast() = _state.update { it.copy(toast = null) }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    FavoritesViewModel(application) as T
            }
    }
}
