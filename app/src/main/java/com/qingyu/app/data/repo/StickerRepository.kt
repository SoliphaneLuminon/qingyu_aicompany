package com.qingyu.app.data.repo

import android.content.Context
import android.graphics.BitmapFactory
import com.qingyu.app.core.TimeUtil
import com.qingyu.app.data.Attachment
import com.qingyu.app.data.AttachmentKind
import com.qingyu.app.data.ChatMessage
import com.qingyu.app.data.MessageKind
import com.qingyu.app.data.MessageRole
import com.qingyu.app.data.Sticker
import com.qingyu.app.data.db.QingYuDatabase
import com.qingyu.app.media.StickerEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 表情包：导入、存储、发送喵。
 *
 * 这个类集中了表情包最关键的一个设计决定——**"名字与图片各占一半权重"怎么落地**喵。
 *
 * 做法不是把两者拼成一段文字，而是**同时走两条既有通道**喵：
 * 1. 消息的 `content` 写成表情的**名字**，于是它自然进入 [PromptBuilder.turns] 的文本流喵；
 * 2. 图片本体作为一条普通 [Attachment] 挂上去，于是它自然进入既有的多模态 /
 *    识图通道（主模型能看图就直接发图，不能看就由识图模型转成描述）喵。
 *
 * 这样两边各占一半，而且**没有新增任何一条送模型的代码路径**——
 * 表情包复用的是文字与图片各自早就验证过的那套逻辑，行为可预测喵。
 *
 * 为什么不把"名字 + 图片描述"拼进 content 里喵：那会让同一张图被描述两次
 * （一次进 content、一次进 attachments.description），模型看到重复信息，
 * 而且历史里翻出来看时那句描述会一直挂在气泡上，很脏喵。
 */
class StickerRepository(
    private val context: Context,
    private val db: QingYuDatabase,
) {

    class Failure(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

    private val root: File get() = File(context.filesDir, "stickers").apply { mkdirs() }

    suspend fun list(): List<Sticker> = db.stickers.list()

    suspend fun count(): Int = db.stickers.count()

    /**
     * 把一张图片存成表情包喵。
     *
     * [bytes] 已经是图片本体（相册选的图会先由调用方解码成 PNG/JPEG 字节）喵。
     * 名字重复时自动加序号后缀——表情列表按名字辨识，重名会让人分不清哪个是哪个喵。
     */
    suspend fun add(name: String, bytes: ByteArray): Sticker = withContext(Dispatchers.IO) {
        val cleanName = name.trim().ifBlank { "表情" }
        val finalName = uniqueName(cleanName)

        val (width, height) = readBounds(bytes)
        // 扩展名跟着内容走，不硬编码 png：用户从相册导入的可能是 jpg 喵。
        val ext = if (looksLikePng(bytes)) "png" else "jpg"
        val file = File(root, "sticker_${TimeUtil.now()}_${(0..9999).random()}.$ext")
        try {
            file.writeBytes(bytes)
        } catch (t: Throwable) {
            runCatching { file.delete() }
            throw Failure("保存表情失败：${t.message}", t)
        }

        val sticker = Sticker(
            name = finalName,
            filePath = file.absolutePath,
            mimeType = if (ext == "png") "image/png" else "image/jpeg",
            width = width,
            height = height,
            sizeBytes = bytes.size.toLong(),
            createdAt = TimeUtil.now(),
        )
        val id = db.stickers.insert(sticker)
        sticker.copy(id = id)
    }

    /**
     * 批量导入（压缩包）喵。
     *
     * 与 [add] 的区别：这里**一次事务写完**，而且名字去重是在整批之内做喵——
     * 压缩包里常有两个目录下同名的情况，逐个 add 会互相看不见对方，
     * 结果就是一堆重名喵。
     */
    suspend fun importEntries(entries: List<StickerEntry>): Int = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext 0

        // 已存在的名字先占上，避免导入一整套和上次完全相同的表情喵。
        val taken = db.stickers.existingNames().toMutableSet()
        val now = TimeUtil.now()
        val rows = ArrayList<Sticker>(entries.size)
        var order = 0

        entries.forEach { entry ->
            val finalName = uniqueNameWithin(entry.name.trim().ifBlank { "表情" }, taken)
            taken += finalName

            val ext = if (looksLikePng(entry.bytes)) "png" else "jpg"
            val file = File(root, "sticker_${now}_${order}_${(0..9999).random()}.$ext")
            val written = runCatching { file.writeBytes(entry.bytes) }.isSuccess
            if (!written) return@forEach

            val (width, height) = readBounds(entry.bytes)
            rows += Sticker(
                name = finalName,
                filePath = file.absolutePath,
                mimeType = if (ext == "png") "image/png" else "image/jpeg",
                width = width,
                height = height,
                sizeBytes = entry.bytes.size.toLong(),
                sortOrder = order,
                createdAt = now,
            )
            order++
        }

        db.stickers.insertAll(rows)
    }

    suspend fun rename(id: Long, name: String) {
        val clean = name.trim()
        if (clean.isBlank()) return
        db.stickers.rename(id, uniqueName(clean, exceptId = id))
    }

    suspend fun delete(sticker: Sticker) {
        val path = db.stickers.delete(sticker.id)
        runCatching { path?.let { File(it).delete() } }
    }

    /** 供界面按路径直接显示喵。 */
    fun fileOf(sticker: Sticker): File? = File(sticker.filePath).takeIf { it.isFile }

    /**
     * 发一个表情包喵。
     *
     * 返回落库后的消息；调用方拿它去触发模型回复喵。
     * 名字既是 content、也作为附件的 [Attachment.description] 兜底，
     * 这样**即使没配识图模型**，模型至少能从描述里读到"用户发了一个叫 X 的表情"喵。
     */
    suspend fun send(
        conversationId: Long,
        sticker: Sticker,
        timestamp: Long = TimeUtil.now(),
    ): ChatMessage {
        val message = ChatMessage(
            conversationId = conversationId,
            role = MessageRole.USER,
            // 名字直接当正文：文本那一半权重就靠它喵。
            content = sticker.name,
            timestamp = timestamp,
            kind = MessageKind.STICKER,
            stickerId = sticker.id,
        )
        val id = db.messages.insert(message)

        // 图片那一半：作为普通附件挂上，复用既有的多模态 / 识图通道喵。
        //
        // description 刻意**留空**，交给引擎去填喵。引擎的规则是
        // "description 为空才送去识图"，所以喵：
        // - 主模型能看图 → 图片作为多模态内容直接发出，名字走 content，两半都在喵
        // - 主模型看不了图 → 识图模型把这张表情描述出来，content 变成
        //   「开心 + （图片内容：…）」，模型既读到名字也读到画面，仍是两半喵
        // 若在这里预填一句"这是一个表情包"，它就永远不为空、识图永远不跑，
        // 第二条路径下模型就只剩名字可看——图片那一半权重直接丢了喵。
        // 描述会写回 attachments.description，所以同一个表情只识别一次喵。
        val size = runCatching { File(sticker.filePath).length() }.getOrDefault(0L)
        db.attachments.attachToMessage(
            messageId = id,
            attachments = listOf(
                Attachment(
                    messageId = id,
                    conversationId = conversationId,
                    kind = AttachmentKind.IMAGE,
                    localPath = sticker.filePath,
                    mimeType = sticker.mimeType,
                    width = sticker.width,
                    height = sticker.height,
                    sizeBytes = size,
                    createdAt = timestamp,
                ),
            ),
        )
        db.stickers.markUsed(sticker.id)
        db.conversations.touch(conversationId, sticker.name, 1, timestamp)

        return message.copy(id = id)
    }

    // ------------------------------------------------------------ 内部

    /** 名字撞车时加序号后缀喵。 */
    private suspend fun uniqueName(name: String, exceptId: Long = 0L): String {
        val taken = db.stickers.existingNames().toMutableSet()
        if (exceptId != 0L) {
            // 改名时把自己原来的名字从占用集合里去掉，否则会得到"开心 2"这种自增喵。
            db.stickers.get(exceptId)?.let { taken -= it.name }
        }
        return uniqueNameWithin(name, taken)
    }

    private fun uniqueNameWithin(name: String, taken: MutableSet<String>): String {
        if (name !in taken) return name
        var index = 2
        while ("$name $index" in taken) index++
        return "$name $index"
    }

    private fun readBounds(bytes: ByteArray): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }
        return options.outWidth.coerceAtLeast(0) to options.outHeight.coerceAtLeast(0)
    }

    /** 按魔数判断是不是 PNG；不是就当 JPEG 处理喵。 */
    private fun looksLikePng(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        return signature.indices.all { bytes[it] == signature[it] }
    }
}
