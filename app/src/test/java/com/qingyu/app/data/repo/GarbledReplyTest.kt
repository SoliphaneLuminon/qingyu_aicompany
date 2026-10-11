package com.qingyu.app.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 乱码回复的识别喵。
 *
 * 判定必须**保守**：漏掉几个变体只是偶尔还会看到乱码，
 * 而误杀了 `ok`、`2333` 这种真回复就是实打实的 bug 喵。
 */
class GarbledReplyTest {

    @Test
    fun `用户报的那个 UUID 会被认出来`() {
        assertTrue(
            "这就是用户反馈的那串",
            GarbledReply.isOpaqueToken("8b8f0b4e-9d4b-4f1a-9e2a-6c3d4f5a6b7c"),
        )
    }

    @Test
    fun `大写与带引号括号的 UUID 也认`() {
        assertTrue(GarbledReply.isOpaqueToken("8B8F0B4E-9D4B-4F1A-9E2A-6C3D4F5A6B7C"))
        assertTrue(GarbledReply.isOpaqueToken("`8b8f0b4e-9d4b-4f1a-9e2a-6c3d4f5a6b7c`"))
        assertTrue(GarbledReply.isOpaqueToken("\"8b8f0b4e-9d4b-4f1a-9e2a-6c3d4f5a6b7c\""))
        assertTrue(GarbledReply.isOpaqueToken("【8b8f0b4e-9d4b-4f1a-9e2a-6c3d4f5a6b7c】"))
    }

    @Test
    fun `长十六进制串算乱码`() {
        assertTrue(
            "32 位哈希",
            GarbledReply.isOpaqueToken("d41d8cd98f00b204e9800998ecf8427e"),
        )
    }

    @Test
    fun `短数字是正常回复不该误杀`() {
        // 这些在聊天里真的会出现喵。
        assertFalse("666 是正常回复", GarbledReply.isOpaqueToken("666"))
        assertFalse("2333 是正常回复", GarbledReply.isOpaqueToken("2333"))
        assertFalse("短数字不该被杀", GarbledReply.isOpaqueToken("12345678"))
    }

    @Test
    fun `短英文是正常回复不该误杀`() {
        assertFalse("ok 是正常回复", GarbledReply.isOpaqueToken("ok"))
        assertFalse("hello 是正常回复", GarbledReply.isOpaqueToken("hello"))
        assertFalse("deadbeef 只有 8 位", GarbledReply.isOpaqueToken("deadbeef"))
    }

    @Test
    fun `中文回复一律放行`() {
        assertFalse(GarbledReply.isOpaqueToken("今天天气不错"))
        assertFalse("中英混排也该放行", GarbledReply.isOpaqueToken("好的ok"))
    }

    @Test
    fun `夹在句子里的十六进制不算乱码`() {
        // 用户可能真的在聊验证码、订单号，那时该照常显示喵。
        assertFalse(
            "整行判定，不做部分匹配",
            GarbledReply.isOpaqueToken("验证码是 d41d8cd98f00b204e9800998ecf8427e"),
        )
    }

    @Test
    fun `空串不算乱码`() {
        assertFalse(GarbledReply.isOpaqueToken(""))
        assertFalse(GarbledReply.isOpaqueToken("   "))
    }

    @Test
    fun `删掉乱码行后正常内容还在`() {
        // 只判整段的话，乱码跟在正常话后面时仍会漏出去喵。
        val cleaned = GarbledReply.stripOpaqueLines(
            "我在呢\n8b8f0b4e-9d4b-4f1a-9e2a-6c3d4f5a6b7c",
        )
        assertEquals("正常那句该留下", "我在呢", cleaned)
    }

    @Test
    fun `整段都是乱码时结果为空`() {
        val cleaned = GarbledReply.stripOpaqueLines("8b8f0b4e-9d4b-4f1a-9e2a-6c3d4f5a6b7c")
        assertTrue("应当被清空", cleaned.isEmpty())
    }

    @Test
    fun `没有乱码时原样返回`() {
        val text = "第一句\n第二句"
        assertEquals("不该被动过", text, GarbledReply.stripOpaqueLines(text))
    }
}
