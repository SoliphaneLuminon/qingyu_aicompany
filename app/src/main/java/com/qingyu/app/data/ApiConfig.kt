package com.qingyu.app.data

/**
 * 接口地址补全喵。
 *
 * 用户会填出各种形态：`https://api.deepseek.com`、`…/v1`、`…/v1/`喵、
 * 甚至整条 `…/v1/chat/completions`。这里统一收口成"根地址 + 资源路径"喵，
 * 纯函数，有单元测试喵。
 *
 * 轻语现在要对接三类接口（对话、语音合成、搜索喵），它们都是"可能带 /v1 的根地址 +
 * 一个固定路径"，所以这一份逻辑被三处共用喵。
 */
object ApiEndpoint {

    fun resolve(baseUrl: String, path: String, versionPrefix: String = "v1"): String {
        val raw = baseUrl.trim().trimEnd('/')
        if (raw.isEmpty()) return ""
        val cleanPath = path.trim('/')
        if (cleanPath.isEmpty()) return raw
        // 已经带完整路径就原样返回喵
        if (raw.endsWith("/$cleanPath")) return raw
        return when {
            raw.endsWith("/$versionPrefix") || raw.endsWith("/v1beta") || raw.endsWith("/openai") ->
                "$raw/$cleanPath"

            else -> "$raw/$versionPrefix/$cleanPath"
        }
    }
}

/** 常用接口的地址喵。 */
object Endpoints {
    const val CHAT_PATH = "chat/completions"
    const val SPEECH_PATH = "audio/speech"
    const val TRANSCRIPTION_PATH = "audio/transcriptions"
    const val MODELS_PATH = "models"

    fun chat(baseUrl: String): String = ApiEndpoint.resolve(baseUrl, CHAT_PATH)
    fun speech(baseUrl: String): String = ApiEndpoint.resolve(baseUrl, SPEECH_PATH)
    fun models(baseUrl: String): String = ApiEndpoint.resolve(baseUrl, MODELS_PATH)
}

/** 语音合成的音色来源喵。 */
enum class TtsProvider(val label: String) {
    /** 系统自带 TTS：零配置、离线，但音色普通喵。 */
    SYSTEM("系统语音"),

    /** OpenAI 兼容 /v1/audio/speech：音色自然，需要联网与 key喵。 */
    OPENAI("AI 语音（OpenAI 兼容）");

    companion object {
        fun fromName(raw: String?): TtsProvider =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: SYSTEM
    }
}

/**
 * `/v1/audio/speech` 的常用预设音色喵。
 *
 * 各家网关都照着 OpenAI 这套名字实现，所以列出来当快捷选项；
 * 真正要用别家的音色就走"导入外部音色"那条路喵。
 */
val PRESET_SPEECH_VOICES = listOf("alloy", "echo", "fable", "onyx", "nova", "shimmer", "coral", "verse")

/** 出厂音色，也是删掉导入音色后的回退目标喵。 */
const val DEFAULT_SPEECH_VOICE = "alloy"

/** 搜索服务商喵。 */
enum class SearchProvider(val label: String, val needsKey: Boolean, val hint: String) {
    BOCHA(
        "博查 Bocha",
        needsKey = true,
        hint = "中文效果好、国内可直连。到 open.bochaai.com 申请 key。",
    ),
    TAVILY(
        "Tavily",
        needsKey = true,
        hint = "英文与研究向内容更强，有免费额度。到 tavily.com 申请 key。",
    );

    companion object {
        fun fromName(raw: String?): SearchProvider =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: BOCHA
    }
}

/**
 * 某个接口的连接配置喵。
 *
 * 三类外部能力（识图、语音、搜索）各持一份，互不影响——喵
 * 用户可以用 DeepSeek 聊天 + 别家的识图 + 第三家的语音喵。
 */
data class ApiConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
) {
    val configured: Boolean get() = apiKey.isNotBlank() || baseUrl.isNotBlank()
}
