package com.qingyu.app.data.archive

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.qingyu.app.core.TimeUtil
import com.qingyu.app.data.ChatMessage
import com.qingyu.app.data.MessageRole
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 聊天记录导入的解析层：纯函数，不碰数据库与文件系统喵。
 *
 * 抽出来单独一层是有原因的——导入的输入来自"早期导出格式的文件"喵，
 * 字段名、时间格式、包装方式都不受我们控制，是全年最容易出错的一块喵；
 * 做成纯函数才能被单元测试直接覆盖（见 ChatImportParserTest喵）。
 */
object ChatImportParser {

    /** 一次导入解析出来的内容喵。 */
    data class Parsed(
        val messages: List<ChatMessage>,
        /** 角色名：优先取文件里的字段，其次从文件名推断喵。 */
        val personaName: String?,
        val personaEmoji: String?,
        val personaTagline: String?,
        val personaSystemPrompt: String?,
        /** 文件里带的会话标题喵。 */
        val title: String?,
    )

    /** 解析失败时抛出，消息直接展示给用户喵。 */
    class ParseException(message: String) : IllegalArgumentException(message)

    /**
     * 解析一份聊天记录 JSON喵。
     *
     * 支持两种形态喵：
     * 1. 裸数组（早期导出格式 `聊天记录_苏婉_2026-10-06_15-54.json` 就是这种喵）：
     *    `[{"content":"在吗","role":"user","timestamp":1791261040298}]`喵
     * 2. 带包装的对象：`{"messages":[...],"persona":{...},"conversation":{"title":"..."}}`喵，
     *    字段名兼容 messages / chatHistory / history喵。
     *
     * @param fileName 上传时的文件名，用于在文件里没有角色名时反推（`聊天记录_苏婉_….json`喵）。
     */
    fun parse(raw: String, fileName: String? = null): Parsed {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) throw ParseException("文件是空的")

        val element = runCatching { JsonParser.parseString(trimmed) }
            .getOrElse { throw ParseException("不是合法的 JSON：${it.message}") }

        val rawMessages: List<JsonObject>
        var personaFromFile: JsonObject? = null
        var titleFromFile: String? = null

        when {
            element.isJsonArray -> {
                rawMessages = element.asJsonArray.mapNotNull { it.asObjectOrNull() }
            }

            element.isJsonObject -> {
                val obj = element.asJsonObject
                val array = obj.getAsJsonArray("messages")
                    ?: obj.getAsJsonArray("chatHistory")
                    ?: obj.getAsJsonArray("history")
                    ?: obj.getAsJsonArray("data")
                rawMessages = array?.mapNotNull { it.asObjectOrNull() }.orEmpty()
                personaFromFile = obj.getAsJsonObject("persona") ?: obj.getAsJsonObject("character")
                titleFromFile = obj.getAsJsonObject("conversation")?.get("title").asStringOrNull()
                    ?: obj.get("title").asStringOrNull()
            }

            else -> throw ParseException("无法识别的文件格式")
        }

        val messages = rawMessages.mapNotNull { toMessage(it) }
        if (messages.isEmpty()) throw ParseException("文件里没有可导入的聊天消息")

        val nameFromFile = personaFromFile?.get("name").asStringOrNull()?.takeIf { it.isNotBlank() }
        return Parsed(
            messages = messages,
            personaName = nameFromFile ?: personaNameFromFileName(fileName),
            personaEmoji = personaFromFile?.get("avatarEmoji").asStringOrNull()
                ?: personaFromFile?.get("emoji").asStringOrNull(),
            personaTagline = personaFromFile?.get("tagline").asStringOrNull(),
            personaSystemPrompt = personaFromFile?.get("systemPrompt").asStringOrNull(),
            title = titleFromFile,
        )
    }

    /**
     * `聊天记录_苏婉_2026-10-06_15-54.json` → `苏婉`喵。
     *
     * 文件名的中文可能以百分号转义形式出现，所以先尝试解码喵。
     */
    fun personaNameFromFileName(fileName: String?): String? {
        if (fileName.isNullOrBlank()) return null
        val decoded = runCatching { URLDecoder.decode(fileName, "UTF-8") }.getOrDefault(fileName)
        val base = decoded.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
        val parts = base.split('_')
        if (parts.size >= 3 && parts.first().trim() == "聊天记录") {
            return parts[1].trim().takeIf { it.isNotEmpty() }
        }
        return null
    }

    /** 字段名做了多重兼容：早期导出格式、自家 schema、常见第三方导出喵。 */
    private fun toMessage(json: JsonObject): ChatMessage? {
        val content = json.stringOf("content", "text", "message", "msg") ?: return null
        if (content.isBlank()) return null
        val role = MessageRole.fromWire(json.stringOf("role", "sender", "type", "from"))
        val timestamp = json.longOf("timestamp", "time", "createdAt", "ts") ?: TimeUtil.now()
        val reasoningDuration = json.longOf("reasoningDuration", "reasoning_duration", "thinkingMs") ?: 0L
        return ChatMessage(
            // 会话 id 由调用方在落库时补齐（这里统一留 0喵）。
            conversationId = 0L,
            role = role,
            content = content,
            timestamp = timestamp,
            reasoningDuration = reasoningDuration,
            completed = true,
            error = json.stringOf("error"),
        )
    }

    private fun JsonElement?.asObjectOrNull(): JsonObject? =
        if (this != null && isJsonObject) asJsonObject else null

    private fun JsonElement?.asStringOrNull(): String? {
        if (this == null || isJsonNull) return null
        if (isJsonPrimitive) {
            val primitive = asJsonPrimitive
            if (primitive.isString || primitive.isNumber || primitive.isBoolean) {
                val text = primitive.asString
                // 别的应用把字段导出成 null（或字符串 "null"）时喵，
                // 不能让它变成一条内容为 "null" 的消息喵。
                if (text.equals("null", ignoreCase = true)) return null
                return text
            }
        }
        return null
    }

    private fun JsonObject.stringOf(vararg keys: String): String? {
        keys.forEach { key ->
            val value = get(key).asStringOrNull()
            if (value != null) return value
        }
        return null
    }

    private fun JsonObject.longOf(vararg keys: String): Long? {
        keys.forEach { key ->
            val element = get(key) ?: return@forEach
            if (element.isJsonNull || !element.isJsonPrimitive) return@forEach
            val primitive = element.asJsonPrimitive
            if (primitive.isNumber) return primitive.asLong
            val text = primitive.asString.trim()
            text.toLongOrNull()?.let { return it }
            parseDate(text)?.let { return it }
        }
        return null
    }

    private val dateFormats = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy/MM/dd HH:mm:ss",
    )

    private fun parseDate(text: String): Long? {
        dateFormats.forEach { pattern ->
            runCatching {
                return SimpleDateFormat(pattern, Locale.getDefault()).parse(text)?.time
            }
        }
        return null
    }
}
