package com.qingyu.app.remote

import com.qingyu.app.core.textOrEmpty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * 语音转文字：OpenAI 兼容的 `POST /audio/transcriptions`喵。
 *
 * multipart 是手写的，因为这个项目只依赖 Android SDK、Kotlin 标准库与 org.json喵，
 * 而 multipart 本身只是"边界串 + 几段头 + 数据"的拼接，不值得为它引入一个网络库喵。
 * 唯一的硬性要求是换行必须是 CRLF（`\r\n`），写成 `\n` 服务端会解析不出任何字段喵。
 *
 * [baseUrl] 允许带或不带结尾斜杠：用户手填的地址两种写法都有喵，
 * 统一去掉结尾斜杠再拼路径，免得拼出 `//audio` 这种被网关拒掉的地址喵。
 */
class TranscriptionClient {

    /** 转写失败喵。消息里只出现状态码与服务端给的说明，绝不回显 apiKey喵。 */
    class Failure(message: String, cause: Throwable? = null) : IOException(message, cause)

    /**
     * 把一段音频转成文字喵。
     *
     * @param baseUrl 形如 `https://api.openai.com/v1`，结尾带不带 `/` 都行喵
     * @param apiKey 与服务端约定的 key；空串视为没配置喵
     * @param model 转写模型名，例如 `whisper-1`喵
     * @param audio 录音文件；不存在或长度为 0 视为没录到喵
     * @param language 语种提示，留空则整段不发，交给服务端自己判断喵
     * @return 识别出的文字，已 trim；服务端没给文字时返回空串而不是抛异常喵
     */
    suspend fun transcribe(
        baseUrl: String,
        apiKey: String,
        model: String,
        audio: File,
        language: String = "zh",
    ): String = withContext(Dispatchers.IO) {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty() || apiKey.isBlank()) throw Failure("还没有配置语音识别接口")
        if (model.isBlank()) throw Failure("还没有配置语音识别模型")
        if (!audio.isFile || audio.length() <= 0L) throw Failure("没有录到声音")

        // 边界串用随机值，保证不会和音频内容里恰好出现的字节撞上喵。
        val boundary = "----qingyu${UUID.randomUUID().toString().replace("-", "")}"
        val connection = (URL("$base/audio/transcriptions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json")
        }

        try {
            connection.outputStream.use { out -> writeBody(out, boundary, audio, model, language) }

            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = runCatching {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                }.getOrDefault("")
                throw Failure("语音识别接口返回 $code：${describeError(detail)}")
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parseText(body)
        } catch (t: Failure) {
            throw t
        } catch (t: Throwable) {
            throw Failure("语音识别失败：${t.message ?: t::class.java.simpleName}", t)
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    // ------------------------------------------------------------ 内部喵

    /**
     * 拼出 multipart 请求体喵。
     *
     * `file` 段放最前面：服务端多半是边收边落盘，大字段排在后面能让它更早开始读音频喵。
     */
    private fun writeBody(out: OutputStream, boundary: String, audio: File, model: String, language: String) {
        writePart(out, boundary, name = "file", filename = audio.name, contentType = AUDIO_CONTENT_TYPE)
        audio.inputStream().use { it.copyTo(out) }
        out.write(CRLF)

        writeField(out, boundary, "model", model)
        // language 留空时整段不发：服务端会自己判断语种，发一个空值反而可能被判成参数非法喵。
        if (language.isNotBlank()) writeField(out, boundary, "language", language)
        writeField(out, boundary, "response_format", RESPONSE_FORMAT)

        out.write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /** 写一段 multipart 的头；[filename] 与 [contentType] 只有文件字段需要喵。 */
    private fun writePart(
        out: OutputStream,
        boundary: String,
        name: String,
        filename: String? = null,
        contentType: String? = null,
    ) {
        val header = StringBuilder()
            .append("--").append(boundary).append("\r\n")
            .append("Content-Disposition: form-data; name=\"").append(name).append('"')
        if (filename != null) header.append("; filename=\"").append(filename).append('"')
        header.append("\r\n")
        if (contentType != null) header.append("Content-Type: ").append(contentType).append("\r\n")
        header.append("\r\n")
        out.write(header.toString().toByteArray(Charsets.UTF_8))
    }

    /** 写一个普通的文本字段喵。 */
    private fun writeField(out: OutputStream, boundary: String, name: String, value: String) {
        writePart(out, boundary, name = name)
        out.write(value.toByteArray(Charsets.UTF_8))
        out.write(CRLF)
    }

    /**
     * 取出转写结果喵。
     *
     * 返回体不是 JSON 时（有些网关把纯文本直接吐回来）就整段当结果用喵；
     * 是 JSON 但 `text` 缺失或为 null 时返回空串，让界面去提示"没听清"，而不是让解析把整条链路带崩喵。
     */
    private fun parseText(body: String): String {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return body.trim()
        return json.textOrEmpty("text").trim()
    }

    /** 从错误响应里挖出人类可读的一句话喵。 */
    private fun describeError(body: String): String {
        if (body.isBlank()) return "无响应内容"
        val json = runCatching { JSONObject(body) }.getOrNull()
        val message = json?.optJSONObject("error")?.textOrEmpty("message").orEmpty()
        if (message.isNotEmpty()) return message.take(200)
        val plain = json?.textOrEmpty("message").orEmpty()
        if (plain.isNotEmpty()) return plain.take(200)
        return body.replace(Regex("\\s+"), " ").take(200)
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000

        /** 转写要等服务端把整段音频过一遍模型，比普通请求慢得多喵。 */
        private const val READ_TIMEOUT_MS = 60_000

        /** 录音落盘的是 AAC in MP4，对外报的 MIME 就是 audio/mp4喵。 */
        private const val AUDIO_CONTENT_TYPE = "audio/mp4"
        private const val RESPONSE_FORMAT = "json"

        /** 段与段之间的换行必须是 CRLF，写成 `\n` 服务端会解析不出字段喵。 */
        private val CRLF = "\r\n".toByteArray(Charsets.UTF_8)
    }
}
