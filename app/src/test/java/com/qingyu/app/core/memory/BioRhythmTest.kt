package com.qingyu.app.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时空与生物感知的回归测试喵。
 *
 * 这里只钉**不随时区漂移**的部分：时段划分与间隔分档喵。
 * 星期几那类依赖本地时区与星期的断言刻意不写死喵，
 * 否则换个时区跑 CI 就会红，而且红得莫名其妙喵。
 */
class BioRhythmTest {

    @Test
    fun `一天按时段分成七档`() {
        assertEquals(DayPhase.LATE_NIGHT, BioRhythm.phaseAt(2))
        assertEquals(DayPhase.EARLY_MORNING, BioRhythm.phaseAt(6))
        assertEquals(DayPhase.MORNING, BioRhythm.phaseAt(10))
        assertEquals(DayPhase.NOON, BioRhythm.phaseAt(13))
        assertEquals(DayPhase.AFTERNOON, BioRhythm.phaseAt(15))
        assertEquals(DayPhase.EVENING, BioRhythm.phaseAt(18))
        assertEquals(DayPhase.NIGHT, BioRhythm.phaseAt(21))
    }

    @Test
    fun `小时数越界会被夹紧而不是抛异常`() {
        assertEquals(DayPhase.LATE_NIGHT, BioRhythm.phaseAt(-5))
        assertEquals(DayPhase.NIGHT, BioRhythm.phaseAt(99))
    }

    @Test
    fun `第一次见面没有间隔描述`() {
        assertEquals("", BioRhythm.gapLabel(-1L))
    }

    @Test
    fun `间隔按档位给出自然语言`() {
        assertEquals("你们刚刚还在聊", BioRhythm.gapLabel(1_000L))
        assertEquals("几分钟前你们才说完话", BioRhythm.gapLabel(120_000L))
        assertEquals("上一次对话是20分钟前", BioRhythm.gapLabel(1_200_000L))
        assertEquals("上一次对话是2小时前", BioRhythm.gapLabel(7_200_000L))
        assertEquals("你们已经很久没有联系了", BioRhythm.gapLabel(40 * 86_400_000L))
    }

    @Test
    fun `没有上次对话时间时标记为第一次见面`() {
        val context = BioRhythm.snapshot(nowMillis = System.currentTimeMillis(), lastChatAtMillis = 0L)
        assertTrue(context.firstMeeting)
        assertEquals(-1L, context.gapMillis)
        assertTrue(context.hint().startsWith("现在是"))
    }

    @Test
    fun `有上次对话时间时算出间隔`() {
        val now = System.currentTimeMillis()
        val context = BioRhythm.snapshot(now, now - 3_600_000L)
        assertFalse(context.firstMeeting)
        assertEquals(3_600_000L, context.gapMillis)
        assertTrue(context.hint().contains("上一次对话是1小时前"))
    }

    @Test
    fun `星期几是合法的中文标签`() {
        val context = BioRhythm.snapshot(System.currentTimeMillis(), 0L)
        assertTrue(
            context.weekdayLabel in setOf("周日", "周一", "周二", "周三", "周四", "周五", "周六"),
        )
    }

    @Test
    fun `兜底快照不包含任何线索`() {
        assertTrue(TemporalContext.blank().firstMeeting)
        assertEquals("", TemporalContext.blank().gapLabel)
    }

    @Test
    fun `兜底快照一个字都不注入`() {
        // 早期实现会注入"今天的傍晚"——phase 默认 EVENING 造成的凭空捏造喵。
        // 兜底的语义是"宁可不说"，不能编一个时刻给模型喵。
        assertEquals("", TemporalContext.blank().hint())
    }

    @Test
    fun `注入的线索带有真实日期与时刻`() {
        // 只有"晚上"这种模糊时段时，模型被问到几点会自己编，所以必须给出确切时刻喵。
        val context = BioRhythm.snapshotNow(lastChatAtMillis = 0L)
        val hint = context.hint()

        assertTrue(hint.startsWith("现在是"))
        assertTrue("缺少年份：$hint", hint.contains("年"))
        assertTrue("缺少月份：$hint", hint.contains("月"))
        assertTrue("缺少日期：$hint", hint.contains("日"))
        assertTrue("缺少时刻：$hint", hint.contains(":"))
        assertTrue("缺失时段词：$hint", hint.contains(context.phase.label))
    }

    @Test
    fun `时钟不合法时回退到系统时间而不是1970年`() {
        // 调用方漏传 now 时必须回退到真实系统时间：否则 Calendar 会按 1970 年喵
        // 算出星期与时段，注入一句跟此刻完全无关的状态喵。
        val before = BioRhythm.snapshot(System.currentTimeMillis(), 0L)
        val context = BioRhythm.snapshot(nowMillis = 0L, lastChatAtMillis = 0L)
        val after = BioRhythm.snapshot(System.currentTimeMillis(), 0L)

        // 允许恰好跨过小时边界：落在前后两次取样之间即可喵。
        assertTrue(context.hour in setOf(before.hour, after.hour))
        assertFalse(context.weekdayLabel.isBlank())
    }

    @Test
    fun `snapshotNow 直接取系统时间`() {
        val context = BioRhythm.snapshotNow(lastChatAtMillis = 0L)

        assertTrue(context.hour in 0..23)
        // 上次对话时间为 0 表示第一次见面，不应凭空生成"好久不见"喵。
        assertTrue(context.firstMeeting)
        assertEquals(-1L, context.gapMillis)
    }
}
