package com.qingyu.app.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 给漏掉逗号的长句补逗号喵。
 *
 * 用户确认过是**模型压根没写**（不是被拆条丢掉的），所以这里是一道
 * 确定性的补救规则喵。测试的重点因此有一半在**不能乱补**上：
 * 补错比不补更糟，而且用户完全不知道那是程序干的喵。
 */
class CommaRepairTest {

    @Test
    fun `语气助词后面补逗号`() {
        assertEquals(
            "今天上班真的好累啊，明天还要早起开会",
            CommaRepair.repair("今天上班真的好累啊明天还要早起开会"),
        )
    }

    @Test
    fun `从句起首词前面补逗号`() {
        // "真是"不能直接跟在主语后面，所以它前面本来就是一个从句边界喵。
        assertEquals(
            "明天还要早起开会，真是受不了",
            CommaRepair.repair("明天还要早起开会真是受不了"),
        )
    }

    @Test
    fun `短句一个字都不补`() {
        // 短句本来就不需要逗号，碰它就是画蛇添足喵。
        assertEquals("好的没问题", CommaRepair.repair("好的没问题"))
        assertEquals("今天好累啊", CommaRepair.repair("今天好累啊"))
    }

    @Test
    fun `已经有标点的片段一动不动`() {
        val text = "还行吧，就是今天上班有点累"
        assertEquals("已经断好的句子不该被再动一次", text, CommaRepair.repair(text))
    }

    @Test
    fun `不会补出连续逗号`() {
        val got = CommaRepair.repair("今天好累啊明天还要早起开会呢后天还得加班")
        assertTrue("不该出现连续逗号：$got", !got.contains("，，"))
    }

    @Test
    fun `末尾不补逗号`() {
        val got = CommaRepair.repair("今天上班真的好累啊明天还要早起开会")
        assertTrue("结尾不该多一个逗号：$got", !got.endsWith("，"))
    }

    @Test
    fun `找不到可靠落点时原样不动`() {
        // 补错比不补更糟：宁可让这句挤一点，也绝不按长度硬插逗号喵。
        val text = "我妈刚才又打电话来催我相亲烦死了我都不知道怎么回她"
        assertEquals("没有落点就不该动它", text, CommaRepair.repair(text))
    }

    @Test
    fun `不会在「了」后面补逗号`() {
        // "少得了辣"里的"了"在词中，补成"能少得了，辣"会把"辣"甩到下一句，意思就歪了喵。
        val got = CommaRepair.repair("你哪次吃饭能少得了辣我都记得清清楚楚的")
        assertTrue("不该在「了」后面补：$got", !got.contains("了，"))
    }

    @Test
    fun `原有的标点一个都不会丢`() {
        val got = CommaRepair.repair("他说：今天好累啊明天还要早起开会！")
        assertTrue("冒号要留着：$got", got.contains("："))
        assertTrue("叹号要留在结尾：$got", got.endsWith("！"))
    }

    @Test
    fun `换行会被当成已有的边界`() {
        // 换行本来就把话分开了，不该在它两边再补逗号喵。
        val got = CommaRepair.repair("今天好累啊\n明天还要早起开会")
        assertTrue("不该出现逗号：$got", !got.contains("，"))
    }

    @Test
    fun `空串不炸`() {
        assertEquals("", CommaRepair.repair(""))
    }
}