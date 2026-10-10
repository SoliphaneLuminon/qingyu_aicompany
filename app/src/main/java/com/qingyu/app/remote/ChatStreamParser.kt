package com.qingyu.app.remote

import com.qingyu.app.core.TimeUtil
import org.json.JSONObject

/** 流式增量：正文与思考分开推送，前端可以分别渲染喵。 */
sealed interface ChatDelta {
    data class Content(val text: String) : ChatDelta
    data class Reasoning(val text: String) : ChatDelta

    /** 本轮结束，[finishReason] 常见值为 stop / length喵。 */
    data class Done(val finishReason: String, val elapsedMs: Long) : ChatDelta
}

/**
 * OpenAI 兼容响应的解析层：纯函数，不碰网络与协程喵。
 *
 * 抽出来单独一层，关键在**`org.json` 的 `optString()` 在字段存在且值为喵
 * JSON `null` 时，会返回字符串 `"null"` 而不是空串**。思考型模型在思考阶段的 chunk 长这样喵：
 *
 * ```json
 * {"choices":[{"delta":{"content":null,"reasoning_content":"他"}}喵]}
 * ```
 *
 * 于是每一段思考都会额外追加一次字面量 `null`，聊天里出现满屏的 "nullnullnull…"喵。
 * 这类协议边界必须用测试钉住，所以解析逻辑全部收在这里喵（见 ChatStreamParserTest）。
 */
object ChatStreamParser {

    /** 从 `data:` 后面的 JSON 文本解析出一个 chunk。无法识别时返回 null喵。 */
    fun parseChunk(json: String): ChatDelta? {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null

        obj.optJSONObject("error")?.let { error ->
            throw ChatClient.Failure(error.optStringText("message").ifBlank { "接口返回错误" })
        }

        val choice = obj.optJSONArray("choices")?.optJSONObject(0) ?: return null
        val delta = choice.optJSONObject("delta") ?: return null

        // 先看正文；正文非空就直接返回（常见于纯回答阶段喵）。
        val content = delta.optStringText("content")
        if (content.isNotEmpty()) return ChatDelta.Content(content)

        val reasoning = delta.optStringText("reasoning_content").ifEmpty { delta.optStringText("reasoning") }
        if (reasoning.isNotEmpty()) return ChatDelta.Reasoning(reasoning)

        return null
    }

    /** 读取结束原因；`"null"` 是 JSON null 被 optString 字面化的产物，必须当成没有喵。 */
    fun parseFinishReason(json: String): String? {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val reason = obj.optJSONArray("choices")?.optJSONObject(0)?.optStringText("finish_reason").orEmpty()
        return reason.ifEmpty { null }
    }

    /**
     * 解析非流式的完整响应体喵。
     *
     * @return 思考文本与正文文本，任一可能为空串喵。
     */
    fun parseWholeBody(body: String): Pair<String, String> {
        val obj = runCatching { JSONObject(body) }.getOrNull() ?: return "" to ""
        val message = obj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: return "" to ""
        val reasoning = message.optStringText("reasoning_content").ifEmpty { message.optStringText("reasoning") }
        return reasoning to message.optStringText("content")
    }

    /** 从完整响应体里取 finish_reason喵。 */
    fun wholeBodyFinishReason(body: String): String? {
        val obj = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val reason = obj.optJSONArray("choices")?.optJSONObject(0)?.optStringText("finish_reason").orEmpty()
        return reason.ifEmpty { null }
    }

    /** 把整个非流式响应拼成一组 delta喵。 */
    fun wholeBodyDeltas(body: String, started: Long): List<ChatDelta> {
        val (reasoning, content) = parseWholeBody(body)
        val deltas = ArrayList<ChatDelta>(3)
        if (reasoning.isNotEmpty()) deltas.add(ChatDelta.Reasoning(reasoning))
        if (content.isNotEmpty()) deltas.add(ChatDelta.Content(content))
        deltas.add(ChatDelta.Done(wholeBodyFinishReason(body) ?: "stop", TimeUtil.now() - started))
        return deltas
    }

    /**
     * 取值：把 JSON null、缺字段、以及被 `optString` 字面化的 `"null"` 一律视为"没有内容"喵。
     *
     * 这是整个文件的核心——所有取字符串的地方都必须走这里喵。
     */
    private fun JSONObject.optStringText(key: String): String {
        if (!has(key) || isNull(key)) return ""
        val value = optString(key, "")
        // 上游把 null 写成了字符串（"null" / "NULL"）时同样视为没有内容喵。
        return if (value.equals("null", ignoreCase = true)) "" else value
    }
}
