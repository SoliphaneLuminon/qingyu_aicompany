package com.qingyu.app.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.qingyu.app.core.TimeUtil
import com.qingyu.app.core.memory.MemoryDecay
import com.qingyu.app.core.memory.PromptRefiner
import com.qingyu.app.core.memory.UtilityLlmConfig
import com.qingyu.app.core.memory.UtilityProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 主题模式喵。 */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色");

    companion object {
        fun fromName(raw: String?): ThemeMode =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: SYSTEM
    }
}

/** 全局设置快照。不可变，改动通过 [SettingsStore.update] 生成新快照并推给 UI喵。 */
data class AppSettings(
    /** OpenAI 兼容端点，例如 https://api.deepseek.com/v1 。[SettingsStore.chatEndpoint] 会补全路径喵。 */
    val apiBaseUrl: String = DEFAULT_BASE_URL,
    val apiKey: String = "",
    val model: String = "",
    val temperature: Double = 0.7,
    val maxTokens: Int = 4096,
    val topP: Double = 1.0,
    /** 连续回复条数：模型一次输出里按空行拆成多条消息，模拟真人连续发消息喵。 */
    val burstReplies: Boolean = true,
    /** 打字机速度：每条消息逐字显示的间隔（毫秒）。0 = 即时，默认 180ms喵。 */
    val typingSpeedMs: Int = 180,
    val streamResponse: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,

    // ---------------------------------------------------------------- 识图
    /** 主模型本身是否支持看图。为 true 时图片直接随对话发出喵。 */
    val visionMainModelSupportsImages: Boolean = false,
    /** 主模型不支持看图时，用这个模型把图片转成文字描述喵。 */
    val vision: ApiConfig = ApiConfig(),

    // ---------------------------------------------------------------- 语音
    val ttsProvider: TtsProvider = TtsProvider.SYSTEM,
    /** AI 语音合成接口（OpenAI 兼容 /v1/audio/speech喵）。 */
    val speech: ApiConfig = ApiConfig(
        model = "tts-1",
    ),
    val speechVoice: String = DEFAULT_SPEECH_VOICE,
    val speechFormat: String = "mp3",
    /**
     * 用户从外部导入的音色喵。
     *
     * 选中的那个由 [speechVoice] 指认（存的是 URI 喵），直接作为 `voice` 下发喵。
     */
    val customVoices: List<CustomVoice> = emptyList(),
    /**
     * 语音转文字（识别）用的模型名喵。
     *
     * 与 [speech] 共用同一套接口地址与 Key：OpenAI 兼容网关里
     * `/audio/speech`（合成）与 `/audio/transcriptions`（识别）本来就是同一族接口，
     * 再让用户填一遍地址纯属折腾喵。但模型名必须分开——
     * `tts-1` 是合成模型，拿去识别会被服务端拒掉喵。
     */
    val transcribeModel: String = "whisper-1",
    val ttsEnabled: Boolean = false,
    val ttsRate: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val autoSpeakReplies: Boolean = false,

    // ---------------------------------------------------------------- 联网搜索
    val searchProvider: SearchProvider = SearchProvider.BOCHA,
    /** 两家各自的 key，切换服务商时不用重填喵。 */
    val searchKeyBocha: String = "",
    val searchKeyTavily: String = "",
    /** 自定义搜索网关地址，留空则用服务商默认地址喵。 */
    val searchBaseUrlOverride: String = "",
    val searchResultCount: Int = 5,
    /** 新消息默认是否开启联网喵。 */
    val searchDefaultOn: Boolean = false,

    val petEnabled: Boolean = false,
    /** 桌宠悬浮窗上次停留的位置喵。 */
    val petX: Int = 40,
    val petY: Int = 420,
    val proactiveMessages: Boolean = false,
    /** 主动消息间隔（分钟喵）。 */
    val proactiveIntervalMinutes: Int = 180,
    val systemPromptSuffix: String = "",
    val injectMemories: Boolean = true,
    val historyLimit: Int = 30,
    val showReasoning: Boolean = true,
    val onboarded: Boolean = false,
    val lastPersonaId: Long = 0L,

    // ---------------------------------------------------------------- 记忆：遗忘曲线与唤醒
    /** 关闭后鲜活度不再衰减，等同于"永不遗忘"喵。 */
    val memoryDecayEnabled: Boolean = true,
    /** 普通记忆被唤醒后的硬性冷却轮数（人设节点不受影响喵）。 */
    val memoryCooldownTurns: Int = MemoryDecay.COOLDOWN_TURNS,
    /** 关闭后只要与当前话题相关就注入，不再掷骰子喵。 */
    val memoryProbabilityEnabled: Boolean = true,
    /** 一轮最多注入几条记忆喵。 */
    val memoryInjectLimit: Int = 8,
    /** 是否把"现在几点 / 多久没见"这类时空状态注入提示词喵。 */
    val memoryTemporalEnabled: Boolean = true,
    /** 每轮结束后是否异步提炼新记忆（提炼失败静默忽略喵）。 */
    val autoMemoryExtraction: Boolean = true,
    /** 最低重要度门槛：候选记忆的 importance 低于此值则不进入检索。0 = 关闭喵。 */
    val memoryMinImportance: Int = 0,
    /** 是否在检索后用后台小模型 Reranker 对唤醒候选做交叉比对精排喵。 */
    val memoryRerankerEnabled: Boolean = false,

    // ---------------------------------------------------------------- 记忆：后台小模型
    /** 是否用后台小模型润色系统提示词；超时即自动降级为原生提示词喵。 */
    val promptRefineEnabled: Boolean = false,
    /** 润色与提炼共用的服务商，MAIN 表示复用主模型的连接配置喵。 */
    val utilityProvider: UtilityProvider = UtilityProvider.MAIN,
    /** 润色/提炼的超时（毫秒喵）。 */
    val utilityTimeoutMs: Long = PromptRefiner.DEFAULT_TIMEOUT_MS,
    val utilityModel: String = "",
    val utilityBaseUrl: String = "",
    val utilityApiKey: String = "",
) {
    companion object {
        const val DEFAULT_BASE_URL = ""
    }
}

/**
 * 全局设置 → 后台小模型连接配置的翻译层喵。
 *
 * 润色与提炼共用同一条连接（同一个 provider / key / 地址喵），
 * 但各自的**开关是独立的**：可以只提炼不润色，也可以只润色不提炼喵。
 * 所以这里给出两份配置，`enabled` 分别取自各自的开关喵。
 */
fun AppSettings.utilityConfig(enabled: Boolean): UtilityLlmConfig = UtilityLlmConfig(
    enabled = enabled,
    provider = utilityProvider,
    model = utilityModel,
    baseUrl = utilityBaseUrl,
    apiKey = utilityApiKey,
    timeoutMs = utilityTimeoutMs,
)

/** 润色一路是否启用，由 [AppSettings.promptRefineEnabled] 决定喵。 */
fun AppSettings.refineConfig(): UtilityLlmConfig = utilityConfig(promptRefineEnabled)

/** 提炼一路是否启用，由 [AppSettings.autoMemoryExtraction] 决定喵。 */
fun AppSettings.extractConfig(): UtilityLlmConfig = utilityConfig(autoMemoryExtraction)

/**
 * 合成时实际该用的语音模型喵。
 *
 * 选中的如果是从外部导入的音色，就用它自带的模型——音色是绑在模型上的喵，
 * 拿全局模型去套别人的音色多半合成不出来喵。其余情况仍走设置里的语音模型喵。
 */
fun AppSettings.effectiveSpeechModel(): String =
    customVoices.firstOrNull { it.uri == speechVoice }?.model?.takeIf { it.isNotBlank() }
        ?: speech.model

/** 当前选中的音色是不是导入进来的外部音色喵。 */
fun AppSettings.selectedCustomVoice(): CustomVoice? =
    customVoices.firstOrNull { it.uri == speechVoice }

/**
 * 设置存取喵。
 *
 * 用 SharedPreferences 而不是 DataStore：设置项少、读写频繁，StateFlow 已经提供了响应式喵；
 * 少一个依赖就少一处版本冲突喵。
 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val gson = Gson()

    /** 音色列表要整段序列化进 SharedPreferences，所以需要一个列表类型喵。 */
    private val voiceListType = object : TypeToken<List<CustomVoice>>() {}.type

    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    val current: AppSettings get() = _state.value

    /** 供网络层使用：把用户填的 base url 补全成完整地址喵。 */
    val chatEndpoint: String get() = Endpoints.chat(current.apiBaseUrl)

    val speechEndpoint: String get() = Endpoints.speech(current.speech.baseUrl)

    val visionEndpoint: String get() = Endpoints.chat(current.vision.baseUrl)

    /** 当前服务商对应的搜索 key（切换服务商不用重填喵）。 */
    val searchKey: String
        get() = when (current.searchProvider) {
            SearchProvider.BOCHA -> current.searchKeyBocha
            SearchProvider.TAVILY -> current.searchKeyTavily
        }

    val searchConfigured: Boolean get() = searchKey.isNotBlank()

    fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        val next = transform(_state.value)
        _state.value = next
        persist(next)
        return next
    }

    private fun load(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            apiBaseUrl = prefs.getString(KEY_BASE_URL, null) ?: defaults.apiBaseUrl,
            apiKey = prefs.getString(KEY_API_KEY, null) ?: defaults.apiKey,
            model = prefs.getString(KEY_MODEL, null) ?: defaults.model,
            temperature = prefs.getFloat(KEY_TEMPERATURE, defaults.temperature.toFloat()).toDouble(),
            maxTokens = prefs.getInt(KEY_MAX_TOKENS, defaults.maxTokens),
            topP = prefs.getFloat(KEY_TOP_P, defaults.topP.toFloat()).toDouble(),
            burstReplies = prefs.getBoolean(KEY_BURST, defaults.burstReplies),
            typingSpeedMs = prefs.getInt(KEY_TYPING_SPEED, defaults.typingSpeedMs),
            streamResponse = prefs.getBoolean(KEY_STREAM, defaults.streamResponse),
            themeMode = ThemeMode.fromName(prefs.getString(KEY_THEME, null)),
            visionMainModelSupportsImages = prefs.getBoolean(KEY_VISION_NATIVE, defaults.visionMainModelSupportsImages),
            vision = ApiConfig(
                baseUrl = prefs.getString(KEY_VISION_URL, null) ?: defaults.vision.baseUrl,
                apiKey = prefs.getString(KEY_VISION_KEY, null) ?: defaults.vision.apiKey,
                model = prefs.getString(KEY_VISION_MODEL, null) ?: defaults.vision.model,
            ),
            ttsProvider = TtsProvider.fromName(prefs.getString(KEY_TTS_PROVIDER, null)),
            speech = ApiConfig(
                baseUrl = prefs.getString(KEY_SPEECH_URL, null) ?: defaults.speech.baseUrl,
                apiKey = prefs.getString(KEY_SPEECH_KEY, null) ?: defaults.speech.apiKey,
                model = prefs.getString(KEY_SPEECH_MODEL, null) ?: defaults.speech.model,
            ),
            speechVoice = prefs.getString(KEY_SPEECH_VOICE, null) ?: defaults.speechVoice,
            speechFormat = prefs.getString(KEY_SPEECH_FORMAT, null) ?: defaults.speechFormat,
            customVoices = decodeVoices(prefs.getString(KEY_CUSTOM_VOICES, null)),
            transcribeModel = prefs.getString(KEY_TRANSCRIBE_MODEL, null) ?: defaults.transcribeModel,
            ttsEnabled = prefs.getBoolean(KEY_TTS_ENABLED, defaults.ttsEnabled),
            ttsRate = prefs.getFloat(KEY_TTS_RATE, defaults.ttsRate),
            ttsPitch = prefs.getFloat(KEY_TTS_PITCH, defaults.ttsPitch),
            autoSpeakReplies = prefs.getBoolean(KEY_AUTO_SPEAK, defaults.autoSpeakReplies),
            searchProvider = SearchProvider.fromName(prefs.getString(KEY_SEARCH_PROVIDER, null)),
            searchKeyBocha = prefs.getString(KEY_SEARCH_KEY_BOCHA, null) ?: defaults.searchKeyBocha,
            searchKeyTavily = prefs.getString(KEY_SEARCH_KEY_TAVILY, null) ?: defaults.searchKeyTavily,
            searchBaseUrlOverride = prefs.getString(KEY_SEARCH_URL, null) ?: defaults.searchBaseUrlOverride,
            searchResultCount = prefs.getInt(KEY_SEARCH_COUNT, defaults.searchResultCount),
            searchDefaultOn = prefs.getBoolean(KEY_SEARCH_DEFAULT_ON, defaults.searchDefaultOn),
            petEnabled = prefs.getBoolean(KEY_PET, defaults.petEnabled),
            petX = prefs.getInt(KEY_PET_X, defaults.petX),
            petY = prefs.getInt(KEY_PET_Y, defaults.petY),
            proactiveMessages = prefs.getBoolean(KEY_PROACTIVE, defaults.proactiveMessages),
            proactiveIntervalMinutes = prefs.getInt(KEY_PROACTIVE_INTERVAL, defaults.proactiveIntervalMinutes),
            systemPromptSuffix = prefs.getString(KEY_PROMPT_SUFFIX, null) ?: defaults.systemPromptSuffix,
            injectMemories = prefs.getBoolean(KEY_INJECT_MEMORY, defaults.injectMemories),
            historyLimit = prefs.getInt(KEY_HISTORY_LIMIT, defaults.historyLimit),
            showReasoning = prefs.getBoolean(KEY_SHOW_REASONING, defaults.showReasoning),
            onboarded = prefs.getBoolean(KEY_ONBOARDED, defaults.onboarded),
            lastPersonaId = prefs.getLong(KEY_LAST_PERSONA, defaults.lastPersonaId),
            memoryDecayEnabled = prefs.getBoolean(KEY_MEMORY_DECAY, defaults.memoryDecayEnabled),
            memoryCooldownTurns = prefs.getInt(KEY_MEMORY_COOLDOWN, defaults.memoryCooldownTurns),
            memoryProbabilityEnabled = prefs.getBoolean(KEY_MEMORY_PROB, defaults.memoryProbabilityEnabled),
            memoryInjectLimit = prefs.getInt(KEY_MEMORY_LIMIT, defaults.memoryInjectLimit),
            memoryTemporalEnabled = prefs.getBoolean(KEY_MEMORY_TEMPORAL, defaults.memoryTemporalEnabled),
            autoMemoryExtraction = prefs.getBoolean(KEY_AUTO_EXTRACT, defaults.autoMemoryExtraction),
            memoryMinImportance = prefs.getInt(KEY_MEMORY_MIN_IMPORTANCE, defaults.memoryMinImportance),
            memoryRerankerEnabled = prefs.getBoolean(KEY_MEMORY_RERANKER, defaults.memoryRerankerEnabled),
            promptRefineEnabled = prefs.getBoolean(KEY_PROMPT_REFINE, defaults.promptRefineEnabled),
            utilityProvider = UtilityProvider.fromName(prefs.getString(KEY_UTILITY_PROVIDER, null)),
            utilityTimeoutMs = prefs.getLong(KEY_UTILITY_TIMEOUT, defaults.utilityTimeoutMs),
            utilityModel = prefs.getString(KEY_UTILITY_MODEL, null) ?: defaults.utilityModel,
            utilityBaseUrl = prefs.getString(KEY_UTILITY_URL, null) ?: defaults.utilityBaseUrl,
            utilityApiKey = prefs.getString(KEY_UTILITY_KEY, null) ?: defaults.utilityApiKey,
        )
    }

    /**
     * 读外部音色列表喵。
     *
     * 存档是整段 JSON，坏掉或换了形态都可能解析失败；这里一律退化成空列表喵——
     * 宁可让用户重导一次音色，也不能因为一条脏数据让整个设置页打不开喵。
     */
    private fun decodeVoices(raw: String?): List<CustomVoice> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { gson.fromJson<List<CustomVoice>>(raw, voiceListType) }
            .getOrNull()
            ?.filter { it.uri.isNotBlank() }
            .orEmpty()
    }

    private fun persist(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_BASE_URL, settings.apiBaseUrl)
            .putString(KEY_API_KEY, settings.apiKey)
            .putString(KEY_MODEL, settings.model)
            .putFloat(KEY_TEMPERATURE, settings.temperature.toFloat())
            .putInt(KEY_MAX_TOKENS, settings.maxTokens)
            .putFloat(KEY_TOP_P, settings.topP.toFloat())
            .putBoolean(KEY_BURST, settings.burstReplies)
            .putInt(KEY_TYPING_SPEED, settings.typingSpeedMs)
            .putBoolean(KEY_STREAM, settings.streamResponse)
            .putString(KEY_THEME, settings.themeMode.name)
            .putBoolean(KEY_VISION_NATIVE, settings.visionMainModelSupportsImages)
            .putString(KEY_VISION_URL, settings.vision.baseUrl)
            .putString(KEY_VISION_KEY, settings.vision.apiKey)
            .putString(KEY_VISION_MODEL, settings.vision.model)
            .putString(KEY_TTS_PROVIDER, settings.ttsProvider.name)
            .putString(KEY_SPEECH_URL, settings.speech.baseUrl)
            .putString(KEY_SPEECH_KEY, settings.speech.apiKey)
            .putString(KEY_SPEECH_MODEL, settings.speech.model)
            .putString(KEY_SPEECH_VOICE, settings.speechVoice)
            .putString(KEY_SPEECH_FORMAT, settings.speechFormat)
            .putString(KEY_CUSTOM_VOICES, gson.toJson(settings.customVoices))
            .putString(KEY_TRANSCRIBE_MODEL, settings.transcribeModel)
            .putBoolean(KEY_TTS_ENABLED, settings.ttsEnabled)
            .putFloat(KEY_TTS_RATE, settings.ttsRate)
            .putFloat(KEY_TTS_PITCH, settings.ttsPitch)
            .putBoolean(KEY_AUTO_SPEAK, settings.autoSpeakReplies)
            .putString(KEY_SEARCH_PROVIDER, settings.searchProvider.name)
            .putString(KEY_SEARCH_KEY_BOCHA, settings.searchKeyBocha)
            .putString(KEY_SEARCH_KEY_TAVILY, settings.searchKeyTavily)
            .putString(KEY_SEARCH_URL, settings.searchBaseUrlOverride)
            .putInt(KEY_SEARCH_COUNT, settings.searchResultCount)
            .putBoolean(KEY_SEARCH_DEFAULT_ON, settings.searchDefaultOn)
            .putBoolean(KEY_PET, settings.petEnabled)
            .putInt(KEY_PET_X, settings.petX)
            .putInt(KEY_PET_Y, settings.petY)
            .putBoolean(KEY_PROACTIVE, settings.proactiveMessages)
            .putInt(KEY_PROACTIVE_INTERVAL, settings.proactiveIntervalMinutes)
            .putString(KEY_PROMPT_SUFFIX, settings.systemPromptSuffix)
            .putBoolean(KEY_INJECT_MEMORY, settings.injectMemories)
            .putInt(KEY_HISTORY_LIMIT, settings.historyLimit)
            .putBoolean(KEY_SHOW_REASONING, settings.showReasoning)
            .putBoolean(KEY_ONBOARDED, settings.onboarded)
            .putLong(KEY_LAST_PERSONA, settings.lastPersonaId)
            .putBoolean(KEY_MEMORY_DECAY, settings.memoryDecayEnabled)
            .putInt(KEY_MEMORY_COOLDOWN, settings.memoryCooldownTurns)
            .putBoolean(KEY_MEMORY_PROB, settings.memoryProbabilityEnabled)
            .putInt(KEY_MEMORY_LIMIT, settings.memoryInjectLimit)
            .putBoolean(KEY_MEMORY_TEMPORAL, settings.memoryTemporalEnabled)
            .putBoolean(KEY_AUTO_EXTRACT, settings.autoMemoryExtraction)
            .putInt(KEY_MEMORY_MIN_IMPORTANCE, settings.memoryMinImportance)
            .putBoolean(KEY_MEMORY_RERANKER, settings.memoryRerankerEnabled)
            .putBoolean(KEY_PROMPT_REFINE, settings.promptRefineEnabled)
            .putString(KEY_UTILITY_PROVIDER, settings.utilityProvider.name)
            .putLong(KEY_UTILITY_TIMEOUT, settings.utilityTimeoutMs)
            .putString(KEY_UTILITY_MODEL, settings.utilityModel)
            .putString(KEY_UTILITY_URL, settings.utilityBaseUrl)
            .putString(KEY_UTILITY_KEY, settings.utilityApiKey)
            .apply()
    }

    private companion object {
        const val PREFS = "qingyu_settings"
        const val KEY_BASE_URL = "api_base_url"
        const val KEY_API_KEY = "api_key"
        const val KEY_MODEL = "model"
        const val KEY_TEMPERATURE = "temperature"
        const val KEY_MAX_TOKENS = "max_tokens"
        const val KEY_TOP_P = "top_p"
        const val KEY_BURST = "burst_replies"
        const val KEY_TYPING_SPEED = "typing_speed_ms"
        const val KEY_STREAM = "stream"
        const val KEY_THEME = "theme_mode"
        const val KEY_VISION_NATIVE = "vision_native"
        const val KEY_VISION_URL = "vision_url"
        const val KEY_VISION_KEY = "vision_key"
        const val KEY_VISION_MODEL = "vision_model"
        const val KEY_TTS_PROVIDER = "tts_provider"
        const val KEY_SPEECH_URL = "speech_url"
        const val KEY_SPEECH_KEY = "speech_key"
        const val KEY_SPEECH_MODEL = "speech_model"
        const val KEY_SPEECH_VOICE = "speech_voice"
        const val KEY_SPEECH_FORMAT = "speech_format"
        const val KEY_CUSTOM_VOICES = "custom_voices"
        const val KEY_TRANSCRIBE_MODEL = "transcribe_model"
        const val KEY_SEARCH_PROVIDER = "search_provider"
        const val KEY_SEARCH_KEY_BOCHA = "search_key_bocha"
        const val KEY_SEARCH_KEY_TAVILY = "search_key_tavily"
        const val KEY_SEARCH_URL = "search_url"
        const val KEY_SEARCH_COUNT = "search_count"
        const val KEY_SEARCH_DEFAULT_ON = "search_default_on"
        const val KEY_TTS_ENABLED = "tts_enabled"
        const val KEY_TTS_RATE = "tts_rate"
        const val KEY_TTS_PITCH = "tts_pitch"
        const val KEY_AUTO_SPEAK = "auto_speak"
        const val KEY_PET = "pet_enabled"
        const val KEY_PET_X = "pet_x"
        const val KEY_PET_Y = "pet_y"
        const val KEY_PROACTIVE = "proactive"
        const val KEY_PROACTIVE_INTERVAL = "proactive_interval"
        const val KEY_PROMPT_SUFFIX = "prompt_suffix"
        const val KEY_INJECT_MEMORY = "inject_memories"
        const val KEY_HISTORY_LIMIT = "history_limit"
        const val KEY_SHOW_REASONING = "show_reasoning"
        const val KEY_ONBOARDED = "onboarded"
        const val KEY_LAST_PERSONA = "last_persona"
        const val KEY_MEMORY_DECAY = "memory_decay"
        const val KEY_MEMORY_COOLDOWN = "memory_cooldown_turns"
        const val KEY_MEMORY_PROB = "memory_probability"
        const val KEY_MEMORY_LIMIT = "memory_inject_limit"
        const val KEY_MEMORY_TEMPORAL = "memory_temporal"
        const val KEY_AUTO_EXTRACT = "auto_memory_extraction"
        const val KEY_MEMORY_MIN_IMPORTANCE = "memory_min_importance"
        const val KEY_MEMORY_RERANKER = "memory_reranker"
        const val KEY_PROMPT_REFINE = "prompt_refine"
        const val KEY_UTILITY_PROVIDER = "utility_provider"
        const val KEY_UTILITY_TIMEOUT = "utility_timeout_ms"
        const val KEY_UTILITY_MODEL = "utility_model"
        const val KEY_UTILITY_URL = "utility_base_url"
        const val KEY_UTILITY_KEY = "utility_api_key"
    }
}

/** 首次启动时写入的示例角色喵。 */
object SeedPersonas {

    fun all(now: Long = TimeUtil.now()): List<Persona> = emptyList()
}
