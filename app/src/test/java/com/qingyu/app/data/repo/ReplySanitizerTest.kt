package com.qingyu.app.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回复里被抄回来的"系统旁注"清洗喵。
 *
 * 用户报的两种形态都在这儿钉住喵：
 * - `（你发来一个表情包（表情包名字））` —— 连模板占位符一起抄了下来；
 * - `用户发的信息只有一条` —— 元信息旁白，在向用户播报"我读到了几条消息"。
 *
 * 另一半测试同样是重点：**不能误杀**。角色真说的话里也有括号，
 * `（你发的表情包好可爱）`、`（笑）` 都必须原样留着喵。
 */
class ReplySanitizerTest {

    @Test
    fun `对方发的表情旁注被抄回来时整行清掉`() {
        assertEquals("", ReplySanitizer.sanitize("（对方发来一个表情包：开心）"))
    }

    @Test
    fun `自己发的表情旁注被抄回来时也清掉`() {
        assertEquals("", ReplySanitizer.sanitize("（你发了 一个表情包：笑到打滚）"))
    }

    @Test
    fun `用户报的那种连占位符一起抄的形态要清掉`() {
        // 原文里带一个换行，模型把模板变量另起了一层括号喵。
        val raw = "（你发来一个表情包\n（表情包名字））"
        assertEquals("", ReplySanitizer.sanitize(raw))
    }

    @Test
    fun `元信息旁白要清掉`() {
        assertEquals("", ReplySanitizer.sanitize("用户发的信息只有一条"))
    }

    @Test
    fun `元信息旁白的另一种语序也要清掉`() {
        assertEquals("", ReplySanitizer.sanitize("只有一条消息"))
    }

    @Test
    fun `图片与红包旁注要清掉`() {
        assertEquals("", ReplySanitizer.sanitize("（图片内容：一只猫趴在窗台上）"))
        assertEquals("", ReplySanitizer.sanitize("（第 2 张图片：一只猫）"))
        assertEquals("", ReplySanitizer.sanitize("（发了一个 5.00 元的红包）"))
        assertEquals("", ReplySanitizer.sanitize("（用户发来了一张图片，但还没有配置识图模型，你看不到内容。请如实说明。）"))
    }

    @Test
    fun `解析失败的发表情包指令也要清掉`() {
        // StickerDirective 认不出名字时会把这行原样留下（它宁可留着也不吞），
        // 但对用户来说那仍然是个不该出现的字段喵。
        assertEquals("", ReplySanitizer.sanitize("【表情包：笑到打滚】"))
    }

    @Test
    fun `正文里带冒号的旁注被删掉而句子其余部分留着`() {
        val got = ReplySanitizer.sanitize("哈哈哈（对方发来一个表情包：开心）真好笑")
        assertEquals("哈哈哈真好笑", got)
    }

    @Test
    fun `只清掉旁注那一行正文照常保留`() {
        val raw = "今天有点累\n（对方发来一个表情包：叹气）\n不过还好"
        assertEquals("今天有点累\n不过还好", ReplySanitizer.sanitize(raw))
    }

    // ------------------------------------------------------- 下面全是"不能误杀"

    @Test
    fun `角色真说的括号评论不能被吃掉`() {
        // 这是最要紧的反例喵：'表情包'后面跟着别的话，就不是旁注而是人话喵。
        val raw = "（你发的表情包好可爱）"
        assertEquals(raw, ReplySanitizer.sanitize(raw))
    }

    @Test
    fun `普通括号动作不能被吃掉`() {
        assertEquals("（笑）", ReplySanitizer.sanitize("（笑）"))
        assertEquals("好呀（点头）", ReplySanitizer.sanitize("好呀（点头）"))
    }

    @Test
    fun `第二人称的数量说法不能被吃掉`() {
        // "你发的消息我看了"是真话；数量旁白只认第三人称主语喵。
        val raw = "你发的消息我看了"
        assertEquals(raw, ReplySanitizer.sanitize(raw))
    }

    @Test
    fun `正常回复原样通过`() {
        val raw = "今天上班好累啊，\n明天还要早起开会"
        assertEquals(raw, ReplySanitizer.sanitize(raw))
    }

    @Test
    fun `空串不炸`() {
        assertEquals("", ReplySanitizer.sanitize(""))
    }

    @Test
    fun `判定是整行语义而不是碰巧包含关键词`() {
        assertTrue(ReplySanitizer.isSystemNote("（对方发来一个表情包：开心）"))
        assertFalse(ReplySanitizer.isSystemNote("（你发的表情包好可爱）"))
        assertFalse(ReplySanitizer.isSystemNote("我今天发表情包了"))
    }
}