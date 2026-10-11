package com.qingyu.app.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.qingyu.app.AppContainer
import com.qingyu.app.data.Attachment
import com.qingyu.app.data.ChatMessage
import com.qingyu.app.data.Conversation
import com.qingyu.app.data.MessageKind
import com.qingyu.app.data.MessageRole
import com.qingyu.app.data.Persona
import com.qingyu.app.data.RedPacket
import com.qingyu.app.data.Sticker
import com.qingyu.app.data.repo.ChatEvent
import com.qingyu.app.data.repo.ChatStreamState
import com.qingyu.app.data.repo.SendBatchTracker
import com.qingyu.app.media.StickerImporter
import com.qingyu.app.core.TimeUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 聊天页控制器，全项目最重的一个 ViewModel喵。
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val container = AppContainer.get(application)
    private val engine = container.chatEngine
    private val db = container.database

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var streamJob: Job? = null

    // ---- 发送合并 ----
    /**
     * 连发合并的规则状态喵。
     *
     * 它只记"欠了几轮生成"，**不记消息内容**——因为消息在用户按下发送的那一刻
     * 就已经落库了，引擎读历史时自然会把同一批的都带上，不需要我们再攒一遍喵。
     */
    private val batchTracker = SendBatchTracker()

    /** 当前这个合并窗口的计时协程喵。 */
    private var windowJob: Job? = null

    /**
     * 发送流水线的串行锁喵。
     *
     * 它保证**同一时刻只有一条流水线在跑**，且后到的那条会在前一条退出后再看一眼
     * 有没有新批次。没有它就会出现"窗口恰好在收尾时关闭 → 那一批没人处理"的丢消息竞态喵。
     */
    private val pipelineLock = Mutex()

    // ---- 打字机 ----
    /** 待逐字播放的已落库回复段喵。 */
    private val typingQueue = ArrayDeque<ChatMessage>()
    /** 正在运行的打字机协程喵。 */
    private var typingJob: Job? = null
    /** 流结束了但打字机还没播完，必须等到播完才能 [reload喵]。 */
    private var deferredReload: Boolean = false
    /** 当前正在打字的那条消息的 id（用于把 partial 映射到完成态喵）。 */
    private var activeTypingId: Long = -1L

    init {
        viewModelScope.launch {
            bootstrap()
            // 新消息默认是否联网，跟随设置喵
            _state.update { it.copy(searchEnabled = container.settings.current.searchDefaultOn) }
        }
    }

    // ------------------------------------------------------------ 图片

    /** 从相册选了一张图：存成附件并挂到待发区喵。 */
    fun attachImage(uri: android.net.Uri) {
        viewModelScope.launch {
            _state.update { it.copy(importingImage = true) }
            val result = runCatching { container.imageStore.importFromUri(uri) }
            _state.update { current ->
                result.fold(
                    onSuccess = { attachment ->
                        current.copy(
                            importingImage = false,
                            pendingAttachments = current.pendingAttachments + attachment,
                        )
                    },
                    onFailure = { error ->
                        current.copy(importingImage = false, banner = error.message ?: "图片导入失败")
                    },
                )
            }
        }
    }

    fun removePendingAttachment(attachment: Attachment) {
        _state.update { it.copy(pendingAttachments = it.pendingAttachments - attachment) }
        viewModelScope.launch { runCatching { container.imageStore.deleteFile(attachment) } }
    }

    // ------------------------------------------------------------ 联网

    fun toggleSearch() {
        _state.update { it.copy(searchEnabled = !it.searchEnabled) }
    }

    // ------------------------------------------------------------ 看大图

    fun openImagePreview(attachment: Attachment) {
        _state.update { it.copy(previewImage = attachment) }
    }

    fun closeImagePreview() {
        _state.update { it.copy(previewImage = null) }
    }

    // ------------------------------------------------------------ 语音

    fun speak(message: ChatMessage) {
        container.speech.speak(message.content)
    }

    fun stopSpeaking() {
        container.speech.stop()
    }

    private suspend fun bootstrap() {
        if (db.personas.count() == 0) {
            com.qingyu.app.data.SeedPersonas.all().forEach { db.personas.insert(it) }
        }
        // 先把列表真正读进来，再撤下 loading 喵。
        // 顺序不能反：若先 `refreshPersonas()`（它内部是 launch）再立刻置 false，
        // 首帧就会出现"loading 已是 false、列表却还空着"，界面先闪一下
        // 「还没有对话」再变成真实列表喵。
        loadLists()
        _state.update { it.copy(loading = false) }
    }

    /**
     * 真正去读库并写状态的那一步（挂起，可被 await）喵。
     */
    private suspend fun loadLists() {
        val entries = container.conversations.chatList()
        val all = db.personas.list()
        // 通讯录与角色资料页要看**全部**对话（含被移除的），所以这里一并取来按角色索引喵。
        // 放在状态里而不是让界面自己查，是为了避免"界面拿到的是过期数据"这类问题喵。
        val byPersona = db.conversations.list().associateBy { it.personaId }
        _state.update {
            it.copy(
                chatList = entries,
                allPersonas = all,
                personas = entries.map { entry -> entry.persona },
                conversationByPersona = byPersona,
            )
        }
    }

    /**
     * 刷新主界面的对话列表，以及通讯录要用的角色列表喵。
     *
     * 这是修掉"新建角色后看不到"的关键：以前这个列表只在返回时刷新一次，
     * 新建角色后聊天页手里还是旧列表，自然找不到刚建的人喵。
     * 现在任何会改动角色或对话的事件都走这里喵。
     *
     * [chatList] 只含**没被移除**的对话（主界面只管对话）；
     * [allPersonas] 含**全部**角色（通讯录要能看到被移除对话的角色）喵。
     */
    fun refreshPersonas() {
        viewModelScope.launch { loadLists() }
    }

    /**
     * 进入某个角色的对话喵。
     *
     * 一个角色一段对话，所以这里不做"挑一个最近的对话"以外的任何选择喵；
     * 对话不存在时才由 [ChatEngine.openConversation] 建一个（并在有开场白时写入）喵。
     */
    fun openPersona(persona: Persona) {
        streamJob?.cancel()
        typingJob?.cancel()
        batchTracker.reset()
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            container.settings.update { it.copy(lastPersonaId = persona.id) }
            val conversation = engine.openConversation(persona.id)
            val messages = engine.history(conversation.id)
            _state.update {
                it.copy(
                    loading = false,
                    persona = persona,
                    conversation = conversation,
                    messages = messages,
                    stream = ChatStreamState(),
                    lastContext = null,
                    banner = null,
                    needsSetup = container.settings.current.apiKey.isBlank(),
                )
            }
            // 开场白可能是刚写进去的，回列表时预览要能立刻反映出来喵。
            refreshPersonas()
        }
    }

    /**
     * 把当前这段对话从主界面移除喵。
     *
     * 这是**隐藏**而不是删除：角色、消息、记忆一概不动，只是不在主界面列出来喵。
     * 从通讯录点进这个角色、或发出新消息，它就会自己回来喵。
     */
    fun hideCurrentChat() {
        val conversation = _state.value.conversation ?: return
        viewModelScope.launch {
            db.conversations.setHidden(conversation.id, true)
            closeChat()
            _state.update { it.copy(banner = "已从主界面移除，聊天记录仍保留") }
        }
    }

    /**
     * 把某个角色的对话从主界面移除喵（长按列表项时用）。
     *
     * 与 [hideCurrentChat] 是同一件事，区别只是从列表按角色触发、
     * 不需要先进入那段对话喵。
     */
    fun hideChatOf(persona: Persona) {
        viewModelScope.launch {
            val conversation = db.conversations.of(persona.id) ?: return@launch
            db.conversations.setHidden(conversation.id, true)
            if (_state.value.persona?.id == persona.id) closeChat()
            refreshPersonas()
        }
    }

    /**
     * 置顶 / 取消置顶某个角色的对话喵（长按列表项时用）。
     *
     * 置顶只影响**列表里的摆放位置**，不改活动时间、也不改 [chatList] 之外任何状态喵。
     */
    fun setPinned(persona: Persona, pinned: Boolean) {
        viewModelScope.launch {
            val conversation = db.conversations.of(persona.id) ?: return@launch
            container.conversations.setPinned(conversation.id, pinned)
            _state.update {
                it.copy(banner = if (pinned) "已置顶「${persona.name}」" else "已取消置顶")
            }
            refreshPersonas()
        }
    }

    /**
     * 清空当前对话的所有消息喵。
     *
     * 与 [hideCurrentChat] 的区别：**这个是真的删**，删完不可恢复，但角色保留喵。
     * 界面上的文案与二次确认必须把两者区分清楚，不然太容易误操作喵。
     */
    fun clearCurrentMessages() {
        val conversation = _state.value.conversation ?: return
        viewModelScope.launch {
            db.messages.deleteAll(conversation.id)
            db.conversations.refreshSummary(conversation.id)
            val messages = engine.history(conversation.id)
            _state.update {
                it.copy(
                    messages = messages,
                    conversation = db.conversations.get(conversation.id) ?: it.conversation,
                    banner = "聊天记录已清空",
                )
            }
            refreshPersonas()
        }
    }

    /** 从对话返回列表：清掉当前对话，让顶栏与输入栏都回到"未选中"状态喵。 */
    fun closeChat() {
        streamJob?.cancel()
        typingJob?.cancel()
        batchTracker.reset()
        _state.update {
            it.copy(
                persona = null,
                conversation = null,
                messages = emptyList(),
                stream = ChatStreamState(),
                lastContext = null,
                input = "",
                pendingAttachments = emptyList(),
            )
        }
        refreshPersonas()
    }

    /**
     * 删除一个角色喵。
     *
     * 会话、消息、记忆、索引都由 [PersonaStore.delete] 连带清理喵；
     * 删掉的若是当前正打开的那个角色，就把对话状态一并清空退回列表喵。
     */
    fun deletePersona(persona: Persona) {
        viewModelScope.launch {
            db.personas.delete(persona.id)
            if (_state.value.persona?.id == persona.id) closeChat()
            refreshPersonas()
        }
    }

    fun onInputChange(value: String) {
        _state.update { it.copy(input = value) }
    }

    fun dismissBanner() {
        _state.update { it.copy(banner = null) }
    }

    /** 用户已经看过首启引导，之后不再弹喵。 */
    fun markSetupSeen() {
        _state.update { it.copy(needsSetup = false) }
    }

    /**
     * 发送喵。
     *
     * **发消息这件事与后端彻底解绑**喵：用户按下发送的这一瞬间，
     * 消息就落库并上屏了，界面立刻能看到、也能立刻继续发下一条——
     * 不管模型是不是正在生成、也不管有没有排队喵。
     *
     * 生成则交给合并窗口：从第一条起等 3 秒，窗口内的都算同一批一起注入；
     * 窗口关闭时若上一轮还在生成，就排队等它**生成完毕**（不必等打字机播完）喵。
     *
     * 这样做同时修掉了两个老毛病喵：
     * - 乐观消息用负数 id、落库后换真 id，导致 LazyColumn 的 key 变化，
     *   `animateItem()` 会在旧位置冻结一个淡出副本（看着像融进背景、滑动时不动）
     * - 模型正在生成时用户发不出消息，只能进一个看不见的队列喵
     */
    fun send() {
        val current = _state.value
        val conversation = current.conversation ?: return
        val persona = current.persona ?: return
        val text = current.input.trim()

        if (text.isEmpty() && current.pendingAttachments.isEmpty()) return

        val attachments = current.pendingAttachments
        val search = current.searchEnabled

        // 清空输入区要**立刻**做：这是用户按下的直接反馈，不能等落库喵。
        //
        // 两个面板也一并收起喵：发送是一个"我说完了"的动作，
        // 面板继续摊在那里会挡住刚发出去的消息，也让人以为还没发出去喵。
        _state.update {
            it.copy(
                input = "",
                pendingAttachments = emptyList(),
                lastFailedInput = null,
                stickerPanelOpen = false,
                plusPanelOpen = false,
            )
        }

        viewModelScope.launch {
            // 先落库再上屏：消息从一开始就带着真实 id，列表 key 不会中途变化喵。
            val saved = runCatching {
                engine.appendUserMessage(conversation.id, text, attachments)
            }.getOrNull()
            if (saved == null) {
                // 落库失败（极少见）：把输入还原回去，别让用户白打一段字喵。
                _state.update {
                    it.copy(
                        input = text,
                        pendingAttachments = attachments,
                        banner = "消息没能存下来，请再试一次",
                    )
                }
                return@launch
            }
            _state.update { it.copy(messages = it.messages + saved) }

            // 发出去就把这段对话放回主界面喵。
            if (conversation.hidden) {
                db.conversations.unhide(conversation.id)
                refreshPersonas()
            }

            onMessageQueued()
        }
    }

    /**
     * 一条消息已经落库上屏，交给合并窗口喵。
     *
     * 窗口逻辑与"这条消息是什么"无关，所以表情包、红包、语音转文字
     * 全都走这里，行为一致喵。
     */
    private fun onMessageQueued() {
        val opened = batchTracker.onSent(TimeUtil.now())
        // 已有窗口开着：这条并进当前批次，等原来那个计时器到点即可喵。
        if (!opened) return

        windowJob?.cancel()
        windowJob = viewModelScope.launch {
            delay(SendBatchTracker.WINDOW_MS)
            batchTracker.onWindowClosed()
            drainBatches()
        }
    }

    /**
     * 把已封口的批次依次跑掉喵。
     *
     * **每次都起一条流水线**，靠 [pipelineLock] 串行化，而不是"看有没有活着的流水线"喵。
     * 后者有个会丢消息的竞态：窗口恰好在流水线收尾那一刻关闭时，
     * 新调用会看到 streamJob 仍是 active 而直接返回，那一批就永远没人处理了喵。
     *
     * 改成加锁后，后到的流水线会排队等前一条退出，进去再自己看一眼：
     * 有批次就接着跑，没有就立刻结束——多起一个协程的代价可以忽略喵。
     */
    private fun drainBatches() {
        streamJob = viewModelScope.launch {
            pipelineLock.withLock {
                while (true) {
                    if (batchTracker.tryTakeReady()) {
                        runOneBatch()
                        continue
                    }
                    // 没有待办的批次：还有窗口开着就等它关，否则收工喵。
                    val window = windowJob
                    if (!batchTracker.hasOpenWindow || window == null) break
                    // 等这个窗口的计时器到点；它到点会把批次封口，循环再取一次喵。
                    window.join()
                }
                // 全部批次跑完，收掉"正在输入"的提示喵。
                _state.update { it.copy(topBarTyping = false) }
            }
        }
    }

    /**
     * 真正跑一轮生成喵。消息已经在库里了，所以这里不插任何东西喵。
     *
     * 本函数不自己起协程、也不 cancel [streamJob]：它就是流水线里的一步，
     * 由 [drainBatches] 的循环串起来，跑完自然返回、接着跑下一批喵。
     */
    private suspend fun runOneBatch() {
        val current = _state.value
        val persona = current.persona ?: return
        val conversation = current.conversation ?: return

        _state.update { it.copy(stream = ChatStreamState(), topBarTyping = true) }

        engine.generate(
            conversationId = conversation.id,
            personaId = persona.id,
            // 联网开关按**这一轮开始时**的设置取：一批消息可能横跨用户切换开关，
            // 但一轮检索只能有一个答案，取最新的即可喵。
            searchRequested = _state.value.searchEnabled,
        ).collect { handleEvent(it) }
    }

    // ------------------------------------------------------------ 语音

    fun togglePlusPanel() {
        _state.update { it.copy(plusPanelOpen = !it.plusPanelOpen, stickerPanelOpen = false) }
    }

    /** 进二级页之前收起面板，否则回来时会看到它还开着喵。 */
    fun togglePlusPanelOff() = _state.update { it.copy(plusPanelOpen = false) }

    fun toggleStickerPanelOff() = _state.update { it.copy(stickerPanelOpen = false) }

    fun showBanner(text: String) = _state.update { it.copy(banner = text) }

    fun hasAudioPermission(): Boolean = container.audioRecorder.hasPermission()

    /** 按住说话：开始录音喵。 */
    fun startRecording() {
        if (_state.value.recording) return
        // start() 有两种失败形态：没权限/已在录返回 false，麦克风打不开抛异常喵。
        // 只判返回值会让后者直接崩掉整个界面，所以两种都要接住喵。
        val started = runCatching { container.audioRecorder.start() }
        val ok = started.getOrDefault(false)
        if (!ok) {
            val reason = started.exceptionOrNull()?.message
                ?: "开始录音失败，可能被别的应用占用了麦克风"
            _state.update { it.copy(banner = reason) }
            return
        }
        _state.update { it.copy(recording = true, recordingStartedAt = System.currentTimeMillis()) }
    }

    /**
     * 松手：停止录音 → 转文字 → 当成普通消息发出去喵。
     *
     * 转写失败不静默吞掉：用户说了一句话却什么都没发生是最难排查的体验，
     * 所以把失败原因摆到提示条上喵。
     */
    fun stopRecordingAndSend() {
        if (!_state.value.recording) return
        _state.update { it.copy(recording = false) }

        viewModelScope.launch {
            val file = container.audioRecorder.stop()
            if (file == null) {
                _state.update { it.copy(banner = "录得太短了，再按住说一会儿") }
                return@launch
            }

            val settings = container.settings.current
            _state.update { it.copy(banner = "正在识别…") }
            val result = runCatching {
                container.transcription.transcribe(
                    baseUrl = settings.speech.baseUrl,
                    apiKey = settings.speech.apiKey,
                    model = settings.transcribeModel,
                    audio = file,
                )
            }
            file.delete()

            result.fold(
                onSuccess = { text ->
                    if (text.isBlank()) {
                        _state.update { it.copy(banner = "没听清，再说一次试试") }
                    } else {
                        // 识别结果写进输入框后直接发，用户不必再点一次发送喵。
                        _state.update { it.copy(input = text, banner = null) }
                        send()
                    }
                },
                onFailure = { error ->
                    _state.update { it.copy(banner = error.message ?: "语音识别失败") }
                },
            )
        }
    }

    /** 录音中途取消（例如页面退出）喵。 */
    fun cancelRecording() {
        if (!_state.value.recording) return
        container.audioRecorder.cancel()
        _state.update { it.copy(recording = false) }
    }

    // ------------------------------------------------------------ 语音通话

    fun setCallState(inCall: Boolean, status: String) {
        _state.update { it.copy(inCall = inCall, callStatus = status) }
    }

    // ------------------------------------------------------------ 表情包

    fun toggleStickerPanel() {
        _state.update { it.copy(stickerPanelOpen = !it.stickerPanelOpen, plusPanelOpen = false) }
        if (_state.value.stickers.isEmpty()) refreshStickers()
    }

    fun refreshStickers() {
        viewModelScope.launch {
            _state.update { it.copy(stickers = container.stickers.list()) }
        }
    }

    fun closeStickerPanel() = _state.update { it.copy(stickerPanelOpen = false) }

    /**
     * 从相册导入一个表情，名字先用文件名，之后可以在表情管理页改喵。
     */
    fun importStickerFromUri(uri: android.net.Uri) {
        viewModelScope.launch {
            _state.update { it.copy(importingStickers = true) }
            val result = runCatching {
                // 复用 ImageStore 的解码链：它已经处理了 EXIF 转正、采样与缩放喵。
                val attachment = container.imageStore.importFromUri(uri)
                val bytes = java.io.File(attachment.localPath).readBytes()
                val name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "表情"
                container.imageStore.deleteFile(attachment)
                container.stickers.add(name, bytes)
            }
            _state.update { current ->
                current.copy(
                    importingStickers = false,
                    stickers = container.stickers.list(),
                    banner = result.fold(
                        onSuccess = { "已添加表情「${it.name}」" },
                        onFailure = { it.message ?: "导入表情失败" },
                    ),
                )
            }
        }
    }

    /**
     * 从压缩包批量导入表情喵。
     *
     * 解压是纯计算，放在 IO 上做；一个几百张的包会明显卡住主线程喵。
     */
    fun importStickersFromZip(uri: android.net.Uri) {
        viewModelScope.launch {
            _state.update { it.copy(importingStickers = true) }
            val result = runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("读不到这个文件")
                }
                val entries = withContext(Dispatchers.IO) { StickerImporter.entriesFromZip(bytes) }
                if (entries.isEmpty()) error("这个压缩包里没有找到图片")
                container.stickers.importEntries(entries)
            }
            _state.update { current ->
                current.copy(
                    importingStickers = false,
                    stickers = container.stickers.list(),
                    banner = result.fold(
                        onSuccess = { "已导入 $it 个表情" },
                        onFailure = { it.message ?: "导入失败" },
                    ),
                )
            }
        }
    }

    fun deleteSticker(sticker: Sticker) {
        viewModelScope.launch {
            container.stickers.delete(sticker)
            _state.update { it.copy(stickers = container.stickers.list(), banner = "已删除表情") }
        }
    }

    fun renameSticker(sticker: Sticker, name: String) {
        viewModelScope.launch {
            container.stickers.rename(sticker.id, name)
            _state.update { it.copy(stickers = container.stickers.list()) }
        }
    }

    /**
     * 发一个表情包喵。
     *
     * 与普通发送的关键区别：**消息由 [StickerRepository.send] 自己落库**，
     * 然后带着它的 id 走 `engine.send(existingUserMessageId = ...)`，
     * 这样引擎不会再插一条重复的用户消息，但流式回复、打字机、记忆提炼
     * 这些既有链路全都照常复用喵。
     */
    fun sendSticker(sticker: Sticker) {
        val current = _state.value
        val conversation = current.conversation ?: return
        // 不拦"正在生成"：表情包也是消息，用户随时能发喵。
        // 它会并进当前合并窗口，或排在当前这一轮之后由流水线接着处理喵。

        _state.update { it.copy(stickerPanelOpen = false) }

        viewModelScope.launch {
            // 与文字消息走同一条路：**先落库、再上屏**，然后交给合并窗口喵。
            // 这样列表 key 不会中途变化，也不会出现"融进背景、滑动时不动"喵。
            val saved = runCatching { container.stickers.send(conversation.id, sticker) }.getOrNull()
                ?: return@launch
            // 附件必须一起上屏：表情气泡靠 message.attachments 渲染图片喵。
            val attached = db.attachments.listForMessage(saved.id)
            _state.update { it.copy(messages = it.messages + saved.copy(attachments = attached)) }

            if (conversation.hidden) {
                db.conversations.unhide(conversation.id)
                refreshPersonas()
            }

            onMessageQueued()
        }
    }

    // ------------------------------------------------------------ 红包

    fun openRedPacket() = _state.update { it.copy(redPacketOpen = true) }

    fun closeRedPacket() = _state.update { it.copy(redPacketOpen = false) }

    /**
     * 发一个虚拟红包喵。
     *
     * 金额与留言都会被写进消息正文，所以角色在上下文里看到的就是
     * 「（发了一个 8.88 元的红包，留言：请你喝奶茶）」这样一句人话，
     * 它会照常按自己的性格回应喵。**不接任何支付，钱是虚拟的**喵。
     */
    fun sendRedPacket(amount: Double, wish: String) {
        val current = _state.value
        val conversation = current.conversation ?: return
        // 同表情包：红包也是消息，随时能发喵。
        if (amount <= 0.0) {
            _state.update { it.copy(banner = "金额得大于 0") }
            return
        }

        val packet = RedPacket(amount = amount.coerceAtMost(RedPacket.MAX_AMOUNT), wish = wish.trim())
        val modelText = packet.toModelText()

        _state.update { it.copy(redPacketOpen = false) }

        viewModelScope.launch {
            // 同样走"先落库、再上屏、交给合并窗口"这条路喵。
            val saved = runCatching {
                val message = ChatMessage(
                    conversationId = conversation.id,
                    role = MessageRole.USER,
                    content = modelText,
                    timestamp = TimeUtil.now(),
                    kind = MessageKind.RED_PACKET,
                    payload = RedPacket.toPayload(packet),
                )
                val id = db.messages.insert(message)
                db.conversations.touch(conversation.id, modelText, 1, TimeUtil.now())
                message.copy(id = id)
            }.getOrNull() ?: return@launch

            _state.update { it.copy(messages = it.messages + saved) }

            if (conversation.hidden) {
                db.conversations.unhide(conversation.id)
                refreshPersonas()
            }

            onMessageQueued()
        }
    }

    /**
     * 重试上一轮喵。
     *
     * 新的发送模型里，消息在用户按下发送的那一刻就已经落库了——
     * 所以"重试"从来不是"重发消息"，而是**再跑一轮生成**喵。
     * 以前这里会重新插入一条用户消息，那在新模型下会造成重复喵。
     *
     * 另外 `lastFailedInput` 这套状态其实一直是空的（从没被赋过非 null 值），
     * 依赖它的旧实现等于永远不执行；这里改成直接看"最后一条是不是用户消息"喵。
     */
    fun retryLast() {
        val current = _state.value
        current.persona ?: return
        current.conversation ?: return
        if (current.stream.generating) return
        // 最后一条不是用户消息就没什么可重试的（例如角色已经回过话了）喵。
        if (current.messages.lastOrNull()?.role != MessageRole.USER) return

        _state.update { it.copy(lastFailedInput = null) }
        // 重试是"立刻再跑一轮"，不走合并窗口——所以先欠一批，
        // 再让流水线把它连同后面可能排着的批次一起跑掉喵。
        batchTracker.reset()
        batchTracker.onSent(TimeUtil.now())
        batchTracker.onWindowClosed()
        drainBatches()
    }

    fun regenerate(messageId: Long) {
        val current = _state.value
        val persona = current.persona ?: return
        val conversation = current.conversation ?: return
        if (current.stream.generating) return

        // 彻底重置打字与等待队列，终止后台播报喵
        typingJob?.cancel()
        typingJob = null
        typingQueue.clear()
        batchTracker.reset()

        val target = current.messages.find { it.id == messageId }
        _state.update { state ->
            val newMessages = if (target != null) {
                state.messages.filter { it.timestamp < target.timestamp }
            } else state.messages

            state.copy(
                messages = newMessages,
                stream = ChatStreamState(), // 重置流与未拆分文本缓存喵
                topBarTyping = false,
                banner = null
            )
        }

        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            delay(500)
            _state.update { it.copy(topBarTyping = true) }

            engine.regenerate(
                conversationId = conversation.id,
                personaId = persona.id,
                fromMessageId = messageId,
                searchRequested = current.searchEnabled,
            ).collect { handleEvent(it) }
        }
    }

    /** 删掉某条消息；如果删的是用户消息，顺带把它之后的回复也清掉喵。 */
    fun deleteMessage(message: ChatMessage) {
        // 彻底清空排队队列，防止被删消息仍在打字队列中播放喵
        typingJob?.cancel()
        typingJob = null
        typingQueue.clear()

        viewModelScope.launch {
            container.conversations.deleteMessage(message.id)
            reload()
        }
    }

    fun stopGenerating() {
        streamJob?.cancel()
        streamJob = null
        batchTracker.reset()
        viewModelScope.launch {
            _state.update { it.copy(stream = ChatStreamState()) }
            reload()
        }
    }

    fun toggleFavorite(message: ChatMessage) {
        val current = _state.value
        val persona = current.persona ?: return
        val conversation = current.conversation ?: return
        viewModelScope.launch {
            if (message.favorite) {
                container.conversations.unfavoriteMessage(message.id)
            } else {
                container.conversations.favoriteMessage(message, persona.name, conversation.title)
            }
            _state.update { it.copy(banner = if (message.favorite) "已取消收藏" else "已收藏到收藏空间") }
            reload()
        }
    }

    fun favoriteConversation() {
        val current = _state.value
        val persona = current.persona ?: return
        val conversation = current.conversation ?: return
        viewModelScope.launch {
            val favorite = container.conversations.favoriteConversation(conversation.id, persona.name)
            _state.update {
                it.copy(banner = if (favorite == null) "这段对话还是空的" else "整段对话已存入收藏空间")
            }
        }
    }

    private suspend fun handleEvent(event: ChatEvent) {
        when (event) {
            is ChatEvent.Context -> {
                // 只留档，不参与渲染：自检页要的是"真实发出去的那一份"喵。
                _state.update { it.copy(lastContext = event.snapshot) }
            }

            is ChatEvent.Stream -> {
                val speed = container.settings.current.typingSpeedMs

                // 打字机模式下，完全抹平 stream.bubbles，UI 层拿不到任何未封包的增长尾巴/原始文本喵。
                val cleanStreamState = if (speed > 0) {
                    event.state.copy(bubbles = emptyList())
                } else {
                    event.state
                }

                viewModelScope.launch(Dispatchers.Main.immediate) {
                    _state.update {
                        it.copy(
                            stream = cleanStreamState,
                            topBarTyping = true
                        )
                    }
                }
            }

            is ChatEvent.Saved -> {
                val msg = event.message
                val speed = container.settings.current.typingSpeedMs

                if (msg.role == MessageRole.ASSISTANT && msg.content.isNotBlank() && speed > 0) {
                    // 打字机模式：完整拆分气泡入队，由 processTypingQueue 负责逐字渲染上屏喵
                    typingQueue.add(msg)

                    viewModelScope.launch(Dispatchers.Main.immediate) {
                        _state.update { it.copy(topBarTyping = false) }

                        // 600ms 停顿间隙模拟对方思考/打字停顿喵
                        delay(600)
                        if (_state.value.stream.generating || typingQueue.isNotEmpty() || typingJob?.isActive == true) {
                            _state.update { it.copy(topBarTyping = true) }
                        }
                    }

                    if (typingJob?.isActive != true) {
                        typingJob = viewModelScope.launch { processTypingQueue() }
                    }
                } else {
                    // 即时模式 (typingSpeedMs == 0 或 USER 乐观消息 / Reasoning 消息喵)
                    viewModelScope.launch(Dispatchers.Main.immediate) {
                        _state.update { current ->
                            val baseMessages = if (msg.role == MessageRole.USER) {
                                current.messages.filterNot { it.id < 0 && it.role == MessageRole.USER }
                            } else {
                                current.messages
                            }

                            val exists = baseMessages.any { it.id == msg.id }
                            current.copy(
                                messages = if (exists) {
                                    baseMessages.map { if (it.id == msg.id) msg else it }
                                } else {
                                    baseMessages + msg
                                },
                                topBarTyping = current.topBarTyping && (typingQueue.isNotEmpty() || current.stream.generating)
                            )
                        }
                    }
                }

                // 通话中必须念出来，**不看"启用朗读 / 自动朗读"这两个开关**喵。
                // 那两个开关是给聊天页的：那里朗读是可选的附加能力；
                // 而通话页的全部意义就是"用嘴说、用耳朵听"，
                // 若不念，用户按住说完一句就只会看到屏幕上一行字，通话等于断了喵。
                val inCall = _state.value.inCall
                if (msg.role == MessageRole.ASSISTANT && msg.content.isNotBlank() && speed == 0 &&
                    (inCall || (container.settings.current.ttsEnabled && container.settings.current.autoSpeakReplies))
                ) {
                    container.speech.speak(msg.content)
                }
            }

            is ChatEvent.Failed -> {
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    _state.update { it.copy(banner = event.message, lastFailedInput = null, topBarTyping = false) }
                }
                // 这一轮废了不必特殊处理：collect 返回后流水线的循环会接着跑下一批喵。
            }

            is ChatEvent.Finished -> {
                // 打字机还没播完的话，reload 要等它播完再做，
                // 否则正在打字的那条会被历史里的完成态顶掉、看起来像跳了一下喵。
                if (typingQueue.isNotEmpty() || typingJob?.isActive == true) {
                    deferredReload = true
                } else {
                    reload()
                    viewModelScope.launch(Dispatchers.Main.immediate) {
                        _state.update { it.copy(topBarTyping = false) }
                    }
                }
                // **下一批由流水线的 while 循环接着跑**，不等打字机喵。
                // 用户要求的是"处理完前一轮"＝生成完毕；让打字机（纯本地回放，
                // 可能还要好几秒）挡住下一轮，会让用户觉得"我发了半天它不理我"喵。
                // 这里刻意不自己再起协程：那会与流水线并存，变成两条线各跑一轮喵。
            }
        }
    }

    private suspend fun reload() {
        val conversation = _state.value.conversation ?: return
        val messages = engine.history(conversation.id)
        val refreshed = db.conversations.get(conversation.id)
        _state.update {
            it.copy(
                messages = messages,
                conversation = refreshed ?: it.conversation,
            )
        }
        // 摘要（预览与排序时间）变了，首页列表要跟上喵。
        refreshPersonas()
    }

    /**
     * 段间延迟排队：从 [typingQueue] 取消息，按字数算出停顿，整条一次入列喵。
     */
    private suspend fun processTypingQueue() {
        val settings = container.settings
        while (typingQueue.isNotEmpty()) {
            val msg = typingQueue.removeFirst()
            val fullText = msg.content
            val speedMs = settings.current.typingSpeedMs.toLong()

            // 按字数算延迟：长句子对方要多打一会儿喵
            val segmentDelay = fullText.length * speedMs

            if (segmentDelay > 0) delay(segmentDelay)

            // 整条一次性曝光喵
            _state.update { current ->
                val exists = current.messages.any { it.id == msg.id }
                current.copy(
                    messages = if (exists) current.messages.map { if (it.id == msg.id) msg else it }
                    else current.messages + msg,
                    topBarTyping = typingQueue.isNotEmpty() || current.stream.generating,
                )
            }
            // 自动朗读：通话中无条件念（理由同上面那处），否则看设置喵。
            if (_state.value.inCall || (settings.current.ttsEnabled && settings.current.autoSpeakReplies)) {
                container.speech.speak(fullText)
            }
            // 条与条之间额外停顿喵
            if (typingQueue.isNotEmpty()) delay(300L)
        }
        activeTypingId = if (typingQueue.isNotEmpty()) typingQueue.first().id else -1L
        if (deferredReload) {
            deferredReload = false
            reload()
            viewModelScope.launch(Dispatchers.Main.immediate) {
                _state.update { it.copy(topBarTyping = false) }
            }
        }
    }

    /** 角色被编辑后同步最新设定喵。 */
    fun onPersonaUpdated(persona: Persona) {
        _state.update { it.copy(persona = persona, personas = it.personas.map { p -> if (p.id == persona.id) persona else p }) }
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ChatViewModel(application) as T
            }
    }
}