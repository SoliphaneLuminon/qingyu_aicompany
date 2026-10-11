package com.qingyu.app.data.repo

import com.qingyu.app.core.TimeUtil
import com.qingyu.app.data.ChatListEntry
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

    /**
     * 首页的角色列表喵。
     *
     * 只发两条查询（角色一次、对话一次）后在内存里配对，而不是每个角色查一次——
     * 角色数量不多，但按角色逐个查库是典型的 N+1，划不来喵。
     */
    suspend fun chatList(): List<ChatListEntry> =
        buildChatList(db.personas.list(), db.conversations.list())

    suspend fun list(): List<Conversation> = db.conversations.list()

    suspend fun messages(conversationId: Long): List<ChatMessage> = db.messages.list(conversationId)

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

/**
 * 把角色与它们各自的对话配成首页列表喵。
 *
 * 抽成顶层纯函数是为了能直接单测：排序与"没聊过怎么办"这两件事
 * 一旦错了，症状是"列表顺序莫名"或"新角色不见了"，很难从界面上看出来喵。
 *
 * 规则喵：
 * - 一个角色配它**最近活动**的那个对话；库里理论上每人只剩一个，
 *   取最近的是为了容忍老库没迁移干净的残留喵
 * - 排序照微信：**最近说过的排最前**；没聊过的用角色创建时间参与排序喵
 * - 没聊过的角色照样出现在列表里，`conversation` 为 null喵
 */
internal fun buildChatList(
    personas: List<com.qingyu.app.data.Persona>,
    conversations: List<Conversation>,
): List<ChatListEntry> {
    val byPersona = conversations.groupBy { it.personaId }
    return personas
        .map { persona ->
            ChatListEntry(
                persona = persona,
                conversation = byPersona[persona.id]?.maxByOrNull { it.updatedAt },
            )
        }
        .sortedByDescending { it.sortTime }
}
