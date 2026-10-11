package com.qingyu.app.data.repo

import com.qingyu.app.core.TimeUtil
import com.qingyu.app.core.memory.PromptRefiner
import com.qingyu.app.core.memory.RefineOutcome
import com.qingyu.app.core.memory.RefineReason
import com.qingyu.app.core.memory.RefineSource
import com.qingyu.app.core.memory.UtilityLlm
import com.qingyu.app.data.AppSettings
import com.qingyu.app.data.Attachment
import com.qingyu.app.data.ChatMessage
import com.qingyu.app.data.Conversation
import com.qingyu.app.data.Endpoints
import com.qingyu.app.data.MemoryItem
import com.qingyu.app.data.MessageRole
import com.qingyu.app.data.Persona
import com.qingyu.app.data.SearchHit
import com.qingyu.app.data.SettingsStore
import com.qingyu.app.data.extractConfig
import com.qingyu.app.data.refineConfig
import com.qingyu.app.data.db.QingYuDatabase
import com.qingyu.app.media.ImageStore
import com.qingyu.app.remote.ChatClient
import com.qingyu.app.remote.ChatDelta
import com.qingyu.app.remote.ChatTurn
import com.qingyu.app.remote.PromptBuilder
import com.qingyu.app.remote.SearchClient
import com.qingyu.app.remote.SearchContext
import com.qingyu.app.remote.UtilityLlmClient
import com.qingyu.app.remote.VisionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** 正在流式生成的内容。全部是内存态，不进数据库喵。 */
data class ChatStreamState(
    val generating: Boolean = false,
    val reasoning: String = "",
    val reasoningDurationMs: Long = 0L,
    /** 已经成型的回复气泡，最后一个还在增长喵。 */
    val bubbles: List<Bubble> = emptyList(),
    val elapsedMs: Long = 0L,
    val error: String? = null,
    /** 正在联网检索 / 识图预处理喵。 */
    val searching: Boolean = false,
    /** 本次回复用到的联网来源喵。 */
    val searchHits: List<SearchHit> = emptyList(),
) {
    data class Bubble(val id: Long, val text: String, val timestamp: Long)
}

/** 聊天过程中向外抛的事件喵。 */
sealed interface ChatEvent {
    data class Stream(val state: ChatStreamState) : ChatEvent
    data class Saved(val message: ChatMessage) : ChatEvent
    data class Failed(val message: String) : ChatEvent

    /**
     * 发请求之前先把"这一轮到底要发什么"留档下来，供上下文自检页原样展示喵。
     *
     * 在流式内容开始之前发一次，本轮不会再有第二个喵；
     * 自检页有它就不必自己重算提示词（重算必然与真实请求有出入喵）。
     */
    data class Context(val snapshot: ContextSnapshot) : ChatEvent

    /** 本轮生成结束；[searchHits] 若有值，调用方应把它记到本轮第一条回复上喵。 */
    data class Finished(val firstAssistantMessageId: Long = 0L, val searchHits: List<SearchHit> = emptyList()) : ChatEvent
}

/**
 * 对话引擎：一次用户输入 → 落库 → 组装上下文 → 流式生成 → 拆成多条消息落库喵。
 *
 * 这是整个应用最核心的一段逻辑，UI 只负责把 [ChatEvent] 画出来喵。
 */
class ChatEngine(
    private val db: QingYuDatabase,
    private val settingsStore: SettingsStore,
    private val client: ChatClient = ChatClient(),
    private val visionClient: VisionClient = VisionClient(client),
    private val searchClient: SearchClient = SearchClient(),
    private val imageStore: ImageStore? = null,
    private val memoryDirector: MemoryDirector = MemoryDirector(db),
    private val extraction: MemoryExtractionService = MemoryExtractionService(db, UtilityLlmClient(client)),
    private val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val conversations = db.conversations
    private val messages = db.messages

    /** 润色用的小模型执行器；与提炼共用一条连接配置喵。 */
    private val utilityLlm: UtilityLlm = UtilityLlmClient(client)

    suspend fun persona(personaId: Long): Persona? = db.personas.get(personaId)

    suspend fun openConversation(personaId: Long, withGreeting: Boolean = true): Conversation {
        val conversation = db.conversations.latestOrCreate(personaId)
        if (withGreeting) maybeInsertGreeting(conversation, personaId)
        return conversation
    }

    suspend fun history(conversationId: Long): List<ChatMessage> = messages.list(conversationId).withAttachments(conversationId)

    /** 把整段会话的附件按消息分组挂上，避免逐条查库喵。 */
    private suspend fun List<ChatMessage>.withAttachments(conversationId: Long): List<ChatMessage> {
        val all = db.attachments.listForConversation(conversationId)
        if (all.isEmpty()) return this
        val grouped = all.groupBy { it.messageId }
        return map { message -> message.copy(attachments = grouped[message.id].orEmpty()) }
    }

    /** 首次进入某个角色时，把开场白写进去喵。 */
    suspend fun maybeInsertGreeting(conversation: Conversation, personaId: Long) {
        if (messages.count(conversation.id) > 0) return
        val persona = db.personas.get(personaId) ?: return
        if (persona.greeting.isBlank()) return
        val now = TimeUtil.now()
        messages.insert(
            ChatMessage(
                conversationId = conversation.id,
                role = MessageRole.ASSISTANT,
                content = persona.greeting.trim(),
                timestamp = now,
            ),
        )
        db.conversations.touch(conversation.id, persona.greeting.trim(), 1, now)
    }

    suspend fun appendUserMessage(
        conversationId: Long,
        text: String,
        attachments: List<Attachment> = emptyList(),
    ): ChatMessage {
        val message = ChatMessage(
            conversationId = conversationId,
            role = MessageRole.USER,
            content = text.trim(),
            timestamp = TimeUtil.now(),
            attachments = attachments,
        )
        val id = messages.insert(message)
        if (attachments.isNotEmpty()) {
            db.attachments.attachToMessage(id, attachments)
        }
        return message.copy(id = id)
    }

    /**
     * 发送一条用户消息并流式接收回复喵。
     *
     * @param existingUserMessageId 若消息已经落库（例如失败重试），传入其 id 避免重复插入喵。
     * @param searchRequested 本次是否联网检索喵。
     */
    fun send(
        conversationId: Long,
        personaId: Long,
        text: String,
        existingUserMessageId: Long = 0L,
        attachments: List<Attachment> = emptyList(),
        searchRequested: Boolean = false,
    ): Flow<ChatEvent> = flow {
        val settings = settingsStore.current
        val persona = db.personas.get(personaId) ?: run {
            emit(ChatEvent.Failed("角色不存在"))
            return@flow
        }

        if (existingUserMessageId == 0L) {
            appendUserMessage(conversationId, text, attachments)
        }
        // 会话标题在创建时就定成了角色名，且一个角色只有一段对话，
        // 所以这里不再拿首条消息去改标题——那是多会话时代用来区分同角色的多个对话的喵。
        val titleSource = text.ifBlank { if (attachments.isEmpty()) "" else "[图片]" }
        db.conversations.touch(conversationId, titleSource.ifBlank { "[图片]" }, messages.count(conversationId))

        generateInto(conversationId, persona, settings, searchRequested).collect { emit(it) }
    }

    /** 重新生成：删掉这条助手消息与其后内容，再基于此前的上下文重新回答喵。 */
    fun regenerate(
        conversationId: Long,
        personaId: Long,
        fromMessageId: Long,
        searchRequested: Boolean = false,
    ): Flow<ChatEvent> = flow {
        val settings = settingsStore.current
        val persona = db.personas.get(personaId) ?: run {
            emit(ChatEvent.Failed("角色不存在"))
            return@flow
        }
        val target = messages.get(fromMessageId)
        if (target != null) {
            messages.deleteAfter(conversationId, target.timestamp)
        }
        db.conversations.refreshSummary(conversationId)
        generateInto(conversationId, persona, settings, searchRequested).collect { emit(it) }
    }

    /**
     * 主模型看不了图时，先把待处理的图片交给识图模型转成描述喵。
     *
     * 描述会写回 attachments.description，所以同一张图只识别一次喵；
     * 对话历史里那条用户消息的内容也会被补上描述，模型才"看得见"当时发了什么喵。
     */
    private suspend fun describePendingImages(
        history: List<ChatMessage>,
        settings: AppSettings,
    ): List<ChatMessage> {
        val store = imageStore ?: return history
        val capable = settings.visionMainModelSupportsImages
        val pending = history.filter { it.role == MessageRole.USER && it.attachments.any { it.description.isBlank() } }
        if (pending.isEmpty()) return history

        // 主模型自己就能看图：不需要识别，图片会在发送时作为多模态内容带上喵。
        if (capable) return history

        val vision = settings.vision
        if (vision.baseUrl.isBlank() || vision.model.isBlank()) {
            // 没配识图模型：明确告诉用户，而不是让模型对着空内容瞎猜喵。
            return history.map { message ->
                if (message.role == MessageRole.USER && message.attachments.any { it.isImage }) {
                    message.copy(content = appendNote(message.content, "（用户发来了一张图片，但还没有配置识图模型，你看不到内容。请如实说明。）"))
                } else {
                    message
                }
            }
        }

        val described = HashMap<Long, String>()
        pending.forEach { message ->
            val results = visionClient.describeAll(
                attachments = message.attachments.filter { it.isImage },
                endpoint = Endpoints.chat(vision.baseUrl),
                apiKey = vision.apiKey,
                model = vision.model,
                imageStore = store,
                onDescription = { attachment, text ->
                    described[attachment.id] = text
                    db.attachments.updateDescription(attachment.id, text)
                },
            )
            results.forEach { (attachment, text) -> if (text.isNotEmpty()) described[attachment.id] = text }
        }

        return history.map { message ->
            if (message.role != MessageRole.USER || message.attachments.isEmpty()) {
                message
            } else {
                val descriptions = message.attachments.mapNotNull { described[it.id] }
                message.copy(
                    content = visionClient.mergeIntoUserText(message.content, descriptions),
                    attachments = message.attachments.map { attachment ->
                        attachment.copy(description = described[attachment.id] ?: attachment.description)
                    },
                )
            }
        }
    }

    /** 检索本次要联网查的关键词喵。 */
    private suspend fun runSearch(history: List<ChatMessage>): List<SearchHit> {
        val settings = settingsStore.current
        val query = lastUserText(history).take(200)
        if (query.isBlank()) return emptyList()
        if (!settingsStore.searchConfigured) return emptyList()

        // 主模型能看图时，图片内容也一并带上，检索更准喵。
        val enriched = if (settings.visionMainModelSupportsImages && imageStore != null) {
            val images = history.lastOrNull { it.role == MessageRole.USER }?.attachments.orEmpty()
            if (images.isEmpty()) query else {
                val descriptions = images.mapNotNull { it.description.takeIf { d -> d.isNotBlank() } }
                if (descriptions.isEmpty()) query else "$query ${descriptions.joinToString(" ")}"
            }
        } else {
            query
        }

        return runCatching {
            searchClient.search(
                provider = settings.searchProvider,
                apiKey = settingsStore.searchKey,
                query = enriched,
                count = settings.searchResultCount,
                baseUrlOverride = settings.searchBaseUrlOverride,
            )
        }.getOrDefault(emptyList())
    }

    private fun lastUserText(history: List<ChatMessage>): String =
        history.lastOrNull { it.role == MessageRole.USER }?.content?.trim().orEmpty()

    private fun appendNote(text: String, note: String): String =
        if (text.isBlank()) note else "$text\n$note"

    /**
     * 主模型自己能看图时，把最后一条用户消息的图片作为多模态内容挂到对应的 turn 上喵。
     *
     * 只挂**最后一条**用户消息的图：更早的图已经聊过一轮，模型能看到自己当时的回答喵，
     * 没必要每轮重发——一张 1280 长边的图约 1600 token，历史里堆三五张就顶到上限了喵。
     * 具体挂到哪一行由 [ImageTurnMatcher] 决定（那部分有测试喵）。
     */
    private suspend fun attachImagesForNativeVision(
        turns: List<ChatTurn>,
        history: List<ChatMessage>,
        settings: AppSettings,
    ): List<ChatTurn> {
        if (!settings.visionMainModelSupportsImages) return turns
        val store = imageStore ?: return turns

        val lastUserMessage = history.lastOrNull { it.role == MessageRole.USER } ?: return turns
        val images = lastUserMessage.attachments.filter { it.isImage }
        if (images.isEmpty()) return turns

        val dataUrls = images.take(MAX_IMAGES_PER_MESSAGE).mapNotNull { attachment ->
            runCatching { store.toDataUrl(attachment) }.getOrNull()
        }
        return ImageTurnMatcher.attach(turns, lastUserMessage.content, dataUrls)
    }

    private fun generateInto(
        conversationId: Long,
        persona: Persona,
        settings: AppSettings,
        searchRequested: Boolean = false,
    ): Flow<ChatEvent> = flow {
        val startedAt = TimeUtil.now()
        val stream = ChatStreamState(generating = true)

        val history = messages.list(conversationId).withAttachments(conversationId).let { loaded ->
            // 主模型看不了图时，先把图变成文字描述并落库；之后每次生成都复用这段描述喵。
            describePendingImages(loaded, settings)
        }

        // 用户开启联网时先检索，把资料塞进系统提示词喵。
        val searchHits = if (searchRequested) runSearch(history) else emptyList()
        val searchContext = if (searchHits.isEmpty()) "" else {
            SearchContext.build(lastUserText(history).ifBlank { persona.name }, searchHits)
        }
        if (searchHits.isNotEmpty()) {
            emit(ChatEvent.Stream(stream.copy(searching = false, searchHits = searchHits)))
        }

        // 记忆上下文：推进轮次 → 衰减 → 检索 → 唤醒 → 回写，全在本地确定性完成喵。
        val memoryContext = memoryDirector.prepare(
            personaId = persona.id,
            conversationId = conversationId,
            query = lastUserText(history),
            settings = settings,
            now = startedAt,
        )
        val temporal = memoryContext.temporal
        val prunedIds = memoryContext.prunedIds
        val temporalHint = temporal.hint()
        val built = PromptBuilder.turns(
            persona = persona,
            memories = memoryContext.memories,
            history = history,
            suffix = settings.systemPromptSuffix,
            burstReplies = settings.burstReplies,
            historyLimit = settings.historyLimit,
            searchContext = searchContext,
            temporalHint = temporalHint,
        ).let { base -> attachImagesForNativeVision(base, history, settings) }

        // 润色：超时或出错都自动降级为上面这版原生提示词，不会拖住生成喵。
        val systemIndex = built.indexOfFirst { it.role == "system" }
        val rawSystem = built.getOrNull(systemIndex)?.content.orEmpty()
        val refineOutcome: RefineOutcome = if (rawSystem.isBlank()) {
            RefineOutcome(rawSystem, RefineSource.NATIVE, RefineReason.DISABLED)
        } else {
            PromptRefiner.refine(
                rawPrompt = rawSystem,
                config = settings.refineConfig(),
                client = utilityLlm,
                mainEndpoint = settingsStore.chatEndpoint,
                mainModel = persona.modelOverride.ifBlank { settings.model },
                mainApiKey = settings.apiKey,
                maxTokens = maxOf(settings.maxTokens, MIN_REFINE_TOKENS),
            )
        }
        // 润色只该改说法。小模型偶尔会把「# 现在」整段省掉，那样角色就不知道此刻几点了喵，
        // 而这恰恰是"时空与生物感知"要保证的东西。发现时段信息没保住就退回原生提示词——喵
        // 润色是纯收益项，不值得拿实时时间去做交换喵。
        val keptTemporal = temporalHint.isBlank() ||
            !refineOutcome.refined ||
            refineOutcome.prompt.contains(temporal.phase.label)
        val systemContent = if (keptTemporal) refineOutcome.prompt else rawSystem

        val turns = if (systemIndex >= 0) {
            built.toMutableList().also { it[systemIndex] = ChatTurn("system", systemContent) }
        } else {
            built
        }

        // 到这里 turns 已经是"最终形态"：润色结果已替换进 system、图片已挂好、联网资料已拼进去喵。
        // 在流式内容开始之前留档，自检页就能逐字看到真正发出去的那一份，而不是自己重算的近似品喵。
        emit(
            ChatEvent.Context(
                ContextSnapshot(
                    turns = turns,
                    memories = memoryContext.memories,
                    decisions = memoryContext.decisions,
                    temporalHint = temporalHint,
                    searchHitCount = searchHits.size,
                    refineSource = refineOutcome.source,
                    refineReason = refineOutcome.reason,
                    at = TimeUtil.now(),
                ),
            ),
        )

        var state = stream
        var reasoningText = ""

        // 把当前内存态推给 UI喵。
        suspend fun emitState() {
            state = state.copy(
                reasoning = reasoningText,
                elapsedMs = TimeUtil.now() - startedAt,
            )
            emit(ChatEvent.Stream(state))
        }

        // 一段成型就落库。BurstAccumulator 保证每段只交一次，不会重复写入喵。
        //
        // 落库的同时把 id 和正文另行攒一份：流结束（Done）时 `state` 会被整体重置喵，
        // 那时再去读 `state.bubbles` 只会拿到空列表——搜索来源和异步提炼都会跟着失联喵。
        val savedIds = ArrayList<Long>()
        val savedReplies = ArrayList<String>()
        val accumulator = BurstAccumulator { segment ->
            val saved = persistBubble(conversationId, segment, savedReplies.size)
            savedIds.add(saved.id)
            savedReplies.add(saved.text)
            state = state.copy(bubbles = state.bubbles + saved)
            emit(ChatEvent.Saved(saved.toMessage(conversationId)))
        }

        try {
            val model = persona.modelOverride.ifBlank { settings.model }
            val temperature = persona.temperatureOverride ?: settings.temperature
            val deltas = client.stream(
                endpoint = settingsStore.chatEndpoint,
                apiKey = settings.apiKey,
                model = model,
                turns = turns,
                temperature = temperature,
                topP = settings.topP,
                maxTokens = settings.maxTokens,
                stream = settings.streamResponse,
            )

            deltas.collect { delta ->
                when (delta) {
                    is ChatDelta.Reasoning -> {
                        reasoningText += delta.text
                        emitState()
                    }

                    is ChatDelta.Content -> {
                        accumulator.feed(delta.text)
                        // 乐观渲染：只保留已定型的 id>0 气泡 + 一条新 tail喵。
                        // [dropLast(1)] 在空行拆分时会把刚入列的定型气泡扔回去喵，
                        // 而旧 tail 留在了列表里——导致同一段文字同时以 id=-1 和 id>0喵
                        // 两条出现，UI 先闪完整段再逐条曝光喵。
                        val tail = accumulator.currentTail
                        val optimistic = state.bubbles.filter { it.id > 0 } + ChatStreamState.Bubble(
                            id = -1L,
                            text = tail,
                            timestamp = TimeUtil.now(),
                        )
                        state = state.copy(bubbles = optimistic, elapsedMs = TimeUtil.now() - startedAt)
                        emit(ChatEvent.Stream(state))
                    }

                    is ChatDelta.Done -> {
                        accumulator.finish()
                        reasoningText = reasoningText.trim()
                        if (reasoningText.isNotEmpty()) {
                            val reasoningMessage = ChatMessage(
                                conversationId = conversationId,
                                role = MessageRole.REASONING,
                                content = reasoningText,
                                timestamp = TimeUtil.now(),
                                reasoningDuration = delta.elapsedMs,
                            )
                            val id = messages.insert(reasoningMessage)
                            emit(ChatEvent.Saved(reasoningMessage.copy(id = id)))
                        }
                        state = ChatStreamState()
                        emit(ChatEvent.Stream(state))
                    }
                }
            }
            db.conversations.refreshSummary(conversationId)
            // 把本轮用到的来源记到第一条回复上：否则刷新一次界面就丢了喵。
            val firstAssistantId = savedIds.firstOrNull { it > 0L } ?: 0L
            if (searchHits.isNotEmpty() && firstAssistantId > 0L) {
                messages.updateSearchResults(firstAssistantId, searchHits)
            }
            // 回复已经拿到手了，提炼才敢往后台丢——它绝不该让这次回复多等一秒喵。
            launchExtraction(persona = persona, history = history, replies = savedReplies)
            // 剪枝残影也顺带交给小模型优化——跑得比提炼还快（每个只 64 token喵），
            // 失败不动机械压缩版本，不影响任何主流程喵。
            if (prunedIds.isNotEmpty()) {
                extraction.launchPruneCompression(
                    scope = appScope,
                    prunedIds = prunedIds,
                    config = settings.extractConfig(),
                    mainEndpoint = settingsStore.chatEndpoint,
                    mainModel = persona.modelOverride.ifBlank { settings.model },
                    mainApiKey = settings.apiKey,
                )
            }
            emit(ChatEvent.Finished(firstAssistantId, searchHits))
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            val message = t.message ?: t::class.java.simpleName
            // 出错时把已经收到的内容交出去，别让用户白等一场喵。
            accumulator.finish()
            if (reasoningText.isNotBlank()) {
                val reasoningMessage = ChatMessage(
                    conversationId = conversationId,
                    role = MessageRole.REASONING,
                    content = reasoningText,
                    timestamp = TimeUtil.now(),
                    reasoningDuration = TimeUtil.now() - startedAt,
                )
                val id = messages.insert(reasoningMessage)
                emit(ChatEvent.Saved(reasoningMessage.copy(id = id)))
            }
            db.conversations.refreshSummary(conversationId)
            state = ChatStreamState(error = message)
            emit(ChatEvent.Stream(state))
            emit(ChatEvent.Failed(message))
        }
    }

    /** 把一段成型文本写成一条助手消息喵。 */
    private suspend fun persistBubble(conversationId: Long, text: String, order: Int): ChatStreamState.Bubble {        val message = ChatMessage(
            conversationId = conversationId,
            role = MessageRole.ASSISTANT,
            content = text,
            // 加 order 毫秒是为了保证同一批消息的先后顺序稳定喵
            timestamp = TimeUtil.now() + order,
        )
        val id = messages.insert(message)
        return ChatStreamState.Bubble(id = id, text = text, timestamp = message.timestamp)
    }

    private fun ChatStreamState.Bubble.toMessage(conversationId: Long) = ChatMessage(
        id = id,
        conversationId = conversationId,
        role = MessageRole.ASSISTANT,
        content = text,
        timestamp = timestamp,
    )

    /** 供调试页/设置页做连通性测试：只发一句话看能不能拿到回复喵。 */
    suspend fun testConnection(): kotlin.Result<String> {
        val settings = settingsStore.current
        val probe = listOf(
            ChatTurn("system", "你是一个测试助手，只回复一个词：正常。"),
            ChatTurn("user", "你好"),
        )
        return try {
            var collected = ""
            client.stream(
                endpoint = settingsStore.chatEndpoint,
                apiKey = settings.apiKey,
                model = settings.model,
                turns = probe,
                temperature = 0.2,
                topP = 1.0,
                maxTokens = 32,
                stream = false,
            ).collect { delta ->
                if (delta is ChatDelta.Content) collected += delta.text
            }
            if (collected.isBlank()) {
                kotlin.Result.failure(IllegalStateException("接口连通但没有返回内容"))
            } else {
                kotlin.Result.success(collected.trim())
            }
        } catch (t: Throwable) {
            kotlin.Result.failure(t)
        }
    }

    /**
     * 拉取接口下可用的模型列表，供设置页"填好地址与 key 后自动列出可选模型"喵。
     */
    suspend fun fetchModels(): kotlin.Result<List<String>> {
        val settings = settingsStore.current
        val endpoint = Endpoints.models(settings.apiBaseUrl)
        if (endpoint.isBlank()) return kotlin.Result.failure(IllegalStateException("还没有配置接口地址"))
        if (settings.apiKey.isBlank()) return kotlin.Result.failure(IllegalStateException("还没有填写 API Key"))
        return runCatching { client.listModels(endpoint, settings.apiKey) }
    }

    /** 让模型基于最近对话自动整理出长期记忆喵。 */
    suspend fun extractMemories(personaId: Long, conversationId: Long): List<MemoryItem> {
        val persona = db.personas.get(personaId) ?: return emptyList()
        val history = messages.list(conversationId)
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
            .takeLast(60)
        if (history.isEmpty()) return emptyList()

        val settings = settingsStore.current
        val transcript = history.joinToString("\n") {
            (if (it.role == MessageRole.USER) "用户" else persona.name) + "：" + it.content
        }
        val instruction = """
            从下面的聊天记录里提炼**值得长期记住**的事实。宁缺毋滥。
            只输出 JSON 数组，不要任何解释或代码块标记。每个元素：
            {"content":"一句完整、具体的中文陈述","importance":1-10,"category":"PROFILE|PREFERENCE|EVENT|RELATION|RULE"}
            不要提取一次性寒暄、临时情绪、笼统感受。最多 8 条；没有可提取的就输出 []。
        """.trimIndent()

        return extraction.extractAndStore(
            personaId = personaId,
            transcript = transcript.take(TRANSCRIPT_LIMIT),
            config = settings.extractConfig(),
            mainEndpoint = settingsStore.chatEndpoint,
            mainModel = persona.modelOverride.ifBlank { settings.model },
            mainApiKey = settings.apiKey,
        ).stored
    }

    /**
     * 本轮结束后异步提炼一次喵。
     *
     * 用应用级 scope 而不是调用方的协程：用户可能一发出消息就退出聊天页喵，
     * 但提炼是"替用户记住这件事"，不该跟着界面一起没了喵。
     */
    private fun launchExtraction(persona: Persona, history: List<ChatMessage>, replies: List<String>) {
        if (replies.isEmpty()) return
        val settings = settingsStore.current
        extraction.launchAsync(
            scope = appScope,
            personaId = persona.id,
            transcript = buildTranscript(history, persona.name, replies).take(TRANSCRIPT_LIMIT),
            config = settings.extractConfig(),
            mainEndpoint = settingsStore.chatEndpoint,
            mainModel = persona.modelOverride.ifBlank { settings.model },
            mainApiKey = settings.apiKey,
        )
    }

    /** 把最近若干条对话拼成"用户：… / 角色：…"的文本，供提炼模型阅读喵。 */
    private fun buildTranscript(
        history: List<ChatMessage>,
        personaName: String,
        extraReplies: List<String> = emptyList(),
        limit: Int = TRANSCRIPT_TURNS,
    ): String {
        val lines = history
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
            .filter { it.content.isNotBlank() }
            .takeLast(limit)
            .map { message ->
                val speaker = if (message.role == MessageRole.USER) "用户" else personaName
                "$speaker：${message.content}"
            }
            .toMutableList()
        extraReplies.filter { it.isNotBlank() }.forEach { text -> lines.add("$personaName：$text") }
        return lines.joinToString("\n")
    }

    private companion object {
        /** 单条消息最多随带几张图：一张 1280 长边约 1600 token，多了容易顶到上限喵。 */
        const val MAX_IMAGES_PER_MESSAGE = 3

        /** 提炼时最多回看多少条历史喵。 */
        const val TRANSCRIPT_TURNS = 60

        /** 送给提炼模型的文本上限，防止把上下文撑爆喵。 */
        const val TRANSCRIPT_LIMIT = 12_000

        /** 润色请求至少给的生成额度：润色比正文短，但给太紧会被截断喵。 */
        const val MIN_REFINE_TOKENS = 1024
    }
}
