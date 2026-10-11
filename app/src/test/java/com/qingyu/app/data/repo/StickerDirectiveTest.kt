package com.qingyu.app.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表情包指令的解析喵。
 *
 * 解析错了不会崩，只会"模型说的话被吃掉"或"表情发不出来"，
 * 所以每种写法都钉住喵。
 */
class StickerDirectiveTest {

    private val available = setOf("开心", "难过", "笑到打滚")

    @Test
    fun `整行指令被剥出来`() {
        val result = StickerDirective.parse(
            "哈哈哈这个太好笑了\n【表情包：笑到打滚】",
            available,
        )
        assertEquals("正文应当只剩那句话", "哈哈哈这个太好笑了", result.text)
        assertEquals("应当解析出一个表情", listOf("笑到打滚"), result.stickerNames)
    }

    @Test
    fun `半角冒号也认`() {
        val result = StickerDirective.parse("【表情包: 开心】", available)
        assertEquals("半角冒号也该认出来", listOf("开心"), result.stickerNames)
        assertTrue("剥掉后正文应当为空", result.text.isEmpty())
    }

    @Test
    fun `指令可以出现在中间`() {
        val result = StickerDirective.parse(
            "先是这样\n【表情包：开心】\n然后又那样",
            available,
        )
        assertEquals("指令行应当被整行剥掉", "先是这样\n然后又那样", result.text)
        assertEquals(listOf("开心"), result.stickerNames)
    }

    @Test
    fun `多个指令按顺序都收下`() {
        val result = StickerDirective.parse(
            "【表情包：开心】\n【表情包：难过】",
            available,
        )
        assertEquals("两个都该按顺序收下", listOf("开心", "难过"), result.stickerNames)
    }

    @Test
    fun `名字不在可用列表里就当普通文字`() {
        // 模型有时会编一个名字出来；那时留着这行会让人看到一句莫名其妙的话喵。
        val result = StickerDirective.parse("【表情包：不存在的表情】", available)
        assertEquals("编出来的名字不该被当成指令", "【表情包：不存在的表情】", result.text)
        assertTrue("也不该发出任何表情", result.stickerNames.isEmpty())
    }

    @Test
    fun `混在句子中间的不算指令`() {
        // 只认整行：混在句中剥离后容易留下半句话，读起来怪喵。
        val result = StickerDirective.parse("我觉得【表情包：开心】挺好的", available)
        assertEquals("句中出现的应当原样保留", "我觉得【表情包：开心】挺好的", result.text)
        assertTrue(result.stickerNames.isEmpty())
    }

    @Test
    fun `没有指令时原样返回`() {
        val result = StickerDirective.parse("就是一句普通的话", available)
        assertEquals("普通文本不该被动过", "就是一句普通的话", result.text)
        assertTrue(result.stickerNames.isEmpty())
    }

    @Test
    fun `空文本不崩`() {
        val result = StickerDirective.parse("", available)
        assertTrue("空串应当返回空正文", result.text.isEmpty())
        assertTrue("也不该有表情", result.stickerNames.isEmpty())
    }

    @Test
    fun `名字两侧的空白会被去掉`() {
        val result = StickerDirective.parse("【表情包：  开心  】", available)
        assertEquals("应当容忍名字两侧的空格", listOf("开心"), result.stickerNames)
    }

    @Test
    fun `剥掉指令后不留多余空行`() {
        val result = StickerDirective.parse(
            "说点什么\n\n【表情包：开心】\n",
            available,
        )
        assertEquals("首尾空行应当收掉", "说点什么", result.text)
    }

    @Test
    fun `没有可用表情时不注入说明`() {
        assertTrue("一个表情都没有时不该给模型这段说明", StickerDirective.instruction(emptyList()).isEmpty())
    }

    @Test
    fun `说明里列出可用表情并强调是辅助`() {
        val text = StickerDirective.instruction(listOf("开心", "难过"))
        assertTrue("要列出可用表情：$text", text.contains("开心") && text.contains("难过"))
        assertTrue("要写明怎么发：$text", text.contains("【表情包：名字】"))
        assertTrue("要强调是辅助表达：$text", text.contains("辅助"))
        assertTrue("要说明只发表情包才专门回应：$text", text.contains("只发"))
    }

    @Test
    fun `表情很多时只列一部分并说明还有多少`() {
        // 这段是每一轮都要塞进系统提示词的，一次导入几百张就会白烧 token喵。
        val many = (1..200).map { "表情$it" }
        val text = StickerDirective.instruction(many)
        assertTrue("应当只列前 ${StickerDirective.MAX_LISTED} 个", text.contains("表情${StickerDirective.MAX_LISTED}"))
        assertTrue("不该列出第 ${StickerDirective.MAX_LISTED + 1} 个", !text.contains("表情${StickerDirective.MAX_LISTED + 1}、"))
        assertTrue("要说明还有多少个没列：$text", text.contains("还有 ${200 - StickerDirective.MAX_LISTED} 个"))
    }

    @Test
    fun `没被列出来的表情依然能解析`() {
        // 截断只影响"告诉模型有哪些"，不该影响"模型说了就认"喵。
        val many = (1..200).map { "表情$it" }
        val available = many.toSet()
        val result = StickerDirective.parse("【表情包：表情200】", available)
        assertEquals("第 200 个虽然没被列出来，也该认得", listOf("表情200"), result.stickerNames)
    }
}
