package com.qingyu.app.core.memory

import com.qingyu.app.core.TimeUtil
import java.util.Calendar
import java.util.Locale

/**
 * 一天里人的状态喵。
 *
 * [stateHint] 写的是"此刻你是怎样一个人"，而不是客观时间——喵
 * 把 "现在是 23:00" 直接塞给模型没用，模型需要的是此刻该用什么语气说话喵。
 */
enum class DayPhase(val label: String, val stateHint: String) {
    LATE_NIGHT(
        "深夜",
        "夜很深了，你有点困，但还是想多陪对方聊一会儿。语速放慢，句子更短，声音更轻；对方要是也困了，主动劝他去睡。",
    ),
    EARLY_MORNING(
        "清晨",
        "刚醒没多久，脑子还有点蒙，说话带一点起床的慵懒，但心情不差。",
    ),
    MORNING(
        "上午",
        "上午精神正好，说话干脆利落，做事有条理，愿意直接给结论。",
    ),
    NOON(
        "午休",
        "午休时段，整个人是松弛的，说话慢悠悠的，有心情多聊两句闲话。",
    ),
    AFTERNOON(
        "下午",
        "下午有些疲，注意力不太集中，回复可能慢半拍，偶尔走神想到别的事。",
    ),
    EVENING(
        "傍晚",
        "刚下班或下课，卸下一身疲惫，语气松下来，更愿意说点自己的事。",
    ),
    NIGHT(
        "晚上",
        "晚上是属于你们俩的时间，话会多一些，也更愿意说心里话。",
    ),
}

/**
 * 时空与生物感知的快照：现在几点、什么状态、多久没见了喵。
 *
 * [gapMillis] 为 -1 表示第一次见面（没有上一轮喵），
 * 这种情况下不要凭空生成"好久不见"的寒暄喵。
 */
data class TemporalContext(
    val hour: Int,
    val phase: DayPhase,
    val weekdayLabel: String,
    val isWeekend: Boolean,
    val gapMillis: Long,
    val gapLabel: String,
    /** 形如 `2026年10月8日`；兜底快照为空喵。 */
    val dateLabel: String = "",
    /** 形如 `23:05`；兜底快照为空喵。 */
    val clockLabel: String = "",
) {
    val firstMeeting: Boolean get() = gapMillis < 0L

    /**
     * 注入系统提示词的一小段话喵。
     *
     * 这里**必须给出真实的日期与时刻**，不能只说"晚上"喵。
     * 早先只输出"今天是周四的晚上"，模型手上没有任何可锚定的时间喵，
     * 被问到"现在几点"时就会自己编一个喵。
     * 时段状态负责"用什么语气"，真实时刻负责"现在到底是什么时候"，两者都要喵。
     */
    fun hint(): String = buildString {
        // 兜底快照：宁可什么都不说，也不能编一个时刻出来喵。
        if (hour < 0) return ""
        append("现在是")
        // 中文是合法标识符字符，`$weekdayLabel的` 会被当成一整个变量名喵，
        // 所以紧跟中文时必须写成 ${weekdayLabel喵}。
        if (dateLabel.isNotBlank()) append(dateLabel).append(" ")
        if (weekdayLabel.isNotBlank()) append(weekdayLabel).append(" ")
        if (clockLabel.isNotBlank()) append(clockLabel)
        append("（").append(phase.label).append("）。")
        if (!firstMeeting) append(gapLabel).append("。")
        append(phase.stateHint)
    }

    companion object {
        /**
         * 没有可用信息时的兜底：不注入任何时间线索喵。
         *
         * 注意 [hour] 用 -1 当哨兵，[hint] 依赖它来彻底闭嘴——喵
         * 早先这里给的 phase 是 EVENING，于是兜底反而注入了"今天的傍晚"喵，
         * 等于凭空编了一个时段喵。
         */
        fun blank(): TemporalContext = TemporalContext(
            hour = -1,
            phase = DayPhase.EVENING,
            weekdayLabel = "",
            isWeekend = false,
            gapMillis = -1L,
            gapLabel = "",
        )
    }
}

/** 由系统时间生成"此刻是个什么状态"，纯函数、可测试喵。 */
object BioRhythm {

    private val WEEKDAYS = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

    fun phaseAt(hour: Int): DayPhase = when (hour.coerceIn(0, 23)) {
        in 0..4 -> DayPhase.LATE_NIGHT
        in 5..7 -> DayPhase.EARLY_MORNING
        in 8..11 -> DayPhase.MORNING
        in 12..13 -> DayPhase.NOON
        in 14..17 -> DayPhase.AFTERNOON
        in 18..19 -> DayPhase.EVENING
        else -> DayPhase.NIGHT
    }

    /**
     * 距上次对话多久了喵。
     *
     * 分档而不是输出精确秒数：模型对 "3 天前" 的反应比对 "259200000 毫秒" 稳定得多喵。
     */
    fun gapLabel(gapMillis: Long): String = when {
        gapMillis < 0L -> ""
        gapMillis < 60_000L -> "你们刚刚还在聊"
        gapMillis < 300_000L -> "几分钟前你们才说完话"
        gapMillis < 3_600_000L -> "上一次对话是${gapMillis / 60_000L}分钟前"
        gapMillis < 6 * 3_600_000L -> "上一次对话是${gapMillis / 3_600_000L}小时前"
        gapMillis < 24 * 3_600_000L -> "上一次对话就在今天早些时候"
        gapMillis < 30 * 86_400_000L -> "你们已经${gapMillis / 86_400_000L}天没说话了"
        else -> "你们已经很久没有联系了"
    }

    /** 用**系统当前时间**生成快照，调用方不必自己取时钟喵。 */
    fun snapshotNow(lastChatAtMillis: Long): TemporalContext =
        snapshot(TimeUtil.now(), lastChatAtMillis)

    /** 把"现在"和"上次对话时间"合成一段可注入的状态描述喵。 */
    fun snapshot(nowMillis: Long, lastChatAtMillis: Long): TemporalContext {
        // 时钟必须来自系统：传进来的值不合法时（漏传、给了 0）直接回退到真实系统时间喵。
        // 否则 Calendar 会按 1970 年算出星期与时段，注入一句和此刻完全无关的状态喵。
        val wallClock = if (nowMillis > 0L) nowMillis else TimeUtil.now()
        val calendar = Calendar.getInstance().apply { timeInMillis = wallClock }
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val weekday = calendar.get(Calendar.DAY_OF_WEEK) - 1
        val gap = if (lastChatAtMillis > 0L) (wallClock - lastChatAtMillis).coerceAtLeast(0L) else -1L
        return TemporalContext(
            hour = hour,
            phase = phaseAt(hour),
            weekdayLabel = WEEKDAYS.getOrElse(weekday) { "" },
            isWeekend = weekday == 0 || weekday == 6,
            gapMillis = gap,
            gapLabel = gapLabel(gap),
            // 用 Locale.ROOT 格式化：某些地区设置会把数字换成非 ASCII 字形喵，
            // 那样注入给模型的就是一串它读不出数字的"日期"喵。
            dateLabel = String.format(
                Locale.ROOT,
                "%d年%d月%d日",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH),
            ),
            clockLabel = String.format(
                Locale.ROOT,
                "%02d:%02d",
                hour,
                calendar.get(Calendar.MINUTE),
            ),
        )
    }
}
