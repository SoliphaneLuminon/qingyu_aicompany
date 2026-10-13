package com.qingyu.app.data.repo

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表情包指令在流式过程中的表现喵。
 *
 * 用户报的现象是「角色回复时**先挂了一条表情包名称的文字消息**，
 * 最后生成完才显示图像且插入准确位置」喵。根因是指令后面通常只有一个换行、
 * 没有空行，而空行拆分要等流结束才收尾——于是 `【表情包：笑】` 会先以原文上屏喵。
 *
 * 下面这组测试就是那个现象的回归测试喵。
 */
class StickerStreamingTest {

    private class Recorder {
        val segments = mutableListOf<String>()
        val accumulator = BurstAccumulator { segments.add(it) }
    }

    @Test
    fun `指令一闭合就立刻交出去，不等流结束`() = runBlocking {
        // 这是用户报的那条路径：模型写完文字、换一个行就写指令喵。
        val recorder = Recorder()
        "哈哈哈这个太好笑了\n【表情包：笑到打滚】".forEach { ch ->
            recorder.accumulator.feed(ch.toString())
        }
        // **还没调用 finish()**——此刻文字与指令都该已经交出去了喵。
        assertEquals(
            "指令闭合时就该交出文字与指令，而不是等到流结束",
            listOf("哈哈哈这个太好笑了", "【表情包：笑到打滚】"),
            recorder.segments,
        )
    }

    @Test
    fun `半个方括号不会显示给用户`() = runBlocking {
        // 流式吐字中途必然出现 `【表情包：笑` 这种半截形态，
        // 它是解析中的中间态，既不是话也不是表情喵。
        val recorder = Recorder()
        "好的\n【表情包：笑".forEach { ch -> recorder.accumulator.feed(ch.toString()) }
        val shown = recorder.accumulator.displayTail
        assertTrue(
            "不该让用户看到半截指令：'$shown'",
            !shown.contains("【") && !shown.contains("表情包"),
        )
    }

    @Test
    fun `完整指令仍然原样保留在尾巴里`() = runBlocking {
        // displayTail 只藏**没闭合**的；闭合的必须原样留着交给解析器喵。
        val recorder = Recorder()
        recorder.accumulator.feed("【表情包：笑】")
        // 已经交出去了，尾巴应当是空的喵。
        assertEquals(emptyList<String>(), recorder.segments.filter { it.isBlank() })
        assertTrue("闭合的指令不该残留在尾巴里", recorder.accumulator.displayTail.isBlank())
    }

    @Test
    fun `指令后面还有文字时两段都交出去`() = runBlocking {
        val recorder = Recorder()
        "先说一句\n【表情包：开心】\n再说一句".forEach { ch ->
            recorder.accumulator.feed(ch.toString())
        }
        assertTrue(
            "文字与指令都该出现：${recorder.segments}",
            recorder.segments.contains("先说一句") && recorder.segments.contains("【表情包：开心】"),
        )
    }

    @Test
    fun `没有指令时行为不变`() = runBlocking {
        // 这条是防止"为了修指令而弄坏了普通拆条"喵。
        // **必须调 finish()**：最后一段本来就留在缓冲区里等流结束才交，
        // 这是既有契约（见 BurstAccumulatorTest 里的同类用例）喵。
        val recorder = Recorder()
        "在啊，怎么啦？\n\n刚忙完".forEach { ch -> recorder.accumulator.feed(ch.toString()) }
        recorder.accumulator.finish()
        assertEquals(listOf("在啊，怎么啦", "刚忙完"), recorder.segments)
    }

    @Test
    fun `指令之后的尾巴仍等流结束才交`() = runBlocking {
        // 与上一条同理：指令后面的文字没有空行边界，就该留在尾巴里等 finish 喵。
        val recorder = Recorder()
        "先说一句\n【表情包：开心】\n再说一句".forEach { ch ->
            recorder.accumulator.feed(ch.toString())
        }
        assertTrue(
            "指令闭合时文字与指令都该交出去了：${recorder.segments}",
            recorder.segments.contains("先说一句") && recorder.segments.contains("【表情包：开心】"),
        )
        assertEquals("尾巴还没到收尾时机", 2, recorder.segments.size)
        recorder.accumulator.finish()
        assertTrue(
            "收尾时最后一句也该交出去：${recorder.segments}",
            recorder.segments.contains("再说一句"),
        )
    }
}
