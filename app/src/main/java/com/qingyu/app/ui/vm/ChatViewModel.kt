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
import com.qingyu.app.data.repo.ReplyRequest
import com.qingyu.app.data.repo.ChatStreamState
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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 聊天页控制器，全项目最重的一个 ViewModel喵。
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val container = AppContainer.get(application)
    private val engine = container.chatEngine
    private val db = container.database

    /**
     * 发送与生成流水线的主人喵。
     *
     * **它活在应用级作用域里，不在本 ViewModel 里**喵。这一条是用户要求的核心：
     * 「无论退出聊天框还是退出应用，都一定要收到回复」——
     * 只要流水线还挂在 `viewModelScope` 上，`closeChat()` 一句 cancel 就把它掐了喵。
     */
    private val coordinator = container.replyCoordinator

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** 订阅流水线事件的协程；它只负责把事件画到界面上喵。 */
    private var streamJob: Job? = null

    // ---- 打字机 ----
    /** 待逐字播放的已落库回复段喵。 */
    private val typingQueue = ArrayDeque<ChatMessage>()
    /** 正在运行的打字机协程喵。 */
    private var typingJob: Job? = null
    /** 流结束了但打字机还没播完，必须等到播完才能 [reload喵]。 */
    private var deferredReload: Boolean = false

    init {
        viewModelScope.launch {
            bootstrap()
            // 新消息默认是否联网，跟随设置喵
            _state.update { it.copy(searchEnabled = container.settings.current.searchDefaultOn) }
        }
        // 把流水线的事件画到界面上喵。
        //
        // 界面没开时这些事件没人听也无所谓（coordinator 的发流是 DROP_OLDEST，
        // 不会因为没人接而卡住生成）；等用户回到对话，openPersona 会从库里读全量喵。
        streamJob = viewModelScope.launch {
            coordinator.events.collect { reply ->
                // **只画属于当前这段对话的事件**喵。
                // 回复已经不再随界面生灭，用户完全可能在等甲回复时切到乙；
                // 不看对话 id 的话，甲的回复会凭空出现在乙的聊天里喵。
                if (reply.conversationId == _state.value.conversation?.id) {
                    handleEvent(reply.event)
                }
            }
        }
        // "正在输入"由流水线持有，界面跟着它走喵：这样即使界面是中途重建的，
        // 也能立刻显示正确的状态；同时只在**当前这段**对话正在生成时才显示喵。
        viewModelScope.launch {
            coordinator.generatingFor.collect { generatingId ->
                _state.update { state ->
                    state.copy(topBarTyping = TypingIndicator.isTyping(state.conversation?.id, generatingId))
                }
            }
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
        // 只停上一段对话的逐字动画喵。
        //
        // **不 cancel streamJob**：它现在是本 ViewModel 常驻的事件订阅，
        // 停掉就再也不画新消息了喵。
        // **也不动 coordinator**：别的对话可能正在后台生成，那跟这次切换无关；
        // 而且用户要求"退出聊天框也要收到回复"，切个对话更不该把它掐断喵。
        typingJob?.cancel()
        typingJob = null
        typingQueue.clear()
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
                    // 这里必须**主动按当前值初始化一次**喵：用户可能中途退出去过，
                    // 而这段对话正在后台生成——StateFlow 只在值**变化**时才再发，
                    // 光靠订阅会漏掉"回到一段正在生成的对话"这种情况，
                    // 于是明明在生成却不显示"正在输入"喵。
                    topBarTyping = TypingIndicator.isTyping(conversation.id, coordinator.generatingFor.value),
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

    /**
     * 从对话返回列表：清掉当前对话，让顶栏与输入栏都回到"未选中"状态喵。
     *
     * **刻意不打断正在生成的回复**喵。用户的要求是「退出聊天框也一定要收到回复」，
     * 而回复由应用级的 [coordinator] 负责，这里清掉的只是界面状态；
     * 等他再点回来时回复早已一条条落库了，`openPersona` 会照常读出来喵。
     *
     * 也**刻意不 resetPending()** 喵：用户可能刚发完消息就退出聊天框，
     * 那时合并窗口还开着、生成还没开始——清掉待办等于把这条消息吞了喵。
     *
     * 打字机可以停：它纯属界面上的逐字动画，没有它回复也已经在库里了喵。
     */
    fun closeChat() {
        typingJob?.cancel()
        typingJob = null
        typingQueue.clear()
        deferredReload = false
        _state.update {
            it.copy(
                persona = null,
                conversation = null,
                messages = emptyList(),
                stream = ChatStreamState(),
                // 离开对话就一定要收起"正在输入"喵：对话 id 已经没了，
                // 而回复仍在后台跑，留着这个标记回列表页会显得莫名其妙喵。
                topBarTyping = false,
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
     * 一条消息已经落库上屏，转交给流水线喵。
     *
     * 窗口逻辑与"这条消息是什么"无关，所以表情包、红包、语音转文字
     * 全都走这里，行为一致喵。
     *
     * **这里只转交、不等待**喵：真正的合并窗口与生成由 [coordinator] 在应用级作用域里跑，
     * 所以界面关掉也不影响它把回复写完喵。
     */
    private fun onMessageQueued() {
        val current = _state.value
        val persona = current.persona ?: return
        val conversation = current.conversation ?: return
        coordinator.enqueue(
            ReplyRequest(
                conversationId = conversation.id,
                personaId = persona.id,
                personaName = persona.name,
                // 联网开关按**这一轮开始时**的设置取：一批消息可能横跨用户切换开关，
                // 但一轮检索只能有一个答案，取最新的即可喵。
                searchRequested = _state.value.searchEnabled,
            ),
        )
    }

    /**
     * 输入框开合状态由界面回报喵。
     *
     * 只记"这一批发出之后"的重新点开时刻喵：发送前就一直开着的输入框不该推迟回复，
     * 那会让每条消息都白等 5 秒喵。
     */
    fun onInputFocusChanged(focused: Boolean) = coordinator.onInputFocusChanged(focused)

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
        // 立刻再跑一轮，不走合并窗口——重试要的就是"点了就动"的手感喵。
        // 交给应用级流水线，这样即使用户马上退出对话或退出应用也会跑完喵。
        coordinator.submitNow(
            ReplyRequest(
                conversationId = current.conversation.id,
                personaId = current.persona.id,
                personaName = current.persona.name,
                searchRequested = current.searchEnabled,
            ),
        )
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

        // 交给应用级流水线跑，这样即使用户马上退出对话或退出应用，
        // 这一轮重新生成也会跑完并落库喵。
        coordinator.submitNow(
            ReplyRequest(
                conversationId = conversation.id,
                personaId = persona.id,
                personaName = persona.name,
                searchRequested = current.searchEnabled,
                regenerateFrom = messageId,
            ),
        )
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
        // 停生成等于打断流水线本身，所以由它来收尾（含停掉前台服务）喵；
        // 这里只负责抹掉界面上的流式残影并回读一次库喵。
        coordinator.cancel()
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

                // 表情包**不走打字机**喵。它的 content 是表情名（"开心"这种），
                // 交给打字机就会被一个字一个字地打成那两个字，而不是显示成图片喵。
                // 而且真人发表情包本来就是"啪"一下就过去了，没有逐字打的道理喵。
                val isSticker = msg.kind == MessageKind.STICKER

                if (msg.role == MessageRole.ASSISTANT && msg.content.isNotBlank() && !isSticker && speed > 0) {
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
                //
                // 表情包不念喵：它的 content 是表情名，念出来就是干巴巴的两个字
                // （"开心"），既不像是人在说话，也不是这张图想表达的东西喵。
                val inCall = _state.value.inCall
                if (msg.role == MessageRole.ASSISTANT && msg.content.isNotBlank() && !isSticker && speed == 0 &&
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