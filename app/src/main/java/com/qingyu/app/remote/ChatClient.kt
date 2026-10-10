package com.qingyu.app.remote

import com.qingyu.app.core.TimeUtil
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OpenAI 兼容的聊天客户端喵。
 *
 * 只用 HttpURLConnection：`/chat/completions` 的流式协议就是 SSE + POST喵，
 * 没必要为此引入 OkHttp + Retrofit + kotlinx.serialization 三件套喵。
 *
 * 职责边界：这里只负责传输（连接、鉴权、读行、断开喵），
 * 报文怎么解读全部交给 [ChatStreamParser]——那部分是有单元测试的纯函数喵。
 */
class ChatClient {

    class Failure(message: String, cause: Throwable? = null) : IOException(message, cause)

    /**
     * 发起一次对话请求喵。
     *
     * @param endpoint 完整地址，例如 https://api.deepseek.com/v1/chat/completions喵
     */
    fun stream(
        endpoint: String,
        apiKey: String,
        model: String,
        turns: List<ChatTurn>,
        temperature: Double,
        topP: Double,
        maxTokens: Int,
        stream: Boolean,
        extraBody: JSONObject? = null,
    ): Flow<ChatDelta> = callbackFlow {
        val started = TimeUtil.now()
        val cancelled = AtomicBoolean(false)
        val worker = Thread {
            var connection: HttpURLConnection? = null
            try {
                if (endpoint.isBlank()) throw Failure("还没有配置接口地址，请到「设置 → 接口」填写")
                if (model.isBlank()) throw Failure("还没有选择模型")
                if (turns.isEmpty()) throw Failure("没有可发送的内容")

                connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 20_000
                    readTimeout = 120_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", if (stream) "text/event-stream" else "application/json")
                    if (apiKey.isNotBlank()) {
                        setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
                    }
                }

                val payload = buildPayload(model, turns, temperature, topP, maxTokens, stream, extraBody)
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(payload.toString())
                    writer.flush()
                }

                val code = connection.responseCode
                if (code !in 200..299) {
                    val detail = runCatching {
                        connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    }.getOrDefault("")
                    throw Failure("接口返回 $code：${summarizeError(detail)}")
                }

                val contentType = connection.contentType.orEmpty()
                if (stream && contentType.contains("event-stream", ignoreCase = true)) {
                    readSse(connection, started, cancelled, this)
                } else {
                    readWholeBody(connection, started, this)
                }
            } catch (t: Throwable) {
                if (!cancelled.get()) {
                    close(t)
                }
            } finally {
                runCatching { connection?.disconnect() }
                close()
            }
        }.apply { isDaemon = true; name = "qingyu-chat"; start() }

        awaitClose {
            cancelled.set(true)
            worker.interrupt()
        }
    }

    /**
     * 拉取 `/v1/models` 下可用的模型 id 列表，供设置页在填好喵
     * 地址与 key 后自动列出可选模型。纯 GET，不需要走 SSE喵。
     *
     * @param endpoint 完整地址，例如 https://api.deepseek.com/v1/models喵
     */
    fun listModels(endpoint: String, apiKey: String): List<String> {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            if (apiKey.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            }
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = runCatching {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                }.getOrDefault("")
                throw Failure("接口返回 $code：${summarizeError(detail)}")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val data = runCatching { JSONObject(body).optJSONArray("data") }.getOrNull() ?: return emptyList()
            val out = ArrayList<String>(data.length())
            for (i in 0 until data.length()) {
                val id = data.optJSONObject(i)?.optString("id")?.trim().orEmpty()
                if (id.isNotEmpty()) out.add(id)
            }
            return out
        } finally {
            connection.disconnect()
        }
    }

    /** 非流式：一次性拿完整回复，然后补一个 Done喵。 */
    private fun readWholeBody(
        connection: HttpURLConnection,
        started: Long,
        producer: kotlinx.coroutines.channels.ProducerScope<ChatDelta>,
    ) {
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        ChatStreamParser.wholeBodyDeltas(body, started).forEach { producer.trySend(it) }
    }

    /** 流式：逐行解析 SSE，边到边发喵。 */
    private fun readSse(
        connection: HttpURLConnection,
        started: Long,
        cancelled: AtomicBoolean,
        producer: kotlinx.coroutines.channels.ProducerScope<ChatDelta>,
    ) {
        val reader: BufferedReader = InputStreamReader(connection.inputStream, Charsets.UTF_8).buffered()
        var finishReason = ""
        reader.useLines { lines ->
            for (line in lines) {
                if (Thread.currentThread().isInterrupted || cancelled.get()) break
                val trimmed = line.trim()
                if (trimmed.isEmpty() || !trimmed.startsWith("data:")) continue
                val data = trimmed.removePrefix("data:").trim()
                if (data == "[DONE]") break

                ChatStreamParser.parseFinishReason(data)?.let { finishReason = it }
                ChatStreamParser.parseChunk(data)?.let { producer.trySend(it) }
            }
        }
        producer.trySend(ChatDelta.Done(finishReason.ifBlank { "stop" }, TimeUtil.now() - started))
    }

    private fun buildPayload(
        model: String,
        turns: List<ChatTurn>,
        temperature: Double,
        topP: Double,
        maxTokens: Int,
        stream: Boolean,
        extraBody: JSONObject?,
    ): JSONObject = ChatPayload.build(model, turns, temperature, topP, maxTokens, stream, extraBody)

    /** 从错误响应里挖出人类可读的一句话喵。 */
    private fun summarizeError(body: String): String {
        if (body.isBlank()) return "无响应内容"
        val json = runCatching { JSONObject(body) }.getOrNull()
        val message = json?.optJSONObject("error")?.optString("message").orEmpty()
        if (message.isNotBlank() && !message.equals("null", ignoreCase = true)) return message.take(300)
        val plain = json?.optString("message").orEmpty()
        if (plain.isNotBlank() && !plain.equals("null", ignoreCase = true)) return plain.take(300)
        return body.replace(Regex("\\s+"), " ").take(300)
    }
}
