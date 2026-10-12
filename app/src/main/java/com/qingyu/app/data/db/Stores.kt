package com.qingyu.app.data.db

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.qingyu.app.core.Result
import com.qingyu.app.core.TimeUtil
import com.qingyu.app.core.runCatchingResult
import com.qingyu.app.core.memory.MemoryDecay
import com.qingyu.app.core.memory.MemoryTermRow
import com.qingyu.app.core.memory.MemoryTextIndex
import com.qingyu.app.data.Conversation
import com.qingyu.app.data.Favorite
import com.qingyu.app.data.FavoriteKind
import com.qingyu.app.data.MemoryCategory
import com.qingyu.app.data.MemoryItem
import com.qingyu.app.data.MessageKind
import com.qingyu.app.data.MessageRole
import com.qingyu.app.data.Persona
import com.qingyu.app.data.Sticker
import com.qingyu.app.data.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 存储层：手写 SQLite DAO喵。
 *
 * 一个文件装下六个 Store 加一个门面，是因为它们共享同一套样板喵：
 * Cursor 取值扩展、事务、以及**记忆主表与倒排索引必须同步维护**的约束喵。
 * 拆成六个文件只会让这些共享约定散开喵。
 *
 * 结构喵：
 * - 顶部工具：[Cursor] 的取值扩展（`str` / `long` / `int` / `bool` / `dbl`喵）、
 *   索引维护（`deleteMemoryIndexFor` / `rebuildMemoryIndex`）、`useRows`喵
 * - `PersonaStore` / `ConversationStore` / `MessageStore` / `AttachmentStore` /
 *   `FavoriteStore`：常规 CRUD喵
 * - `MemoryStore`：最复杂的一个，除了 CRUD 还负责遗忘曲线落库喵
 *   （`applyDecay` / `markRecalled`）与剪枝残影（`pruneMemory` / `pruneCandidate` /喵
 *   `enhancePrune`），并保证索引与主表在同一事务里更新喵
 * - `QingYuDatabase`：门面，把六个 Store 组装起来供上层注入，并提供 `guarded` 统一兜异常喵
 *
 * 约定喵：
 * 1. **写记忆必须走 MemoryStore**，不要在外面直接写表——否则倒排索引会失配喵，
 *    症状是"记忆在但检索不到"，不报错喵。
 * 2. 删除记忆前先清索引（见 `deleteMemoryIndexFor`），残留的孤儿索引会把已删记忆喵
 *    重新召回到结果里喵。
 * 3. 所有 `toXxx()` 反序列化都要容忍缺列与 null：老库升级上来的行可能只有默认值喵。
 * 4. 查询在 IO 线程执行（`withContext(Dispatchers.IO)`），调用方无需再包一层喵。
 */

internal inline fun <T> Cursor.useRows(map: (Cursor) -> T): List<T> {
    val out = ArrayList<T>(minOf(count, 256))
    use { c -> while (c.moveToNext()) out.add(map(c)) }
    return out
}

internal fun Cursor.str(name: String): String = getString(getColumnIndexOrThrow(name)) ?: ""
internal fun Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))
internal fun Cursor.int(name: String): Int = getInt(getColumnIndexOrThrow(name))
internal fun Cursor.bool(name: String): Boolean = getInt(getColumnIndexOrThrow(name)) != 0
internal fun Cursor.dblOrNull(name: String): Double? {
    val index = getColumnIndexOrThrow(name)
    return if (isNull(index)) null else getDouble(index)
}

internal fun Cursor.dbl(name: String): Double = getDouble(getColumnIndexOrThrow(name))

/**
 * 删除记忆主表记录之前，先清掉它在倒排索引里的行喵。
 *
 * 索引行依赖主表存在，必须在同一个事务里跟着一起走喵，
 * 否则会残留孤儿索引，让"已删除的记忆"还能被召回到查询结果里喵。
 */
internal fun deleteMemoryIndexFor(
    db: SQLiteDatabase,
    where: String,
    args: Array<String>,
) {
    val ids = db.rawQuery("SELECT id FROM memories WHERE $where", args).useRows { it.long("id") }
    ids.forEach { id -> db.delete("memory_terms", "memory_id = ?", arrayOf(id.toString())) }
}

/**
 * 重建一条记忆的倒排索引喵。
 *
 * 策略是"整条删掉重写"，不做差分：一条记忆的词通常只有几十个喵，
 * 重建的开销可以忽略，而差分一旦漏掉旧词，就会出现"改过的说法依然能被旧关键词搜到"喵。
 */
internal fun rebuildMemoryIndex(db: SQLiteDatabase, memoryId: Long, text: String) {
    db.delete("memory_terms", "memory_id = ?", arrayOf(memoryId.toString()))
    // 走 MemoryTextIndex.document 而不是自己再切一遍词喵：
    // 写进库的权重必须和检索时算出来的完全一致喵。
    MemoryTextIndex.document(memoryId, text).terms.forEach { (term, weight) ->
        db.insert(
            "memory_terms",
            null,
            ContentValues().apply {
                put("memory_id", memoryId)
                put("term", term)
                put("weight", weight)
            },
        )
    }
}

/** 角色卡存储喵。 */
class PersonaStore(private val helper: DbHelper) {

    private fun Cursor.toPersona() = Persona(
        id = long("id"),
        name = str("name"),
        avatarEmoji = str("avatar_emoji"),
        avatarImage = str("avatar_image"),
        tagline = str("tagline"),
        systemPrompt = str("system_prompt"),
        scenario = str("scenario"),
        sampleDialogue = str("sample_dialogue"),
        greeting = str("greeting"),
        modelOverride = str("model_override"),
        temperatureOverride = dblOrNull("temperature_override"),
        ttsVoiceHint = str("tts_voice_hint"),
        builtIn = bool("built_in"),
        sortOrder = int("sort_order"),
        createdAt = long("created_at"),
        updatedAt = long("updated_at"),
    )

    private suspend fun <T> read(block: (android.database.sqlite.SQLiteDatabase) -> T): T =
        withContext(Dispatchers.IO) { block(helper.readableDatabase) }

    private suspend fun <T> write(block: (android.database.sqlite.SQLiteDatabase) -> T): T =
        withContext(Dispatchers.IO) { helper.inTransaction(block) }

    suspend fun list(): List<Persona> = read { db ->
        db.rawQuery("SELECT * FROM personas ORDER BY sort_order ASC, id ASC", null).useRows { it.toPersona() }
    }

    suspend fun get(id: Long): Persona? = read { db ->
        db.rawQuery("SELECT * FROM personas WHERE id = ?", arrayOf(id.toString())).useRows { it.toPersona() }
            .firstOrNull()
    }

    suspend fun count(): Int = read { db ->
        db.rawQuery("SELECT COUNT(*) FROM personas", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    suspend fun insert(persona: Persona): Long = write { db ->
        val now = TimeUtil.now()
        val values = persona.toValues().apply {
            put("created_at", if (persona.createdAt > 0) persona.createdAt else now)
            put("updated_at", now)
        }
        db.insert("personas", null, values)
    }

    suspend fun update(persona: Persona) = write { db ->
        val values = persona.toValues().apply { put("updated_at", TimeUtil.now()) }
        db.update("personas", values, "id = ?", arrayOf(persona.id.toString()))
    }

    suspend fun delete(id: Long) = write { db ->
        db.delete("personas", "id = ?", arrayOf(id.toString()))
        // 记忆走双库：索引必须先清，否则会留下指向已删主记录的孤儿行喵。
        deleteMemoryIndexFor(db, "persona_id = ?", arrayOf(id.toString()))
        db.delete("memories", "persona_id = ?", arrayOf(id.toString()))
        db.delete("conversations", "persona_id = ?", arrayOf(id.toString()))
        db.delete("memory_state", "persona_id = ?", arrayOf(id.toString()))
        Unit
    }

    private fun Persona.toValues() = ContentValues().apply {
        put("name", name)
        put("avatar_emoji", avatarEmoji)
        put("avatar_image", avatarImage)
        put("tagline", tagline)
        put("system_prompt", systemPrompt)
        put("scenario", scenario)
        put("sample_dialogue", sampleDialogue)
        put("greeting", greeting)
        put("model_override", modelOverride)
        if (temperatureOverride == null) putNull("temperature_override") else put("temperature_override", temperatureOverride)
        put("tts_voice_hint", ttsVoiceHint)
        put("built_in", if (builtIn) 1 else 0)
        put("sort_order", sortOrder)
    }
}

/** 会话存储喵。 */
class ConversationStore(private val helper: DbHelper) {

    private fun Cursor.toConversation() = Conversation(
        id = long("id"),
        personaId = long("persona_id"),
        title = str("title"),
        createdAt = long("created_at"),
        updatedAt = long("updated_at"),
        lastMessagePreview = str("last_preview"),
        messageCount = int("message_count"),
        pinned = bool("pinned"),
        hidden = bool("hidden"),
        // 防御式读喵：老库（或迁移到一半被打断的库）里可能还没有这一列喵。
        // 读会话是启动路径上必经的一步，这里直接抛异常就等于"一打开就崩"喵。
        unread = getColumnIndex("unread_count").takeIf { it >= 0 }?.let { getInt(it) } ?: 0,
    )

    /**
     * 给会话的未读数 +1，并返回加完之后的值喵。
     *
     * 写成 `unread_count = unread_count + 1` 而不是"读出来加一再写回去"喵：
     * 后者在两条回复几乎同时落地时会丢计数——两边都读到 0，都写回 1喵。
     */
    suspend fun addUnread(id: Long): Int = write { db ->
        db.execSQL(
            "UPDATE conversations SET unread_count = unread_count + 1 WHERE id = ?",
            arrayOf<Any>(id),
        )
        db.rawQuery("SELECT unread_count FROM conversations WHERE id = ?", arrayOf(id.toString()))
            .useRows { it.getInt(0) }
            .firstOrNull() ?: 0
    }

    /** 把会话的未读清零喵。用户进这段对话时调用喵。 */
    suspend fun clearUnread(id: Long) = write { db ->
        db.execSQL("UPDATE conversations SET unread_count = 0 WHERE id = ?", arrayOf<Any>(id))
        Unit
    }

    /**
     * 全部会话的未读之和喵。
     *
     * 刻意不走界面那份列表喵：主界面的列表是**过滤过的**（被移除的会话不在里面），
     * 而红点该反映"一共还有多少条没看"——包括那些被移除、但还没读的会话喵。
     */
    suspend fun totalUnread(): Int = read { db ->
        db.rawQuery("SELECT SUM(unread_count) FROM conversations", null).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getInt(0) else 0
        }
    }

    private suspend fun <T> read(block: (android.database.sqlite.SQLiteDatabase) -> T): T =
        withContext(Dispatchers.IO) { block(helper.readableDatabase) }

    private suspend fun <T> write(block: (android.database.sqlite.SQLiteDatabase) -> T): T =
        withContext(Dispatchers.IO) { helper.inTransaction(block) }

    /** 取该角色最近一次会话；没有就新建一个喵。 */
    suspend fun latestOrCreate(personaId: Long): Conversation = write { db ->
        val existing = db.rawQuery(
            "SELECT * FROM conversations WHERE persona_id = ? ORDER BY updated_at DESC LIMIT 1",
            arrayOf(personaId.toString()),
        ).useRows { it.toConversation() }.firstOrNull()
        if (existing != null) {
            existing
        } else {
            val now = TimeUtil.now()
            // 一个角色只有一段对话，所以标题直接用角色名——
            // 收藏、导出、搜索里显示的都会是这个标题，用"新的对话"没有信息量喵。
            val title = db.rawQuery(
                "SELECT name FROM personas WHERE id = ?",
                arrayOf(personaId.toString()),
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
            }.ifBlank { "对话" }
            val values = ContentValues().apply {
                put("persona_id", personaId)
                put("title", title)
                put("created_at", now)
                put("updated_at", now)
                put("last_preview", "")
                put("message_count", 0)
                put("pinned", 0)
                put("hidden", 0)
            }
            val id = db.insert("conversations", null, values)
            Conversation(id = id, personaId = personaId, title = title, createdAt = now, updatedAt = now)
        }
    }

    /**
     * 只读地取该角色唯一的那段对话，没有就返回 null喵。
     *
     * 与 [latestOrCreate] 的区别是**不建库**：角色列表只需要展示预览，
     * 为每个角色凭空建一个空对话既浪费又会让"有没有聊过"这个判断失真喵。
     *
     * 注意它**不过滤 hidden**：通讯录要能看到被移除的对话，
     * 否则从通讯录点进角色就会以为"没聊过"、白白丢掉那段历史喵。
     */
    suspend fun of(personaId: Long): Conversation? = read { db ->
        db.rawQuery(
            "SELECT * FROM conversations WHERE persona_id = ? ORDER BY updated_at DESC LIMIT 1",
            arrayOf(personaId.toString()),
        ).useRows { it.toConversation() }.firstOrNull()
    }

    /** 主界面用：排除已从主界面移除的对话喵。 */
    suspend fun listVisible(): List<Conversation> = read { db ->
        db.rawQuery(
            "SELECT * FROM conversations WHERE hidden = 0 ORDER BY pinned DESC, updated_at DESC",
            null,
        ).useRows { it.toConversation() }
    }

    /**
     * 把对话从主界面移除 / 放回来喵。
     *
     * 只改 `hidden` 一个字段，消息与角色一概不动——这是"移除"而非"删除"喵。
     */
    suspend fun setHidden(id: Long, hidden: Boolean) = write { db ->
        db.update(
            "conversations",
            ContentValues().apply { put("hidden", if (hidden) 1 else 0) },
            "id = ?",
            arrayOf(id.toString()),
        )
        Unit
    }

    /** 有了新动静就自动放回主界面：发消息、收到主动消息都走这里喵。 */
    suspend fun unhide(id: Long) = write { db ->
        db.update(
            "conversations",
            ContentValues().apply { put("hidden", 0) },
            "id = ? AND hidden = 1",
            arrayOf(id.toString()),
        )
        Unit
    }

    /**
     * 置顶 / 取消置顶喵。
     *
     * 只改 `pinned` 一列，**不动 `updated_at`**喵——置顶是个"摆放"动作，
     * 不是"聊天"动作；若顺手刷新了活动时间，取消置顶后它会假装自己刚聊过、
     * 窜到列表最前面喵。
     */
    suspend fun setPinned(id: Long, pinned: Boolean) = write { db ->
        db.update(
            "conversations",
            ContentValues().apply { put("pinned", if (pinned) 1 else 0) },
            "id = ?",
            arrayOf(id.toString()),
        )
        Unit
    }

    suspend fun get(id: Long): Conversation? = read { db ->
        db.rawQuery("SELECT * FROM conversations WHERE id = ?", arrayOf(id.toString()))
            .useRows { it.toConversation() }.firstOrNull()
    }

    /**
     * 全部对话喵。
     *
     * 一个角色只有一段对话之后，"一个角色的对话"与"这个角色的对话"已经没有区别喵；
     * [ConversationStore.of] 用于单个角色，这里是给首页一次性取全量用的喵。
     * 保留 `pinned` 排序只是为了让老库里手动置顶过的那个仍排在前面喵。
     */
    suspend fun list(): List<Conversation> = read { db ->
        db.rawQuery("SELECT * FROM conversations ORDER BY pinned DESC, updated_at DESC", null)
            .useRows { it.toConversation() }
    }

    suspend fun listForPersona(personaId: Long): List<Conversation> = read { db ->
        db.rawQuery(
            "SELECT * FROM conversations WHERE persona_id = ? ORDER BY pinned DESC, updated_at DESC",
            arrayOf(personaId.toString()),
        ).useRows { it.toConversation() }
    }

    /** 有活动时刷新摘要、计数与排序时间喵。 */
    suspend fun touch(id: Long, preview: String, messageCount: Int, updatedAt: Long = TimeUtil.now()) = write { db ->
        db.update(
            "conversations",
            ContentValues().apply {
                put("last_preview", preview.take(120))
                put("message_count", messageCount)
                put("updated_at", updatedAt)
            },
            "id = ?",
            arrayOf(id.toString()),
        )
        Unit
    }

    /** 依据库里的消息重算会话摘要：预览取最后一条非思考消息喵。 */
    suspend fun refreshSummary(conversationId: Long) = write { db ->
        val messages = db.rawQuery(
            "SELECT role, content, timestamp FROM messages WHERE conversation_id = ? ORDER BY timestamp ASC, id ASC",
            arrayOf(conversationId.toString()),
        ).useRows { cursor ->
            Triple(
                MessageRole.fromWire(cursor.getString(0)),
                cursor.getString(1) ?: "",
                cursor.getLong(2),
            )
        }
        val preview = messages.lastOrNull { it.first != MessageRole.REASONING }?.second
            ?.replace("\n", " ").orEmpty()
        val updatedAt = messages.lastOrNull()?.third ?: TimeUtil.now()
        db.update(
            "conversations",
            ContentValues().apply {
                put("last_preview", preview.take(120))
                put("message_count", messages.size)
                put("updated_at", updatedAt)
            },
            "id = ?",
            arrayOf(conversationId.toString()),
        )
        Unit
    }

    suspend fun delete(id: Long) = write { db ->
        db.delete("messages", "conversation_id = ?", arrayOf(id.toString()))
        db.delete("conversations", "id = ?", arrayOf(id.toString()))
        Unit
    }
}

/** 消息存储喵。 */
class MessageStore(private val helper: DbHelper) {

    private fun Cursor.toMessage() = ChatMessage(
        id = long("id"),
        conversationId = long("conversation_id"),
        role = MessageRole.fromWire(str("role")),
        content = str("content"),
        timestamp = long("timestamp"),
        reasoningDuration = long("reasoning_duration"),
        completed = bool("completed"),
        favorite = bool("favorite"),
        error = getString(getColumnIndexOrThrow("error")),
        searchResults = str("search_json").toSearchHits(),
        kind = MessageKind.fromWire(str("kind")),
        stickerId = long("sticker_id"),
        payload = str("payload"),
    )

    private suspend fun <T> read(block: (android.database.sqlite.SQLiteDatabase) -> T): T =
        withContext(Dispatchers.IO) { block(helper.readableDatabase) }

    private suspend fun <T> write(block: (android.database.sqlite.SQLiteDatabase) -> T): T =
        withContext(Dispatchers.IO) { helper.inTransaction(block) }

    private fun ChatMessage.toValues() = ContentValues().apply {
        put("conversation_id", conversationId)
        put("role", role.wire)
        put("content", content)
        put("timestamp", timestamp)
        put("reasoning_duration", reasoningDuration)
        put("completed", if (completed) 1 else 0)
        put("favorite", if (favorite) 1 else 0)
        if (error == null) putNull("error") else put("error", error)
        put("search_json", searchResults.toSearchJson())
        put("has_image", if (attachments.any { it.isImage }) 1 else 0)
        put("kind", kind.wire)
        put("sticker_id", stickerId)
        put("payload", payload)
    }

    suspend fun insert(message: ChatMessage): Long = write { db ->
        db.insert("messages", null, message.toValues())
    }

    /** 批量写入，用于导入聊天记录，整体一个事务喵。 */
    suspend fun insertAll(messages: List<ChatMessage>): Int = write { db ->
        var inserted = 0
        for (message in messages) {
            val id = db.insert("messages", null, message.copy(completed = true, favorite = false).toValues())
            if (id > 0) inserted++
        }
        inserted
    }

    suspend fun list(conversationId: Long): List<ChatMessage> = read { db ->
        db.rawQuery(
            "SELECT * FROM messages WHERE conversation_id = ? ORDER BY timestamp ASC, id ASC",
            arrayOf(conversationId.toString()),
        ).useRows { it.toMessage() }
    }

    suspend fun get(id: Long): ChatMessage? = read { db ->
        db.rawQuery("SELECT * FROM messages WHERE id = ?", arrayOf(id.toString()))
            .useRows { it.toMessage() }.firstOrNull()
    }

    /** 给一条助手消息挂上它用到的联网来源喵。 */
    suspend fun updateSearchResults(id: Long, hits: List<com.qingyu.app.data.SearchHit>) = write { db ->
        db.update(
            "messages",
            ContentValues().apply { put("search_json", hits.toSearchJson()) },
            "id = ?",
            arrayOf(id.toString()),
        )
        Unit
    }

    suspend fun setFavorite(id: Long, favorite: Boolean) = write { db ->
        db.update(
            "messages",
            ContentValues().apply { put("favorite", if (favorite) 1 else 0) },
            "id = ?",
            arrayOf(id.toString()),
        )
        Unit
    }

    suspend fun delete(id: Long) = write { db ->
        db.delete("messages", "id = ?", arrayOf(id.toString()))
        Unit
    }

    suspend fun deleteAfter(conversationId: Long, timestamp: Long) = write { db ->
        db.delete("messages", "conversation_id = ? AND timestamp >= ?", arrayOf(conversationId.toString(), timestamp.toString()))
        Unit
    }

    /**
     * 清空一段对话的所有消息喵。
     *
     * 附件与消息是分开存的，所以要把该会话的附件一起删掉，
     * 否则会在库里留下永远读不到的孤儿行喵。轮次状态也一并重置，
     * 不然清空后冷却与生物感知还记着"聊过多少轮"喵。
     */
    suspend fun deleteAll(conversationId: Long) = write { db ->
        val args = arrayOf(conversationId.toString())
        db.delete("messages", "conversation_id = ?", args)
        db.delete("attachments", "conversation_id = ?", args)
        db.delete("memory_state", "conversation_id = ?", args)
        Unit
    }

    /** 关键词搜索，返回匹配消息（用于搜索页喵）。 */
    suspend fun search(keyword: String, limit: Int = 200): List<ChatMessage> = read { db ->
        db.rawQuery(
            "SELECT * FROM messages WHERE content LIKE ? AND role != 'reasoning' ORDER BY timestamp DESC LIMIT ?",
            arrayOf("%$keyword%", limit.toString()),
        ).useRows { it.toMessage() }
    }

    suspend fun count(conversationId: Long): Int = read { db ->
        db.rawQuery("SELECT COUNT(*) FROM messages WHERE conversation_id = ?", arrayOf(conversationId.toString()))
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }
}

/**
 * 附件（目前只有图片）存储喵。
 *
 * 文件本体在应用私有目录，这里只管理数据库记录喵。
 */
class AttachmentStore(private val helper: DbHelper) {

    private fun Cursor.toAttachment() = com.qingyu.app.data.Attachment(
        id = long("id"),
        messageId = long("message_id"),
        conversationId = long("conversation_id"),
        kind = com.qingyu.app.data.AttachmentKind.fromName(str("kind")),
        localPath = str("local_path"),
        mimeType = str("mime_type"),
        width = int("width"),
        height = int("height"),
        sizeBytes = long("size_bytes"),
        description = str("description"),
        createdAt = long("created_at"),
    )

    private suspend fun <T> read(block: (android.database.sqlite.SQLiteDatabase) -> T): T =
        withContext(Dispatchers.IO) { block(helper.readableDatabase) }

    private suspend fun <T> write(block: (android.database.sqlite.SQLiteDatabase) -> T): T =
        withContext(Dispatchers.IO) { helper.inTransaction(block) }

    private fun com.qingyu.app.data.Attachment.toValues() = ContentValues().apply {
        put("message_id", messageId)
        put("conversation_id", conversationId)
        put("kind", kind.name)
        put("local_path", localPath)
        put("mime_type", mimeType)
        put("width", width)
        put("height", height)
        put("size_bytes", sizeBytes)
        put("description", description)
        put("created_at", if (createdAt > 0) createdAt else TimeUtil.now())
    }

    suspend fun insert(attachment: com.qingyu.app.data.Attachment): Long = write { db ->
        db.insert("attachments", null, attachment.toValues())
    }

    /**
     * 一条消息落库后，把附件挂到它上面喵。
     *
     * [conversationId] 非 0 时会**补上**附件缺失的会话归属喵：
     * 图片导入得比"这段对话是谁"更早，那时它的 conversationId 还是 0，
     * 而 [listForConversation] 是按 conversation_id 查的——落成 0 就再也查不回来，
     * 表现为"发出去的图，消息一刷新就没了"喵。
     */
    suspend fun attachToMessage(
        messageId: Long,
        attachments: List<com.qingyu.app.data.Attachment>,
        conversationId: Long = 0L,
    ) = write { db ->
        attachments.forEach { attachment ->
            val owned = if (conversationId > 0L && attachment.conversationId <= 0L) {
                attachment.copy(conversationId = conversationId)
            } else {
                attachment
            }
            db.insert("attachments", null, owned.copy(messageId = messageId).toValues())
        }
        Unit
    }

    suspend fun listForMessage(messageId: Long): List<com.qingyu.app.data.Attachment> = read { db ->
        db.rawQuery(
            "SELECT * FROM attachments WHERE message_id = ? ORDER BY id ASC",
            arrayOf(messageId.toString()),
        ).useRows { it.toAttachment() }
    }

    /** 一次取回整段会话的附件，避免每条消息查一次库喵。 */
    suspend fun listForConversation(conversationId: Long): List<com.qingyu.app.data.Attachment> = read { db ->
        db.rawQuery(
            "SELECT * FROM attachments WHERE conversation_id = ? ORDER BY id ASC",
            arrayOf(conversationId.toString()),
        ).useRows { it.toAttachment() }
    }

    suspend fun updateDescription(id: Long, description: String) = write { db ->
        db.update(
            "attachments",
            ContentValues().apply { put("description", description) },
            "id = ?",
            arrayOf(id.toString()),
        )
        Unit
    }

    suspend fun delete(id: Long) = write { db ->
        db.delete("attachments", "id = ?", arrayOf(id.toString()))
        Unit
    }

    /** 孤儿附件（消息被删了）——用于清理文件喵。 */
    suspend fun listOrphans(): List<com.qingyu.app.data.Attachment> = read { db ->
        db.rawQuery(
            """
            SELECT a.* FROM attachments a
            LEFT JOIN messages m ON m.id = a.message_id
            WHERE a.message_id = 0 OR m.id IS NULL
            """.trimIndent(),
            null,
        ).useRows { it.toAttachment() }
    }
}

/** 收藏空间存储喵。 */
class FavoriteStore(private val helper: DbHelper) {

    private fun Cursor.toFavorite() = Favorite(
        id = long("id"),
        kind = FavoriteKind.fromName(str("kind")),
        title = str("title"),
        content = str("content"),
        detail = str("detail"),
        personaName = str("persona_name"),
        conversationTitle = str("conversation_title"),
        sourceMessageId = long("source_message_id"),
        tags = str("tags").toTagsList(),
        sortOrder = int("sort_order"),
        createdAt = long("created_at"),
    )

    private suspend fun <T> read(block: (android.database.sqlite.SQLiteDatabase) -> T): T =
        withContext(Dispatchers.IO) { block(helper.readableDatabase) }

    private suspend fun <T> write(block: (android.database.sqlite.SQLiteDatabase) -> T): T =
        withContext(Dispatchers.IO) { helper.inTransaction(block) }

    suspend fun list(kind: FavoriteKind? = null, keyword: String = ""): List<Favorite> = read { db ->
        val where = StringBuilder()
        val args = ArrayList<String>()
        if (kind != null) {
            where.append("kind = ?")
            args.add(kind.name)
        }
        if (keyword.isNotBlank()) {
            if (where.isNotEmpty()) where.append(" AND ")
            where.append("(title LIKE ? OR content LIKE ? OR detail LIKE ? OR tags LIKE ?)")
            repeat(4) { args.add("%$keyword%") }
        }
        val sql = buildString {
            append("SELECT * FROM favorites")
            if (where.isNotEmpty()) append(" WHERE ").append(where)
            append(" ORDER BY sort_order ASC, created_at DESC")
        }
        db.rawQuery(sql, args.toTypedArray()).useRows { it.toFavorite() }
    }

    suspend fun insert(favorite: Favorite): Long = write { db ->
        db.insert(
            "favorites",
            null,
            ContentValues().apply {
                put("kind", favorite.kind.name)
                put("title", favorite.title)
                put("content", favorite.content)
                put("detail", favorite.detail)
                put("persona_name", favorite.personaName)
                put("conversation_title", favorite.conversationTitle)
                put("source_message_id", favorite.sourceMessageId)
                put("tags", favorite.tags.toTagsJson())
                put("sort_order", favorite.sortOrder)
                put("created_at", if (favorite.createdAt > 0) favorite.createdAt else TimeUtil.now())
            },
        )
    }

    suspend fun insertAll(favorites: List<Favorite>): Int = write { db ->
        var inserted = 0
        for (favorite in favorites) {
            val id = db.insert(
                "favorites",
                null,
                ContentValues().apply {
                    put("kind", favorite.kind.name)
                    put("title", favorite.title)
                    put("content", favorite.content)
                    put("detail", favorite.detail)
                    put("persona_name", favorite.personaName)
                    put("conversation_title", favorite.conversationTitle)
                    put("source_message_id", favorite.sourceMessageId)
                    put("tags", favorite.tags.toTagsJson())
                    put("sort_order", favorite.sortOrder)
                    put("created_at", if (favorite.createdAt > 0) favorite.createdAt else TimeUtil.now())
                },
            )
            if (id > 0) inserted++
        }
        inserted
    }

    suspend fun update(favorite: Favorite) = write { db ->
        db.update(
            "favorites",
            ContentValues().apply {
                put("kind", favorite.kind.name)
                put("title", favorite.title)
                put("content", favorite.content)
                put("detail", favorite.detail)
                put("persona_name", favorite.personaName)
                put("conversation_title", favorite.conversationTitle)
                put("tags", favorite.tags.toTagsJson())
                put("sort_order", favorite.sortOrder)
            },
            "id = ?",
            arrayOf(favorite.id.toString()),
        )
        Unit
    }

    suspend fun delete(id: Long) = write { db ->
        db.delete("favorites", "id = ?", arrayOf(id.toString()))
        Unit
    }

    suspend fun find(sourceMessageId: Long): Favorite? = read { db ->
        db.rawQuery("SELECT * FROM favorites WHERE source_message_id = ? AND source_message_id != 0 LIMIT 1", arrayOf(sourceMessageId.toString()))
            .useRows { it.toFavorite() }.firstOrNull()
    }

    suspend fun count(kind: FavoriteKind? = null): Int = read { db ->
        val sql = if (kind == null) "SELECT COUNT(*) FROM favorites" else "SELECT COUNT(*) FROM favorites WHERE kind = ?"
        val args = if (kind == null) null else arrayOf(kind.name)
        db.rawQuery(sql, args).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }
}

/**
 * 长期记忆存储喵。
 *
 * 这一层同时管两张表，而且**永远在同一个事务里写**喵：
 * - `memories` 是主库，存三元组与遗忘曲线状态喵；
 * - `memory_terms` 是倒排索引（第二个库），只在需要"按当前话题召回"时用喵。
 *
 * 之所以不把索引更新丢给后台去慢慢补：一旦两张表不同步喵，
 * 症状会非常难查——"明明记得的事却想不起来"。同步做的代价只是一次额外的 delete+insert喵，
 * 比事后排查便宜得多喵。
 */
class MemoryStore(private val helper: DbHelper) {

    private suspend fun <T> read(block: (android.database.sqlite.SQLiteDatabase) -> T): T =
        withContext(Dispatchers.IO) { block(helper.readableDatabase) }

    private suspend fun <T> write(block: (android.database.sqlite.SQLiteDatabase) -> T): T =
        withContext(Dispatchers.IO) { helper.inTransaction(block) }

    private fun Cursor.toMemory() = MemoryItem(
        id = long("id"),
        personaId = long("persona_id"),
        category = MemoryCategory.fromName(str("category")),
        content = str("content"),
        importance = int("importance"),
        enabled = bool("enabled"),
        createdAt = long("created_at"),
        updatedAt = long("updated_at"),
        subject = str("subject"),
        predicate = str("predicate"),
        obj = str("object"),
        context = str("context"),
        emotionalWeight = dbl("emotional_weight"),
        vividness = dbl("vividness"),
        recallCount = int("recall_count"),
        lastRecalledTurn = int("last_recalled_turn"),
        isUserEdited = bool("is_user_edited"),
        isPermanent = bool("is_permanent"),
        pruned = bool("pruned"),
        originalContent = str("original_content"),
        pruneEnhanced = int("prune_enhanced"),
    )

    private fun MemoryItem.toValues(now: Long, isNew: Boolean) = ContentValues().apply {
        put("persona_id", personaId)
        put("category", category.name)
        put("content", content)
        put("importance", importance.coerceIn(1, 10))
        put("enabled", if (enabled) 1 else 0)
        if (isNew) put("created_at", now)
        put("updated_at", now)
        put("subject", subject)
        put("predicate", predicate)
        put("object", obj)
        put("context", context)
        put("emotional_weight", emotionalWeight.coerceIn(0.0, 1.0))
        put("vividness", vividness.coerceIn(0.0, 1.0))
        put("recall_count", recallCount.coerceAtLeast(0))
        put("last_recalled_turn", lastRecalledTurn)
        put("is_user_edited", if (isUserEdited) 1 else 0)
        put("is_permanent", if (isPermanent) 1 else 0)
        put("pruned", if (pruned) 1 else 0)
        put("original_content", originalContent)
        put("prune_enhanced", pruneEnhanced)
    }

    /** 记忆页列表：人设永远在最前面喵。 */
    suspend fun list(personaId: Long): List<MemoryItem> = read { db ->
        db.rawQuery(
            "SELECT * FROM memories WHERE persona_id = ? ORDER BY is_permanent DESC, importance DESC, updated_at DESC",
            arrayOf(personaId.toString()),
        ).useRows { it.toMemory() }
    }

    /** 系统提示词注入 / 唤醒判定用：只要启用的，人设优先喵。 */
    suspend fun enabledFor(personaId: Long, limit: Int = 40): List<MemoryItem> = read { db ->
        db.rawQuery(
            """
            SELECT * FROM memories
            WHERE persona_id = ? AND enabled = 1
            ORDER BY is_permanent DESC, importance DESC, updated_at DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(personaId.toString(), limit.toString()),
        ).useRows { it.toMemory() }
    }

    suspend fun insert(item: MemoryItem): Long = write { db ->
        val id = db.insert("memories", null, item.toValues(TimeUtil.now(), isNew = true))
        if (id > 0) rebuildMemoryIndex(db, id, item.searchText)
        id
    }

    suspend fun insertAll(items: List<MemoryItem>): Int = write { db ->
        var inserted = 0
        for (item in items) {
            val id = db.insert("memories", null, item.toValues(TimeUtil.now(), isNew = true))
            if (id > 0) {
                rebuildMemoryIndex(db, id, item.searchText)
                inserted++
            }
        }
        inserted
    }

    /**
     * 普通更新：只覆盖业务字段，不动遗忘曲线状态喵。
     *
     * 索引照样跟着重写——内容变了但索引没变，等于新措辞用旧词搜不到喵。
     */
    suspend fun update(item: MemoryItem) = write { db ->
        db.update(
            "memories",
            ContentValues().apply {
                put("category", item.category.name)
                put("content", item.content)
                put("importance", item.importance.coerceIn(1, 10))
                put("enabled", if (item.enabled) 1 else 0)
                put("updated_at", TimeUtil.now())
            },
            "id = ?",
            arrayOf(item.id.toString()),
        )
        rebuildMemoryIndex(db, item.id, item.searchText)
        Unit
    }

    /**
     * 用户手动改一条记忆喵。
     *
     * 与 [update] 的区别就在这两个副作用：鲜活度补满 + 标记 is_user_edited喵
     * （衰减减半、基础唤醒概率翻倍）。用户亲手纠正过的东西不该被时间冲掉喵。
     * 索引也会一并重写——改了措辞之后，旧词必须失效喵。
     */
    suspend fun userUpdate(item: MemoryItem): Boolean = write { db ->
        if (item.id <= 0L) return@write false
        val values = item.toValues(TimeUtil.now(), isNew = false).apply {
            put("vividness", MemoryDecay.initialVividness())
            put("is_user_edited", 1)
        }
        val updated = db.update("memories", values, "id = ?", arrayOf(item.id.toString()))
        rebuildMemoryIndex(db, item.id, item.searchText)
        updated > 0
    }

    /** 人设只保留一份：先把旧的清掉再写入新的喵。 */
    suspend fun setPartnerPersona(
        personaId: Long,
        predicate: String,
        obj: String,
        context: String = "核心人设",
    ): Long = write { db ->
        // 顺序不能反：先按同样的条件清索引，再删主表喵。
        deleteMemoryIndexFor(db, "persona_id = ? AND is_permanent = 1", arrayOf(personaId.toString()))
        db.delete("memories", "persona_id = ? AND is_permanent = 1", arrayOf(personaId.toString()))

        val subject = "AI伴侣"
        val pred = predicate.trim()
        val objectText = obj.trim()
        val item = MemoryItem(
            personaId = personaId,
            category = MemoryCategory.RELATION,
            content = "$subject$pred$objectText",
            importance = 5,
            subject = subject,
            predicate = pred,
            obj = objectText,
            context = context.trim(),
            emotionalWeight = 1.0,
            vividness = MemoryDecay.initialVividness(),
            isUserEdited = true,
            isPermanent = true,
        )
        val id = db.insert("memories", null, item.toValues(TimeUtil.now(), isNew = true))
        if (id > 0) rebuildMemoryIndex(db, id, item.searchText)
        id
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) = write { db ->
        db.update(
            "memories",
            ContentValues().apply { put("enabled", if (enabled) 1 else 0) },
            "id = ?",
            arrayOf(id.toString()),
        )
        Unit
    }

    suspend fun delete(id: Long) = write { db ->
        db.delete("memory_terms", "memory_id = ?", arrayOf(id.toString()))
        db.delete("memories", "id = ?", arrayOf(id.toString()))
        Unit
    }

    /**
     * 被唤醒后回写：把冷却起点挪到当前轮，唤醒次数 +1喵。
     *
     * 这里刻意**不**改鲜活度：抗衰减已经由公式分母 `R + 1` 表达了喵，
     * 再额外补一次鲜活度等于把同一个效应算了两遍，会让老记忆永远不褪色喵。
     */
    suspend fun markRecalled(ids: List<Long>, turn: Int, now: Long = TimeUtil.now()) = write { db ->
        ids.forEach { id ->
            // 先看这条记忆是不是剪枝残影——苏醒需要还原原文喵。
            val pruned = db.rawQuery(
                "SELECT pruned, original_content FROM memories WHERE id = ?",
                arrayOf(id.toString()),
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.bool("pruned") else false
            }
            if (pruned) {
                db.execSQL(
                    """
                    UPDATE memories
                    SET content = original_content, original_content = '',
                        pruned = 0, prune_enhanced = 0,
                        vividness = ${MemoryDecay.REAWAKEN_VIVIDNESS},
                        last_recalled_turn = ?, recall_count = recall_count + 1,
                        updated_at = ?
                    WHERE id = ?
                    """.trimIndent(),
                    arrayOf<Any>(turn, now, id),
                )
                // 苏醒后的正文变了，索引必须重建喵。
                db.execSQL("DELETE FROM memory_terms WHERE memory_id = ?", arrayOf(id.toString()))
                val text = db.rawQuery("SELECT content FROM memories WHERE id = ?", arrayOf(id.toString()))
                    .use { c -> if (c.moveToFirst()) c.str("content") else "" }
                if (text.isNotBlank()) rebuildMemoryIndex(db, id, text)
            } else {
                db.execSQL(
                    "UPDATE memories SET last_recalled_turn = ?, recall_count = recall_count + 1, updated_at = ? WHERE id = ?",
                    arrayOf<Any>(turn, now, id),
                )
            }
        }
        Unit
    }

    /**
     * 把启用的记忆按墙上时间衰减喵。
     *
     * 指数在 Kotlin 里算完再逐条写回：SQLite 的 `exp()` 需要编译期开启数学函数喵，
     * Android 各版本支持情况不一致，不能依赖喵。
     *
     * **每条记忆各自使用自己的 `updated_at` 算 delta，而不是拿全局轮次。**喵
     * `updated_at` 在每次写入（新增、修改、markRecalled）时刷新喵，
     * 因此被想起过的记忆会自动获得更小的 delta，衰减更慢——喵
     * 这是 `R+1` 间隔重复效应之外的第二层"新鲜保护"喵。
     *
     * 如果一条记忆的鲜活度跌到 [MemoryDecay.PRUNE_VIVIDNESS_THRESHOLD] 以下喵，
     * 它会在这里被就地**剪枝**：正文机械压缩为最核心的名词喵，
     * 原稿存进 `original_content`。剪枝记忆仍然可检索；如果后续被唤醒会苏醒喵。
     *
     * @param now 当前系统毫秒时间戳喵。
     * @return 鲜活度变了的条数与本次新剪枝的 id 列表喵。
     */
    suspend fun applyDecay(personaId: Long, now: Long = TimeUtil.now()): DecayOutcome = write { db ->
        val rows = db.rawQuery(
            """
            SELECT id, vividness, recall_count, is_user_edited, is_permanent, updated_at,
                   content, subject, "object", pruned
            FROM memories WHERE persona_id = ? AND enabled = 1
            """.trimIndent(),
            arrayOf(personaId.toString()),
        ).useRows { c ->
            DecayRow(
                id = c.long("id"),
                vividness = c.dbl("vividness"),
                recallCount = c.int("recall_count"),
                isUserEdited = c.bool("is_user_edited"),
                isPermanent = c.bool("is_permanent"),
                updatedAt = c.long("updated_at"),
                content = c.str("content"),
                subject = c.str("subject"),
                obj = c.str("object"),
                pruned = c.bool("pruned"),
            )
        }

        var changed = 0
        val prunedIds = ArrayList<Long>()
        rows.forEach { row ->
            val lambda = MemoryDecay.lambdaForDays(row.isUserEdited, row.isPermanent)
            if (lambda <= 0.0) return@forEach
            val deltaMs = (now - row.updatedAt).coerceAtLeast(0L)
            val deltaDays = deltaMs.toDouble() / 86_400_000.0
            val next = MemoryDecay.decayedByDays(row.vividness, deltaDays, row.recallCount, lambda)
            if (next == row.vividness) return@forEach

            db.execSQL("UPDATE memories SET vividness = ? WHERE id = ?", arrayOf<Any>(next, row.id))
            changed++

            // 剪枝判定：鲜活度跌破阈值且非永久、非已剪枝喵。
            if (!row.isPermanent && !row.pruned && next < MemoryDecay.PRUNE_VIVIDNESS_THRESHOLD) {
                pruneMemory(db, row, next)
                prunedIds.add(row.id)
            }
        }
        DecayOutcome(changed, prunedIds)
    }

    /**
     * 机械剪枝一条记忆（鲜活度已跌到阈值以下喵）。
     *
     * 压缩策略：三元组的宾语（`obj`）通常是整条记忆最有辨识度的词——喵
     * "美式咖啡""杭州""设计师"这类。如果宾语为空则用主语；都没有就截取喵。
     *
     * 正文被替换为压缩版；原稿存进 `original_content`，方便苏醒时还原喵。
     * 鲜活度重置到 [MemoryDecay.VIVIDNESS_FLOOR]：剪枝记忆停在最低可唤醒水位喵。
     */
    private fun pruneMemory(db: SQLiteDatabase, row: DecayRow, vividness: Double) {
        val compressed = when {
            row.obj.isNotBlank() -> row.obj
            row.subject.isNotBlank() -> row.subject
            else -> row.content.take(10).trim()
        }
        db.execSQL(
            """
            UPDATE memories SET content = ?, original_content = ?, pruned = 1, prune_enhanced = 0,
            vividness = ?, updated_at = ?
            WHERE id = ?
            """.trimIndent(),
            arrayOf<Any>(
                compressed,
                row.content,
                MemoryDecay.VIVIDNESS_FLOOR,
                TimeUtil.now(),
                row.id,
            ),
        )
        // 索引跟正文同步：主表改了正文，索引必须换为压缩文本，否则搜新正文搜不到喵。
        rebuildMemoryIndex(db, row.id, compressed)
    }

    /** 本轮衰减的产出喵。 */
    data class DecayOutcome(val changed: Int, val prunedIds: List<Long> = emptyList()) {
        val pruned: Boolean get() = prunedIds.isNotEmpty()
    }

    /** 一个会话的轮次 +1，并返回上一次对话的时间（用于算"多久没见"喵）。 */
    suspend fun advanceTurn(conversationId: Long, personaId: Long, now: Long = TimeUtil.now()): TurnAdvance = write { db ->
        val previous = turnState(db, conversationId)
        val turn = previous.turn + 1
        db.insertWithOnConflict(
            "memory_state",
            null,
            ContentValues().apply {
                put("conversation_id", conversationId)
                put("persona_id", personaId)
                put("turn", turn)
                put("updated_at", now)
            },
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
        )
        TurnAdvance(turn = turn, previousAt = previous.lastTurnAt)
    }

    /** 只读当前轮次，不推进。给调试页用喵。 */
    suspend fun turnOf(conversationId: Long): Int = read { db -> turnState(db, conversationId).turn }

    /** 只读上次活跃时间，不推进轮次。给调试页自检"多久没见"用喵。 */
    suspend fun lastActiveAt(conversationId: Long): Long =
        read { db -> turnState(db, conversationId).lastTurnAt }

    /** 记忆页顶部的概览数字喵。 */
    suspend fun stats(personaId: Long): MemoryStats = read { db ->
        db.rawQuery(
            """
            SELECT COUNT(*),
                   COALESCE(SUM(CASE WHEN is_permanent = 1 THEN 1 ELSE 0 END), 0),
                   COALESCE(AVG(vividness), 0.0),
                   COALESCE(SUM(recall_count), 0)
            FROM memories WHERE persona_id = ? AND enabled = 1
            """.trimIndent(),
            arrayOf(personaId.toString()),
        ).use { c ->
            if (!c.moveToFirst()) return@use MemoryStats()
            MemoryStats(
                total = c.getInt(0),
                permanent = c.getInt(1),
                averageVividness = c.getDouble(2),
                totalRecalls = c.getLong(3),
            )
        }
    }

    /**
     * 一次取回索引，避免逐条记忆各查一次喵。
     *
     * @param memoryIds 只取这些记忆的索引；留空表示整个角色全取喵。
     */
    suspend fun indexFor(
        personaId: Long,
        memoryIds: Collection<Long> = emptySet(),
        chunkSize: Int = 400,
    ): Map<Long, Map<String, Double>> = read { db ->
        val rows = ArrayList<MemoryTermRow>()
        val idFilter = memoryIds.filter { it > 0L }
        val chunks = if (idFilter.isEmpty()) listOf(emptyList()) else idFilter.chunked(chunkSize.coerceAtLeast(1))
        chunks.forEach { chunk ->
            val args = ArrayList<String>(chunk.size + 1).apply {
                add(personaId.toString())
                chunk.forEach { add(it.toString()) }
            }
            val idClause = if (chunk.isEmpty()) "" else "AND t.memory_id IN (${List(chunk.size) { "?" }.joinToString(",")})"
            db.rawQuery(
                """
                SELECT t.memory_id, t.term, t.weight
                FROM memory_terms t
                INNER JOIN memories m ON m.id = t.memory_id
                WHERE m.persona_id = ? AND m.enabled = 1 $idClause
                """.trimIndent(),
                args.toTypedArray(),
            ).useRows { c ->
                MemoryTermRow(memoryId = c.getLong(0), term = c.getString(1).orEmpty(), weight = c.getDouble(2))
            }.let { rows.addAll(it) }
        }
        MemoryTextIndex.fromRows(rows).associate { it.id to it.terms }
    }

    /**
     * 第一阶段的粗召回：只问"哪些记忆跟当前话题有共同 term"喵。
     *
     * 真正打分交给第二阶段（余弦相似度），这里只是把候选面从"全部记忆"喵
     * 收窄到"可能相关的几条"——记忆攒到几百条时，这一步就是 `idx_terms_term` 该干的活喵。
     *
     * 传进来的 term 会按 [chunkSize] 分批：SQLite 对单条语句的绑定参数个数有上限喵，
     * 中文二元组又特别容易产生几十上百个 term，不分批会直接报绑定参数过多喵。
     */
    suspend fun matchIds(personaId: Long, terms: Collection<String>, chunkSize: Int = 400): Set<Long> = read { db ->
        val ids = LinkedHashSet<Long>()
        val chunks = terms.filter { it.isNotBlank() }.chunked(chunkSize.coerceAtLeast(1))
        chunks.forEach { chunk ->
            if (chunk.isEmpty()) return@forEach
            val placeholders = List(chunk.size) { "?" }.joinToString(",")
            val args = arrayOf(personaId.toString()) + chunk.toTypedArray()
            db.rawQuery(
                """
                SELECT DISTINCT t.memory_id
                FROM memory_terms t
                INNER JOIN memories m ON m.id = t.memory_id
                WHERE m.persona_id = ? AND m.enabled = 1 AND t.term IN ($placeholders)
                """.trimIndent(),
                args,
            ).useRows { it.getLong(0) }.let { ids.addAll(it) }
        }
        ids
    }

    /** 已经记过一模一样的话就不重复写了喵。 */
    suspend fun exists(personaId: Long, statement: String): Boolean = read { db ->
        val normalized = statement.trim()
        if (normalized.isEmpty()) return@read false
        db.rawQuery(
            "SELECT COUNT(*) FROM memories WHERE persona_id = ? AND content = ? LIMIT 1",
            arrayOf(personaId.toString(), normalized),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) > 0 else false }
    }

    /**
     * 取一条已剪枝记忆的原稿与当前压缩文本，供小模型优化压缩用喵。
     *
     * @return null 表示不存在或不是剪枝状态（可能已被苏醒喵）。
     */
    suspend fun pruneCandidate(memoryId: Long): PruneCandidate? = read { db ->
        db.rawQuery(
            """SELECT original_content, content FROM memories WHERE id = ? AND pruned = 1 AND prune_enhanced = 0""",
            arrayOf(memoryId.toString()),
        ).use { c ->
            if (!c.moveToFirst()) return@read null
            PruneCandidate(
                memoryId = memoryId,
                originalContent = c.getString(0).orEmpty(),
                compressedText = c.getString(1).orEmpty(),
            )
        }
    }

    /**
     * 用小模型优化后的压缩文本替换当前残影喵。
     *
     * 索引跟着一起换；`prune_enhanced` 标为 1 之后不会被再次拾起喵。
     */
    suspend fun enhancePrune(memoryId: Long, compressed: String) = write { db ->
        db.execSQL(
            "UPDATE memories SET content = ?, prune_enhanced = 1, updated_at = ? WHERE id = ?",
            arrayOf<Any?>(compressed.trim().take(20), TimeUtil.now(), memoryId),
        )
        rebuildMemoryIndex(db, memoryId, compressed.trim().take(20))
        Unit
    }

    /** 索引同步：整条删掉重写。一张记忆的词通常只有几十个，重建比差分更省心喵。 */
    private fun turnState(db: android.database.sqlite.SQLiteDatabase, conversationId: Long): TurnState =
        db.rawQuery(
            "SELECT turn, updated_at FROM memory_state WHERE conversation_id = ?",
            arrayOf(conversationId.toString()),
        ).use { c ->
            if (c.moveToFirst()) TurnState(turn = c.getInt(0), lastTurnAt = c.getLong(1)) else TurnState()
        }

    /**
     * 会话的轮次状态喵。
     *
     * [lastTurnAt] 为 0 表示这张表里还没有记录——也就是第一次聊喵，
     * 上层拿它去算"多久没见"，为 0 时不输出任何间隔描述喵。
     */
    private data class TurnState(
        val turn: Int = 0,
        val lastTurnAt: Long = 0L,
    )

    private data class DecayRow(
        val id: Long,
        val vividness: Double,
        val recallCount: Int,
        val isUserEdited: Boolean,
        val isPermanent: Boolean,
        val updatedAt: Long,
        val content: String = "",
        val subject: String = "",
        val obj: String = "",
        val pruned: Boolean = false,
    )
}

/** 剪枝记忆的原稿与当前压缩文本，供小模型优化喵。 */
data class PruneCandidate(
    val memoryId: Long,
    val originalContent: String,
    val compressedText: String,
)

/** 本轮推进后的轮次信息。[previousAt] 为 0 表示这是第一轮喵。 */
data class TurnAdvance(val turn: Int, val previousAt: Long)

/** 记忆页顶部的概览数字喵。 */
data class MemoryStats(
    val total: Int = 0,
    val permanent: Int = 0,
    val averageVividness: Double = 0.0,
    val totalRecalls: Long = 0L,
)

/**
 * 表情包存储喵。
 *
 * 与其他 Store 不同的两点喵：
 * - **按使用次数排序**：最近常发的排在前面，符合"表情包越用越顺手"的直觉喵；
 *   但首次导入时大家都还是 0，所以要退化成按导入顺序排，否则顺序会随机跳喵
 * - 删除时**顺手把文件也删掉**：表情包图片只被这一行引用，不像附件那样可能
 *   被多条消息共享；留下文件就是永久垃圾喵
 */
class StickerStore(private val helper: DbHelper) {

    private fun Cursor.toSticker() = Sticker(
        id = long("id"),
        name = str("name"),
        filePath = str("file_path"),
        mimeType = str("mime_type"),
        width = int("width"),
        height = int("height"),
        sizeBytes = long("size_bytes"),
        useCount = int("use_count"),
        sortOrder = int("sort_order"),
        createdAt = long("created_at"),
    )

    private suspend fun <T> read(block: (android.database.sqlite.SQLiteDatabase) -> T): T =
        withContext(Dispatchers.IO) { block(helper.readableDatabase) }

    private suspend fun <T> write(block: (android.database.sqlite.SQLiteDatabase) -> T): T =
        withContext(Dispatchers.IO) { helper.inTransaction(block) }

    /** 全部表情包，常用的在前喵。 */
    suspend fun list(): List<Sticker> = read { db ->
        db.rawQuery(
            "SELECT * FROM stickers ORDER BY use_count DESC, sort_order ASC, id ASC",
            null,
        ).useRows { it.toSticker() }
    }

    suspend fun get(id: Long): Sticker? = read { db ->
        db.rawQuery("SELECT * FROM stickers WHERE id = ?", arrayOf(id.toString()))
            .useRows { it.toSticker() }.firstOrNull()
    }

    suspend fun count(): Int = read { db ->
        db.rawQuery("SELECT COUNT(*) FROM stickers", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /** 同名表情是否已存在（导入时用来避免重复导入同一套）喵。 */
    suspend fun existingNames(): Set<String> = read { db ->
        db.rawQuery("SELECT name FROM stickers", null).use { c ->
            buildSet {
                while (c.moveToNext()) add(c.getString(0))
            }
        }
    }

    suspend fun insert(sticker: Sticker): Long = write { db ->
        db.insert(
            "stickers",
            null,
            ContentValues().apply {
                put("name", sticker.name)
                put("file_path", sticker.filePath)
                put("mime_type", sticker.mimeType)
                put("width", sticker.width)
                put("height", sticker.height)
                put("size_bytes", sticker.sizeBytes)
                put("use_count", sticker.useCount)
                put("sort_order", sticker.sortOrder)
                put("created_at", if (sticker.createdAt > 0) sticker.createdAt else TimeUtil.now())
            },
        )
    }

    /** 批量导入，整体一个事务喵。 */
    suspend fun insertAll(stickers: List<Sticker>): Int = write { db ->
        var inserted = 0
        for (sticker in stickers) {
            val id = db.insert(
                "stickers",
                null,
                ContentValues().apply {
                    put("name", sticker.name)
                    put("file_path", sticker.filePath)
                    put("mime_type", sticker.mimeType)
                    put("width", sticker.width)
                    put("height", sticker.height)
                    put("size_bytes", sticker.sizeBytes)
                    put("use_count", sticker.useCount)
                    put("sort_order", sticker.sortOrder)
                    put("created_at", if (sticker.createdAt > 0) sticker.createdAt else TimeUtil.now())
                },
            )
            if (id > 0) inserted++
        }
        inserted
    }

    suspend fun rename(id: Long, name: String) = write { db ->
        db.update(
            "stickers",
            ContentValues().apply { put("name", name) },
            "id = ?",
            arrayOf(id.toString()),
        )
        Unit
    }

    /** 发过一次就记一次，用于把常用的顶到前面喵。 */
    suspend fun markUsed(id: Long) = write { db ->
        db.execSQL("UPDATE stickers SET use_count = use_count + 1 WHERE id = ?", arrayOf(id))
    }

    /** 删除一行，返回它的路径供调用方清理文件喵。 */
    suspend fun delete(id: Long): String? = write { db ->
        val path = db.rawQuery("SELECT file_path FROM stickers WHERE id = ?", arrayOf(id.toString()))
            .use { c -> if (c.moveToFirst()) c.getString(0) else null }
        db.delete("stickers", "id = ?", arrayOf(id.toString()))
        path
    }
}

/** 数据库门面：把各 Store 组装在一起，供上层依赖注入喵。 */
class QingYuDatabase(context: android.content.Context) {
    val helper = DbHelper(context)
    val personas = PersonaStore(helper)
    val conversations = ConversationStore(helper)
    val messages = MessageStore(helper)
    val favorites = FavoriteStore(helper)
    val memories = MemoryStore(helper)
    val attachments = AttachmentStore(helper)
    val stickers = StickerStore(helper)

    /** 包一层，方便 UI 统一处理数据库异常喵。 */
    suspend fun <T> guarded(block: suspend () -> T): Result<T> = runCatchingResult { block() }
}
