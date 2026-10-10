package com.qingyu.app.data.archive

import com.qingyu.app.data.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入解析的回归测试喵。
 *
 * 用例里的第一段 JSON 是**从一份早期备份里原样抄下来的**喵，
 * 它同时钉住了三件事：role 的取值（含 reasoning）、时间戳是毫秒整数喵、
 * 以及"思考消息带 reasoningDuration"。这三条一旦变动，导入就会静默丢内容喵。
 */
class ChatImportParserTest {

    /** `聊天记录_苏婉_2026-10-06_12-30.json` 这类早期备份的真实内容喵。 */
    private val legacyExport = """
        [{"content":"在吗","role":"user","timestamp":1791261040298},
        {"content":"（他这么突然喊我，是刚忙完还是又遇到什么想跟我说的事了。）","reasoningDuration":0,"role":"reasoning","timestamp":1791261045780},
        {"content":"在啊，怎么啦？","role":"assistant","timestamp":1791261046417},
        {"content":"刚忙完手头的事，正想着你呢。","role":"assistant","timestamp":1791261047425},
        {"content":"中午饭吃了吗？","role":"assistant","timestamp":1791261048442}]
    """.trimIndent()

    @Test
    fun `能解析早期备份的裸数组`() {
        val parsed = ChatImportParser.parse(legacyExport)
        assertEquals("应当解析出 5 条消息", 5, parsed.messages.size)
        assertEquals(MessageRole.USER, parsed.messages[0].role)
        assertEquals("在吗", parsed.messages[0].content)
        assertEquals(1791261040298L, parsed.messages[0].timestamp)
        assertEquals(MessageRole.REASONING, parsed.messages[1].role)
        assertEquals(MessageRole.ASSISTANT, parsed.messages[2].role)
        assertEquals("中午饭吃了吗？", parsed.messages.last().content)
    }

    @Test
    fun `消息顺序保持文件里的先后`() {
        val parsed = ChatImportParser.parse(legacyExport)
        val timestamps = parsed.messages.map { it.timestamp }
        assertEquals(timestamps.sorted(), timestamps)
    }

    @Test
    fun `没有时间戳的消息也能导入`() {
        val parsed = ChatImportParser.parse("""[{"content":"喂","role":"user"}]""")
        assertEquals(1, parsed.messages.size)
        assertTrue("应当补一个当前时间", parsed.messages[0].timestamp > 0L)
    }

    @Test
    fun `缺少内容的条目会被丢掉而不是崩掉`() {
        val parsed = ChatImportParser.parse(
            """[{"content":"留下","role":"user"},{"role":"assistant"},{"content":"   ","role":"user"}]""",
        )
        assertEquals(1, parsed.messages.size)
        assertEquals("留下", parsed.messages[0].content)
    }

    @Test
    fun `内容为 JSON null 的条目被丢掉，不会导入一条文本是 null 的消息`() {
        val parsed = ChatImportParser.parse(
            """[{"content":null,"role":"user","timestamp":1},{"content":"真的在","role":"user","timestamp":2}]""",
        )
        assertEquals(1, parsed.messages.size)
        assertEquals("真的在", parsed.messages[0].content)
    }

    @Test
    fun `内容被字面化成字符串 null 的条目同样丢掉`() {
        val parsed = ChatImportParser.parse(
            """[{"content":"null","role":"assistant","timestamp":1},{"content":"正常","role":"assistant","timestamp":2}]""",
        )
        assertEquals(1, parsed.messages.size)
        assertEquals("正常", parsed.messages[0].content)
    }

    @Test
    fun `角色名与标题为 null 时不会变成文本 null`() {
        val parsed = ChatImportParser.parse(
            """
            {"conversation":{"title":null},"persona":{"name":null,"tagline":"null"},
             "messages":[{"content":"在吗","role":"user"}]}
            """.trimIndent(),
        )
        assertNull("标题是 null 时应当当作没给", parsed.title)
        assertNull("角色名是 null 时应当当作没给", parsed.personaName)
        assertNull("简介是 null 时应当当作没给", parsed.personaTagline)
    }

    @Test
    fun `未知 role 退化成 assistant 而不是失败`() {
        val parsed = ChatImportParser.parse("""[{"content":"你好","role":"unknown_role"}]""")
        assertEquals(MessageRole.ASSISTANT, parsed.messages[0].role)
    }

    @Test
    fun `能解析带包装的对象并读出角色与标题`() {
        val json = """
            {
              "schema": "qingyu.chat.v1",
              "conversation": {"title": "傍晚那段"},
              "persona": {"name": "苏婉", "avatarEmoji": "🌙", "tagline": "邻家女孩"},
              "messages": [
                {"content": "在吗", "role": "user", "timestamp": 1791261040298},
                {"content": "在啊", "role": "assistant", "timestamp": 1791261046417}
              ]
            }
        """.trimIndent()
        val parsed = ChatImportParser.parse(json)
        assertEquals(2, parsed.messages.size)
        assertEquals("苏婉", parsed.personaName)
        assertEquals("🌙", parsed.personaEmoji)
        assertEquals("邻家女孩", parsed.personaTagline)
        assertEquals("傍晚那段", parsed.title)
    }

    @Test
    fun `chatHistory 与 history 也能识别`() {
        val a = ChatImportParser.parse("""{"chatHistory":[{"content":"a","role":"user"}]}""")
        val b = ChatImportParser.parse("""{"history":[{"content":"b","role":"user"}]}""")
        assertEquals("a", a.messages[0].content)
        assertEquals("b", b.messages[0].content)
    }

    @Test
    fun `字符串时间戳沿用原本格式解析`() {
        val parsed = ChatImportParser.parse(
            """[{"content":"早","role":"user","time":"2026-10-06 15:54"}]""",
        )
        assertTrue("应当解析成毫秒时间戳", parsed.messages[0].timestamp > 1_700_000_000_000L)
    }

    @Test
    fun `不同字段名的内容都能读到`() {
        val parsed = ChatImportParser.parse(
            """
            [
              {"text": "甲", "sender": "user"},
              {"message": "乙", "type": "assistant"},
              {"msg": "丙", "from": "user"}
            ]
            """.trimIndent(),
        )
        assertEquals(listOf("甲", "乙", "丙"), parsed.messages.map { it.content })
    }

    @Test
    fun `从文件名反推角色名`() {
        assertEquals("苏婉", ChatImportParser.personaNameFromFileName("聊天记录_苏婉_2026-10-06_15-54.json"))
        assertEquals("苏婉", ChatImportParser.personaNameFromFileName("C:/Download/聊天记录_苏婉_2026-10-06_15-54.json"))
        assertEquals("临舟", ChatImportParser.personaNameFromFileName("聊天记录_临舟_2026-10-06_12-30.json"))
    }

    @Test
    fun `百分号转义的文件名也能解出角色名`() {
        val encoded = "%E8%81%8A%E5%A4%A9%E8%AE%B0%E5%BD%95_%E8%8B%8F%E5%A9%89_2026-10-06_15-54.json"
        assertEquals("苏婉", ChatImportParser.personaNameFromFileName(encoded))
    }

    @Test
    fun `不符合命名习惯的文件名不猜角色`() {
        assertNull(ChatImportParser.personaNameFromFileName("backup.json"))
        assertNull(ChatImportParser.personaNameFromFileName(null))
    }

    @Test
    fun `空文件与非法 JSON 给出可读的错误`() {
        val empty = runCatching { ChatImportParser.parse("   ") }.exceptionOrNull()
        assertTrue(empty is ChatImportParser.ParseException)
        assertEquals("文件是空的", empty?.message)

        val broken = runCatching { ChatImportParser.parse("{ 这不是 json ]") }.exceptionOrNull()
        assertTrue(broken is ChatImportParser.ParseException)
        assertTrue(
            "错误信息应当说明是 JSON 问题，实际：${broken?.message}",
            broken?.message.orEmpty().contains("JSON"),
        )
    }

    @Test
    fun `没有消息的包装对象会被拒绝`() {
        val error = runCatching { ChatImportParser.parse("""{"messages":[]}""") }.exceptionOrNull()
        assertTrue(error is ChatImportParser.ParseException)
    }
}
