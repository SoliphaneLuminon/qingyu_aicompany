package com.qingyu.app

import android.content.Context
import com.qingyu.app.data.SettingsStore
import com.qingyu.app.data.db.QingYuDatabase
import com.qingyu.app.data.repo.ChatEngine
import com.qingyu.app.data.repo.ConversationRepository
import com.qingyu.app.data.repo.MemoryDirector
import com.qingyu.app.data.repo.MemoryExtractionService
import com.qingyu.app.data.repo.MemoryTidyService
import com.qingyu.app.data.repo.StickerRepository
import com.qingyu.app.data.archive.ChatArchive
import com.qingyu.app.media.AudioRecorder
import com.qingyu.app.media.AvatarStore
import com.qingyu.app.media.ImageStore
import com.qingyu.app.remote.ChatClient
import com.qingyu.app.remote.SearchClient
import com.qingyu.app.remote.SpeechClient
import com.qingyu.app.remote.TranscriptionClient
import com.qingyu.app.remote.UtilityLlmClient
import com.qingyu.app.remote.VisionClient
import com.qingyu.app.tts.SpeechPlayer
import com.qingyu.app.tts.TextToSpeechEngine
import com.qingyu.app.notify.AppVisibility
import com.qingyu.app.notify.ReplyNotifier
import com.qingyu.app.data.repo.ChatReplyCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 手写的依赖容器喵。
 *
 * 依赖只有十来个、生命周期和应用一致，用 Hilt/Koin 反而增加构建复杂度与出错面喵。
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    /**
     * 应用级作用域：只用于"界面可能已经没了，但事情还得做完"的任务喵，
     * 目前是每轮结束后的记忆提炼喵。
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: QingYuDatabase by lazy { QingYuDatabase(appContext) }
    val settings: SettingsStore by lazy { SettingsStore(appContext) }
    val chatClient: ChatClient by lazy { ChatClient() }
    val searchClient: SearchClient by lazy { SearchClient() }
    val speechClient: SpeechClient by lazy { SpeechClient() }
    val visionClient: VisionClient by lazy { VisionClient(chatClient) }
    val imageStore: ImageStore by lazy { ImageStore(appContext) }
    val avatarStore: AvatarStore by lazy { AvatarStore(appContext) }
    val audioRecorder: AudioRecorder by lazy { AudioRecorder(appContext) }
    val transcription: TranscriptionClient by lazy { TranscriptionClient() }
    val utilityLlm: UtilityLlmClient by lazy { UtilityLlmClient(chatClient) }

    /**
     * 记忆检索与精排的编排喵。
     *
     * 后三个 lambda 是**延迟读取**的，不是多此一举喵：
     * `memoryDirector` 在 `chatEngine` 之前构建，而主模型的地址/模型/Key
     * 要等用户设置就绪才知道。用 lambda 而不是直接取值，
     * 是为了让"此刻读到什么"发生在**每次精排时**，而不是构建容器的那一刻——
     * 否则用户在设置页改了接口，精排还会一直用旧地址喵。
     */
    val memoryDirector: MemoryDirector by lazy {
        MemoryDirector(
            db = database,
            rerankerLlm = utilityLlm,
            mainEndpoint = { settings.chatEndpoint },
            mainModel = { settings.current.model },
            mainApiKey = { settings.current.apiKey },
        )
    }
    val memoryExtraction: MemoryExtractionService by lazy { MemoryExtractionService(database, utilityLlm) }

    /** 一次性整理记忆库（聚类 + AI 融合），只在用户手动触发时跑喵。 */
    val memoryTidy: MemoryTidyService by lazy { MemoryTidyService(database, utilityLlm) }
    val chatEngine: ChatEngine by lazy {
        ChatEngine(
            db = database,
            settingsStore = settings,
            client = chatClient,
            visionClient = visionClient,
            searchClient = searchClient,
            imageStore = imageStore,
            memoryDirector = memoryDirector,
            extraction = memoryExtraction,
            appScope = appScope,
        )
    }
    val conversations: ConversationRepository by lazy { ConversationRepository(database) }
    val stickers: StickerRepository by lazy { StickerRepository(appContext, database) }
    val archive: ChatArchive by lazy { ChatArchive(appContext, database) }
    val tts: TextToSpeechEngine by lazy { TextToSpeechEngine(appContext) }
    val speech: SpeechPlayer by lazy { SpeechPlayer(appContext, speechClient, settings) }

    /**
     * 应用是否在前台喵。回复跑完要不要发系统通知靠它判断喵。
     *
     * 由 [QingYuApp] 注册到 Activity 生命周期上，所以这里只是拿同一个实例喵。
     */
    val appVisibility: AppVisibility = AppVisibility()

    val replyNotifier: ReplyNotifier by lazy { ReplyNotifier(appContext) }

    /**
     * 发送与生成流水线的主人喵。
     *
     * 它跑在 [appScope] 上，所以**退出聊天框、甚至退出应用都不会中断回复**喵——
     * 这是用户明确要求的能力，也是它不放在 ViewModel 里的唯一原因喵。
     */
    val replyCoordinator: ChatReplyCoordinator by lazy {
        ChatReplyCoordinator(
            engine = chatEngine,
            scope = appScope,
            notifier = replyNotifier,
            visibility = appVisibility,
            appContext = appContext,
        )
    }

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        fun get(context: Context): AppContainer = instance ?: synchronized(this) {
            instance ?: AppContainer(context).also { instance = it }
        }
    }
}
