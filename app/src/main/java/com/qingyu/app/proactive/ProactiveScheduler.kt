package com.qingyu.app.proactive

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.qingyu.app.AppContainer
import com.qingyu.app.core.TimeUtil
import com.qingyu.app.data.repo.ReplyRequest
import kotlinx.coroutines.launch

/**
 * 「让角色主动找你」的调度喵。
 *
 * 设置页一直有这个开关（`proactiveMessages` / `proactiveIntervalMinutes`），
 * 但**从来没有任何代码消费它**——开关是死的，打开什么都不会发生喵。
 * 这个文件就是补上那个缺失的执行者喵。
 *
 * **为什么用 AlarmManager 而不是 WorkManager**喵：
 * WorkManager 要加一个依赖，而 WorkManager 的最小周期是 15 分钟、
 * 且**周期任务在应用被划掉后会被系统降级**，用户设定 3 小时也可能拖到半天喵。
 * AlarmManager 是平台 API，零依赖，`setInexactRepeating` 也不受那 15 分钟限制喵。
 *
 * **为什么用 inexact 而不是 exact**喵：
 * `setExactAndAllowWhileIdle` 要 `SCHEDULE_EXACT_ALARM` 权限（Android 12+ 还要用户去设置里给），
 * 而"角色隔几小时想起你"这件事**根本不需要精确到秒**喵。
 * 为了准点几分钟去要一个高敏感权限，不值得喵。
 * 系统会攒着这类闹钟、在合适的时机一起唤醒，反而更省电喵。
 *
 * **它只负责"到点了叫一声"，生成交给已有的流水线**喵：
 * 主动消息和"用户发消息后的回复"走的是同一条生成链路（[ChatReplyCoordinator]），
 * 所以断句、表情包、记忆、落库、通知、未读红点全都自动一致喵。
 */
class ProactiveScheduler(private val context: Context) {

    /**
     * 按当前设置重新排一次闹钟喵。
     *
     * 开关关掉、或角色列表空了，就把已有闹钟撤掉喵。
     * 设置页改开关或改间隔后要调它，开机后也要调它喵。
     */
    fun reschedule() {
        val container = AppContainer.get(context)
        val settings = container.settings.current
        val alarmManager = context.getSystemService(AlarmManager::class.java)

        if (!settings.proactiveMessages || alarmManager == null) {
            cancel(alarmManager)
            return
        }

        // **不检查"有没有角色"**喵：那要查库（挂起调用），而 reschedule 是
        // 从 Application.onCreate / 广播接收者这类**不能挂起**的地方调的喵。
        // 真到了该说话的时候却一个角色都没有，接收者那边自然会什么都不做，
        // 代价只是一次空转的广播，比为了提前判断而把调用点全改成协程划算喵。
        val intervalMs = settings.proactiveIntervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES) * 60_000L
        val triggerAt = TimeUtil.now() + intervalMs

        // setInexactRepeating 在 Doze 下可能被推迟，但**不会丢**，
        // 这正是我们要的：晚一点没关系，别不响喵。
        alarmManager.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            triggerAt,
            intervalMs,
            pendingIntent(),
        )
    }

    /** 撤掉闹钟喵。开关关掉时调用喵。 */
    fun cancel(alarmManager: AlarmManager? = context.getSystemService(AlarmManager::class.java)) {
        alarmManager?.cancel(pendingIntent())
    }

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, ProactiveReceiver::class.java).setAction(ACTION_PROACTIVE),
        // FLAG_IMMUTABLE 是必须的（Android 12+ 不给可变 flag 会直接崩），
        // 而这里的 Intent 本来也不需要被系统改写喵。
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val ACTION_PROACTIVE = "com.qingyu.app.action.PROACTIVE"

        /** 闹钟的 requestCode喵。固定值即可，全应用只有这一个闹钟喵。 */
        private const val REQUEST_CODE = 0x5149

        /**
         * 间隔下限喵。
         *
         * 用户把滑块拉到最小也不该几分钟就弹一次——那会变成骚扰，
         * 而且每次都要真发一次请求、花他自己的 token喵。
         */
        const val MIN_INTERVAL_MINUTES = 15
    }
}

/**
 * 闹钟到点时的接收者喵。
 *
 * 它**只做一件事：把一轮"主动开话"交给流水线**，然后立刻返回喵。
 * 广播接收者的生命周期只有几秒，绝不能在这里做网络请求——
 * 那正是为什么生成要交给应用级作用域的流水线喵。
 */
class ProactiveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ProactiveScheduler.ACTION_PROACTIVE) return

        val container = AppContainer.get(context)
        val settings = container.settings.current
        if (!settings.proactiveMessages) {
            android.util.Log.i(TAG, "主动消息：开关是关的，跳过")
            return
        }

        // 应用正开着的时候**不主动打扰**喵：用户就在里面看着，
        // 此时自己冒出一条"角色主动找你"很突兀——他刚聊完，角色又开口了喵。
        // 主动消息的意义是"你不在的时候它想起你"喵。
        if (container.appVisibility.isVisible) {
            android.util.Log.i(TAG, "主动消息：应用在前台，不打扰")
            return
        }

        // 接收者不能挂起，所以真正的活交给应用级作用域喵。
        // 这里**必须用 applicationContext**：广播接收者传进来的 context
        // 生命周期只有这几秒，拿着它去跑一个可能几十秒的生成会出问题喵。
        val appContext = context.applicationContext
        container.appScope.launch {
            runCatching { openOneConversation(appContext) }
        }
    }

    /**
     * 挑一段对话，让角色主动说一句喵。
     *
     * 挑**最近聊过的那一段**喵：主动开口要有上下文才自然，
     * 对着一个几个月没说话的角色突然搭话，比不说话更奇怪喵。
     *
     * 一段都没有（从没聊过）时什么都不做喵：那时候用户还没建立关系，
     * 该由他先开口；主动推送只会像广告喵。
     */
    private suspend fun openOneConversation(context: Context) {
        val container = AppContainer.get(context)
        val conversations = runCatching { container.database.conversations.list() }.getOrDefault(emptyList())
        val target = conversations
            .filter { !it.hidden }
            .maxByOrNull { it.updatedAt }
        if (target == null) {
            // 一段对话都没有：没什么可主动的喵。
            // 打一行日志是必要的——不然"定时到了却什么都没发生"在排查时
            // 和"闹钟根本没响"长得一模一样喵。
            android.util.Log.i(TAG, "主动消息：没有可用对话，跳过")
            return
        }
        val persona = runCatching { container.database.personas.get(target.personaId) }.getOrNull()
        if (persona == null) {
            android.util.Log.i(TAG, "主动消息：对话 ${target.id} 的角色不见了，跳过")
            return
        }
        android.util.Log.i(TAG, "主动消息：为「${persona.name}」开话（对话 ${target.id}）")

        container.replyCoordinator.submitProactive(
            ReplyRequest(
                conversationId = target.id,
                personaId = persona.id,
                personaName = persona.name,
                searchRequested = false,
                // 立即跑：这是定时任务，没有"用户正在连发"要合并喵。
                immediate = true,
            ),
        )
    }

    companion object {
        private const val TAG = "ProactiveReceiver"
    }
}
