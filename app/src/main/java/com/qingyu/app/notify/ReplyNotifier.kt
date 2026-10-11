package com.qingyu.app.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.qingyu.app.MainActivity
import com.qingyu.app.R

/**
 * 把角色的回复发成系统通知喵。
 *
 * 这是"退出应用也必须收到回复"的最后一段喵：回复已经在后台生成并落库了，
 * 但用户看不到界面，所以要用通知告诉他喵。
 *
 * 走**高优先级 + 同一个通知 id**，效果贴近微信喵：
 * - 高优先级才会横幅弹出（heads-up），否则只在状态栏里静静躺着喵
 * - 同一个 id 会让同一段对话的后续回复**覆盖**前一条，而不是堆一屏喵
 *
 * 通知权限是运行时权限（Android 13+），没给就在这里安静地什么都不做——
 * 不能因为发不出通知就让回复流程报错喵。
 */
class ReplyNotifier(private val context: Context) {

    /**
     * 发一条回复通知喵。
     *
     * @param personaId 点通知要打开哪个角色；带过去让界面直接进对应对话喵
     * @param personaName 通知标题（角色名）喵
     * @param text 回复正文；太长会被系统截断，这里先自己收一下喵
     */
    fun notifyReply(personaId: Long, personaName: String, text: String) {
        if (!canNotify()) return

        val body = text.trim().ifBlank { "（对方发来一个表情包）" }
        val intent = Intent(context, MainActivity::class.java).apply {
            // 必须带上这些 flag：从通知点进来时若 Activity 还活着，
            // 要让系统复用已有的实例并走 onNewIntent，而不是叠一个新的喵。
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_PERSONA_ID, personaId)
        }
        val pending = PendingIntent.getActivity(
            context,
            // requestCode 用 personaId 取模，保证不同角色各有各的 PendingIntent，
            // 否则 extras 会被第一个创建的那份顶掉，点谁都进同一个对话喵。
            (personaId % Int.MAX_VALUE).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_REPLY)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(personaName.ifBlank { "轻语" })
            .setContentText(body)
            // 长回复折行显示，而不是被截成一行加省略号喵。
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(personaId.toInt(), notification)
        }
    }

    /**
     * 前台服务的常驻通知喵。
     *
     * 它必须存在（前台服务不能没有通知），但用户不该被它打扰，
     * 所以走 IMPORTANCE_MIN 的**另一个**频道：低优先级、不响不亮、也不弹横幅喵。
     */
    fun buildProgressNotification(title: String, text: String): android.app.Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pending)
            .build()
    }

    /** 建好两个频道；重复调用无副作用喵。 */
    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REPLY,
                "新消息",
                // 高重要性是"像微信一样横幅弹出"的前提喵。
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "角色回复你时提醒" },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROGRESS,
                "后台回复中",
                NotificationManager.IMPORTANCE_MIN,
            ).apply { description = "正在后台等待角色回复时的常驻提示，可忽略" },
        )
    }

    /** 有通知权限吗喵。Android 13 以下默认有喵。 */
    private fun canNotify(): Boolean =
        runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }
            .getOrDefault(false)

    companion object {
        const val CHANNEL_REPLY = "qingyu_reply"
        const val CHANNEL_PROGRESS = "qingyu_reply_progress"

        /** 从通知点进来时要打开哪个角色喵。 */
        const val EXTRA_PERSONA_ID = "qingyu.persona_id"
    }
}
