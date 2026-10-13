package com.qingyu.app.data.repo

import com.qingyu.app.core.TimeUtil
import com.qingyu.app.core.memory.PromptRefiner
import com.qingyu.app.core.memory.RefineOutcome
import com.qingyu.app.core.memory.RefineReason
import com.qingyu.app.core.memory.RefineSource
import com.qingyu.app.core.memory.UtilityLlm
import com.qingyu.app.data.AppSettings
import com.qingyu.app.data.Attachment
import com.qingyu.app.data.AttachmentKind
import com.qingyu.app.data.ChatMessage
import com.qingyu.app.data.Conversation
import com.qingyu.app.data.Endpoints
import com.qingyu.app.data.MemoryItem
import com.qingyu.app.data.MessageKind
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
import java.io.File

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

    // ------------------------------------------------------------ 未读

    /**
     * 给某段对话的未读数 +1，返回加完后的值喵。
     *
     * 只是把 [com.qingyu.app.data.db.ConversationStore] 转交出来喵：加未读这件事
     * 发生在生成流水线那一侧，而流水线不该直接抓数据库、绕过引擎这层封装喵。
     */
    suspend fun bumpUnread(conversationId: Long): Int = conversations.addUnread(conversationId)

    /** 把某段对话的未读清零喵。用户进这段对话时调用喵。 */
    suspend fun clearUnread(conversationId: Long) = conversations.clearUnread(conversationId)

    /** 全部未读之和喵，界面标签与桌面角标都要用喵。 */
    suspend fun totalUnread(): Int = conversations.totalUnread()

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
            // **必须把对话 id 补上**喵：图片是在"还不知道要发给哪段对话"时导入的，
            // 那时它的 conversationId 还是 0；而 [history] 是按 conversation_id 查附件的，
            // 落成 0 就等于这条消息再也查不到图——
            // 用户看到的是"发出去时还在、消息一刷新就没了"喵。
            val stamped = attachments.map { it.copy(conversationId = conversationId) }
            db.attachments.attachToMessage(id, stamped, conversationId)
            return message.copy(id = id, attachments = stamped)
        }
        return message.copy(id = id)
    }

    /**
     * 只生成、不插消息喵。
     *
     * 给"消息早已落库上屏、只是还没轮到生成"的流程用喵：用户发出消息的那一刻
     * 消息就已经进库了（所以界面立刻能看到、也能立刻继续发），
     * 之后等合并窗口关闭、且引擎空出来，才调用这里真正跑一轮喵。
     *
     * 与 [send] 的区别只有一件事：**绝不插入用户消息**喵。
     * 之所以单开一个入口而不是让调用方传 `existingUserMessageId = 0`，
     * 是因为那个参数的含义是"我还没插，你来插"——传 0 会插出重复消息喵。
     */
    fun generate(
        conversationId: Long,
        personaId: Long,
        searchRequested: Boolean = false,
    ): Flow<ChatEvent> = flow {
        val settings = settingsStore.current
        val persona = db.personas.get(personaId) ?: run {
            emit(ChatEvent.Failed("角色不存在"))
            return@flow
        }
        generateInto(conversationId, persona, settings, searchRequested).collect { emit(it) }
    }

    /**
     * 让角色**主动开话**喵（定时任务用）。
     *
     * 与 [generate] 的区别只有一处：会临时往上下文里插一句"开话由头"喵。
     *
     * **为什么必须有这个由头**喵：不插的话，模型看到的历史最后一条是它自己
     * 上次说的话，于是它会接着那句话往下说——用户看到的是"角色自言自语"，
     * 或者干脆回一句"嗯嗯"这种没有信息量的东西喵。
     * 明确告诉它"现在轮到你主动开口了"，它才会真的找话题喵。
     *
     * 这句由头**只进请求、不落库**喵：它是给模型的舞台提示，
     * 不是对话内容；落库会让用户在自己的聊天记录里看到一句莫名其妙的话喵。
     */
    fun generateProactive(
        conversationId: Long,
        personaId: Long,
        searchRequested: Boolean = false,
    ): Flow<ChatEvent> = flow {
        val settings = settingsStore.current
        val persona = db.personas.get(personaId) ?: run {
            emit(ChatEvent.Failed("角色不存在"))
            return@flow
        }
        generateInto(
            conversationId = conversationId,
            persona = persona,
            settings = settings,
            searchRequested = searchRequested,
            proactiveCue = true,
        ).collect { emit(it) }
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
     *
     * **表情包不走这条路**喵：它的语义已经由名字给定了（见 [PromptBuilder] 的
     * renderHistoryContent），而识图模型的措辞每次都不完全一样。
     * 让同一张表情每次都被重新描述，模型读到的就是三段不同的文字，
     * 自然表现得像"每次都头一回见"喵——那正是"连发同一张表情反应一样"的根因喵。
     */
    private suspend fun describePendingImages(
        history: List<ChatMessage>,
        settings: AppSettings,
    ): List<ChatMessage> {
        val store = imageStore ?: return history
        val capable = settings.visionMainModelSupportsImages
        val pending = history.filter {
            it.role == MessageRole.USER &&
                it.kind != MessageKind.STICKER &&
                it.attachments.any { attachment -> attachment.description.isBlank() }
        }
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

    /**
     * 主动开话时补在末尾的那句舞台提示喵。
     *
     * 写成"用户侧"的一句话，是因为模型对 user 轮的反应就是"该我回了"喵。
     * 内容刻意**不指定话题**：让角色按人设、记忆与当前时段自己找话头，
     * 指定了反而会让每次主动消息都长得差不多喵。
     *
     * 明确要求"别提这是自动消息"喵：那会立刻把角色打回机器，
     * 用户要的是"它想起我了"，不是"一个定时任务触发了"喵。
     */
    private val PROACTIVE_CUE = """
        （现在距离你们上次说话已经过去了一段时间，你忽然想起了对方，想主动说句话。）
        请你以角色本人的身份，主动开口聊一句。
        要求：
        1. 结合你们的关系、你记得的事、以及现在的时间，挑一个自然的话头；
        2. 不要提"自动""定时""系统"这类词，也不要解释你为什么忽然说话——就像你真的刚好想起了对方；
        3. 简短、口语化，一到两句就够。
    """.trimIndent()

    private fun generateInto(
        conversationId: Long,
        persona: Persona,
        settings: AppSettings,
        searchRequested: Boolean = false,
        /** 这一轮是"角色主动开话"喵（定时任务），要额外给模型一句开话由头喵。 */
        proactiveCue: Boolean = false,
    ): Flow<ChatEvent> = flow {
        val startedAt = TimeUtil.now()
        val stream = ChatStreamState(generating = true)

        val history = messages.list(conversationId).withAttachments(conversationId).let { loaded ->
            // 主模型看不了图时，先把图变成文字描述并落库；之后每次生成都复用这段描述喵。
            describePendingImages(loaded, settings)
        }

        // 这个角色能发哪些表情包：交给提示词，模型才知道自己可以发喵。
        val stickerNames = runCatching { db.stickers.list().map { it.name } }.getOrDefault(emptyList())

        // 发表情包的积极度跟着**实际生效的温度**走喵：
        // 角色可以单独覆盖温度，而那个才是真正发出去的参数；
        // 若这里读全局设置，会给模型一个与它实际随机性不匹配的频率要求喵。
        val effectiveTemperature = persona.temperatureOverride ?: settings.temperature
        val stickerEagerness = StickerEagerness.levelFor(effectiveTemperature)

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
            // 把可用表情名交给提示词，模型才知道自己能发哪些喵。
            stickerNames = stickerNames,
            stickerEagerness = stickerEagerness,
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

        // 同理也要护住【发送要求】喵：它决定了回复会不会被断成多条短消息。
        // 小模型很容易把它当排版说明删掉，而一旦没了它，主模型就会交出一整段
        // 不带逗号的长句——用户在聊天里看到的就是"既没拆开也没逗号"喵。
        // 宁可放弃这次润色，也不能让断句要求丢掉喵。
        val keptSendingRules = !settings.burstReplies ||
            !refineOutcome.refined ||
            PromptBuilder.keepsSendingRules(refineOutcome.prompt)

        val systemContent =
            if (keptTemporal && keptSendingRules) refineOutcome.prompt else rawSystem

        val turns = if (systemIndex >= 0) {
            built.toMutableList().also { it[systemIndex] = ChatTurn("system", systemContent) }
        } else {
            built
        }.let { list ->
            // 主动开话：往末尾补一条**只进请求、不落库**的用户侧提示喵。
            // 见 [generateProactive] 的说明——没有它，模型会接着自己上一句话往下说，
            // 用户看到的就是"角色自言自语"喵。
            if (proactiveCue) list + ChatTurn("user", PROACTIVE_CUE) else list
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
        // 本轮一共落了几条表情：判断"到底有没有产出"时要用喵。
        var savedStickerCount = 0
        val accumulator = BurstAccumulator { segment ->
            val persisted = persistBubble(conversationId, segment, savedReplies.size)
            savedStickerCount += persisted.stickers.size
            val saved = persisted.bubble
            if (saved != null) {
                savedIds.add(saved.id)
                savedReplies.add(saved.text)
                state = state.copy(bubbles = state.bubbles + saved)
                // **文字先上屏、表情后上屏**喵：persistBubble 给表情的时间戳排在文字之后
                // （now + order + index + 1），先发文字才与时间戳的顺序一致喵。
                // 反过来的话表情会插到它本该在的那句话前面，要等整轮结束 reload 才被摆正——
                // 用户看到的就是"表情先冒出来、位置还不对"喵。
                emit(ChatEvent.Saved(saved.toMessage(conversationId)))
            } else {
                // null 表示这一段只有表情、没有文字。段数仍要推进，
                // 否则下一段的时间戳会与这一段的挤在一起喵。
                savedReplies.add("")
            }
            persisted.stickers.forEach { emit(ChatEvent.Saved(it)) }
        }

        try {
            val model = persona.modelOverride.ifBlank { settings.model }
            val temperature = effectiveTemperature
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
            // 一个字都没产出：模型给了空回复，或全部内容都是被清掉的乱码喵。
            // 这时必须明确报错，否则界面上"正在输入"会安静地消失、用户以为消息丢了喵。
            if (savedIds.isEmpty() && savedStickerCount == 0 && reasoningText.isBlank()) {
                emit(ChatEvent.Failed("这次没生成出内容来，再说一句试试"))
                return@flow
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

    /**
     * 把一段成型文本写成助手消息喵。
     *
     * 这里顺手处理模型写的「发表情包」指令喵（见 [StickerDirective]）：
     * 指令行从正文里剥掉，剩下的文字照常落成一条消息；
     * 每个指令再单独落成一条**表情包消息**，与用户发的表情走同一套渲染喵。
     *
     * 顺序是"先文字后表情"喵：模型通常是"说完一句话，顺手配个表情"，
     * 反过来会显得表情孤零零地抢在话前面喵。
     *
     * 本函数不 emit——它不是 flow 作用域里的代码，拿不到 `emit` 喵。
     * 落好的表情消息随返回值交给调用方去发事件喵。
     *
     * @return 本段的结果：文字气泡（可能为 null，表示整段只有表情）
     *   与已落库的表情消息喵
     */
    private suspend fun persistBubble(
        conversationId: Long,
        text: String,
        order: Int,
    ): PersistedSegment {
        val available = runCatching { db.stickers.list() }.getOrDefault(emptyList())
        val parsed = StickerDirective.parse(text, available.map { it.name }.toSet())

        // 表情的时间戳排在文字之后，列表按时间戳排序就自然是"先话后表情"喵。
        val stickers = parsed.stickerNames.mapIndexedNotNull { index, name ->
            val sticker = available.firstOrNull { it.name == name } ?: return@mapIndexedNotNull null
            val timestamp = TimeUtil.now() + order + index + 1
            val message = ChatMessage(
                conversationId = conversationId,
                role = MessageRole.ASSISTANT,
                // 正文仍是名字：与用户发的表情保持同一套约定，
                // 提示词里再统一渲染成「（对方发来一个表情包：名字）」喵。
                content = sticker.name,
                timestamp = timestamp,
                kind = MessageKind.STICKER,
                stickerId = sticker.id,
            )
            val id = messages.insert(message)
            val attachment = Attachment(
                messageId = id,
                conversationId = conversationId,
                kind = AttachmentKind.IMAGE,
                localPath = sticker.filePath,
                mimeType = sticker.mimeType,
                width = sticker.width,
                height = sticker.height,
                sizeBytes = runCatching { File(sticker.filePath).length() }.getOrDefault(0L),
                createdAt = timestamp,
            )
            db.attachments.attachToMessage(
                messageId = id,
                attachments = listOf(attachment),
                conversationId = conversationId,
            )
            db.stickers.markUsed(sticker.id)
            // **把附件一起带回去**喵：这条消息是直接上屏的（见调用方），
            // 不带附件的话界面拿不到图，只能退化成占位符，
            // 要等整轮结束 reload 才从库里读回图——用户看到的就是
            // "表情先冒出个名字/占位、位置还不对，过一会儿才变成图"喵。
            message.copy(id = id, attachments = listOf(attachment))
        }

        // 挡掉"只是个标识符"的行喵（见 [GarbledReply]）：有用户反馈过偶尔收到一条
        // 长得像 UUID 的"回复"，而全项目都不生成这种串，它来自模型/网关那边喵。
        // 拿不到原始响应就无法根治，但这类内容一律是垃圾，落库前清掉用户至少不会看到乱码喵。
        //
        // 再清一遍"被抄回来的系统旁注"喵（见 [ReplySanitizer]）：模型有时会照着
        // 上下文里那些括号标注抄一行回过来，甚至把模板占位符「表情包名字」原样带出来喵。
        // 两道清洗串起来跑：乱码先走，剩下的再判旁注喵。
        // 三道清洗串起来跑，顺序有讲究喵：
        // 1. 先剥乱码——它连标点都没有，后面两步对它没意义；
        // 2. 再判系统旁注——它按"整行"判，得趁标点还没被动过；
        // 3. 最后补逗号（见 [CommaRepair]）——它只处理"完全没标点的片段"，
        //    所以必须放在把指令与旁注都抠走之后，否则会往那些字段里插逗号喵。
        val bodyText = CommaRepair.repair(
            ReplySanitizer.sanitize(GarbledReply.stripOpaqueLines(parsed.text)),
        )
        // 整段只有表情、没有文字时返回 null 气泡：
        // 若返回一条空文本气泡，调用方会拿它发 Saved，带着表情的 id 把表情覆盖成空白喵。
        // 清掉乱码后剩下的也是空，同样按"没有文字"处理喵。
        val bubble = if (bodyText.isBlank()) {
            null
        } else {
            val message = ChatMessage(
                conversationId = conversationId,
                role = MessageRole.ASSISTANT,
                content = bodyText,
                // 加 order 毫秒是为了保证同一批消息的先后顺序稳定喵
                timestamp = TimeUtil.now() + order,
            )
            val id = messages.insert(message)
            ChatStreamState.Bubble(id = id, text = bodyText, timestamp = message.timestamp)
        }
        return PersistedSegment(bubble = bubble, stickers = stickers)
    }

    /** [persistBubble] 的产出喵。 */
    private data class PersistedSegment(
        val bubble: ChatStreamState.Bubble?,
        val stickers: List<ChatMessage>,
    )

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
        return fetchModels(settings.apiBaseUrl, settings.apiKey)
    }

    /**
     * 按任意一组地址与 key 拉模型列表喵。
     *
     * 识图、语音、小模型这些能力各自可能有独立的服务商，所以不能只认主连接那一组喵；
     * 只要它是 OpenAI 兼容端点，`GET /models` 就是通用的喵。
     *
     * **key 允许为空**：本地 Ollama 这类不需要鉴权的端点也要能列模型，
     * 客户端那边在没有 key 时本就不发 Authorization 头喵。
     */
    suspend fun fetchModels(baseUrl: String, apiKey: String): kotlin.Result<List<String>> {
        val endpoint = Endpoints.models(baseUrl)
        if (endpoint.isBlank()) return kotlin.Result.failure(IllegalStateException("还没有配置接口地址"))
        return runCatching { client.listModels(endpoint, apiKey) }
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
