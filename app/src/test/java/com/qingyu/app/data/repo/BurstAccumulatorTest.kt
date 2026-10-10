package com.qingyu.app.data.repo

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 流式拆条状态机的回归测试喵。
 *
 * 这里钉住的是一个线上故障：同一句话被重复写入数据库、在聊天里刷满一屏喵。
 * 根因是流式累积缓冲区在段落成型后没有推进，于是每来一个新 chunk喵
 * 都会把之前所有段落再落库一次。所以下面的每个用例都在验证同一件事喵：
 * **每个段落只被交出去一次**，无论上游怎么切分喵。
 */
class BurstAccumulatorTest {

    private class Recorder {
        val segments = mutableListOf<String>()
        val accumulator = BurstAccumulator { segments.add(it) }
    }

    /** 把一串分片喂进去并收尾喵。 */
    private fun run(vararg chunks: String): MutableList<String> = runBlocking {
        val recorder = Recorder()
        chunks.forEach { recorder.accumulator.feed(it) }
        recorder.accumulator.finish()
        recorder.segments
    }

    @Test
    fun `逐字喂入一条消息只得到一条`() = runBlocking {
        val recorder = Recorder()
        "还行吧，就是今天上班有点累。".forEach { char ->
            recorder.accumulator.feed(char.toString())
        }
        recorder.accumulator.finish()
        // 结尾的句号会被有意去掉：真人连发消息时不会每条都补句号喵，
        // 带着标点反而像念稿子（见 BurstAccumulator.splitByCommaAndPunctuation 的说明喵）。
        // 本用例钉的是"一条就是一条"，不是标点本身喵。
        assertEquals(listOf("还行吧，就是今天上班有点累"), recorder.segments)
    }

    @Test
    fun `空行分隔的多条消息各交一次`() {
        val segments = run("在啊，怎么啦？", "\n\n", "刚忙完手头的事。", "\n\n", "中午饭吃了吗？")
        // 空行只决定"消息边界"，每条内部还要按逗号/句读再断一次，断开处的标点按约定丢弃喵，
        // 所以这三条的尾标点都不会留下喵。
        assertEquals(listOf("在啊，怎么啦", "刚忙完手头的事", "中午饭吃了吗"), segments)
    }

    @Test
    fun `模型逐条发送时不会出现倍增`() {
        // 故障复现的最小形态：模型严格执行"拆成多条"，每次只发一条短消息喵
        val line = "还行吧，就是今天上班有点累。"
        // 过一遍段内断句后，尾句号按约定去掉喵
        val emitted = "还行吧，就是今天上班有点累"
        val segments = run(*Array(7) { arrayOf(line, "\n\n") }.flatten().toTypedArray())
        // 模型确实把同一句话发了 7 次就是 7 条，但绝不能因为"重扫缓冲区"而变成 14、21 条喵
        assertEquals(7, segments.size)
        assertEquals(7, segments.count { it == emitted })
    }

    @Test
    fun `一次收到完整回复时按空行拆条`() {
        val segments = run("在啊，怎么啦？\n\n刚忙完手头的事。\n\n中午饭吃了吗？")
        // 空行定边界，段内再按逗号/句读断一次；断开处的标点按约定丢弃喵。
        assertEquals(listOf("在啊，怎么啦", "刚忙完手头的事", "中午饭吃了吗"), segments)
    }

    @Test
    fun `边界跨 chunk 切分也不会重复`() {
        val segments = run("第一句\n", "\n第二句\n\n", "第三句")
        assertEquals(listOf("第一句", "第二句", "第三句"), segments)
    }

    @Test
    fun `连续多个空行只算一个分隔`() {
        assertEquals(listOf("甲", "乙"), run("甲\n\n\n\n乙"))
    }

    @Test
    fun `单换行也被当作新消息的开头`() {
        // 有的模型用单换行分条。只要已经出现过空行边界，尾巴后面的换行就不再是内容喵，
        // 因此后续内容会被当成新的一条，而不是拼到上一句尾巴上喵。
        assertEquals(listOf("甲", "乙继续"), run("甲\n\n乙\n", "继续"))
    }

    @Test
    fun `空行边界分两次到达时也不重复`() {
        // 最容易出错的切分：分隔符本身被拆开喵
        val segments = run("甲", "\n", "\n", "乙", "\n", "\n", "丙")
        assertEquals(listOf("甲", "乙", "丙"), segments)
    }

    @Test
    fun `null 噪音不会进入消息`() {
        assertEquals(listOf("真正的回复"), run("nullnull", "真正的回复"))
    }

    @Test
    fun `没有内容时不会交出空消息`() {
        assertEquals(emptyList<String>(), run("", "   ", "\n\n"))
    }

    @Test
    fun `currentTail 反映正在增长的尾巴`() = runBlocking {
        val recorder = Recorder()
        recorder.accumulator.feed("第一句\n\n第二")
        assertEquals("第二", recorder.accumulator.currentTail)
        recorder.accumulator.feed("句")
        assertEquals("第二句", recorder.accumulator.currentTail)
        recorder.accumulator.finish()
        assertEquals(listOf("第一句", "第二句"), recorder.segments)
    }

    @Test
    fun `segmentCount 与实际交出的段数一致`() = runBlocking {
        val recorder = Recorder()
        recorder.accumulator.feed("甲\n\n乙\n\n丙")
        recorder.accumulator.finish()
        assertEquals(3, recorder.accumulator.segmentCount)
        assertEquals(recorder.segments.size, recorder.accumulator.segmentCount)
    }

    @Test
    fun `先交过段之后收尾不再按空行重拆`() {
        // 已经流式交过一段，尾巴就应当整体作为最后一条，而不是再拆一次喵
        val segments = run("开头那段\n\n结尾这段")
        assertEquals(listOf("开头那段", "结尾这段"), segments)
    }

    @Test
    fun `逗号分句会被切成多条短消息`() {
        // 「写逗号」这件事必须在提示词里明确要求，原因就在这条用例里喵：
        // 切分器唯一的断句信号是标点，断开处那个逗号会被丢掉（标点不进聊天正文喵）。
        val segments = run("你哪次吃饭能少得了辣，我都记得清清楚楚的，你那无辣不欢的劲儿我可太熟啦")
        assertEquals(
            listOf("你哪次吃饭能少得了辣", "我都记得清清楚楚的", "你那无辣不欢的劲儿我可太熟啦"),
            segments,
        )
    }

    @Test
    fun `空行分出的那一条内部还会按逗号继续拆短`() {
        // 统一口径的核心：空行只定"消息边界"，条内仍按逗号断句喵。
        // 否则模型把一条写成"分句串"时，空行路径会把整串原样送出喵，
        // 长句永远不会被拆开——这正是线上"该断开的地方没断开"的成因喵。
        val segments = run(
            "你哪次吃饭能少得了辣，我都记得清清楚楚的，你那无辣不欢的劲儿我可太熟啦\n\n早点睡吧",
        )
        assertEquals(
            listOf(
                "你哪次吃饭能少得了辣",
                "我都记得清清楚楚的",
                "你那无辣不欢的劲儿我可太熟啦",
                "早点睡吧",
            ),
            segments,
        )
    }

    @Test
    fun `通篇没有标点时只能原样交出一条`() {
        // 钉住这个已知边界：模型不写逗号，切分器就没有任何信号可用，只能整段交出去喵。
        // 典型的坏形态——一句话里连着四五个分句，一个逗号都没有喵。
        // 因此 PromptBuilder.BURST_INSTRUCTION 必须显式要求写逗号，不能只靠模型自觉喵。
        val segments = run("你哪次吃饭能少得了辣我都记得清清楚楚的")
        assertEquals(listOf("你哪次吃饭能少得了辣我都记得清清楚楚的"), segments)
    }
}
