package com.qingyu.app.remote

import com.qingyu.app.data.Attachment
import com.qingyu.app.media.ImageStore

/**
 * 识图：把图片变成文字描述喵。
 *
 * 走的是 OpenAI 兼容的 `/v1/chat/completions`，只是 messages 里带图片喵。
 * 主模型自己能看图时根本不用这个类喵；只有主模型是纯文本时，
 * 才先让一个识图模型把图"读"成一段描述，再把描述当作用户消息的一部分发出去喵。
 */
class VisionClient(private val chatClient: ChatClient = ChatClient()) {

    class Failure(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

    /**
     * 描述一张图喵。
     *
     * @return 描述文本；模型没吐内容时抛出 [Failure]，由调用方决定是否降级喵。
     */
    suspend fun describe(
        endpoint: String,
        apiKey: String,
        model: String,
        imageDataUrl: String,
    ): String {
        if (endpoint.isBlank() || model.isBlank()) {
            throw Failure("还没有配置识图模型")
        }
        val collected = StringBuilder()
        chatClient.stream(
            endpoint = endpoint,
            apiKey = apiKey,
            model = model,
            turns = listOf(
                ChatTurn(role = "system", content = VisionPrompt.SYSTEM),
                ChatTurn(role = "user", content = VisionPrompt.USER, images = listOf(imageDataUrl)),
            ),
            temperature = 0.2,
            topP = 1.0,
            maxTokens = 512,
            stream = false,
        ).collect { delta ->
            if (delta is ChatDelta.Content) collected.append(delta.text)
        }
        val text = collected.toString().trim()
        if (text.isEmpty()) throw Failure("识图模型没有返回内容")
        return text
    }

    /**
     * 一次处理整组图片喵。
     *
     * @param onDescription 每识别完一张就回调，便于逐张更新界面喵。
     * @return 与 [attachments] 同序的描述；单张失败时该项为空串（不中断其余图片喵）。
     */
    suspend fun describeAll(
        attachments: List<Attachment>,
        endpoint: String,
        apiKey: String,
        model: String,
        imageStore: ImageStore,
        onDescription: suspend (Attachment, String) -> Unit = { _, _ -> },
    ): List<Pair<Attachment, String>> {
        val results = ArrayList<Pair<Attachment, String>>(attachments.size)
        for (attachment in attachments) {
            val description = try {
                val dataUrl = imageStore.toDataUrl(attachment)
                describe(endpoint, apiKey, model, dataUrl)
            } catch (t: Throwable) {
                ""
            }
            if (description.isNotEmpty()) onDescription(attachment, description)
            results.add(attachment to description)
        }
        return results
    }

    /**
     * 把图片描述拼进用户消息喵。
     *
     * 加成"（发来一张图片：…）"的形式，而不是另起一条消息喵：
     * 模型更容易把描述和用户那句话当成同一个人的同一次发言喵。
     */
    fun mergeIntoUserText(userText: String, descriptions: List<String>): String {
        val usable = descriptions.filter { it.isNotBlank() }
        if (usable.isEmpty()) return userText
        val blocks = usable.mapIndexed { index, description ->
            if (usable.size == 1) "（图片内容：$description）" else "（第 ${index + 1} 张图片：$description）"
        }
        return buildString {
            if (userText.isNotBlank()) {
                append(userText)
                append('\n')
            }
            append(blocks.joinToString("\n"))
        }.trim()
    }
}
