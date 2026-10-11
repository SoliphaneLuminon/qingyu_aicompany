package com.qingyu.app.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.qingyu.app.core.memory.MemoryDecay
import org.json.JSONArray
import org.json.JSONObject

/**
 * 轻语的本地库喵。
 *
 * 用原生 SQLite 而不是 Room：这个应用的表结构很稳定，且导入/导出需要精确控制事务喵，
 * 手写 DAO 反而更小、更快、编译期零注解处理喵。
 */
object Db {
    const val NAME = "qingyu.db"

    /**
     * v3：记忆系统升级喵。
     * - `memories` 加上三元组（subject / predicate / object / context）与喵
     *   遗忘曲线状态（emotional_weight / vividness / recall_count /喵
     *   last_recalled_turn / is_user_edited / is_permanent喵）；
     * - 新增 `memory_terms`：本地倒排索引，也就是"双库"里的第二个库喵；
     * - 新增 `memory_state`：每个会话的轮次与上次对话时间，供冷却与生物感知使用喵。
     *
     * v4：记忆剪枝喵。
     * - 鲜活度跌到阈值以下时自动压缩为残影（保留最核心的名词喵）；
     * - 残影仍可被检索触发，触发后会"重新苏醒"恢复完整内容喵；
     * - 小模型可异步优化压缩质量（`prune_enhanced` 标记喵）。
     *
     * v5：重建倒排索引权重喵。
     * - 打分口径改了：单字（「了」「的」「是」）不再计分，只留中文二元组 / 拉丁词 / 数字喵；
     * - 老行的权重是按旧口径归一化的，不重算的话老记忆得分会系统性低于新记忆喵。
     *
     * v6：一个角色只剩一个对话喵。
     * - 界面改成"角色即会话"：进角色就是进它唯一的那段对话，不再有新建对话喵；
     * - 升级时每个角色只保留最近活动过的那个对话，其余连同消息、附件、
     *   轮次状态一起删除（取舍见 [collapseConversationsPerPersona] 的说明喵）。
     *
     * v7：对话可以"从主界面移除"喵。
     * - 主界面只显示没被移除的对话，但**角色、消息、记忆一个都不删**喵；
     * - 从通讯录进去或发出新消息就会回来，所以它是一个"隐藏"标记而不是删除喵
     *   （`conversations.hidden`）。
     *
     * v8：头像可上传、加表情包与红包喵。
     * - `personas.avatar_image` 存自定义头像的文件名（空 = 仍用 emoji）喵；
     * - 新建 `stickers` 表：表情包的图片与**名字**，名字要参与发送时的语义喵；
     * - `messages.kind` 区分普通文字与"表情包 / 红包"这两类结构化消息喵。
     *   用单独一列而不是塞进 content 里做前缀标记，是因为前缀会污染送给模型的文本，
     *   而且用户手打的字里出现同样的前缀就会误判喵。
     */
    const val VERSION = 8
}

class DbHelper(context: Context) : SQLiteOpenHelper(context.applicationContext, Db.NAME, null, Db.VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        createPersonas(db)
        createConversations(db)
        createMessages(db)
        createFavorites(db)
        createMemories(db)
        createAttachments(db)
        createMemoryTerms(db)
        createMemoryState(db)
        createStickers(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v1 -> v2：只加列加表，不动既有数据喵。
            db.execSQL("ALTER TABLE messages ADD COLUMN search_json TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE messages ADD COLUMN has_image INTEGER NOT NULL DEFAULT 0")
            createAttachments(db)
        }
        if (oldVersion < 3) {
            upgradeMemoriesToV3(db)
            createMemoryTerms(db)
            createMemoryState(db)
            backfillMemoryIndex(db)
        }
        if (oldVersion < 4) {
            upgradeMemoriesToV4(db)
        }
        if (oldVersion < 5) {
            // v4 -> v5：索引打分口径变了，必须按新口径重算一遍已有记忆的权重喵。
            // 不重算的症状很隐蔽——老记忆并非"想不起来"，而是分数被系统性压低喵，
            // 永远排在新建的记忆后面。backfillMemoryIndex 本身就是"整条删掉重写"，可安全重复执行喵。
            backfillMemoryIndex(db)
        }
        if (oldVersion < 6) {
            collapseConversationsPerPersona(db)
        }
        if (oldVersion < 7) {
            // v6 -> v7：只加一列，不动既有数据；老对话默认都是"显示中"（0）喵。
            db.execSQL("ALTER TABLE conversations ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 8) {
            // v7 -> v8：只加列加表，老数据一律走默认值喵。
            // `avatar_image` 默认空串 = 继续用 emoji，所以老角色升级后外观完全不变喵。
            // `kind` 默认 'text'，历史消息全部落进普通文字分支喵。
            db.execSQL("ALTER TABLE personas ADD COLUMN avatar_image TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE messages ADD COLUMN kind TEXT NOT NULL DEFAULT 'text'")
            db.execSQL("ALTER TABLE messages ADD COLUMN sticker_id INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN payload TEXT NOT NULL DEFAULT ''")
            createStickers(db)
        }
    }

    /**
     * v5 -> v6：每个角色只留一个对话喵。
     *
     * 界面改成"角色即会话"之后，同一个角色下的旧对话在界面上再也没有入口喵，
     * 留着只会白占空间、还会让 `latestOrCreate` 每次都要排序挑选喵。
     * 所以升级时直接收敛：按 `updated_at` 留下最近活动的那一个，其余删掉喵。
     *
     * 取舍：**不把多个对话的消息合并成一个**——合并会把不同时期的话题揉在一起，
     * 时间线错乱、上下文也会互相污染，读起来比丢掉更糟喵。宁可只留最近那段喵。
     *
     * 删除顺序有讲究：先从子表（消息 / 附件 / 轮次状态）删，最后删会话本身，
     * 与 `PersonaStore.delete` 保持同一套清理口径，不留孤儿行喵。
     */
    private fun collapseConversationsPerPersona(db: SQLiteDatabase) {
        val keep = mutableSetOf<Long>()
        val drop = mutableListOf<Long>()
        // 按角色分组、组内按最近活动倒序，每组第一条就是要留下的那个喵。
        db.rawQuery(
            "SELECT id, persona_id FROM conversations ORDER BY persona_id ASC, updated_at DESC, id DESC",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val personaId = cursor.getLong(1)
                if (!keep.add(personaId)) drop += id
            }
        }
        if (drop.isEmpty()) return
        val ids = drop.joinToString(",")
        db.execSQL("DELETE FROM messages WHERE conversation_id IN ($ids)")
        db.execSQL("DELETE FROM attachments WHERE conversation_id IN ($ids)")
        db.execSQL("DELETE FROM memory_state WHERE conversation_id IN ($ids)")
        db.execSQL("DELETE FROM conversations WHERE id IN ($ids)")
    }

    private fun createPersonas(db: SQLiteDatabase) = db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS personas (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            avatar_emoji TEXT NOT NULL DEFAULT '🌙',
            tagline TEXT NOT NULL DEFAULT '',
            system_prompt TEXT NOT NULL DEFAULT '',
            scenario TEXT NOT NULL DEFAULT '',
            sample_dialogue TEXT NOT NULL DEFAULT '',
            greeting TEXT NOT NULL DEFAULT '',
            model_override TEXT NOT NULL DEFAULT '',
            temperature_override REAL,
            tts_voice_hint TEXT NOT NULL DEFAULT '',
            avatar_image TEXT NOT NULL DEFAULT '',
            built_in INTEGER NOT NULL DEFAULT 0,
            sort_order INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        )
        """.trimIndent(),
    )

    private fun createConversations(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS conversations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                persona_id INTEGER NOT NULL,
                title TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                last_preview TEXT NOT NULL DEFAULT '',
                message_count INTEGER NOT NULL DEFAULT 0,
                pinned INTEGER NOT NULL DEFAULT 0,
                hidden INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_conv_persona ON conversations(persona_id, updated_at DESC)")
    }

    private fun createMessages(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                conversation_id INTEGER NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL DEFAULT '',
                timestamp INTEGER NOT NULL,
                reasoning_duration INTEGER NOT NULL DEFAULT 0,
                completed INTEGER NOT NULL DEFAULT 1,
                favorite INTEGER NOT NULL DEFAULT 0,
                error TEXT,
                search_json TEXT NOT NULL DEFAULT '',
                has_image INTEGER NOT NULL DEFAULT 0,
                kind TEXT NOT NULL DEFAULT 'text',
                sticker_id INTEGER NOT NULL DEFAULT 0,
                payload TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_msg_conv ON messages(conversation_id, timestamp, id)")
    }

    /**
     * 表情包表喵。
     *
     * `name` 不是装饰：发送时它作为文字内容进入上下文，图片同时交给多模态模型，
     * 两边各占一半语义——所以名字必须能表达这个表情是什么意思喵。
     * 也因此**没有**把名字做成可空的：没有名字的表情包等于一段无意义的图，
     * 模型只能瞎猜，不如导入时就补一个喵。
     *
     * `file_path` 存应用私有目录内的绝对路径，与 [createAttachments] 的口径一致喵；
     * 不存字节是为了让列表滚动时不必反复读库、也让备份可以只带路径喵。
     */
    private fun createStickers(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS stickers (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL DEFAULT '',
                file_path TEXT NOT NULL,
                mime_type TEXT NOT NULL DEFAULT 'image/png',
                width INTEGER NOT NULL DEFAULT 0,
                height INTEGER NOT NULL DEFAULT 0,
                size_bytes INTEGER NOT NULL DEFAULT 0,
                use_count INTEGER NOT NULL DEFAULT 0,
                sort_order INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sticker_order ON stickers(sort_order, created_at DESC)")
    }

    private fun createFavorites(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS favorites (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL,
                title TEXT NOT NULL DEFAULT '',
                content TEXT NOT NULL DEFAULT '',
                detail TEXT NOT NULL DEFAULT '',
                persona_name TEXT NOT NULL DEFAULT '',
                conversation_title TEXT NOT NULL DEFAULT '',
                source_message_id INTEGER NOT NULL DEFAULT 0,
                tags TEXT NOT NULL DEFAULT '[]',
                sort_order INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_fav_kind ON favorites(kind, sort_order, created_at DESC)")
    }

    private fun createMemories(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS memories (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                persona_id INTEGER NOT NULL,
                category TEXT NOT NULL,
                content TEXT NOT NULL,
                importance INTEGER NOT NULL DEFAULT 3,
                enabled INTEGER NOT NULL DEFAULT 1,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                subject TEXT NOT NULL DEFAULT '',
                predicate TEXT NOT NULL DEFAULT '',
                "object" TEXT NOT NULL DEFAULT '',
                context TEXT NOT NULL DEFAULT '',
                emotional_weight REAL NOT NULL DEFAULT 0.5,
                vividness REAL NOT NULL DEFAULT 1.0,
                recall_count INTEGER NOT NULL DEFAULT 0,
                last_recalled_turn INTEGER NOT NULL DEFAULT ${MemoryDecay.NEVER_RECALLED_TURN},
                is_user_edited INTEGER NOT NULL DEFAULT 0,
                is_permanent INTEGER NOT NULL DEFAULT 0,
                pruned INTEGER NOT NULL DEFAULT 0,
                original_content TEXT NOT NULL DEFAULT '',
                prune_enhanced INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_mem_persona ON memories(persona_id, importance DESC)")
    }

    /**
     * v2 -> v3：给 memories 加列喵。
     *
     * 两个必须注意的点喵：
     * 1. `object` 在 SQLite 里不是保留字，但为了避免和 SQL 语法混淆统一加了引号喵，
     *    之后 rawQuery 里也照原样写列名即可喵；
     * 2. 已存在的记忆 `last_recalled_turn` 会被补成 [MemoryDecay.NEVER_RECALLED_TURN喵]。
     *    默认 0 会让它们一升级就被冷却挡住 20 轮（表现为"老记忆集体消失"喵），
     *    这不是用户期望的升级体验喵。
     */
    private fun upgradeMemoriesToV3(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE memories ADD COLUMN subject TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE memories ADD COLUMN predicate TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE memories ADD COLUMN \"object\" TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE memories ADD COLUMN context TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE memories ADD COLUMN emotional_weight REAL NOT NULL DEFAULT 0.5")
        db.execSQL("ALTER TABLE memories ADD COLUMN vividness REAL NOT NULL DEFAULT 1.0")
        db.execSQL("ALTER TABLE memories ADD COLUMN recall_count INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE memories ADD COLUMN last_recalled_turn INTEGER NOT NULL DEFAULT ${MemoryDecay.NEVER_RECALLED_TURN}")
        db.execSQL("ALTER TABLE memories ADD COLUMN is_user_edited INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE memories ADD COLUMN is_permanent INTEGER NOT NULL DEFAULT 0")
    }

    /**
     * v3 -> v4：记忆剪枝喵。
     *
     * 残影列 `original_content` 在触发苏醒前为空喵；
     * `prune_enhanced = 0` 表示机械压缩，`-1` 表示模型尝试失败喵。
     * 老记忆一律按未剪枝处理（`pruned = 0`喵）。
     */
    private fun upgradeMemoriesToV4(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE memories ADD COLUMN pruned INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE memories ADD COLUMN original_content TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE memories ADD COLUMN prune_enhanced INTEGER NOT NULL DEFAULT 0")
    }

    /**
     * 建完索引表后把已有的记忆补进去喵。
     *
     * 这一步不能省：老记忆是在还没有索引表的版本里写的喵，
     * 不回填的话它们一条都匹配不上，症状是"升级完所有记忆都想不起来了"喵。
     * 之后新建/修改记忆都由 `MemoryStore` 同步维护喵。
     */
    private fun backfillMemoryIndex(db: SQLiteDatabase) {
        val rows = ArrayList<Pair<Long, String>>()
        db.rawQuery(
            """SELECT id, subject, predicate, "object", context, content FROM memories""",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val text = listOf(
                    cursor.getString(1),
                    cursor.getString(2),
                    cursor.getString(3),
                    cursor.getString(4),
                    cursor.getString(5),
                ).filter { it.isNotBlank() }.joinToString(" ")
                rows.add(id to text)
            }
        }
        rows.forEach { (id, text) -> rebuildMemoryIndex(db, id, text) }
    }

    /**
     * 倒排索引：一条记忆切成若干 term，每行一个（记忆 id + 词 + 权重喵）。
     *
     * 和主库的同步由 `MemoryStore` 在同一个事务里完成喵，
     * 不存在"主库有、索引没有"的中间态喵。
     */
    private fun createMemoryTerms(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS memory_terms (
                memory_id INTEGER NOT NULL,
                term TEXT NOT NULL,
                weight REAL NOT NULL DEFAULT 0,
                PRIMARY KEY (memory_id, term)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_terms_term ON memory_terms(term)")
    }

    /** 每个会话记一个轮次与上次发起对话的时间喵。 */
    private fun createMemoryState(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS memory_state (
                conversation_id INTEGER PRIMARY KEY,
                persona_id INTEGER NOT NULL DEFAULT 0,
                turn INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
    }

    private fun createAttachments(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS attachments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                message_id INTEGER NOT NULL DEFAULT 0,
                conversation_id INTEGER NOT NULL DEFAULT 0,
                kind TEXT NOT NULL DEFAULT 'IMAGE',
                local_path TEXT NOT NULL,
                mime_type TEXT NOT NULL DEFAULT 'image/jpeg',
                width INTEGER NOT NULL DEFAULT 0,
                height INTEGER NOT NULL DEFAULT 0,
                size_bytes INTEGER NOT NULL DEFAULT 0,
                description TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_att_message ON attachments(message_id, id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_att_conv ON attachments(conversation_id, id)")
    }

    /** 在事务中执行，异常时回滚喵。 */
    fun <T> inTransaction(block: (SQLiteDatabase) -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = block(db)
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }
}

/** `tags` 列与 `List<String>` 之间的转换喵。 */
internal fun List<String>.toTagsJson(): String {
    val array = JSONArray()
    forEach { array.put(it) }
    return array.toString()
}

internal fun String.toTagsList(): List<String> {
    if (isBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(this)
        (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> s.isNotBlank() } }
    }.getOrDefault(emptyList())
}

/**
 * 联网搜索结果与 JSON 的互转喵。
 *
 * 用 JSON 存进 `messages.search_json` 而不是单独开表：来源只需要跟着消息一起读出来展示喵，
 * 不需要按字段查询，一列 JSON 比一张表更省事喵。
 */
internal fun List<com.qingyu.app.data.SearchHit>.toSearchJson(): String {
    if (isEmpty()) return ""
    val array = JSONArray()
    forEach { hit ->
        array.put(
            JSONObject().apply {
                put("title", hit.title)
                put("url", hit.url)
                put("snippet", hit.snippet)
                put("publishedAt", hit.publishedAt)
            },
        )
    }
    return array.toString()
}

internal fun String.toSearchHits(): List<com.qingyu.app.data.SearchHit> {
    if (isBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(this)
        (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            com.qingyu.app.data.SearchHit(
                title = obj.optString("title"),
                url = obj.optString("url"),
                snippet = obj.optString("snippet"),
                publishedAt = obj.optString("publishedAt"),
            )
        }
    }.getOrDefault(emptyList())
}
