package com.qingyu.app.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.qingyu.app.AppContainer
import com.qingyu.app.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页：UI 状态与控制器——所有 [AppSettings] 的读写与接口测试喵。
 *
 * [SettingsUiState] 持有完整设置快照、toast、可用模型列表与测试忙碌标志喵；
 * [SettingsViewModel] 提供统一入口 `update(transform)`：任何设置页改动都是对喵
 * [AppSettings] 做不可变替换，落盘集中在 [SettingsStore.persist喵]
 * （新增字段时四处改齐，见 docs/参数与版本管理.md §4.1喵）。
 *
 * 四个"测试"按钮（主连接 / 识图 / 语音 / 搜索）各自独立请求、独立提示喵；
 * 可用模型列表按"地址｜Key"分桶缓存，切换服务商不串数据喵；
 * `appVersion` 启动时从系统包信息读一次，版本号唯一真源在 build.gradle.kts喵。
 */
data class SettingsUiState(
    val testing: Boolean = false,
    val testResult: String? = null,
    val testOk: Boolean = false,
    val toast: String? = null,
    val appVersion: String = "",
    /** 正在测试哪个外部能力：chat / vision / speech / search喵。 */
    val testingKind: String = "",
    val visionTestResult: String? = null,
    val visionTestOk: Boolean = false,
    val speechTestResult: String? = null,
    val speechTestOk: Boolean = false,
    val searchTestResult: String? = null,
    val searchTestOk: Boolean = false,
    /** 正在拉取可用模型列表喵。 */
    val fetchingModels: Boolean = false,
    /** 拉取到的可用模型 id 列表，点选即填入设置喵。 */
    val modelList: List<String> = emptyList(),
    /** 拉取失败时的提示喵。 */
    val modelsError: String? = null,
    /** 已为哪一组 (接口地址, API Key) 拉取过，避免同样的连接反复请求喵。 */
    val modelsFetchedForKey: String = "",
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val container = AppContainer.get(application)

    val settings: StateFlow<AppSettings> = container.settings.state

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        val version = runCatching {
            application.packageManager.getPackageInfo(application.packageName, 0).versionName
        }.getOrNull() ?: "1.0.0"
        _state.update { it.copy(appVersion = version) }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        container.settings.update(transform)
    }

    fun testConnection() {
        if (_state.value.testing) return
        viewModelScope.launch {
            _state.update { it.copy(testing = true, testResult = null) }
            val result = withContext(Dispatchers.IO) { container.chatEngine.testConnection() }
            _state.update {
                it.copy(
                    testing = false,
                    testOk = result.isSuccess,
                    testResult = result.fold(
                        onSuccess = { reply -> "连接正常，模型回复：$reply" },
                        onFailure = { error -> "连接失败：${error.message}" },
                    ),
                )
            }
        }
    }

    /**
     * 拉取当前接口下可用的模型列表喵。
     *
     * @param auto 是否由 UI 在输入地址与 key 后自动触发。自动触发时喵，
     *  若这组 (地址, key) 已经成功拉取过就不再重复请求喵。
     */
    fun fetchModels(auto: Boolean = false) {
        val s = container.settings.current
        if (s.apiBaseUrl.isBlank() || s.apiKey.isBlank()) {
            _state.update { it.copy(modelsError = "先填接口地址和 API Key", modelList = emptyList()) }
            return
        }
        val key = modelsKey(s)
        if (auto && _state.value.modelsFetchedForKey == key && _state.value.modelList.isNotEmpty()) return
        if (_state.value.fetchingModels) return
        viewModelScope.launch {
            _state.update { it.copy(fetchingModels = true, modelsError = null) }
            val result = withContext(Dispatchers.IO) { container.chatEngine.fetchModels() }
            _state.update {
                it.copy(
                    fetchingModels = false,
                    modelList = result.getOrDefault(emptyList()),
                    modelsError = result.exceptionOrNull()?.message,
                    modelsFetchedForKey = key,
                )
            }
        }
    }

    /** 地址或 key 被清空时，收起已经拉出来的列表喵。 */
    fun clearModels() = _state.update {
        it.copy(modelList = emptyList(), modelsError = null, modelsFetchedForKey = "")
    }

    private fun modelsKey(s: AppSettings): String = "${s.apiBaseUrl.trim()}｜${s.apiKey.trim()}"

    fun clearToast() = _state.update { it.copy(toast = null) }

    /** 测试识图接口：用一张内置生成的图片去问一句，看能否拿到描述喵。 */
    fun testVision() {
        val settings = container.settings.current
        if (settings.vision.model.isBlank()) {
            _state.update { it.copy(visionTestOk = false, visionTestResult = "先填识图模型名") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(testingKind = "vision", visionTestResult = null) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val dataUrl = sampleImageDataUrl()
                        ?: error("生成测试图片失败")
                    container.visionClient.describe(
                        endpoint = container.settings.visionEndpoint,
                        apiKey = container.settings.current.vision.apiKey,
                        model = container.settings.current.vision.model,
                        imageDataUrl = dataUrl,
                    )
                }
            }
            _state.update {
                it.copy(
                    testingKind = "",
                    visionTestOk = result.isSuccess,
                    visionTestResult = result.fold(
                        onSuccess = { text -> "识图正常：${text.take(80)}" },
                        onFailure = { error -> "识图失败：${error.message}" },
                    ),
                )
            }
        }
    }

    /** 测试语音合成：合成一句短话，只验证能否拿到音频，不播放喵。 */
    fun testSpeech() {
        val settings = container.settings.current
        if (settings.speech.model.isBlank() || settings.speech.baseUrl.isBlank()) {
            _state.update { it.copy(speechTestOk = false, speechTestResult = "先填接口地址与语音模型") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(testingKind = "speech", speechTestResult = null) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = container.speechClient.synthesize(
                        endpoint = container.settings.speechEndpoint,
                        apiKey = container.settings.current.speech.apiKey,
                        model = container.settings.current.speech.model,
                        voice = container.settings.current.speechVoice,
                        format = container.settings.current.speechFormat,
                        text = "轻语语音测试",
                        speed = container.settings.current.ttsRate,
                    )
                    bytes.size
                }
            }
            _state.update {
                it.copy(
                    testingKind = "",
                    speechTestOk = result.isSuccess,
                    speechTestResult = result.fold(
                        onSuccess = { size -> "合成成功，收到 ${size / 1024} KB 音频" },
                        onFailure = { error -> "合成失败：${error.message}" },
                    ),
                )
            }
        }
    }

    /** 测试联网搜索喵。 */
    fun testSearch() {
        val settings = container.settings.current
        if (!container.settings.searchConfigured) {
            _state.update { it.copy(searchTestOk = false, searchTestResult = "先填 ${settings.searchProvider.label} 的 API Key") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(testingKind = "search", searchTestResult = null) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    container.searchClient.search(
                        provider = container.settings.current.searchProvider,
                        apiKey = container.settings.searchKey,
                        query = "今天的日期",
                        count = 3,
                        baseUrlOverride = container.settings.current.searchBaseUrlOverride,
                    )
                }
            }
            _state.update {
                it.copy(
                    testingKind = "",
                    searchTestOk = result.isSuccess && result.getOrNull()?.isNotEmpty() == true,
                    searchTestResult = result.fold(
                        onSuccess = { hits ->
                            if (hits.isEmpty()) "接口通了，但没返回结果" else "搜索正常，返回 ${hits.size} 条：${hits.first().title.take(30)}"
                        },
                        onFailure = { error -> "搜索失败：${error.message}" },
                    ),
                )
            }
        }
    }

    /** 生成一张纯色测试图，避免为了测识图去相册挑图喵。 */
    private fun sampleImageDataUrl(): String? = runCatching {
        val bitmap = android.graphics.Bitmap.createBitmap(320, 160, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.parseColor("#6C5CE7"))
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 48f
            isAntiAlias = true
        }
        canvas.drawText("轻语 测试图", 40f, 95f, paint)
        val bytes = java.io.ByteArrayOutputStream().use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        bitmap.recycle()
        "data:image/png;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }.getOrNull()

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SettingsViewModel(application) as T
            }
    }
}
