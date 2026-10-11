package com.qingyu.app.tts

import android.content.Context
import android.media.MediaPlayer
import com.qingyu.app.data.AppSettings
import com.qingyu.app.data.SettingsStore
import com.qingyu.app.data.TtsProvider
import com.qingyu.app.data.effectiveSpeechModel
import com.qingyu.app.remote.SpeechClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 语音播放：AI 合成优先，系统 TTS 兜底喵。
 *
 * 为什么要有这条回退链：AI 合成依赖网络与 key，一旦没配、超时或被限流喵，
 * "点朗读没反应"是最难排查的体验问题。所以只要 AI 这条路走不通喵，
 * 就静默退回系统 TTS——音色差一点，但一定能出声喵。
 *
 * 合成的音频会缓存到 `cache/tts`，同一段文字重复朗读不再二次付费喵。
 */
class SpeechPlayer(
    private val context: Context,
    private val client: SpeechClient,
    private val settingsStore: SettingsStore,
    private val systemTts: TextToSpeechEngine = TextToSpeechEngine(context),
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var player: MediaPlayer? = null
    private var job: Job? = null

    private val _state = MutableStateFlow(SpeechState())
    val state: StateFlow<SpeechState> = _state.asStateFlow()

    /** [error] 保留最近一次失败原因，供设置页的"测试"按钮展示喵。 */
    data class SpeechState(
        val speaking: Boolean = false,
        val preparing: Boolean = false,
        val usingAi: Boolean = false,
        val error: String? = null,
    )

    private val cacheDir: File get() = File(context.cacheDir, "tts").apply { mkdirs() }

    fun speak(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        stop()

        val settings = settingsStore.current
        if (settings.ttsProvider == TtsProvider.OPENAI && settings.speech.baseUrl.isNotBlank()) {
            job = scope.launch { speakWithAi(clean) }
        } else {
            speakWithSystem(clean)
        }
    }

    /** 直接走 AI 合成；失败时抛出，供设置页的测试按钮给出明确报错喵。 */
    suspend fun synthesizeToFile(text: String): File {
        val settings = settingsStore.current
        val bytes = client.synthesize(
            endpoint = settingsStore.speechEndpoint,
            apiKey = settings.speech.apiKey,
            model = settings.effectiveSpeechModel(),
            voice = settings.speechVoice,
            format = settings.speechFormat,
            text = text,
            speed = settings.ttsRate,
        )
        val file = cacheFile(text, settings)
        file.writeBytes(bytes)
        return file
    }

    /**
     * 音频缓存的文件名喵。
     *
     * 用"文本 + 音色 + 模型"三者的哈希，而不是把音色名直接拼进去喵：
     * 导入的外部音色是一条 URI，里面带 `:` 这类不适合出现在文件名里的字符喵；
     * 而且同一段文字换了模型就该重新合成，缓存键必须含模型，
     * 否则换了模型还是会放回旧音色的音频喵。
     */
    private fun cacheFile(text: String, settings: AppSettings): File {
        val voiceKey = "${settings.speechVoice}|${settings.effectiveSpeechModel()}".hashCode()
        return File(cacheDir, "tts_${text.hashCode()}_$voiceKey.${settings.speechFormat}")
    }

    private suspend fun speakWithAi(text: String) {
        _state.value = SpeechState(preparing = true, usingAi = true)
        try {
            val settings = settingsStore.current
            val cached = cacheFile(text, settings)
            val file = if (cached.exists() && cached.length() > 0) cached else synthesizeToFile(text)
            play(file)
        } catch (t: Throwable) {
            // AI 这条路不通就退回系统 TTS，不让用户面对"点了没反应"喵。
            _state.value = SpeechState(error = t.message ?: "AI 语音合成失败，已回退系统语音")
            speakWithSystem(text)
        }
    }

    private fun play(file: File) {
        val mediaPlayer = MediaPlayer()
        try {
            mediaPlayer.setDataSource(file.absolutePath)
            mediaPlayer.setOnPreparedListener { mp ->
                _state.value = SpeechState(speaking = true, usingAi = true)
                mp.start()
            }
            mediaPlayer.setOnCompletionListener { mp ->
                _state.value = SpeechState()
                mp.release()
                if (player === mp) player = null
            }
            mediaPlayer.setOnErrorListener { mp, _, _ ->
                _state.value = SpeechState(error = "音频播放失败")
                mp.release()
                if (player === mp) player = null
                true
            }
            mediaPlayer.prepareAsync()
            player = mediaPlayer
        } catch (t: Throwable) {
            runCatching { mediaPlayer.release() }
            _state.value = SpeechState(error = t.message ?: "音频播放失败")
        }
    }

    private fun speakWithSystem(text: String) {
        val settings = settingsStore.current
        systemTts.configure(rate = settings.ttsRate, pitch = settings.ttsPitch)
        systemTts.speak(text)
        _state.value = SpeechState(speaking = true, usingAi = false)
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching {
            player?.let { mp ->
                if (mp.isPlaying) mp.stop()
                mp.release()
            }
        }
        player = null
        systemTts.stop()
        _state.value = SpeechState()
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    /** 清空音频缓存喵。 */
    fun clearCache(): Int {
        val files = cacheDir.listFiles().orEmpty()
        var removed = 0
        files.forEach { if (runCatching { it.delete() }.getOrDefault(false)) removed++ }
        return removed
    }

    fun shutdown() {
        stop()
        systemTts.shutdown()
    }
}
