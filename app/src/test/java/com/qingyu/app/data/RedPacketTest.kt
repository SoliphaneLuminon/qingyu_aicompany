package com.qingyu.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 红包的序列化与送给模型的那句话喵。
 *
 * 这两件事都必须钉住喵：
 * - **payload 往返**：界面画红包卡片用的是 payload 里的金额，
 *   一旦序列化/反序列化不对称，红包就会显示成 0 元或干脆退化成纯文本喵
 * - **给模型的文案**：这是角色"看到"红包的唯一途径，
 *   措辞里必须同时出现金额与留言，否则角色收到的信息是残缺的喵
 */
class RedPacketTest {

    @Test
    fun `payload 往返保住金额与留言`() {
        val original = RedPacket(amount = 8.88, wish = "请你喝奶茶")
        val restored = RedPacket.fromPayload(RedPacket.toPayload(original))
        assertEquals("金额应当原样还原", 8.88, restored?.amount ?: 0.0, 0.001)
        assertEquals("留言应当原样还原", "请你喝奶茶", restored?.wish)
    }

    @Test
    fun `整数金额往返不丢精度`() {
        val restored = RedPacket.fromPayload(RedPacket.toPayload(RedPacket(amount = 100.0)))
        assertEquals("整数金额应当原样还原", 100.0, restored?.amount ?: 0.0, 0.001)
    }

    @Test
    fun `留言里有引号与逗号也不会串味`() {
        // 用 JSON 而不是自定义分隔符，正是为了这种留言喵。
        val wish = "生日快乐，\"永远\"开心"
        val restored = RedPacket.fromPayload(RedPacket.toPayload(RedPacket(amount = 52.0, wish = wish)))
        assertEquals("带引号与逗号的留言应当完整还原", wish, restored?.wish)
    }

    @Test
    fun `坏 payload 返回 null 而不是崩`() {
        assertNull("空串应当返回 null", RedPacket.fromPayload(""))
        assertNull("乱码应当返回 null", RedPacket.fromPayload("这不是 json"))
        assertNull("缺金额应当返回 null", RedPacket.fromPayload("""{"wish":"你好"}"""))
        assertNull("金额为 0 应当返回 null", RedPacket.fromPayload("""{"amount":0,"wish":""}"""))
    }

    @Test
    fun `金额显示整数不带小数点`() {
        assertEquals("整数不该显示小数位", "100", RedPacket.formatAmount(100.0))
        assertEquals("小数保留两位", "8.88", RedPacket.formatAmount(8.88))
        assertEquals("一位小数补成两位", "5.20", RedPacket.formatAmount(5.2))
    }

    @Test
    fun `给模型的文案同时含金额与留言`() {
        val withWish = RedPacket(amount = 8.88, wish = "请你喝奶茶").toModelText()
        assertTrue("文案要含金额：$withWish", withWish.contains("8.88"))
        assertTrue("文案要含留言：$withWish", withWish.contains("请你喝奶茶"))

        val noWish = RedPacket(amount = 100.0).toModelText()
        assertTrue("没留言时也要含金额：$noWish", noWish.contains("100"))
    }

    @Test
    fun `金额上限拦得住手滑`() {
        // 上限本身不在这层裁剪（由 ViewModel 做），这里只钉住常量是有限值，
        // 免得有人把它改成 Double.MAX_VALUE 之后角色的反应彻底失真喵。
        assertTrue("上限应当是个有限且合理的值", RedPacket.MAX_AMOUNT in 1.0..1_000_000.0)
    }
}
