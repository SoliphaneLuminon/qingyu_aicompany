package com.qingyu.app.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

/**
 * 本地语音朗读喵。
 *
 * 用系统 TTS：零体积、离线可用。内置 sherpa-onnx + onnx 模型（约 100MB 以上）音色更好喵，
 * 但会把 APK 撑大一个数量级，所以这里把它留成可替换的接口喵
 * （[speak] 的 voiceHint 参数就是留给未来接入 onnx 音色的喵）。
 */
class TextToSpeechEngine(private val context: Context) {

    private var engine: TextToSpeech? = null

    private var pendingLocale: Locale = Locale.SIMPLIFIED_CHINESE

    /** 首次调用时异步初始化系统 TTS 引擎喵。 */
    fun warmUp() {
        if (engine != null) return
        engine = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                applyLocale(pendingLocale)
                engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                    }

                    override fun onDone(utteranceId: String?) {
                    }

                    @Deprecated("系统回调", ReplaceWith(""))
                    override fun onError(utteranceId: String?) {
                    }
                })
            } else {
            }
        }
    }

    /** 中文优先，找不到中文再退回系统默认喵。 */
    private fun applyLocale(locale: Locale) {
        val tts = engine ?: return
        val result = tts.isLanguageAvailable(locale)
        if (result >= TextToSpeech.LANG_AVAILABLE) {
            tts.language = locale
        } else {
            tts.language = Locale.getDefault()
        }
    }

    fun configure(locale: Locale = Locale.SIMPLIFIED_CHINESE, rate: Float, pitch: Float) {
        warmUp()
        pendingLocale = locale
        applyLocale(locale)
        engine?.setSpeechRate(rate.coerceIn(0.4f, 2.2f))
        engine?.setPitch(pitch.coerceIn(0.5f, 1.8f))
    }

    /**
     * 朗读一段文本喵。
     *
     * @param voiceHint 预留：未来接入本地神经网络音色时用来选具体音色喵。
     */
    fun speak(text: String, voiceHint: String = "", flush: Boolean = true) {
        val clean = sanitize(text)
        if (clean.isBlank()) return
        warmUp()
        val tts = engine
        if (tts == null) {
            return
        }
        val queueMode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val result = tts.speak(clean, queueMode, null, UUID.randomUUID().toString())
        if (result == TextToSpeech.ERROR) {
        }
    }

    fun stop() {
        engine?.stop()
    }

    fun shutdown() {
        engine?.stop()
        engine?.shutdown()
        engine = null
    }

    /** 去掉不适合朗读的符号与 Markdown，避免念出"星号星号"喵。 */
    private fun sanitize(text: String): String = text
        .replace(Regex("```[\\s\\S]*?```"), "（代码块）")
        .replace(Regex("[*_`>#~]"), "")
        .replace(Regex("\\(.*?笑.*?\\)"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(1200)
}
