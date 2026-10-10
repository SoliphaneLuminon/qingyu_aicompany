package com.qingyu.app.core

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 统一的轻量结果类型，避免在 UI 层散落 try/catch喵。 */
sealed interface Result<out T> {
    data class Ok<T>(val value: T) : Result<T>
    data class Err(val message: String, val cause: Throwable? = null) : Result<Nothing>
}

inline fun <T> runCatchingResult(block: () -> T): Result<T> = try {
    Result.Ok(block())
} catch (t: Throwable) {
    Result.Err(t.message ?: t::class.java.simpleName, t)
}

fun Result<*>.errorMessageOrNull(): String? = (this as? Result.Err)?.message

/** 时间戳工具：所有时间统一用毫秒 Long 存储喵。 */
object TimeUtil {

    fun now(): Long = System.currentTimeMillis()

    private val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val md = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    private val ymd = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val fileStamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault())

    fun clock(ts: Long): String = hm.format(Date(ts))

    /** 智能时间：今天只显示时分，今年显示月日，跨年显示完整日期喵。 */
    fun smart(ts: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = ts }
        return when {
            now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR) -> hm.format(Date(ts))
            now.get(Calendar.YEAR) == then.get(Calendar.YEAR) -> md.format(Date(ts))
            else -> ymd.format(Date(ts))
        }
    }

    /** 用于导出文件名，例如 聊天记录_苏婉_2026-10-06_15-54喵。 */
    fun fileStamp(ts: Long = now()): String = fileStamp.format(Date(ts))

    /** "刚刚 / 3 分钟前 / 昨天" 这类相对描述喵。 */
    fun relative(ts: Long): String {
        val diff = now() - ts
        return when {
            diff < 60_000L -> "刚刚"
            diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
            diff < 86_400_000L -> "${diff / 3_600_000L} 小时前"
            diff < 172_800_000L -> "昨天"
            diff < 604_800_000L -> "${diff / 86_400_000L} 天前"
            else -> ymd.format(Date(ts))
        }
    }
}
