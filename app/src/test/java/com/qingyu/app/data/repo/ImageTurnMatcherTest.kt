package com.qingyu.app.data.repo

import com.qingyu.app.remote.ChatTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片挂载位置的测试喵。
 *
 * 挂错行不会报错，只会让模型"答非所问"，所以规则必须钉住喵。
 */
class ImageTurnMatcherTest {

    private val image = "data:image/jpeg;base64,AAAA"

    private fun turns(vararg pairs: Pair<String, String>) =
        pairs.map { ChatTurn(it.first, it.second) }

    @Test
    fun `挂到最后一条同内容的用户消息上`() {
        val input = turns(
            "system" to "你是苏婉",
            "user" to "在吗",
            "assistant" to "在啊",
            "user" to "看看这个",
        )
        val result = ImageTurnMatcher.attach(input, "看看这个", listOf(image))
        val sizes = result.map { it.images.size }
        assertEquals("各行的图片数应当是 [0,0,0,1]，实际 $sizes", listOf(0, 0, 0, 1), sizes)
    }

    @Test
    fun `系统提示词里出现同样文字时不会挂错`() {
        // 系统提示词里恰好包含用户那句话（例如角色卡里的示例对话喵）
        val input = turns(
            "system" to "说话示例：用户：看看这个",
            "user" to "看看这个",
        )
        val result = ImageTurnMatcher.attach(input, "看看这个", listOf(image))
        assertEquals("图片不能挂到 system 上", 0, result[0].images.size)
        assertEquals(1, result[1].images.size)
    }

    @Test
    fun `内容对不上时退回最后一条用户消息`() {
        val input = turns(
            "system" to "设定",
            "user" to "第一句",
            "assistant" to "回应",
            "user" to "第二句",
        )
        val result = ImageTurnMatcher.attach(input, "第三句（历史里没有）", listOf(image))
        assertEquals("应当退回最后一条 user", 1, result[3].images.size)
        assertEquals(0, result[1].images.size)
    }

    @Test
    fun `没有用户消息时原样返回`() {
        val input = turns("system" to "设定", "assistant" to "开场白")
        val result = ImageTurnMatcher.attach(input, "文字", listOf(image))
        assertEquals(input, result)
    }

    @Test
    fun `没有图片时不动`() {
        val input = turns("user" to "在吗")
        assertEquals(input, ImageTurnMatcher.attach(input, "在吗", emptyList()))
    }

    @Test
    fun `多张图全部挂上`() {
        val input = turns("user" to "三张")
        val images = listOf("data:1", "data:2", "data:3")
        val result = ImageTurnMatcher.attach(input, "三张", images)
        assertEquals(images, result[0].images)
        assertTrue(result[0].hasImages)
    }

    @Test
    fun `内容两边有空白也算匹配`() {
        val input = turns("user" to "  看看这个  ")
        val result = ImageTurnMatcher.attach(input, "看看这个\n", listOf(image))
        assertEquals(1, result[0].images.size)
    }

    @Test
    fun `只发图不写字时也能挂上`() {
        val input = turns("system" to "设定", "user" to "")
        val result = ImageTurnMatcher.attach(input, "", listOf(image))
        assertEquals(1, result[1].images.size)
    }
}
