package com.qingyu.app.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.qingyu.app.AppContainer
import com.qingyu.app.data.Attachment
import com.qingyu.app.data.ChatMessage
import com.qingyu.app.data.MessageRole
import com.qingyu.app.data.Persona
import com.qingyu.app.data.repo.ChatEvent
import com.qingyu.app.data.repo.ChatStreamState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

    // ---- 排队发送队列 ----
    private data class PendingInput(
        val text: String,
        val attachments: List<Attachment>,
        val searchRequested: Boolean
    )
    private val pendingInputQueue = ArrayDeque<PendingInput>()

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
        val personas = db.personas.list()
        val preferred = container.settings.current.lastPersonaId
            .takeIf { id -> personas.any { it.id == id } }
            ?: personas.firstOrNull()?.id
            ?: 0L
        val persona = personas.firstOrNull { it.id == preferred } ?: personas.firstOrNull()
        if (persona == null) {
            _state.update { it.copy(loading = false, banner = "还没有角色，先创建一个吧") }
            return
        }
        openPersona(persona)
    }

    /** 切换到某个角色：取最近会话，必要时写入开场白喵。 */
    fun openPersona(persona: Persona) {
        streamJob?.cancel()
        pendingInputQueue.clear()
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
                    conversations = db.conversations.listForPersona(persona.id),
                    personas = db.personas.list(),
                    banner = null,
                    needsSetup = container.settings.current.apiKey.isBlank(),
                )
            }
        }
    }

    fun refreshPersonas() {
        viewModelScope.launch {
            _state.update { it.copy(personas = db.personas.list()) }
        }
    }

    fun switchConversation(conversationId: Long) {
        streamJob?.cancel()
        typingQueue.clear()
        pendingInputQueue.clear()
        viewModelScope.launch {
            val conversation = db.conversations.get(conversationId) ?: return@launch
            val persona = db.personas.get(conversation.personaId) ?: return@launch
            val messages = engine.history(conversationId)
            _state.update {
                it.copy(
                    persona = persona,
                    conversation = conversation,
                    messages = messages,
                    stream = ChatStreamState(),
                    lastContext = null,
                    conversations = db.conversations.listForPersona(persona.id),
                )
            }
        }
    }

    fun newConversation() {
        val persona = _state.value.persona ?: return
        streamJob?.cancel()
        typingQueue.clear()
        pendingInputQueue.clear()
        viewModelScope.launch {
            val conversation = container.conversations.create(persona.id)
            engine.maybeInsertGreeting(conversation, persona.id)
            val messages = engine.history(conversation.id)
            _state.update {
                it.copy(
                    conversation = conversation,
                    messages = messages,
                    stream = ChatStreamState(),
                    lastContext = null,
                    conversations = db.conversations.listForPersona(persona.id),
                )
            }
        }
    }

    fun deleteConversation(conversationId: Long) {
        val persona = _state.value.persona ?: return
        viewModelScope.launch {
            db.conversations.delete(conversationId)
            val remaining = db.conversations.listForPersona(persona.id)
            val next = remaining.firstOrNull()
            if (next == null) {
                val fresh = engine.openConversation(persona.id)
                engine.maybeInsertGreeting(fresh, persona.id)
                _state.update {
                    it.copy(
                        conversation = fresh,
                        messages = engine.history(fresh.id),
                        conversations = db.conversations.listForPersona(persona.id),
                    )
                }
            } else {
                switchConversation(next.id)
            }
        }
    }

    fun renameConversation(conversationId: Long, title: String) {
        viewModelScope.launch {
            db.conversations.rename(conversationId, title)
            _state.update { current ->
                current.copy(
                    conversations = db.conversations.listForPersona(current.persona?.id ?: 0L),
                    conversation = current.conversation?.takeIf { it.id != conversationId }
                        ?: db.conversations.get(conversationId),
                )
            }
        }
    }

    fun setPinned(conversationId: Long, pinned: Boolean) {
        viewModelScope.launch {
            db.conversations.setPinned(conversationId, pinned)
            _state.update { current ->
                current.copy(conversations = db.conversations.listForPersona(current.persona?.id ?: 0L))
            }
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

    fun send() {
        val current = _state.value
        val conversation = current.conversation ?: return
        val text = current.input.trim()

        if (text.isEmpty() && current.pendingAttachments.isEmpty()) return

        val attachments = current.pendingAttachments
        val search = current.searchEnabled

        val userMsgTimestamp = System.currentTimeMillis()
        val optimisticUserMsg = ChatMessage(
            id = -userMsgTimestamp,
            conversationId = conversation.id,
            role = MessageRole.USER,
            content = text,
            attachments = attachments,
            timestamp = userMsgTimestamp,
        )

        // 1. 立即上屏用户消息（乐观更新），同时清空输入框喵
        _state.update {
            it.copy(
                input = "",
                pendingAttachments = emptyList(),
                lastFailedInput = null,
                messages = it.messages + optimisticUserMsg,
            )
        }

        // 检查 AI 是否正在生成或打字机是否在排队播放喵
        val isBusy = current.stream.generating || typingQueue.isNotEmpty() || typingJob?.isActive == true

        if (isBusy) {
            // 正在生成/播放中：存入等待队列，上屏后等待上一轮完成后自动触发喵
            pendingInputQueue.add(PendingInput(text, attachments, search))
        } else {
            // 闲置状态：直接发起发送喵
            launchSendJob(text, attachments, search)
        }
    }

    /**
     * 底层发起 API 调用的包装方法喵
     */
    private fun launchSendJob(text: String, attachments: List<Attachment>, search: Boolean) {
        val current = _state.value
        val persona = current.persona ?: return
        val conversation = current.conversation ?: return

        // 重置状态喵
        _state.update {
            it.copy(
                stream = ChatStreamState(),
                topBarTyping = false
            )
        }

        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            delay(500)
            _state.update { it.copy(topBarTyping = true) }

            engine.send(
                conversationId = conversation.id,
                personaId = persona.id,
                text = text,
                attachments = attachments,
                searchRequested = search,
            ).collect { handleEvent(it) }
        }
    }

    /**
     * 检查并拉取等待队列中的下一条用户消息注入大模型喵
     */
    private fun checkAndProcessNextPendingInput() {
        if (pendingInputQueue.isNotEmpty()) {
            val nextInput = pendingInputQueue.removeFirst()
            launchSendJob(nextInput.text, nextInput.attachments, nextInput.searchRequested)
        }
    }

    fun retryLast() {
        val current = _state.value
        val persona = current.persona ?: return
        val conversation = current.conversation ?: return
        val pending = current.lastFailedInput ?: return
        if (current.stream.generating) return
        _state.update { it.copy(lastFailedInput = null) }
        streamJob = viewModelScope.launch {
            engine.send(
                conversationId = conversation.id,
                personaId = persona.id,
                text = pending,
                searchRequested = current.searchEnabled,
            ).collect { handleEvent(it) }
        }
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
        pendingInputQueue.clear()

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
        pendingInputQueue.clear()
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

                if (container.settings.current.ttsEnabled && container.settings.current.autoSpeakReplies &&
                    msg.role == MessageRole.ASSISTANT && msg.content.isNotBlank() && speed == 0
                ) {
                    container.speech.speak(msg.content)
                }
            }

            is ChatEvent.Failed -> {
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    _state.update { it.copy(banner = event.message, lastFailedInput = null, topBarTyping = false) }
                }
                // 异常发生时，继续尝试消费等待队列里的下一条喵
                checkAndProcessNextPendingInput()
            }

            is ChatEvent.Finished -> {
                if (typingQueue.isNotEmpty() || typingJob?.isActive == true) {
                    deferredReload = true
                } else {
                    reload()
                    viewModelScope.launch(Dispatchers.Main.immediate) {
                        _state.update { it.copy(topBarTyping = false) }
                    }
                    // 生成结束且无需等待打字播放时，自动唤醒消费队列喵
                    checkAndProcessNextPendingInput()
                }
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
                conversations = db.conversations.listForPersona(it.persona?.id ?: 0L),
            )
        }
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
            // 自动朗读喵
            if (settings.current.ttsEnabled && settings.current.autoSpeakReplies) {
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
            // 延迟 reload（打字排队）播放完成后，唤醒消费等待队列喵
            checkAndProcessNextPendingInput()
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