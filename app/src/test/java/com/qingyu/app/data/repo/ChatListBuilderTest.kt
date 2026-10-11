package com.qingyu.app.data.repo

import com.qingyu.app.data.Conversation
import com.qingyu.app.data.Persona
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主界面对话列表的配对与排序喵。
 *
 * 这里错了不会崩，只会"顺序莫名"或"刚聊过的角色不见了"，
 * 从界面上很难判断是不是 bug，所以规则必须钉住喵。
 *
 * 注意主界面的口径已经变了：**只列有对话、且没被移除的角色**。
 * 从没聊过的人属于通讯录，不再是主界面的职责喵。
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
        hidden: Boolean = false,
        pinned: Boolean = false,
    ) = Conversation(
        id = id,
        personaId = personaId,
        title = "标题",
        createdAt = updatedAt,
        updatedAt = updatedAt,
        lastMessagePreview = preview,
        messageCount = messageCount,
        hidden = hidden,
        pinned = pinned,
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
    fun `从没聊过的角色不进主界面`() {
        // 没聊过的人属于通讯录。微信的聊天列表也不会列出从来没聊过的人喵。
        val list = buildChatList(
            personas = listOf(persona(1, "老朋友"), persona(2, "刚建的")),
            conversations = listOf(conversation(10, 1, updatedAt = 100L)),
        )
        assertEquals(listOf("老朋友"), list.map { it.persona.name })
    }

    @Test
    fun `空对话不算聊过`() {
        // 进角色时 latestOrCreate 会建出空对话；点进去看一眼再退出不该多出一行喵。
        val list = buildChatList(
            personas = listOf(persona(1, "甲")),
            conversations = listOf(conversation(10, 1, updatedAt = 100L, messageCount = 0)),
        )
        assertEquals(emptyList<Any>(), list)
    }

    @Test
    fun `被移除的对话不出现在主界面`() {
        // 「删除对话」= 隐藏：角色与消息都还在，只是不在主界面列出来喵。
        val list = buildChatList(
            personas = listOf(persona(1, "甲"), persona(2, "乙")),
            conversations = listOf(
                conversation(10, 1, updatedAt = 100L, hidden = true),
                conversation(20, 2, updatedAt = 200L),
            ),
        )
        assertEquals(listOf("乙"), list.map { it.persona.name })
    }

    @Test
    fun `全部被移除时主界面为空`() {
        val list = buildChatList(
            personas = listOf(persona(1, "甲")),
            conversations = listOf(conversation(10, 1, updatedAt = 100L, hidden = true)),
        )
        assertEquals(emptyList<Any>(), list)
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
    fun `最近的那个被移除时整个角色都从主界面消失`() {
        // 先取最近那段、再看它是否被移除。顺序反了的话更早的残留会顶上来，
        // 看起来就像「删除对话」没生效喵。
        val list = buildChatList(
            personas = listOf(persona(1, "甲")),
            conversations = listOf(
                conversation(10, 1, updatedAt = 100L, preview = "旧的"),
                conversation(11, 1, updatedAt = 900L, hidden = true),
            ),
        )
        assertEquals(emptyList<Any>(), list)
    }

    @Test
    fun `对话属于已删除的角色时被忽略`() {
        val list = buildChatList(
            personas = listOf(persona(1, "甲")),
            conversations = listOf(conversation(99, personaId = 404, updatedAt = 999L)),
        )
        assertEquals(emptyList<Any>(), list)
    }

    @Test
    fun `空列表不报错`() {
        assertTrue(buildChatList(emptyList(), emptyList()).isEmpty())
    }

    // ---------------------------------------------------------------- 置顶

    @Test
    fun `置顶的排在所有普通对话之前`() {
        // 即使置顶那条很久没说话，也要压在最新说话的普通对话上面喵。
        val list = buildChatList(
            personas = listOf(persona(1, "很久没聊的"), persona(2, "刚聊过的")),
            conversations = listOf(
                conversation(10, 1, updatedAt = 100L, pinned = true),
                conversation(20, 2, updatedAt = 9_000L),
            ),
        )
        assertEquals(listOf("很久没聊的", "刚聊过的"), list.map { it.persona.name })
    }

    @Test
    fun `置顶组内仍按最近说过排序`() {
        val list = buildChatList(
            personas = listOf(persona(1, "甲"), persona(2, "乙"), persona(3, "丙")),
            conversations = listOf(
                conversation(10, 1, updatedAt = 100L, pinned = true),
                conversation(20, 2, updatedAt = 300L, pinned = true),
                conversation(30, 3, updatedAt = 500L),
            ),
        )
        // 两条置顶内部：乙(300) 在 甲(100) 之前；普通的丙排在最后喵。
        assertEquals(listOf("乙", "甲", "丙"), list.map { it.persona.name })
    }

    @Test
    fun `取消置顶后回到普通组的正确位置`() {
        val personas = listOf(persona(1, "甲"), persona(2, "乙"))
        val pinnedList = buildChatList(
            personas = personas,
            conversations = listOf(
                conversation(10, 1, updatedAt = 100L, pinned = true),
                conversation(20, 2, updatedAt = 9_000L),
            ),
        )
        assertEquals(listOf("甲", "乙"), pinnedList.map { it.persona.name })

        // 取消置顶之后，它该按活动时间落回乙的后面喵。
        val unpinnedList = buildChatList(
            personas = personas,
            conversations = listOf(
                conversation(10, 1, updatedAt = 100L, pinned = false),
                conversation(20, 2, updatedAt = 9_000L),
            ),
        )
        assertEquals(listOf("乙", "甲"), unpinnedList.map { it.persona.name })
    }

    @Test
    fun `被移除的置顶对话照样不出现在主界面`() {
        // 隐藏优先于置顶：移除过的对话不该因为被置顶而重新冒出来喵。
        val list = buildChatList(
            personas = listOf(persona(1, "甲"), persona(2, "乙")),
            conversations = listOf(
                conversation(10, 1, updatedAt = 9_000L, hidden = true, pinned = true),
                conversation(20, 2, updatedAt = 100L),
            ),
        )
        assertEquals(listOf("乙"), list.map { it.persona.name })
    }
}
