package com.qingyu.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外部音色信息块的解析喵。
 *
 * 这段文本是用户从服务商控制台复制过来的，格式完全不受我们控制喵：
 * 标签可能中英文混用、冒号可能全角、模型与参考文本都可能没给喵。
 * 所以把能想到的形态都钉住喵。
 */
class CustomVoiceParserTest {

    private val fullBlock = """
        音色名称: wansu
        URI: speech:wansu:daojl2tsssvc739t29kg:dndkmulxmkworqocghrx
        模型: FunAudioLLM/CosyVoice2-0.5B
        参考文本: 我哪有你说的那么好啊
    """.trimIndent()

    @Test
    fun `解析控制台复制的完整信息块`() {
        val voice = CustomVoiceParser.parse(fullBlock)!!
        assertEquals("wansu", voice.name)
        assertEquals("speech:wansu:daojl2tsssvc739t29kg:dndkmulxmkworqocghrx", voice.uri)
        assertEquals("FunAudioLLM/CosyVoice2-0.5B", voice.model)
        assertEquals("我哪有你说的那么好啊", voice.referenceText)
        assertTrue(voice.hasModel)
    }

    @Test
    fun `只给一条 URI 时从 URI 里推出名称`() {
        val voice = CustomVoiceParser.parse("speech:wansu:daojl2tsssvc739t29kg:dndkmulxmkworqocghrx")!!
        assertEquals("wansu", voice.name)
        assertEquals("speech:wansu:daojl2tsssvc739t29kg:dndkmulxmkworqocghrx", voice.uri)
        assertEquals("", voice.model)
    }

    @Test
    fun `全角冒号与标签两侧空格都能认`() {
        val voice = CustomVoiceParser.parse(
            """
                音色名称 ： wansu
                URI：speech:wansu:abc:def
                模型 ：FunAudioLLM/CosyVoice2-0.5B
            """.trimIndent(),
        )!!
        assertEquals("wansu", voice.name)
        assertEquals("speech:wansu:abc:def", voice.uri)
        assertEquals("FunAudioLLM/CosyVoice2-0.5B", voice.model)
    }

    @Test
    fun `英文标签与大小写混写也能认`() {
        val voice = CustomVoiceParser.parse(
            """
                Name: wansu
                URI: speech:wansu:abc:def
                Model: FunAudioLLM/CosyVoice2-0.5B
                Reference: hello there
            """.trimIndent(),
        )!!
        assertEquals("wansu", voice.name)
        assertEquals("speech:wansu:abc:def", voice.uri)
        assertEquals("FunAudioLLM/CosyVoice2-0.5B", voice.model)
        assertEquals("hello there", voice.referenceText)
    }

    @Test
    fun `一个标签都没对上时从多行里挑出标识与模型`() {
        val voice = CustomVoiceParser.parse(
            """
                wansu
                speech:wansu:daojl2tsssvc739t29kg:dndkmulxmkworqocghrx
                FunAudioLLM/CosyVoice2-0.5B
            """.trimIndent(),
        )!!
        assertEquals("speech:wansu:daojl2tsssvc739t29kg:dndkmulxmkworqocghrx", voice.uri)
        assertEquals("FunAudioLLM/CosyVoice2-0.5B", voice.model)
        assertEquals("wansu", voice.name)
    }

    @Test
    fun `参考文本里的冒号不会被当成标签分隔`() {
        val voice = CustomVoiceParser.parse(
            """
                URI: speech:wansu:abc:def
                参考文本: 我当时就说：这不太好吧
            """.trimIndent(),
        )!!
        assertEquals("我当时就说：这不太好吧", voice.referenceText)
    }

    @Test
    fun `有名称标签时不会被 URI 推出的名字覆盖`() {
        val voice = CustomVoiceParser.parse(
            """
                音色名称: 温柔的邻家女孩
                URI: speech:wansu:abc:def
            """.trimIndent(),
        )!!
        assertEquals("温柔的邻家女孩", voice.name)
    }

    @Test
    fun `没有标识就返回 null，不凭空造一个音色`() {
        assertNull(CustomVoiceParser.parse("模型: FunAudioLLM/CosyVoice2-0.5B"))
        assertNull(CustomVoiceParser.parse("这是一段随便写的说明文字"))
        assertNull(CustomVoiceParser.parse(""))
        assertNull(CustomVoiceParser.parse("   \n  \n "))
    }

    @Test
    fun `http 地址不会被误认成音色标识`() {
        // 只贴了一个接口地址时不该被当成音色喵
        assertNull(CustomVoiceParser.parse("https://api.siliconflow.cn/v1/audio/speech"))
    }

    @Test
    fun `名称与模型都缺失时给出兜底名称`() {
        val voice = CustomVoiceParser.parse("URI: custom-voice-12345")!!
        assertEquals("自定义音色", voice.name)
        assertEquals("custom-voice-12345", voice.uri)
    }

    @Test
    fun `从标识里取名只在 speech 与 voice 两种协议下生效`() {
        assertEquals("wansu", CustomVoiceParser.nameFromUri("speech:wansu:abc:def"))
        assertEquals("wansu", CustomVoiceParser.nameFromUri("voice:wansu:abc:def"))
        assertEquals("", CustomVoiceParser.nameFromUri("https://example.com/wansu"))
        assertEquals("", CustomVoiceParser.nameFromUri("custom-voice-12345"))
    }

    @Test
    fun `行首的列表符号会被忽略`() {
        val voice = CustomVoiceParser.parse(
            """
                - 音色名称: wansu
                * URI: speech:wansu:abc:def
            """.trimIndent(),
        )!!
        assertEquals("wansu", voice.name)
        assertEquals("speech:wansu:abc:def", voice.uri)
    }
}
