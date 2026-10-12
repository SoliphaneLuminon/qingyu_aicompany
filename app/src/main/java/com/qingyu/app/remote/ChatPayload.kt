package com.qingyu.app.remote

import com.qingyu.app.core.textOrEmpty
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条待发送给模型的消息喵。
 *
 * [images] 里放 data URL（`data:image/jpeg;base64,…`）。为空时按普通文本发送喵；
 * 非空时该条消息的 `content` 会变成 OpenAI 的多模态数组格式喵：
 *
 * ```json
 * {"role":"user","content":[
 *   {"type":"text","text":"看这个"},喵
 *   {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,…"}}
 * ]}
 * ```
 */
data class ChatTurn(
    val role: String,
    val content: String,
    val images: List<String> = emptyList(),
) {
    val hasImages: Boolean get() = images.isNotEmpty()
}

/**
 * 请求体构造：纯函数，有单元测试喵。
 *
 * 单独抽出来是因为"文本消息"和"多模态消息"是两种完全不同的字段形状喵，
 * 写错了不会报错，只会让模型看不见图——这种 bug 只能靠测试发现喵。
 */
object ChatPayload {

    fun build(
        model: String,
        turns: List<ChatTurn>,
        temperature: Double,
        topP: Double,
        maxTokens: Int,
        stream: Boolean,
        extraBody: JSONObject? = null,
    ): JSONObject {
        val messages = JSONArray()
        turns.forEach { turn -> messages.put(message(turn)) }
        return JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("stream", stream)
            put("temperature", temperature)
            put("top_p", topP)
            put("max_tokens", maxTokens)
            extraBody?.keys()?.forEach { key -> put(key, extraBody.get(key)) }
        }
    }

    /** 单条消息：没有图片就是纯文本，有图片就是多模态数组喵。 */
    fun message(turn: ChatTurn): JSONObject {
        if (!turn.hasImages) {
            return JSONObject().put("role", turn.role).put("content", turn.content)
        }
        val parts = JSONArray()
        if (turn.content.isNotBlank()) {
            parts.put(JSONObject().put("type", "text").put("text", turn.content))
        }
        turn.images.forEach { dataUrl ->
            parts.put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", dataUrl)),
            )
        }
        return JSONObject().put("role", turn.role).put("content", parts)
    }
}

/** 识图：把图片转成文字描述喵。 */
object VisionPrompt {

    /** 让识图模型输出"信息密度高、可用于对话"的描述，而不是泛泛而谈喵。 */
    val SYSTEM = """
        你是一个识图助手。用户会给你一张图片，请客观、具体地描述它，供另一个人格化的聊天角色参考。
        要求：
        1. 先说这是什么类型的内容（自拍/截图/风景/文档/商品/表情包/聊天记录…）。
        2. 再说画面里确定存在的信息：人物（外貌、表情、动作、穿着）、文字（原样抄出来）、场景、物体、界面元素。
        3. 如果像聊天截图或文档，把可读的文字按顺序摘出来。
        4. 只写你确实看到的，不要猜测动机或编造细节；看不清就说不清楚。
        5. 用中文，200 字以内，不要用列表编号，直接写成一段话。
    """.trimIndent()

    const val USER = "请描述这张图片。"
}
