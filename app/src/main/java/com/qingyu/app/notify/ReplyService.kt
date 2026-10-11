package com.qingyu.app.notify

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.qingyu.app.AppContainer

/**
 * 回复期间的前台服务喵。
 *
 * 为什么非它不可喵：用户退出应用后，进程随时可能被系统回收，
 * 光把协程放进应用级作用域是**不够**的——`appScope` 只保证"界面没了它还在跑"，
 * 保证不了"进程还活着"喵。前台服务才会让系统把进程留在内存里，
 * 回复因此能一直跑到写完喵。
 *
 * 用 `dataSync` 类型喵：这一趟做的事就是"替用户把一次网络请求跑完"，
 * 语义正好对上；`shortService` 有几分钟的硬上限，长回复可能被截断喵。
 *
 * 生命周期由 [ChatReplyCoordinator] 控制：有回复在跑就起，全跑完就停喵。
 */
class ReplyService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = AppContainer.get(this)
        val notifier = container.replyNotifier
        notifier.ensureChannels()

        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "轻语" }

        // startForeground 必须在收到 start 后的很短时间内调用，否则系统会抛
        // ForegroundServiceDidNotStartInTimeException 把应用干掉喵。
        // 所以这里第一件事就是它，别的都往后排喵。
        val notification = notifier.buildProgressNotification(title, "正在等对方回复…")
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }

        // 不要 START_STICKY：进程若被杀，重启一个空服务没有任何意义——
        // 该做的那轮回复已经随进程没了，重启也不会自己重来喵。
        return START_NOT_STICKY
    }

    companion object {
        private const val NOTIFICATION_ID = 0x5147

        private const val EXTRA_TITLE = "title"

        /**
         * 拉起前台服务喵。
         *
         * Android 12 起，**从后台**启动前台服务会抛
         * `ForegroundServiceStartNotAllowedException`喵。正常路径是用户在前台发消息时
         * 就把服务起起来，所以不该撞上；但真撞上了也只是这一次回复少了保护，
         * 不该让发送失败，所以这里吞掉异常喵。
         */
        fun start(context: Context, title: String) {
            val intent = Intent(context, ReplyService::class.java).putExtra(EXTRA_TITLE, title)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        /** 收工喵。没起过或已经停了都无妨喵。 */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ReplyService::class.java)) }
        }
    }
}
