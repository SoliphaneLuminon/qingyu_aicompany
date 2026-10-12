package com.qingyu.app.data.archive

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.qingyu.app.core.SafeRead
import com.qingyu.app.core.TimeUtil
import com.qingyu.app.data.ChatMessage
import com.qingyu.app.data.Favorite
import com.qingyu.app.data.Persona
import com.qingyu.app.data.db.QingYuDatabase

/**
 * 聊天记录的导入导出喵。
 *
 * 解析在 [ChatImportParser] 里（纯函数，有单元测试），这里只负责落库与落盘喵。
 * 导入端刻意做得宽容：早期导出格式的 `聊天记录_<角色>_<时间>.json` 就是一个裸数组喵，
 * 我们直接读；同时也能读带 `schema` 包装的自家格式喵。
 */
class ChatArchive(private val context: Context, private val db: QingYuDatabase) {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    // ---------------------------------------------------------------- 导出

    suspend fun exportConversation(conversationId: Long): Uri? {
        val conversation = db.conversations.get(conversationId) ?: return null
        val persona = db.personas.get(conversation.personaId)
        val messages = db.messages.list(conversationId)

        val root = JsonObject().apply {
            addProperty("schema", SCHEMA)
            addProperty("exportedAt", TimeUtil.now())
            addProperty("app", "轻语")
            add("conversation", JsonObject().apply {
                addProperty("title", conversation.title)
                addProperty("createdAt", conversation.createdAt)
                addProperty("updatedAt", conversation.updatedAt)
            })
            persona?.let { add("persona", personaToJson(it)) }
            add("messages", messagesToJson(messages))
        }

        val safeName = sanitizeFileName(persona?.name ?: "对话")
        val fileName = "聊天记录_${safeName}_${TimeUtil.fileStamp()}.json"
        return writeToDownloads(fileName, gson.toJson(root))
    }

    /** 导出全部收藏空间条目喵。 */
    suspend fun exportFavorites(): Uri? {
        val favorites = db.favorites.list()
        if (favorites.isEmpty()) return null
        val root = JsonObject().apply {
            addProperty("schema", SCHEMA)
            addProperty("exportedAt", TimeUtil.now())
            addProperty("app", "轻语")
            add("favorites", gson.toJsonTree(favorites.map { it.toMap() }))
        }
        val fileName = "收藏空间_${TimeUtil.fileStamp()}.json"
        return writeToDownloads(fileName, gson.toJson(root))
    }

    /** 导出任意纯文本（收藏条目、单条消息等喵）。 */
    fun exportPlainText(fileName: String, content: String): Uri? {
        val base = fileName.substringBeforeLast('.', fileName)
        return writeToDownloads("${sanitizeFileName(base)}.md", content)
    }

    // ---------------------------------------------------------------- 导入

    /** 导入结果喵。 */
    data class ImportResult(
        val conversationId: Long,
        val personaId: Long,
        val personaName: String,
        val messageCount: Int,
    )

    /**
     * 从一个 JSON 文件导入喵。
     *
     * @param personaId 指定导入到哪个角色；为 0 时按文件里的角色名新建/复用角色喵。
     */
    suspend fun import(uri: Uri, personaId: Long = 0L): ImportResult {
        // 带上限读喵（见 [SafeRead]）：这是个别人可以随便塞给你的文件，
        // 一次性 readBytes 会让一个超大 JSON 直接把应用撑爆喵。
        val raw = context.contentResolver.openInputStream(uri)
            ?.use { SafeRead.readCapped(it).toString(Charsets.UTF_8) }
            ?: error("无法读取该文件")
        val displayName = queryDisplayName(uri) ?: uri.lastPathSegment
        return importFromJson(raw, displayName, personaId)
    }

    suspend fun importFromJson(raw: String, fileName: String? = null, personaId: Long = 0L): ImportResult {
        val parsed = ChatImportParser.parse(raw, fileName)

        val resolvedPersonaId = if (personaId > 0L) {
            personaId
        } else {
            findOrCreatePersona(
                name = parsed.personaName ?: "导入的角色",
                emoji = parsed.personaEmoji,
                tagline = parsed.personaTagline,
                systemPrompt = parsed.personaSystemPrompt,
            )
        }

        val persona = db.personas.get(resolvedPersonaId) ?: error("角色不存在")

        // 一个角色只有一段对话，所以导入进来的记录直接**并入**那段对话，
        // 而不是另开一个——另开的话会在库里留下界面永远看不到的孤儿对话喵。
        val conversation = db.conversations.latestOrCreate(resolvedPersonaId)
        val messages = parsed.messages.map { it.copy(conversationId = conversation.id) }
        val inserted = db.messages.insertAll(messages)

        db.conversations.refreshSummary(conversation.id)

        return ImportResult(
            conversationId = conversation.id,
            personaId = resolvedPersonaId,
            personaName = persona.name,
            messageCount = inserted,
        )
    }

    private suspend fun findOrCreatePersona(
        name: String,
        emoji: String?,
        tagline: String?,
        systemPrompt: String?,
    ): Long {
        val existing = db.personas.list().firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (existing != null) return existing.id
        val now = TimeUtil.now()
        val persona = Persona(
            name = name,
            avatarEmoji = emoji?.takeIf { it.isNotBlank() } ?: "🫧",
            tagline = tagline?.takeIf { it.isNotBlank() } ?: "由聊天记录导入",
            systemPrompt = systemPrompt?.takeIf { it.isNotBlank() }
                ?: "你是$name。你会用自然、口语化的方式与用户聊天，保持自己的性格，不要自称 AI。",
            greeting = "",
            createdAt = now,
            updatedAt = now,
        )
        return db.personas.insert(persona)
    }

    // ---------------------------------------------------------------- 工具

    private fun messagesToJson(messages: List<ChatMessage>) = gson.toJsonTree(
        messages.map { message ->
            linkedMapOf<String, Any?>(
                "content" to message.content,
                "role" to message.role.wire,
                "timestamp" to message.timestamp,
                "reasoningDuration" to message.reasoningDuration,
            ).also { map ->
                message.error?.let { map["error"] = it }
            }
        },
    )

    private fun personaToJson(persona: Persona) = JsonObject().apply {
        addProperty("name", persona.name)
        addProperty("avatarEmoji", persona.avatarEmoji)
        addProperty("tagline", persona.tagline)
        addProperty("systemPrompt", persona.systemPrompt)
        addProperty("greeting", persona.greeting)
    }

    private fun Favorite.toMap(): Map<String, Any?> = linkedMapOf(
        "kind" to kind.name,
        "title" to title,
        "content" to content,
        "detail" to detail,
        "personaName" to personaName,
        "conversationTitle" to conversationTitle,
        "tags" to tags,
        "createdAt" to createdAt,
    )

    /** 文件系统不接受的字符统一换成下划线，并限制长度喵。 */
    fun sanitizeFileName(raw: String): String =
        raw.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(48).ifBlank { "记录" }

    /**
     * 写入系统「下载」目录喵。
     *
     * Android 10 起用 MediaStore，不需要任何存储权限喵。
     */
    private fun writeToDownloads(fileName: String, content: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(
                MediaStore.MediaColumns.MIME_TYPE,
                if (fileName.endsWith(".md")) "text/markdown" else "application/json",
            )
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/轻语")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
        return uri
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    companion object {
        const val SCHEMA = "qingyu.chat.v1"
    }
}
