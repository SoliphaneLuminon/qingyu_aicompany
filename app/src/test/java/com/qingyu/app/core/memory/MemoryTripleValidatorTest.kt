package com.qingyu.app.core.memory

import com.qingyu.app.data.MemoryCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提炼结果强类型校验的回归测试喵。
 *
 * 这条防线拦的是模型"差不多对"的输出——`null`、字符串数字、越界重要度喵、
 * 以及在 JSON 外面包一层寒暄。这里的约定是"校验失败直接丢弃"喵，
 * 所以这里所有非法输入都期望**整条消失**，而不是被好心地修一修喵。
 */
class MemoryTripleValidatorTest {

    @Test
    fun `老数组格式可以解析`() {
        val raw = """
            [{"category":"PREFERENCE","content":"用户喜欢喝美式","importance":4}]
        """.trimIndent()
        val parsed = MemoryTripleValidator.parse(raw)
        assertEquals(1, parsed.size)
        assertEquals(MemoryCategory.PREFERENCE, parsed.single().category)
        assertEquals("用户喜欢喝美式", parsed.single().statement)
    }

    @Test
    fun `文档里的对象格式可以解析`() {
        val raw = """
            {"has_memory":true,"memories":[{"subject":"用户","predicate":"喜欢","object":"咖啡","context":"早上"}]}
        """.trimIndent()
        val parsed = MemoryTripleValidator.parse(raw)
        assertEquals(1, parsed.size)
        val memory = parsed.single()
        assertTrue(memory.hasTriple)
        assertEquals("用户喜欢咖啡", memory.statement)
    }

    @Test
    fun `夹在散文里的 JSON 也能抠出来`() {
        val raw = "好的，我提取结果如下：\n[{\"content\":\"用户养猫\"}]\n希望有帮助！"
        val parsed = MemoryTripleValidator.parse(raw)
        assertEquals(1, parsed.size)
        assertEquals("用户养猫", parsed.single().statement)
    }

    @Test
    fun `has_memory 为假时返回空`() {
        val raw = """{"has_memory":false}"""
        assertEquals(emptyList<ExtractedMemory>(), MemoryTripleValidator.parse(raw))
    }

    @Test
    fun `content 为 null 时整条丢弃`() {
        val raw = """[{"content":null,"category":"PROFILE"}]"""
        assertEquals(emptyList<ExtractedMemory>(), MemoryTripleValidator.parse(raw))
    }

    @Test
    fun `缺少正文与三元组时丢弃`() {
        val raw = """[{"category":"PROFILE"}]"""
        assertEquals(emptyList<ExtractedMemory>(), MemoryTripleValidator.parse(raw))
    }

    @Test
    fun `只有 content 也能成立`() {
        val raw = """[{"content":"用户是设计师"}]"""
        val parsed = MemoryTripleValidator.parse(raw)
        assertEquals(1, parsed.size)
        assertEquals("用户是设计师", parsed.single().statement)
        // 三元组缺失时不硬凑喵
        assertTrue(!parsed.single().hasTriple)
    }

    @Test
    fun `emotional_weight 是字符串数字也能收`() {
        val raw = """[{"content":"用户怕黑","emotional_weight":"0.8"}]"""
        val parsed = MemoryTripleValidator.parse(raw)
        assertEquals(0.8, parsed.single().emotionalWeight, 0.0001)
    }

    @Test
    fun `emotional_weight 不是数字时丢弃`() {
        val raw = """[{"content":"用户怕黑","emotional_weight":"很高"}]"""
        assertEquals(emptyList<ExtractedMemory>(), MemoryTripleValidator.parse(raw))
    }

    @Test
    fun `emotional_weight 超出范围被夹紧`() {
        val raw = """[{"content":"用户怕黑","emotional_weight":9.0}]"""
        assertEquals(1.0, MemoryTripleValidator.parse(raw).single().emotionalWeight, 0.0001)
    }

    @Test
    fun `importance 越界时丢弃`() {
        val raw = """[{"content":"用户怕黑","importance":99}]"""
        assertEquals(emptyList<ExtractedMemory>(), MemoryTripleValidator.parse(raw))
    }

    @Test
    fun `importance 是数字字符串可以收`() {
        val raw = """[{"content":"用户怕黑","importance":"5"}]"""
        assertEquals(5, MemoryTripleValidator.parse(raw).single().importance)
    }

    @Test
    fun `缺失的可选字段用默认值`() {
        val raw = """[{"content":"用户怕黑"}]"""
        val memory = MemoryTripleValidator.parse(raw).single()
        assertEquals(0.5, memory.emotionalWeight, 0.0001)
        assertEquals(3, memory.importance)
        assertEquals("", memory.context)
    }

    @Test
    fun `同一批里重复的只留一条`() {
        val raw = """
            [{"content":"用户喜欢咖啡"},{"content":"用户喜欢咖啡"},{"content":"用户喜欢跑步"}]
        """.trimIndent()
        val parsed = MemoryTripleValidator.parse(raw)
        assertEquals(2, parsed.size)
    }

    @Test
    fun `条目数量有上限`() {
        // 夹具正文必须长于 MIN_STATEMENT_LENGTH，否则会被长度门槛拦掉喵，
        // 这个用例就退化成在测长度而不是测条数上限喵。
        val entries = (1..50).joinToString(",") { """{"content":"用户记住的第${it}件事"}""" }
        val parsed = MemoryTripleValidator.parse("[$entries]")
        assertEquals(MemoryTripleValidator.MAX_ITEMS, parsed.size)
    }

    @Test
    fun `整段不是 JSON 时返回空而不是崩`() {
        assertEquals(emptyList<ExtractedMemory>(), MemoryTripleValidator.parse("我不是 JSON"))
    }

    @Test
    fun `空字符串返回空`() {
        assertEquals(emptyList<ExtractedMemory>(), MemoryTripleValidator.parse(""))
    }

    @Test
    fun `importance = 10 合法`() {
        val raw = """[{"content":"用户是公司创始人","importance":10}]"""
        assertEquals(10, MemoryTripleValidator.parse(raw).single().importance)
    }

    @Test
    fun `importance = 1 合法`() {
        val raw = """[{"content":"用户路过一家店","importance":1}]"""
        assertEquals(1, MemoryTripleValidator.parse(raw).single().importance)
    }

    @Test
    fun `过短的陈述被丢弃`() {
        // "嗯嗯好"（3 个字符）连一个完整的事实都构不成，达不到最低长度门槛喵。
        val raw = """[{"content":"嗯嗯好"}]"""
        assertEquals(emptyList<ExtractedMemory>(), MemoryTripleValidator.parse(raw))
    }

    @Test
    fun `刚好够短的陈述可以接受`() {
        // "用户养猫" 正好 4 个字符，卡在门槛上——这是最短的"具体事实"喵。
        val raw = """[{"content":"用户养猫"}]"""
        assertEquals(1, MemoryTripleValidator.parse(raw).size)
    }
}
