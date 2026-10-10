package com.qingyu.app.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.qingyu.app.AppContainer
import com.qingyu.app.core.memory.MemoryDecay
import com.qingyu.app.data.MemoryCategory
import com.qingyu.app.data.MemoryItem
import com.qingyu.app.data.db.MemoryStats
import com.qingyu.app.data.extractConfig
import com.qingyu.app.data.repo.MemoryTidyService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 记忆页：UI 状态与控制器——长期记忆的管理界面喵。
 *
 * [MemoryUiState] 持有列表、加载态、编辑弹窗的草稿与目标、toast喵；
 * [MemoryViewModel] 负责按角色加载、编辑弹窗（三元组与重要度）、启用开关喵、
 * 永久标记、手动刷新鲜活度、人设记忆快捷编辑，以及"从聊天提炼"手动入口喵
 * （与每轮自动提炼共用 [MemoryExtractionService喵]）。
 *
 * 记忆的衰减 / 唤醒在 [MemoryDirector] 与 [MemoryDecay]，本文件不碰那些逻辑喵。
 */
data class MemoryUiState(
    val loading: Boolean = true,
    val items: List<MemoryItem> = emptyList(),
    val personaId: Long = 0L,
    val personaName: String = "",
    val extracting: Boolean = false,
    /** 正在整理记忆库（聚类 + AI 融合喵）。 */
    val tidying: Boolean = false,
    val toast: String? = null,
    val editorTarget: MemoryItem? = null,
    val editorOpen: Boolean = false,
    val draftCategory: MemoryCategory = MemoryCategory.PROFILE,
    val draftContent: String = "",
    val draftImportance: Int = 3,
    val draftSubject: String = "",
    val draftPredicate: String = "",
    val draftObject: String = "",
    val draftContext: String = "",
    val draftPermanent: Boolean = false,
    val stats: MemoryStats = MemoryStats(),
)

/**
 * 记忆页的读写喵。
 *
 * 这一层的语义约定只有一条：**凡是用户改过的记忆，都必须立刻变"新"**喵。
 * 也就是 [MemoryItem.isUserEdited] 置 1、鲜活度补满（衰减速度减半、唤醒概率提高喵）。
 * 用户亲手纠正过的事被时间冲淡，是最伤人的一种 bug喵。
 */
class MemoryViewModel(application: Application) : AndroidViewModel(application) {

    private val container = AppContainer.get(application)
    private val db = container.database

    private val _state = MutableStateFlow(MemoryUiState())
    val state: StateFlow<MemoryUiState> = _state.asStateFlow()

    fun load(personaId: Long) {
        if (personaId <= 0L) {
            _state.update { it.copy(loading = false, items = emptyList()) }
            return
        }
        viewModelScope.launch {
            val persona = db.personas.get(personaId)
            val items = db.memories.list(personaId)
            val stats = db.memories.stats(personaId)
            _state.update {
                it.copy(
                    loading = false,
                    personaId = personaId,
                    personaName = persona?.name.orEmpty(),
                    items = items,
                    stats = stats,
                )
            }
        }
    }

    fun openEditor(existing: MemoryItem? = null) {
        _state.update {
            it.copy(
                editorOpen = true,
                editorTarget = existing,
                draftCategory = existing?.category ?: MemoryCategory.PROFILE,
                draftContent = existing?.content.orEmpty(),
                draftImportance = existing?.importance ?: 3,
                draftSubject = existing?.subject.orEmpty(),
                draftPredicate = existing?.predicate.orEmpty(),
                draftObject = existing?.obj.orEmpty(),
                draftContext = existing?.context.orEmpty(),
                draftPermanent = existing?.isPermanent ?: false,
            )
        }
    }

    fun closeEditor() = _state.update { it.copy(editorOpen = false, editorTarget = null) }

    fun onDraftChange(
        category: MemoryCategory? = null,
        content: String? = null,
        importance: Int? = null,
        subject: String? = null,
        predicate: String? = null,
        obj: String? = null,
        context: String? = null,
        permanent: Boolean? = null,
    ) {
        _state.update {
            it.copy(
                draftCategory = category ?: it.draftCategory,
                draftContent = content ?: it.draftContent,
                draftImportance = importance ?: it.draftImportance,
                draftSubject = subject ?: it.draftSubject,
                draftPredicate = predicate ?: it.draftPredicate,
                draftObject = obj ?: it.draftObject,
                draftContext = context ?: it.draftContext,
                draftPermanent = permanent ?: it.draftPermanent,
            )
        }
    }

    fun saveDraft() {
        val current = _state.value
        val content = current.draftContent.trim().ifBlank {
            listOf(current.draftSubject, current.draftPredicate, current.draftObject)
                .filter { it.isNotBlank() }
                .joinToString("")
        }
        if (content.isBlank()) return

        viewModelScope.launch {
            val existing = current.editorTarget
            if (existing == null) {
                db.memories.insert(
                    MemoryItem(
                        personaId = current.personaId,
                        category = current.draftCategory,
                        content = content,
                        importance = current.draftImportance,
                        subject = current.draftSubject.trim(),
                        predicate = current.draftPredicate.trim(),
                        obj = current.draftObject.trim(),
                        context = current.draftContext.trim(),
                        emotionalWeight = if (current.draftPermanent) 1.0 else 0.5,
                        vividness = MemoryDecay.initialVividness(),
                        isUserEdited = true,
                        isPermanent = current.draftPermanent,
                    ),
                )
            } else {
                db.memories.userUpdate(
                    existing.copy(
                        category = current.draftCategory,
                        content = content,
                        importance = current.draftImportance,
                        subject = current.draftSubject.trim(),
                        predicate = current.draftPredicate.trim(),
                        obj = current.draftObject.trim(),
                        context = current.draftContext.trim(),
                        isPermanent = current.draftPermanent,
                    ),
                )
            }
            _state.update { it.copy(editorOpen = false, editorTarget = null, toast = "已记住") }
            load(current.personaId)
        }
    }

    fun toggleEnabled(item: MemoryItem) {
        viewModelScope.launch {
            db.memories.setEnabled(item.id, !item.enabled)
            load(_state.value.personaId)
        }
    }

    /** 提升为 / 取消核心人设。人设享有免衰减、免冷却与 100% 优先唤醒喵。 */
    fun togglePermanent(item: MemoryItem) {
        viewModelScope.launch {
            db.memories.userUpdate(item.copy(isPermanent = !item.isPermanent))
            _state.update {
                it.copy(toast = if (item.isPermanent) "已取消人设标记" else "已设为核心人设")
            }
            load(_state.value.personaId)
        }
    }

    /** 鲜活度补满，等价于"又想了一遍"，让这条记忆回到刚记住的状态喵。 */
    fun refresh(item: MemoryItem) {
        viewModelScope.launch {
            db.memories.userUpdate(item)
            _state.update { it.copy(toast = "已重新记牢") }
            load(_state.value.personaId)
        }
    }

    /** 写入伴侣人设：同一角色只保留最新一份喵。 */
    fun setPartnerPersona(predicate: String, obj: String, context: String = "核心人设") {
        val current = _state.value
        if (current.personaId <= 0L) return
        viewModelScope.launch {
            db.memories.setPartnerPersona(current.personaId, predicate, obj, context)
            _state.update { it.copy(toast = "人设已更新") }
            load(current.personaId)
        }
    }

    /** 用户直接删掉一条记忆（连索引一起清喵）。 */
    fun delete(item: MemoryItem) {
        viewModelScope.launch {
            db.memories.delete(item.id)
            _state.update { it.copy(toast = "已忘记") }
            load(_state.value.personaId)
        }
    }

    /**
     * 用模型读一遍最近的对话，自动补几条记忆喵。
     *
     * 会话自己在这里取最新的那条：调用方（界面）只认识角色喵，
     * 让它去传 conversationId 很容易和 personaId 弄混喵，
     * 于是提炼出来的东西全落到了一个根本不存在的会话上喵。
     */
    fun extractFromChat() {
        val current = _state.value
        if (current.personaId <= 0L || current.extracting) return
        viewModelScope.launch {
            _state.update { it.copy(extracting = true) }
            val conversation = db.conversations.listForPersona(current.personaId).firstOrNull()
            val result = runCatching {
                container.chatEngine.extractMemories(current.personaId, conversation?.id ?: 0L)
            }
            _state.update {
                it.copy(
                    extracting = false,
                    toast = result.fold(
                        onSuccess = { items ->
                            if (items.isEmpty()) "这段对话里没有新的可记住的信息" else "已记住 ${items.size} 条"
                        },
                        onFailure = { error -> "整理失败：${error.message}" },
                    ),
                )
            }
            load(current.personaId)
        }
    }

    /**
     * 一次性整理：把散成好几条的同一件事融回一条喵。
     *
     * 与 [extractFromChat] 的分工：那个是"再记点新的"，这个是"收拾旧的"——喵
     * 每轮自动去重只管新记忆，管不了库里已经存在的重复喵。
     *
     * 破坏性操作，所以只有这个手动入口，且判定拿不准时一条都不动喵。
     */
    fun tidy() {
        val current = _state.value
        if (current.personaId <= 0L || current.tidying) return
        val settings = container.settings.current
        viewModelScope.launch {
            _state.update { it.copy(tidying = true) }
            val result = runCatching {
                container.memoryTidy.tidy(
                    personaId = current.personaId,
                    config = settings.extractConfig(),
                    mainEndpoint = container.settings.chatEndpoint,
                    mainModel = settings.model,
                    mainApiKey = settings.apiKey,
                )
            }
            _state.update {
                it.copy(
                    tidying = false,
                    toast = result.fold(
                        onSuccess = ::describe,
                        onFailure = { error -> "整理失败：${error.message}" },
                    ),
                )
            }
            load(current.personaId)
        }
    }

    /**
     * 把整理结果说成一句人话喵。
     *
     * "什么都没做"的原因也讲清楚：否则用户会以为按钮坏了喵，
     * 而这恰恰是最常见的结果——模型判定它们本来就不是同一件事喵。
     */
    private fun describe(report: MemoryTidyService.Report): String = when {
        !report.ok -> "整理失败：${report.error}"
        report.scanned == 0 -> "还没有可整理的记忆"

        // "判不出来"必须与"确实没有重复"分开说喵。
        // 两条并作一句显示的话，模型超时会被误读成"没有发现同一件事"喵。
        report.merges == 0 && report.failedBatches > 0 ->
            "看了一遍 ${report.scanned} 条，有 ${report.failedBatches} 批没能判定（超时或答复异常），未做改动"

        report.merges == 0 -> "看了一遍 ${report.scanned} 条，没有发现同一件事"

        report.failedBatches > 0 ->
            "整理了 ${report.merges} 组：融合保留 ${report.merges} 条，删除 ${report.removed} 条；" +
                "另有 ${report.failedBatches} 批没能判定"

        else -> "整理了 ${report.merges} 组：融合保留 ${report.merges} 条，删除 ${report.removed} 条"
    }

    fun clearToast() = _state.update { it.copy(toast = null) }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MemoryViewModel(application) as T
            }
    }
}
