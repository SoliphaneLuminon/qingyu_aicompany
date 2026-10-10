package com.qingyu.app.core.memory

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 提示词的来源：到底是模型润色过的，还是代码拼出来的喵。 */
enum class RefineSource { MODEL, NATIVE }

/** 为什么走了降级。设置页和调试页会把它显示出来喵。 */
enum class RefineReason(val label: String) {
    OK("已润色"),
    DISABLED("未启用润色"),
    NO_CONFIG("缺少配置"),
    TIMEOUT("超时熔断"),
    ERROR("调用异常"),
    EMPTY("返回为空"),
    TOO_SHORT("结果过短"),
}

/** 润色结果。[prompt] 永远非空——拿不到就用原生提示词顶上喵。 */
data class RefineOutcome(
    val prompt: String,
    val source: RefineSource,
    val reason: RefineReason,
) {
    val refined: Boolean get() = source == RefineSource.MODEL
}

/**
 * 前台 Prompt 润色器（Refiner & Fallback喵）。
 *
 * 拼接好的系统提示词常常是"模块标题 + 列表项"的机器形状，读起来生硬喵。
 * 这里让一个小模型把措辞捋顺。**但润色是纯收益项，不是必经之路**喵：
 * 超过 [DEFAULT_TIMEOUT_MS] 还没返回就直接丢掉，用代码拼好的原生提示词接着发喵。
 *
 * 三条降级纪律喵：
 * 1. 超时优先于质量 —— 用户等的是回复，不是更漂亮的提示词喵；
 * 2. 任何异常都降级，不往上抛 —— 润色挂了不该让对话失败喵；
 * 3. 结果反而更短就别用 —— 常见的失败形态是模型只回了"好的"两个字喵。
 */
object PromptRefiner {

    /** 严苛超时：800ms喵。 */
    const val DEFAULT_TIMEOUT_MS = 800L

    /** 润色结果至少要这么长才采信，短于此基本是模型敷衍喵。 */
    const val MIN_REFINED_LENGTH = 20

    /** 润色指令。明确禁止改动事实与人设——润色只能改说法喵。 */
    val INSTRUCTION: String = """
        润色输入的Prompt，使其自然连贯，严禁改变或遗漏任何事实/人设，直接输出润色后内容。
    """.trimIndent()

    /**
     * 润色一段提示词，失败时返回原样喵。
     *
     * @param config 小模型配置（含 provider 与超时喵）。
     * @param client 执行器；为 null 时直接走原生提示词喵。
     */
    suspend fun refine(
        rawPrompt: String,
        config: UtilityLlmConfig,
        client: UtilityLlm?,
        systemInstruction: String = INSTRUCTION,
        mainEndpoint: String = "",
        mainModel: String = "",
        mainApiKey: String = "",
        maxTokens: Int = 2048,
    ): RefineOutcome {
        if (!config.enabled || client == null) {
            return RefineOutcome(rawPrompt, RefineSource.NATIVE, RefineReason.DISABLED)
        }

        val request = UtilityLlmRouter.request(
            config = config,
            mainEndpoint = mainEndpoint,
            mainModel = mainModel,
            mainApiKey = mainApiKey,
            systemInstruction = systemInstruction,
            userContent = rawPrompt,
            jsonMode = false,
            maxTokens = maxTokens,
            temperature = 0.2,
        )
        if (request == null) {
            return RefineOutcome(rawPrompt, RefineSource.NATIVE, RefineReason.NO_CONFIG)
        }

        return withContext(Dispatchers.Default) {
            // withTimeoutOrNull 超时返回 null；调用抛异常则走 runCatching 分支喵。
            // 两者要区分开：超时说明模型太慢，异常说明配置/接口有问题喵，
            // 用户在设置页看到的提示完全不同喵。
            val invoked = runCatching {
                withTimeoutOrNull(config.timeoutMs) { client.complete(request) }
            }

            val failure = invoked.exceptionOrNull()
            if (failure != null) {
                // 取消是调用方的决定，必须继续往外传，不能当成"润色失败"吞掉喵。
                if (failure is CancellationException) throw failure
                return@withContext RefineOutcome(rawPrompt, RefineSource.NATIVE, RefineReason.ERROR)
            }

            val polished = invoked.getOrNull()
            when {
                polished == null ->
                    RefineOutcome(rawPrompt, RefineSource.NATIVE, RefineReason.TIMEOUT)

                polished.isBlank() ->
                    RefineOutcome(rawPrompt, RefineSource.NATIVE, RefineReason.EMPTY)

                polished.trim().length < MIN_REFINED_LENGTH ->
                    RefineOutcome(rawPrompt, RefineSource.NATIVE, RefineReason.TOO_SHORT)

                else ->
                    RefineOutcome(polished.trim(), RefineSource.MODEL, RefineReason.OK)
            }
        }
    }
}
