package com.qingyu.app.pet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.qingyu.app.AppContainer
import com.qingyu.app.MainActivity
import com.qingyu.app.R
import com.qingyu.app.data.Persona
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 桌面悬浮宠物喵。
 *
 * 用 [WindowManager] 的 overlay 类型而不是无障碍服务喵：
 * 一个只会待在屏幕角落、点击能进入聊天的挂件，没有理由去申请"读取屏幕内容"这种级别的权限喵。
 * 用户只需要给一个"显示在其他应用上层"，代价与功能是匹配的喵。
 */
class PetService : Service() {

    private lateinit var windowManager: WindowManager
    private var petView: View? = null
    private var bubbleView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var bubbleJob: Job? = null

    private lateinit var container: AppContainer

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        container = AppContainer.get(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForeground(NOTIFICATION_ID, buildNotification())
        showPet()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_SAY -> {
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                if (text.isNotBlank()) showBubble(text)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        petView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        petView = null
        bubbleView = null
        container.settings.update { it.copy(petEnabled = false) }
    }

    // ------------------------------------------------------------ 悬浮窗

    private fun showPet() {
        if (petView != null) return
        // 只在创建悬浮窗时读一次角色：数据库查询很快，但仍旧放到 IO 上做完再回到主线程喵。
        scope.launch {
            val persona = runCatching { currentPersona() }.getOrNull()
            attachPet(persona)
        }
    }

    private fun attachPet(persona: Persona?) {
        if (petView != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = container.settings.current.petX
            y = container.settings.current.petY
        }
        layoutParams = params

        val view = TextView(this).apply {
            text = persona?.avatarEmoji ?: "🌙"
            textSize = 30f
            gravity = Gravity.CENTER
            setPadding(18, 18, 18, 18)
            setBackgroundResource(R.drawable.qy_pet_bg)
            setOnClickListener {
                startActivity(
                    Intent(this@PetService, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            }
            setOnLongClickListener {
                stopSelf()
                true
            }
            setOnTouchListener(DragListener(params))
        }

        runCatching { windowManager.addView(view, params) }
        petView = view
    }

    /** 拖动时实时更新位置，松手后落盘喵。 */
    private inner class DragListener(private val params: WindowManager.LayoutParams) : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var moved = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    return false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).roundToInt()
                    val dy = (event.rawY - touchY).roundToInt()
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    petView?.let { runCatching { windowManager.updateViewLayout(it, params) } }
                    return moved
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    container.settings.update { it.copy(petX = params.x, petY = params.y) }
                    return moved
                }
            }
            return false
        }
    }

    /** 在宠物上方临时显示一句话喵。 */
    private fun showBubble(text: String) {
        bubbleJob?.cancel()
        val anchor = layoutParams ?: return
        if (bubbleView == null) {
            val view = TextView(this).apply {
                textSize = 13f
                maxLines = 3
                setPadding(24, 16, 24, 16)
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundResource(R.drawable.qy_pet_bubble_bg)
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }
            runCatching { windowManager.addView(view, params) }
            bubbleView = view
            bubbleView?.tag = params
        }
        val view = bubbleView ?: return
        val params = view.tag as WindowManager.LayoutParams
        view.text = text.take(80)
        params.x = (anchor.x - 20).coerceAtLeast(8)
        params.y = (anchor.y - 120).coerceAtLeast(8)
        view.visibility = View.VISIBLE
        runCatching { windowManager.updateViewLayout(view, params) }

        bubbleJob = scope.launch {
            delay(6_000)
            runCatching { bubbleView?.visibility = View.GONE }
        }
    }

    private suspend fun currentPersona(): Persona? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val id = container.settings.current.lastPersonaId
        if (id > 0L) container.database.personas.get(id) else container.database.personas.list().firstOrNull()
    }

    /** Android 8 起必须用 TYPE_APPLICATION_OVERLAY，低版本退回 TYPE_PHONE喵。 */
    private fun overlayType(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "桌面宠物",
                    NotificationManager.IMPORTANCE_MIN,
                ).apply { description = "轻语桌宠运行中的常驻通知" },
            )
        }
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val hideIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, PetService::class.java).setAction(ACTION_HIDE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_qingyu)
            .setContentTitle("轻语桌宠在陪着你")
            .setContentText("点头像进入聊天，长按头像收起")
            .setContentIntent(openIntent)
            .addAction(0, "收起", hideIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "qingyu_pet"
        private const val NOTIFICATION_ID = 4101
        const val ACTION_HIDE = "com.qingyu.app.pet.HIDE"
        const val ACTION_SAY = "com.qingyu.app.pet.SAY"
        const val EXTRA_TEXT = "text"

        fun start(context: Context) {
            val intent = Intent(context, PetService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PetService::class.java))
        }

        fun say(context: Context, text: String) {
            val intent = Intent(context, PetService::class.java)
                .setAction(ACTION_SAY)
                .putExtra(EXTRA_TEXT, text)
            runCatching { context.startService(intent) }
        }
    }
}
