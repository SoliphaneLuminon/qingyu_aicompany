package com.qingyu.app.remote

import com.qingyu.app.core.memory.UtilityLlm
import com.qingyu.app.core.memory.UtilityRequest
import kotlinx.coroutines.flow.collect
import org.json.JSONObject

/**
 * 小模型任务的执行器 —— 复用主对话那条 OpenAI 兼容通道，只是参数不同喵。
 *
 * 不新开 HTTP 客户端：地址形态、鉴权头、SSE 解析、错误处理都已经在喵
 * [ChatClient] 里收口过了，为了"润色一下提示词"再维护第二套传输是亏的喵。
 */
class UtilityLlmClient(private val client: ChatClient) : UtilityLlm {

    override suspend fun complete(request: UtilityRequest): String {
        val extra = if (request.jsonMode) {
            JSONObject().put("response_format", JSONObject().put("type", "json_object"))
        } else {
            null
        }

        val reply = StringBuilder()
        client.stream(
            endpoint = request.endpoint,
            apiKey = request.apiKey,
            model = request.model,
            turns = listOf(
                ChatTurn("system", request.systemInstruction),
                ChatTurn("user", request.userContent),
            ),
            temperature = request.temperature,
            topP = 1.0,
            maxTokens = request.maxTokens,
            stream = false,
            extraBody = extra,
        ).collect { delta ->
            if (delta is ChatDelta.Content) reply.append(delta.text)
        }
        return reply.toString()
    }
}
