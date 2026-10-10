package com.qingyu.app.ui.vm

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.qingyu.app.AppContainer
import com.qingyu.app.data.MessageRole
import com.qingyu.app.data.ChatMessage
import com.qingyu.app.modules.ModuleHost
import com.qingyu.app.modules.ModuleManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 模块页：UI 状态与控制器——JS 插件的生命周期管理喵。
 *
 * [ModulesUiState] 持有模块列表、忙碌标志、最近一次运行的回显（按 id 区分喵）；
 * [ModulesViewModel] 支持安装（选择本地 zip）、启用/停用、卸载、运行喵。
 * 运行带超时保护；底层由 [ModuleManager] + [SimpleJsEngine] 提供喵
 * 沙箱执行能力（无网络、无文件、无反射喵）。
 */
data class ModulesUiState(
    val loading: Boolean = true,
    val modules: List<ModuleManifest> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
    val lastOutput: String = "",
    val lastOutputId: String = "",
    val runningId: String = "",
)

class ModulesViewModel(application: Application) : AndroidViewModel(application) {

    private val container = AppContainer.get(application)
    private val manager = com.qingyu.app.modules.ModuleManager(application)

    private val _state = MutableStateFlow(ModulesUiState())
    val state: StateFlow<ModulesUiState> = _state.asStateFlow()

    private val values = HashMap<String, String>()

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            _state.update { it.copy(loading = false, modules = manager.list()) }
        }
    }

    fun install(uri: Uri) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val stream = getApplication<Application>().contentResolver.openInputStream(uri)
                        ?: error("无法读取该文件")
                    manager.install(stream)
                }
            }
            _state.update {
                result.fold(
                    onSuccess = { manifest -> it.copy(busy = false, message = "已安装「${manifest.name}」", modules = manager.list()) },
                    onFailure = { error -> it.copy(busy = false, message = "安装失败：${error.message}") },
                )
            }
        }
    }

    fun toggle(manifest: ModuleManifest) {
        viewModelScope.launch {
            manager.setEnabled(manifest.id, !manifest.enabled)
            reload()
        }
    }

    fun uninstall(manifest: ModuleManifest) {
        viewModelScope.launch {
            manager.uninstall(manifest.id)
            _state.update { it.copy(message = "已卸载「${manifest.name}」") }
            reload()
        }
    }

    /** 运行模块并把它的输出显示在页面上喵。 */
    fun run(manifest: ModuleManifest, input: String = "") {
        if (_state.value.runningId.isNotEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(runningId = manifest.id, lastOutput = "") }

            // 先把模块可能用到的上下文一次性查出来，避免在 JS 线程里做数据库访问喵。
            val personaId = container.settings.current.lastPersonaId
            val personaName = runCatching {
                if (personaId > 0L) {
                    container.database.personas.get(personaId)?.name.orEmpty()
                } else {
                    container.database.personas.list().firstOrNull()?.name.orEmpty()
                }
            }.getOrDefault("")

            val conversation = runCatching { container.database.conversations.latestOrCreate(personaId) }.getOrNull()
            val recent = runCatching {
                conversation?.let { current ->
                    container.database.messages.list(current.id)
                        .filter { it.role != MessageRole.REASONING }
                        .takeLast(20)
                }.orEmpty()
            }.getOrDefault(emptyList())

            val created = java.util.Collections.synchronizedList(ArrayList<String>())
            val host = object : ModuleHost {
                override fun personaName(): String = personaName

                override fun recentMessages(count: Int): String =
                    recent.takeLast(count).joinToString("\n") { it.content }

                override fun pushMessage(text: String) {
                    if (text.isNotBlank()) created.add(text)
                }

                override fun getValue(key: String): String = values["${manifest.id}:$key"].orEmpty()

                override fun setValue(key: String, value: String) {
                    values["${manifest.id}:$key"] = value
                }
            }

            val result = withContext(Dispatchers.IO) { manager.run(manifest, host, input) }

            // 模块要求发到聊天里的内容，在事务里落库喵。
            if (created.isNotEmpty() && conversation != null) {
                withContext(Dispatchers.IO) {
                    created.forEach { text ->
                        container.database.messages.insert(
                            ChatMessage(
                                conversationId = conversation.id,
                                role = MessageRole.ASSISTANT,
                                content = text,
                                timestamp = com.qingyu.app.core.TimeUtil.now(),
                            ),
                        )
                    }
                    container.database.conversations.refreshSummary(conversation.id)
                }
            }

            _state.update {
                it.copy(
                    runningId = "",
                    lastOutputId = manifest.id,
                    lastOutput = buildString {
                        append(result.output)
                        if (created.isNotEmpty()) {
                            if (isNotEmpty()) append('\n')
                            append("已发送 ${created.size} 条消息到「${conversation?.title.orEmpty()}」")
                        }
                        if (isEmpty()) append(if (result.ok) "（模块没有输出）" else "运行失败")
                    },
                )
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    /** 一键安装随应用内置的示例模块，让用户先看到模块能做什么喵。 */
    fun installSamples() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            val installed = withContext(Dispatchers.IO) {
                runCatching {
                    manager.bundledSamples().mapNotNull { fileName ->
                        runCatching { manager.installBundled(fileName) }.getOrNull()
                    }
                }.getOrDefault(emptyList())
            }
            _state.update {
                it.copy(
                    busy = false,
                    modules = manager.list(),
                    message = if (installed.isEmpty()) "示例模块已经装过了" else "已安装 ${installed.size} 个示例模块",
                )
            }
        }
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ModulesViewModel(application) as T
            }
    }
}
