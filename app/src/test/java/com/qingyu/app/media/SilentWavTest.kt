package com.qingyu.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 静音 WAV 的头部字段喵。
 *
 * 这个对象的全部价值就是"格式必须对"：WAV 头里任何一个字段写错，
 * 服务端只会回一句笼统的"格式不支持"，在真机上极难定位喵。
 * 所以这里逐字段把它钉死——尤其是**小端**与两个长度字段的一致性，
 * 这两处错了从字节上看不出来，只有解析器会拒绝喵。
 */
class SilentWavTest {

    private fun le32(bytes: ByteArray, offset: Int): Long =
        (0..3).fold(0L) { acc, i -> acc or ((bytes[offset + i].toLong() and 0xFF) shl (i * 8)) }

    private fun le16(bytes: ByteArray, offset: Int): Int =
        (0..1).fold(0) { acc, i -> acc or ((bytes[offset + i].toInt() and 0xFF) shl (i * 8)) }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, Charsets.US_ASCII)

    @Test
    fun `四个块标识符都对`() {
        val wav = SilentWav.build()
        assertEquals("开头必须是 RIFF", "RIFF", ascii(wav, 0, 4))
        assertEquals("第 8 字节起必须是 WAVE", "WAVE", ascii(wav, 8, 4))
        assertEquals("fmt 块标识", "fmt ", ascii(wav, 12, 4))
        // data 块的位置随 fmt 块长度固定为 36喵。
        assertEquals("data 块标识", "data", ascii(wav, 36, 4))
    }

    @Test
    fun `格式字段是 16kHz 单声道 16bit PCM`() {
        val wav = SilentWav.build()
        assertEquals("fmt 块长度固定 16", 16L, le32(wav, 16))
        assertEquals("编码格式 1 = PCM", 1, le16(wav, 20))
        assertEquals("单声道", 1, le16(wav, 22))
        assertEquals("采样率 16000", 16000L, le32(wav, 24))
        assertEquals("位深 16", 16, le16(wav, 34))
    }

    @Test
    fun `字节率与块对齐必须自洽`() {
        // 这两个字段最容易写错：它们是派生值，写错后头部看起来"有值"但解析器会拒喵。
        val wav = SilentWav.build()
        val expectedByteRate = SilentWav.SAMPLE_RATE * SilentWav.CHANNELS * (SilentWav.BITS_PER_SAMPLE / 8)
        val expectedBlockAlign = SilentWav.CHANNELS * (SilentWav.BITS_PER_SAMPLE / 8)
        assertEquals("字节率 = 采样率 × 声道 × 位深字节", expectedByteRate.toLong(), le32(wav, 28))
        assertEquals("块对齐 = 声道 × 位深字节", expectedBlockAlign, le16(wav, 32))
    }

    @Test
    fun `总长度与 data 长度字段一致`() {
        val wav = SilentWav.build()
        val dataSize = le32(wav, 40)
        assertEquals("RIFF 长度应为 36 + data 长度", 36L + dataSize, le32(wav, 4))
        assertEquals("实际字节数应为 44 + data 长度", 44L + dataSize, wav.size.toLong())
    }

    @Test
    fun `时长换算成正确的采样字节数`() {
        val wav = SilentWav.build(millis = 200)
        // 200ms × 16000Hz = 3200 个采样，每采样 2 字节 = 6400 字节喵。
        val expectedData = 16000 * 200 / 1000 * 2
        assertEquals("200ms 的 data 长度", expectedData.toLong(), le32(wav, 40))
        assertEquals("总长 = 44 头 + data", 44 + expectedData, wav.size)
    }

    @Test
    fun `内容全是静音`() {
        val wav = SilentWav.build()
        val dataSize = le32(wav, 40).toInt()
        val body = wav.copyOfRange(44, 44 + dataSize)
        assertTrue("data 段应当全是 0", body.all { it == 0.toByte() })
    }

    @Test
    fun `极短时长也不会产出空音频`() {
        // 时长为 0 的音频会被部分网关直接拒掉，所以下限是 1ms 而不是 0喵。
        val wav = SilentWav.build(millis = 0)
        assertTrue("即便传 0 也应当有音频数据", le32(wav, 40) > 0)
        val negative = SilentWav.build(millis = -100)
        assertTrue("负数同样被抬到下限", le32(negative, 40) > 0)
    }

    @Test
    fun `多次调用结果稳定`() {
        // 探测样本必须是确定性的：若里面掺了时间戳之类的东西，
        // 缓存或比对逻辑就会莫名其妙地失效喵。
        assertTrue("两次生成应当完全一致", SilentWav.build().contentEquals(SilentWav.build()))
    }
}
