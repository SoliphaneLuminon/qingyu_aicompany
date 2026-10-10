package com.qingyu.app

import android.content.Context
import com.qingyu.app.data.SettingsStore
import com.qingyu.app.data.db.QingYuDatabase
import com.qingyu.app.data.repo.ChatEngine
import com.qingyu.app.data.repo.ConversationRepository
import com.qingyu.app.data.repo.MemoryDirector
import com.qingyu.app.data.repo.MemoryExtractionService
import com.qingyu.app.data.repo.MemoryTidyService
import com.qingyu.app.data.archive.ChatArchive
import com.qingyu.app.media.ImageStore
import com.qingyu.app.remote.ChatClient
import com.qingyu.app.remote.SearchClient
import com.qingyu.app.remote.SpeechClient
import com.qingyu.app.remote.UtilityLlmClient
import com.qingyu.app.remote.VisionClient
import com.qingyu.app.tts.SpeechPlayer
import com.qingyu.app.tts.TextToSpeechEngine
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
    val utilityLlm: UtilityLlmClient by lazy { UtilityLlmClient(chatClient) }
    val memoryDirector: MemoryDirector by lazy { MemoryDirector(database) }
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
    val archive: ChatArchive by lazy { ChatArchive(appContext, database) }
    val tts: TextToSpeechEngine by lazy { TextToSpeechEngine(appContext) }
    val speech: SpeechPlayer by lazy { SpeechPlayer(appContext, speechClient, settings) }

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        fun get(context: Context): AppContainer = instance ?: synchronized(this) {
            instance ?: AppContainer(context).also { instance = it }
        }
    }
}
