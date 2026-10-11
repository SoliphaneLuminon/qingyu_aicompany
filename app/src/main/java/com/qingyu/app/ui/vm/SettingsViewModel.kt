package com.qingyu.app.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.qingyu.app.AppContainer
import com.qingyu.app.core.memory.UtilityProvider
import com.qingyu.app.data.ApiConfig
import com.qingyu.app.data.AppSettings
import com.qingyu.app.data.CustomVoiceParser
import com.qingyu.app.data.DEFAULT_SPEECH_VOICE
import com.qingyu.app.data.TtsProvider
import com.qingyu.app.data.effectiveSpeechModel
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
    /** 语音识别测试的结果喵。 */
    val transcribeTestResult: String? = null,
    val transcribeTestOk: Boolean = false,
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
    /**
     * 各个"外部能力"各自的可用模型列表喵。
     *
     * 键是 [ModelSlot] 的名字（vision / speech / transcribe），值是拉到的模型 id喵。
     * 主对话那一路仍用 [modelList]（它是设置页最上面、用得最多的那个）喵。
     */
    val slotModels: Map<String, List<String>> = emptyMap(),
    /** 各能力正在拉取的状态喵。 */
    val slotFetching: Set<String> = emptySet(),
    /** 各能力拉取失败的提示喵。 */
    val slotErrors: Map<String, String> = emptyMap(),
    /** 各能力已成功拉取过的 (地址｜Key)，避免重复请求喵。 */
    val slotFetchedFor: Map<String, String> = emptyMap(),
)

/**
 * 需要"填好地址与 Key 就自动列出模型"的配置位喵。
 *
 * 用枚举而不是散落的字符串键，是为了让"哪些地方需要模型选择"这件事
 * 只有一个真源——将来再加一类能力，编译器会提醒把每个 when 补全喵。
 */
enum class ModelSlot {
    /** 识图模型喵。 */
    VISION,

    /** 语音合成模型喵。 */
    SPEECH,

    /** 语音识别模型喵。 */
    TRANSCRIBE,

    /** 小模型（润色 / 记忆提炼 / 识图描述这类辅助任务）喵。 */
    UTILITY;

    val key: String get() = name.lowercase()
}

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

    // ------------------------------------------------------------ 各能力的模型列表

    /**
     * 按配置位拉取可用模型喵。
     *
     * 三类能力各有独立的服务商，所以各拉各的、各存各的，互不干扰喵。
     * 地址或 Key 一被改空就立刻收起列表，免得旧服务商的模型名留在屏幕上误导人喵。
     *
     * @param auto 由界面在填完地址与 Key 后自动触发；这组连接已经成功拉过就不重复请求喵
     */
    fun fetchSlotModels(slot: ModelSlot, auto: Boolean = false) {
        val config = slotConfig(slot)
        val baseUrl = config.baseUrl.trim()
        val apiKey = config.apiKey.trim()

        // 本地 Ollama 不需要 key，所以"必须有 key"这条只对需要鉴权的服务商成立喵。
        val keyRequired = slot != ModelSlot.UTILITY ||
            container.settings.current.utilityProvider.requiresKey

        if (baseUrl.isBlank() || (keyRequired && apiKey.isBlank())) {
            _state.update {
                it.copy(
                    slotModels = it.slotModels - slot.key,
                    slotErrors = it.slotErrors - slot.key,
                    slotFetchedFor = it.slotFetchedFor - slot.key,
                )
            }
            return
        }
        val key = "$baseUrl｜$apiKey"
        val current = _state.value
        if (auto && current.slotFetchedFor[slot.key] == key && current.slotModels[slot.key]?.isNotEmpty() == true) return
        if (slot.key in current.slotFetching) return

        viewModelScope.launch {
            _state.update {
                it.copy(slotFetching = it.slotFetching + slot.key, slotErrors = it.slotErrors - slot.key)
            }
            val result = withContext(Dispatchers.IO) { container.chatEngine.fetchModels(baseUrl, apiKey) }
            _state.update {
                it.copy(
                    slotFetching = it.slotFetching - slot.key,
                    slotModels = it.slotModels + (slot.key to result.getOrDefault(emptyList())),
                    slotErrors = result.exceptionOrNull()?.message
                        ?.let { message -> it.slotErrors + (slot.key to message) }
                        ?: (it.slotErrors - slot.key),
                    slotFetchedFor = it.slotFetchedFor + (slot.key to key),
                )
            }
        }
    }

    /** 取出某个配置位当前的地址与 Key喵。 */
    private fun slotConfig(slot: ModelSlot): ApiConfig {
        val s = container.settings.current
        return when (slot) {
            // 语音合成与识别共用同一组地址与 Key（同一族接口），所以两者取的是同一份喵。
            ModelSlot.VISION -> s.vision
            ModelSlot.SPEECH, ModelSlot.TRANSCRIBE -> s.speech
            // 小模型有两层回退，必须跟运行时保持一致，否则会拿一个错的地址去拉模型喵：
            // 选了「跟随主模型」就用主连接的地址与 Key；否则地址留空时退回该服务商的默认地址喵。
            ModelSlot.UTILITY -> when (s.utilityProvider) {
                UtilityProvider.MAIN -> ApiConfig(
                    baseUrl = s.apiBaseUrl,
                    apiKey = s.apiKey,
                    model = s.utilityModel,
                )

                else -> ApiConfig(
                    baseUrl = s.utilityBaseUrl.ifBlank { s.utilityProvider.defaultBaseUrl },
                    apiKey = s.utilityApiKey,
                    model = s.utilityModel,
                )
            }
        }
    }

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
        if (settings.effectiveSpeechModel().isBlank() || settings.speech.baseUrl.isBlank()) {
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
                        model = container.settings.current.effectiveSpeechModel(),
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

    /**
     * 测试语音识别：只校验配置是否齐全，**不真的录音**喵。
     *
     * 不录音是有意的：识别要一句真人说话才有意义，而设置页里没法让用户
     * "对着设置页说一句"——那还不如直接去聊天页按住说话喵。
     * 所以这里只做能做的部分：确认地址、Key、模型名都填了，
     * 并且**真的发一次请求**去探接口是否可达（用一个极短的静音音频）喵。
     * 这样"配错了"能在设置页当场发现，而不是等到按住说话时才报错喵。
     */
    fun testTranscription() {
        val settings = container.settings.current
        if (settings.speech.baseUrl.isBlank() || settings.speech.apiKey.isBlank()) {
            _state.update { it.copy(transcribeTestOk = false, transcribeTestResult = "先填接口地址与语音 API Key") }
            return
        }
        if (settings.transcribeModel.isBlank()) {
            _state.update { it.copy(transcribeTestOk = false, transcribeTestResult = "先填语音识别模型") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(testingKind = "transcribe", transcribeTestResult = null) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // 探活用的极短静音 WAV：只验证"这个地址 + Key + 模型名能不能被接受"喵。
                    // 返回空文本是正常的——静音本来就该转不出字喵。
                    val probe = java.io.File.createTempFile("qy_probe", ".wav")
                    try {
                        probe.writeBytes(com.qingyu.app.media.SilentWav.build())
                        container.transcription.transcribe(
                            baseUrl = settings.speech.baseUrl,
                            apiKey = settings.speech.apiKey,
                            model = settings.transcribeModel,
                            audio = probe,
                        )
                    } finally {
                        probe.delete()
                    }
                }
            }
            _state.update {
                it.copy(
                    testingKind = "",
                    transcribeTestOk = result.isSuccess,
                    transcribeTestResult = result.fold(
                        // 能通就行：静音转不出文字是预期结果，不该报成失败喵。
                        onSuccess = { "接口可用（静音样本返回空文本属正常）" },
                        onFailure = { error -> "识别接口失败：${error.message}" },
                    ),
                )
            }
        }
    }

    /**
     * 导入一个外部音色喵。
     *
     * 解析不出音色标识就只回一句提示、不写设置喵——用户需要看到"为什么没导进来"，
     * 而不是按钮点下去毫无反应喵。导入成功会顺手切到 AI 语音并把该音色设为当前音色喵。
     */
    fun importCustomVoice(raw: String) {
        val parsed = CustomVoiceParser.parse(raw)
        if (parsed == null) {
            _state.update { it.copy(toast = "没找到音色标识，检查一下是否粘贴完整") }
            return
        }
        container.settings.update { settings ->
            // 同一个音色重复导入就覆盖旧的，避免列表里堆出一串同名项喵。
            settings.copy(
                customVoices = settings.customVoices.filterNot { it.uri == parsed.uri } + parsed,
                speechVoice = parsed.uri,
                ttsProvider = TtsProvider.OPENAI,
            )
        }
        _state.update { it.copy(toast = "已导入音色「${parsed.name}」") }
    }

    /** 删除一个外部音色；删的正是当前选中的就退回预设音色喵。 */
    fun removeCustomVoice(uri: String) {
        container.settings.update { settings ->
            settings.copy(
                customVoices = settings.customVoices.filterNot { it.uri == uri },
                speechVoice = if (settings.speechVoice == uri) DEFAULT_SPEECH_VOICE else settings.speechVoice,
            )
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
