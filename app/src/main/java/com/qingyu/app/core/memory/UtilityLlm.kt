package com.qingyu.app.core.memory

import com.qingyu.app.data.Endpoints

/**
 * 跑"小模型"活儿的服务商：润色与提炼都在这一层，跟主对话模型分开喵。
 *
 * [MAIN] 表示复用主模型的连接配置——默认就是它喵，
 * 这样不额外配置任何东西也能用（只在每次生成时多一两次轻量请求喵）。
 */
enum class UtilityProvider(
    val label: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    /** 本地 Ollama 不需要 key喵。 */
    val requiresKey: Boolean,
    /** Ollama 的 OpenAI 兼容层对 response_format 支持不一致，不走 JSON 模式喵。 */
    val supportsJsonMode: Boolean,
) {
    MAIN("跟随主模型", "", "", requiresKey = true, supportsJsonMode = true),
    OPENAI("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini", requiresKey = true, supportsJsonMode = true),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", requiresKey = true, supportsJsonMode = true),
    OLLAMA("本地 Ollama", "http://localhost:11434/v1", "llama3.2", requiresKey = false, supportsJsonMode = false);

    companion object {
        fun fromName(raw: String?): UtilityProvider =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: MAIN
    }
}

/**
 * 小模型的连接配置喵。
 *
 * 留空的字段会退回各服务商的默认值，所以只选一个 provider 也能跑喵。
 */
data class UtilityLlmConfig(
    val enabled: Boolean = false,
    val provider: UtilityProvider = UtilityProvider.MAIN,
    val model: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val timeoutMs: Long = PromptRefiner.DEFAULT_TIMEOUT_MS,
)

/** 一次小模型请求喵。 */
data class UtilityRequest(
    val endpoint: String,
    val apiKey: String,
    val model: String,
    val systemInstruction: String,
    val userContent: String,
    val jsonMode: Boolean = false,
    val temperature: Double = 0.2,
    val maxTokens: Int = 1024,
)

/**
 * 小模型的执行抽象 —— 只有这一个方法喵。
 *
 * 抽成接口是为了两件事：换 provider 时不用改调用方喵，
 * 以及测试里可以用一个会 `delay` 的假实现去验证熔断喵。
 */
fun interface UtilityLlm {
    suspend fun complete(request: UtilityRequest): String
}

/**
 * 把配置"补全"成一次真正可发的请求喵。
 *
 * 纯函数，不碰网络：provider 的默认值、地址补全、可用性判断都在这里收口喵，
 * 调用方拿到的是一个要么可用、要么明确不可用的结果喵。
 */
object UtilityLlmRouter {

    /** 补全后的结果。[usable] 为 false 时调用方应直接走降级路径喵。 */
    data class Resolved(
        val endpoint: String,
        val model: String,
        val apiKey: String,
        val timeoutMs: Long,
        val usable: Boolean,
        val reason: String,
    )

    /**
     * @param config 用户在设置页填的小模型配置喵。
     * @param mainEndpoint 主模型的完整地址（provider = MAIN 时复用喵）。
     * @param mainModel 主模型名（model 留空时复用喵）。
     * @param mainApiKey 主模型 key（key 留空时复用喵）。
     */
    fun resolve(
        config: UtilityLlmConfig,
        mainEndpoint: String,
        mainModel: String,
        mainApiKey: String,
    ): Resolved {
        if (!config.enabled) {
            return Resolved("", "", "", config.timeoutMs, false, "未启用小模型")
        }

        val provider = config.provider
        val endpoint = when (provider) {
            UtilityProvider.MAIN -> mainEndpoint
            else -> Endpoints.chat(config.baseUrl.ifBlank { provider.defaultBaseUrl })
        }
        val model = config.model.ifBlank {
            if (provider == UtilityProvider.MAIN) mainModel else provider.defaultModel
        }
        val apiKey = config.apiKey.ifBlank { mainApiKey }

        val missing = when {
            endpoint.isBlank() -> "接口地址"
            model.isBlank() -> "模型名"
            provider.requiresKey && apiKey.isBlank() -> "API Key"
            else -> null
        }
        if (missing != null) {
            return Resolved(endpoint, model, apiKey, config.timeoutMs, false, "缺少$missing")
        }
        return Resolved(endpoint, model, apiKey, config.timeoutMs, true, "ok")
    }

    /** 把配置套用到一段提示词上，得到可发送的请求喵。 */
    fun request(
        config: UtilityLlmConfig,
        mainEndpoint: String,
        mainModel: String,
        mainApiKey: String,
        systemInstruction: String,
        userContent: String,
        jsonMode: Boolean = false,
        maxTokens: Int = 1024,
        temperature: Double = 0.2,
    ): UtilityRequest? {
        val resolved = resolve(config, mainEndpoint, mainModel, mainApiKey)
        if (!resolved.usable) return null
        return UtilityRequest(
            endpoint = resolved.endpoint,
            apiKey = resolved.apiKey,
            model = resolved.model,
            systemInstruction = systemInstruction,
            userContent = userContent,
            jsonMode = jsonMode && config.provider.supportsJsonMode,
            temperature = temperature,
            maxTokens = maxTokens,
        )
    }
}
