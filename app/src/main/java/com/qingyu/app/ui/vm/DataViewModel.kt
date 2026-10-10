package com.qingyu.app.ui.vm

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.qingyu.app.AppContainer
import com.qingyu.app.core.TimeUtil
import com.qingyu.app.core.runCatchingResult
import com.qingyu.app.data.MessageRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 数据页：UI 状态与控制器。负责聊天记录的导入 / 导出喵。
 *
 * [DataUiState] 持有忙碌标志、toast 与最近导入的会话 id喵；
 * [DataViewModel] 支持导入裸数组格式的聊天记录 JSON（自动解析角色与会话喵），
 * 以及导出全部数据为 JSON 或 Markdown喵。
 * 实际读写逻辑委托给 [ChatArchive] 与 [ChatImportParser喵]。
 */
data class DataUiState(
    val busy: Boolean = false,
    val message: String? = null,
    val importedConversationId: Long = 0L,
)

/** 数据页：导入聊天记录、导出全部内容喵。 */
class DataViewModel(application: Application) : AndroidViewModel(application) {

    private val container = AppContainer.get(application)

    private val _state = MutableStateFlow(DataUiState())
    val state: StateFlow<DataUiState> = _state.asStateFlow()

    fun clearMessage() = _state.update { it.copy(message = null) }

    /**
     * 导入一个聊天记录 JSON喵。
     *
     * 导入的文件是裸数组格式，文件名形如 `聊天记录_苏婉_2026-10-06_15-54.json`喵，
     * [com.qingyu.app.data.archive.ChatArchive] 会从文件名里解析出角色名并自动建角色喵。
     */
    fun importChat(uri: Uri) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            val result = withContext(Dispatchers.IO) {
                runCatchingResult { container.archive.import(uri) }
            }
            _state.update {
                when (result) {
                    is com.qingyu.app.core.Result.Ok -> it.copy(
                        busy = false,
                        importedConversationId = result.value.conversationId,
                        message = "已导入 ${result.value.messageCount} 条消息到「${result.value.personaName}」",
                    )

                    is com.qingyu.app.core.Result.Err -> it.copy(busy = false, message = "导入失败：${result.message}")
                }
            }
        }
    }

    /** 导出所有会话（每个会话一个文件），以及收藏空间喵。 */
    fun exportEverything() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            val conversations = container.database.conversations.list()
            var chatCount = 0
            var messageCount = 0
            withContext(Dispatchers.IO) {
                conversations.forEach { conversation ->
                    if (conversation.messageCount <= 0) return@forEach
                    val uri = runCatching { container.archive.exportConversation(conversation.id) }.getOrNull()
                    if (uri != null) chatCount++
                }
                val favoritesUri = runCatching { container.archive.exportFavorites() }.getOrNull()
                messageCount = if (favoritesUri != null) 1 else 0
            }
            _state.update {
                it.copy(
                    busy = false,
                    message = "已导出 $chatCount 个对话${if (messageCount > 0) "，收藏空间已单独导出" else ""} 到 下载/轻语",
                )
            }
        }
    }

    /** 导出某个会话为 Markdown，方便直接阅读或分享喵。 */
    fun exportConversationAsMarkdown(conversationId: Long) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val uri = withContext(Dispatchers.IO) {
                runCatching { container.archive.exportConversationAsText(conversationId) }.getOrNull()
            }
            _state.update {
                it.copy(busy = false, message = if (uri == null) "导出失败" else "已导出到 下载/轻语")
            }
        }
    }

    /** 当前库里的统计信息，展示在设置页喵。 */
    suspend fun stats(): Triple<Int, Int, Int> = withContext(Dispatchers.IO) {
        val conversations = container.database.conversations.list()
        val messages = conversations.sumOf { it.messageCount }
        val favorites = container.database.favorites.count()
        Triple(conversations.size, messages, favorites)
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DataViewModel(application) as T
            }
    }
}
