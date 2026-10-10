package com.qingyu.app.data.repo

import com.qingyu.app.core.TimeUtil
import com.qingyu.app.data.ChatMessage
import com.qingyu.app.data.Conversation
import com.qingyu.app.data.Favorite
import com.qingyu.app.data.FavoriteKind
import com.qingyu.app.data.MessageRole
import com.qingyu.app.data.db.QingYuDatabase

/** 会话相关的读写编排：新建、摘要维护、删除喵。 */
class ConversationRepository(private val db: QingYuDatabase) {

    suspend fun openForPersona(personaId: Long): Conversation =
        db.conversations.latestOrCreate(personaId)

    suspend fun create(personaId: Long, title: String = "新的对话"): Conversation {
        val id = db.conversations.create(personaId, title)
        return db.conversations.get(id) ?: error("会话创建失败")
    }

    suspend fun list(): List<Conversation> = db.conversations.list()

    suspend fun listForPersona(personaId: Long): List<Conversation> = db.conversations.listForPersona(personaId)

    suspend fun messages(conversationId: Long): List<ChatMessage> = db.messages.list(conversationId)

    suspend fun rename(conversationId: Long, title: String) {
        db.conversations.rename(conversationId, title.ifBlank { "未命名对话" })
    }

    suspend fun setPinned(conversationId: Long, pinned: Boolean) = db.conversations.setPinned(conversationId, pinned)

    suspend fun delete(conversationId: Long) = db.conversations.delete(conversationId)

    suspend fun deleteMessage(messageId: Long) {
        val message = db.messages.get(messageId) ?: return
        db.messages.delete(messageId)
        refreshSummary(message.conversationId)
    }

    /** 重算会话摘要（预览、条数、活动时间喵）。 */
    suspend fun refreshSummary(conversationId: Long) {
        val messages = db.messages.list(conversationId)
        val preview = messages.lastOrNull { it.role != MessageRole.REASONING }?.content?.replace("\n", " ").orEmpty()
        val updatedAt = messages.lastOrNull()?.timestamp ?: TimeUtil.now()
        db.conversations.touch(conversationId, preview, messages.size, updatedAt)
    }

    /** 首条用户消息决定了会话标题喵。 */
    suspend fun ensureTitleFromFirstUserMessage(conversationId: Long) {
        val conversation = db.conversations.get(conversationId) ?: return
        if (conversation.title.isNotBlank() && conversation.title != "新的对话") return
        val firstUser = db.messages.list(conversationId).firstOrNull { it.role == MessageRole.USER } ?: return
        val title = firstUser.content.replace("\n", " ").trim().take(18).ifBlank { "新的对话" }
        db.conversations.rename(conversationId, title)
    }

    /** 删除某条消息及其之后的所有内容，用于"从这里重新生成"喵。 */
    suspend fun truncateFrom(conversationId: Long, messageId: Long) {
        val message = db.messages.get(messageId) ?: return
        db.messages.deleteAfter(conversationId, message.timestamp)
        // deleteAfter 用的是 >=，会把这条本身删掉；如果是助手消息则保留之前的用户消息喵。
        refreshSummary(conversationId)
    }

    /**
     * 收藏一条消息到收藏空间喵。
     *
     * 会先做去重：同一条消息重复收藏时返回已存在的收藏喵。
     */
    suspend fun favoriteMessage(message: ChatMessage, personaName: String, conversationTitle: String): Favorite {
        db.favorites.find(message.id)?.let { return it }
        val favorite = Favorite(
            kind = FavoriteKind.MESSAGE,
            title = message.content.take(24).replace("\n", " "),
            content = message.content,
            personaName = personaName,
            conversationTitle = conversationTitle,
            sourceMessageId = message.id,
            tags = listOf(if (message.role == MessageRole.USER) "我" else personaName),
            createdAt = message.timestamp,
        )
        val id = db.favorites.insert(favorite)
        db.messages.setFavorite(message.id, true)
        return favorite.copy(id = id)
    }

    suspend fun unfavoriteMessage(messageId: Long) {
        db.favorites.find(messageId)?.let { db.favorites.delete(it.id) }
        db.messages.setFavorite(messageId, false)
    }

    /** 把整段会话收藏成一条带完整记录的收藏项喵。 */
    suspend fun favoriteConversation(conversationId: Long, personaName: String): Favorite? {
        val conversation = db.conversations.get(conversationId) ?: return null
        val messages = db.messages.list(conversationId).filter { it.role != MessageRole.REASONING }
        if (messages.isEmpty()) return null
        val transcript = messages.joinToString("\n") { message ->
            val speaker = if (message.role == MessageRole.USER) "我" else personaName
            "$speaker：${message.content}"
        }
        val favorite = Favorite(
            kind = FavoriteKind.CONVERSATION,
            title = conversation.title.ifBlank { "一段对话" },
            content = messages.lastOrNull()?.content.orEmpty(),
            detail = transcript,
            personaName = personaName,
            conversationTitle = conversation.title,
            tags = listOf(personaName),
            createdAt = TimeUtil.now(),
        )
        val id = db.favorites.insert(favorite)
        return favorite.copy(id = id)
    }
}
