package com.qingyu.app.data.repo

import com.qingyu.app.data.Conversation
import com.qingyu.app.data.Persona
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页角色列表的配对与排序喵。
 *
 * 这里错了不会崩，只会"顺序莫名"或"刚建的角色不见了"，
 * 从界面上很难判断是不是 bug，所以规则必须钉住喵。
 */
class ChatListBuilderTest {

    private fun persona(id: Long, name: String, createdAt: Long = 0L) = Persona(
        id = id,
        name = name,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun conversation(
        id: Long,
        personaId: Long,
        updatedAt: Long,
        messageCount: Int = 4,
        preview: String = "",
    ) = Conversation(
        id = id,
        personaId = personaId,
        title = "标题",
        createdAt = updatedAt,
        updatedAt = updatedAt,
        lastMessagePreview = preview,
        messageCount = messageCount,
    )

    @Test
    fun `最近说过的排在最前`() {
        val list = buildChatList(
            personas = listOf(persona(1, "甲"), persona(2, "乙"), persona(3, "丙")),
            conversations = listOf(
                conversation(10, 1, updatedAt = 100L),
                conversation(20, 2, updatedAt = 300L),
                conversation(30, 3, updatedAt = 200L),
            ),
        )
        assertEquals(listOf("乙", "丙", "甲"), list.map { it.persona.name })
    }

    @Test
    fun `没聊过的角色也在列表里且能排到前面`() {
        // 刚建的角色（createdAt 最新）应当立刻出现在最前面——
        // 这正是"新建角色后必须再建对话才看得到"那个 bug 的反面喵。
        val list = buildChatList(
            personas = listOf(
                persona(1, "老朋友", createdAt = 10L),
                persona(2, "刚建的", createdAt = 500L),
            ),
            conversations = listOf(conversation(10, 1, updatedAt = 100L)),
        )
        assertEquals(listOf("刚建的", "老朋友"), list.map { it.persona.name })
        assertTrue("刚建的角色还没有对话", list.first().conversation == null)
        assertTrue("刚建的角色应标记为没聊过", list.first().neverChatted)
    }

    @Test
    fun `有对话但一条消息都没有也算没聊过`() {
        // openConversation 会建出空对话，这不该让列表显示一个空预览喵。
        val list = buildChatList(
            personas = listOf(persona(1, "甲")),
            conversations = listOf(conversation(10, 1, updatedAt = 100L, messageCount = 0)),
        )
        assertTrue(list.first().neverChatted)
    }

    @Test
    fun `同一个角色有多个残留对话时取最近活动的那个`() {
        // 老库没迁移干净时可能出现多条，取最近的和 latestOrCreate 保持一致喵。
        val list = buildChatList(
            personas = listOf(persona(1, "甲")),
            conversations = listOf(
                conversation(10, 1, updatedAt = 100L, preview = "旧的"),
                conversation(11, 1, updatedAt = 900L, preview = "新的"),
            ),
        )
        assertEquals(1, list.size)
        assertEquals(11L, list.first().conversation?.id)
        assertEquals("新的", list.first().conversation?.lastMessagePreview)
    }

    @Test
    fun `没有对话的角色 conversation 为 null 且不报错`() {
        val list = buildChatList(
            personas = listOf(persona(1, "甲", createdAt = 50L)),
            conversations = emptyList(),
        )
        assertEquals(1, list.size)
        assertNull(list.first().conversation)
        assertFalse(list.first().neverChatted.not())
        // 排序时间回退到角色创建时间，不能是 0 或负数喵。
        assertEquals(50L, list.first().sortTime)
    }

    @Test
    fun `对话属于已删除的角色时被忽略`() {
        val list = buildChatList(
            personas = listOf(persona(1, "甲", createdAt = 10L)),
            conversations = listOf(conversation(99, personaId = 404, updatedAt = 999L)),
        )
        assertEquals(1, list.size)
        assertNull("孤儿对话不该挂到别人身上", list.first().conversation)
    }

    @Test
    fun `空列表不报错`() {
        assertTrue(buildChatList(emptyList(), emptyList()).isEmpty())
    }
}
