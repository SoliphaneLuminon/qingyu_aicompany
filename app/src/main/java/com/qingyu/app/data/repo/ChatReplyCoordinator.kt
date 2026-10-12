package com.qingyu.app.data.repo

import com.qingyu.app.core.TimeUtil
import com.qingyu.app.data.MessageKind
import com.qingyu.app.data.MessageRole
import com.qingyu.app.notify.AppVisibility
import com.qingyu.app.notify.ReplyNotifier
import com.qingyu.app.notify.ReplyService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 一轮要跑的回复喵。
 *
 * @param regenerateFrom 非空表示"从这条消息之后重新生成"（重新生成按钮）喵
 * @param immediate 跳过合并窗口直接跑（重试与重新生成要的就是这个手感）喵
 */
data class ReplyRequest(
    val conversationId: Long,
    val personaId: Long,
    val personaName: String,
    val searchRequested: Boolean,
    val regenerateFrom: Long? = null,
    val immediate: Boolean = false,
)

/**
 * 一条来自流水线的事件，带着它属于哪段对话喵。
 *
 * 为什么必须带对话 id 喵：回复不再随界面生灭，完全可能"用户在等甲回复时切到了乙"。
 * 那时事件照样会流出来，界面若不看 id 就照单全收，甲的回复会凭空出现在乙的聊天里喵。
 */
data class ReplyEvent(val conversationId: Long, val event: ChatEvent)

/**
 * 发送与生成流水线的主人喵。
 *
 * **它活着的地方是应用级作用域，不是界面**喵。这一点是整件事的关键：
 * 以前这套逻辑跑在 `ChatViewModel.viewModelScope` 上，于是
 * `closeChat()` 一句 `streamJob.cancel()` 就把回复掐了，退出应用更是直接没喵。
 * 用户的要求是「无论退出聊天框还是退出应用，都一定要收到回复」，
 * 所以它必须活在界面之外、并且由前台服务保住进程喵。
 *
 * 职责边界喵：
 * - **它管**：合并窗口（连发的消息攒一批）、按顺序跑生成、把事件广播给界面、
 *   跑完在应用不可见时发通知喵
 * - **它不管**：界面状态怎么变。界面订阅 [events] 自己决定怎么画；
 *   界面不在时事件没人听也无所谓——消息早在生成过程中就一条条落库了喵
 *
 * 事件用 [MutableSharedFlow] 而不是 StateFlow 喵：界面可能来回销毁重建，
 * 而事件是"发生过一次"的东西；重进对话时界面靠 `reload()` 从库里读全量，
 * 不需要补齐历史事件喵。
 */
class ChatReplyCoordinator(
    private val engine: ChatEngine,
    private val scope: CoroutineScope,
    private val notifier: ReplyNotifier,
    private val visibility: AppVisibility,
    private val appContext: android.content.Context,
) {

    private val _events = MutableSharedFlow<ReplyEvent>(
        extraBufferCapacity = 256,
        // 没人听的时候（界面销毁了）事件必须有地方去，否则 emit 会挂起、
        // 把整条生成流程卡死喵。丢掉最旧的即可——正文已经落库了喵。
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<ReplyEvent> = _events.asSharedFlow()

    private val _generatingFor = MutableStateFlow<Long?>(null)

    /**
     * 此刻正在为哪段对话生成；null 表示没有在生成喵。
     *
     * 刻意报**哪一段**而不是"有没有在跑"喵：用户完全可能在等甲回复时切去乙，
     * 那时乙的界面不该显示"正在输入"喵。
     */
    val generatingFor: StateFlow<Long?> = _generatingFor.asStateFlow()

    /**
     * 界面**此刻正打开着**哪段对话；没打开任何对话就是 null喵。
     *
     * 由 [com.qingyu.app.ui.vm.ChatViewModel] 在进出对话时写入喵。
     * 它只用来回答一件事："这条回复到达时，用户是不是正在看着它"喵。
     *
     * @Volatile 是必要的喵：写入在主线程，读它的是应用级作用域里的流水线协程喵。
     */
    @Volatile
    var openConversationId: Long? = null

    private val batchTracker = SendBatchTracker()
    private val pipelineLock = Mutex()

    private var pipelineJob: Job? = null
    private var windowJob: Job? = null

    /** 输入框开合由界面回报，供 [ReplyGate] 判断"用户是不是还在打字"喵。 */
    @Volatile private var inputFocused: Boolean = false
    @Volatile private var inputOpenedAt: Long? = null

    /** 这一轮已经产出的助手正文，用来在后台发通知喵。 */
    private val replyBuffer = StringBuilder()

    /** 这一批是谁的，供通知标题与开窗用喵。 */
    @Volatile private var activeRequest: ReplyRequest? = null

    /**
     * 一条消息已经落库上屏，交给合并窗口喵。
     *
     * 窗口逻辑与"这条消息是什么"无关，所以文字、表情包、红包、语音转文字
     * 全都走这里，行为一致喵。
     */
    fun enqueue(request: ReplyRequest) {
        val sentAt = TimeUtil.now()
        activeRequest = request
        val opened = batchTracker.onSent(sentAt)
        // 已有窗口开着：这条并进当前批次，等原来那个计时器到点即可喵。
        if (!opened) return

        // 这一批的"输入框点开时刻"从零开始算，免得上一批的残留把这一批也推迟喵。
        inputOpenedAt = null
        ensureServiceRunning(request)

        windowJob?.cancel()
        windowJob = scope.launch {
            // 发完先等基础窗口（合并连发的消息）；若用户在这期间又点开了输入框，
            // 就再等到"点开之后 5 秒"——他正打着字时抢答会把他要说的话打断喵。
            while (true) {
                val now = TimeUtil.now()
                if (ReplyGate.isReady(now, sentAt, inputOpenedAt, inputFocused)) break
                val wait = ReplyGate.nextCheckDelayMs(now, sentAt, inputOpenedAt, inputFocused)
                if (wait <= 0L) break
                delay(wait)
            }
            batchTracker.onWindowClosed()
            drain()
        }
    }

    /** 用户已经看过输入框：界面把焦点变化转告过来喵。 */
    fun onInputFocusChanged(focused: Boolean) {
        val wasFocused = inputFocused
        inputFocused = focused
        if (focused && !wasFocused && batchTracker.hasOpenWindow) {
            inputOpenedAt = TimeUtil.now()
        }
    }

    /**
     * 换对话或清空时把待办扔掉喵。
     *
     * 注意**不会打断正在跑的那一轮**：那是用户已经发出去的消息，
     * 换个对话看看不该让它烂尾喵。要停请用 [cancel]喵。
     */
    fun resetPending() {
        windowJob?.cancel()
        windowJob = null
        batchTracker.reset()
        inputOpenedAt = null
    }

    /** 用户主动点了"停止生成"喵。 */
    fun cancel() {
        resetPending()
        pipelineJob?.cancel()
        pipelineJob = null
        _generatingFor.value = null
        ReplyService.stop(appContext)
    }

    /**
     * 立刻跑一轮，不走合并窗口喵。
     *
     * 重试与重新生成要的就是"点了就动"的手感喵。
     */
    fun submitNow(request: ReplyRequest) {
        activeRequest = request
        ensureServiceRunning(request)
        pipelineJob = scope.launch {
            pipelineLock.withLock {
                runOne(request)
            }
            // **必须收工**喵：漏了这一步，前台服务的常驻通知会一直挂在状态栏上喵。
            onPipelineIdle()
        }
    }

    /**
     * 把已封口的批次依次跑掉喵。
     *
     * **每次都起一条流水线**，靠 [pipelineLock] 串行化，而不是"看有没有活着的流水线"喵。
     * 后者有个会丢消息的竞态：窗口恰好在流水线收尾那一刻关闭时，
     * 新调用会看到旧流水线仍是 active 而直接返回，那一批就永远没人处理了喵。
     */
    private fun drain() {
        pipelineJob = scope.launch {
            pipelineLock.withLock {
                while (true) {
                    if (!batchTracker.tryTakeReady()) {
                        // 没有待办的批次：还有窗口开着就等它关，否则收工喵。
                        val window = windowJob
                        if (!batchTracker.hasOpenWindow || window == null) break
                        window.join()
                        continue
                    }
                    val request = activeRequest ?: break
                    runOne(request.copy(immediate = true))
                }
            }
            onPipelineIdle()
        }
    }

    /**
     * 真正跑一轮生成喵。消息已经在库里了，所以这里不插任何东西喵。
     *
     * 本函数不自己起协程：它就是流水线里的一步，由 [drain] 的循环串起来喵。
     */
    private suspend fun runOne(request: ReplyRequest) {
        replyBuffer.clear()
        _generatingFor.value = request.conversationId
        val source: Flow<ChatEvent> = if (request.regenerateFrom != null) {
            engine.regenerate(
                conversationId = request.conversationId,
                personaId = request.personaId,
                fromMessageId = request.regenerateFrom,
                searchRequested = request.searchRequested,
            )
        } else {
            engine.generate(
                conversationId = request.conversationId,
                personaId = request.personaId,
                searchRequested = request.searchRequested,
            )
        }

        try {
            source.collect { event ->
                if (event is ChatEvent.Saved) {
                    // 攒下正文，回复跑完且在后台时拿它发通知喵。
                    if (event.message.role == MessageRole.ASSISTANT) {
                        if (event.message.kind == MessageKind.STICKER) {
                            if (replyBuffer.isEmpty()) replyBuffer.append("（发来一个表情包）")
                        } else if (event.message.content.isNotBlank()) {
                            if (replyBuffer.isNotEmpty()) replyBuffer.append('\n')
                            replyBuffer.append(event.message.content)
                        }
                        // 用户没在看这段对话，就记一条未读喵（他进对话时会清零）喵。
                        // 逐条计而不是整轮计一次喵：红点要的是"多少条消息"，
                        // 角色连发三条就该是 3喵。思考过程不算——它的角色是 REASONING，
                        // 根本进不到这个 ASSISTANT 分支里喵。
                        if (openConversationId != request.conversationId) {
                            val total = engine.bumpUnread(request.conversationId)
                            // 顺手把桌面角标的下限（通知）同步一下喵。
                            notifier.showUnreadSummary(total)
                        }
                    }
                }
                // 广播给界面；带上对话 id，界面据此判断是不是自己那段喵。
                _events.emit(ReplyEvent(request.conversationId, event))
                if (event is ChatEvent.Finished || event is ChatEvent.Failed) {
                    notifyIfBackground(request, event)
                }
            }
        } finally {
            _generatingFor.value = null
        }
    }

    /**
     * 收工喵。
     *
     * 只在**确实没有待办**时才停服务喵：可能还有一批排在锁上等着跑，
     * 那时把服务停掉，那一批就失去了进程保护——而它正是用户等着的那条回复喵。
     */
    private fun onPipelineIdle() {
        if (batchTracker.hasReady || batchTracker.hasOpenWindow) return
        _generatingFor.value = null
        ReplyService.stop(appContext)
    }

    /**
     * 用户没在看这段对话时，把这一轮的结果发成系统通知喵。
     *
     * 判据是「**这条回复是不是正被用户看着**」，而不是「应用在不在前台」喵。
     * 早先写的是"应用不可见才通知"，那留下一个很难受的空档：用户人在应用里、
     * 停在主界面上，角色回了话——按旧规则不发通知（他"看得见"应用），
     * 而主界面又没有未读标记，于是他**完全感知不到**这件事，
     * 得自己点进那段对话才发现「哦，早回了」喵。
     *
     * 现在只要不是"正盯着这段对话"就通知：在看别的对话、在主界面、
     * 或者已经退出应用，三种情况都会提醒喵。
     */
    private fun notifyIfBackground(request: ReplyRequest, event: ChatEvent) {
        // 正在看这段对话就不用打扰——他马上就会看到回复喵。
        if (visibility.isVisible && openConversationId == request.conversationId) return
        val body = when (event) {
            is ChatEvent.Failed -> event.message.ifBlank { "这次没生成出内容来" }
            else -> replyBuffer.toString()
        }
        notifier.notifyReply(
            personaId = request.personaId,
            personaName = request.personaName,
            text = body,
        )
    }

    private fun ensureServiceRunning(request: ReplyRequest) {
        notifier.ensureChannels()
        ReplyService.start(appContext, request.personaName.ifBlank { "轻语" })
    }
}
