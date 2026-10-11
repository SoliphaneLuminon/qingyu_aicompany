package com.qingyu.app.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.qingyu.app.core.TimeUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 录音：把麦克风里的声音录成一个临时文件，交给语音识别喵。
 *
 * 三个取舍写在这里，免得以后被"顺手优化"掉喵：
 * - **16 kHz 单声道 64 kbps**：语音识别接口的标准输入就是 16 kHz 单声道喵，
 *   48 kHz 立体声只会让上传体积翻几倍，识别率并不会变好喵；
 *   采样率再往下压，高频辅音丢失，中文识别率会明显掉喵。
 * - **AAC in MP4（`.m4a`）**：同样一分钟语音，AAC 只有几百 KB，PCM 要好几 MB喵，
 *   上传体积直接决定用户按下"发送"后要等多久喵。
 * - **落在 `cacheDir`**：录音只是转写过程的中间产物，转完就没用了喵，
 *   放缓存目录既不占用户的存储配额，系统清理缓存时也会自动回收喵。
 *
 * 失败约定：**没权限或已经在录时 [start] 返回 false，麦克风打不开时抛 [Failure]**喵。
 * 前者是调用方必须能预判的正常分支，后者是设备或环境异常，混在同一个返回值里会分不清喵。
 */
class AudioRecorder(private val context: Context) {

    /** 录音起不来：麦克风被别的应用占着、设备不认这个音源等喵。 */
    class Failure(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var startedAt = 0L

    /** 是否已拿到录音权限喵。这里只查询，不弹授权框——弹框是界面层的职责喵。 */
    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    /** 录音是否进行中喵。 */
    val isRecording: Boolean get() = recorder != null

    /**
     * 开始录音喵。
     *
     * @return 没权限、或已经在录时返回 false喵；
     *   麦克风打不开（被别的应用占用、设备不支持）时抛 [Failure]喵。
     */
    fun start(): Boolean {
        if (isRecording) return false
        if (!hasPermission()) return false

        val file = newFile()
        val opened = try {
            openRecorder(file)
        } catch (t: Throwable) {
            // 半成品文件长度为 0，留着只会白占缓存，失败路径上就地删掉喵。
            runCatching { file.delete() }
            throw Failure("麦克风打不开，可能被别的应用占用了", t)
        }

        try {
            opened.start()
        } catch (t: Throwable) {
            runCatching { opened.release() }
            runCatching { file.delete() }
            throw Failure("录音启动失败，可能被别的应用占用了", t)
        }

        recorder = opened
        currentFile = file
        startedAt = SystemClock.elapsedRealtime()
        return true
    }

    /**
     * 停止录音，返回录到的音频文件喵。
     *
     * 整段放 [Dispatchers.IO]：`stop()` 要把编码器缓冲刷进文件，在低端机上会阻塞几百毫秒喵。
     *
     * @return 录到的文件；没在录、录得太短、文件为空时返回 null喵。
     */
    suspend fun stop(): File? = withContext(Dispatchers.IO) {
        val active = recorder ?: return@withContext null
        val file = currentFile
        recorder = null
        currentFile = null
        startedAt = 0L

        val stopped = try {
            active.stop()
            true
        } catch (t: Throwable) {
            // 录制不足一秒时 stop() 会直接抛 RuntimeException，这种录音本来也没有内容，当成"没录到"喵。
            false
        } finally {
            // release 必须在 finally 里：漏掉一次，下一次 start() 会因为麦克风仍被自己占着而失败喵。
            runCatching { active.release() }
        }

        val recorded = file?.takeIf { it.isFile && it.length() > 0L }
        if (!stopped || recorded == null) {
            runCatching { file?.delete() }
            return@withContext null
        }
        recorded
    }

    /** 放弃录音并删掉临时文件喵。 */
    fun cancel() {
        val active = recorder
        val file = currentFile
        recorder = null
        currentFile = null
        startedAt = 0L
        // 直接 release 而不是 stop：stop 会把半截录音落盘，还会因为时长太短而抛异常喵。
        runCatching { active?.release() }
        runCatching { file?.delete() }
    }

    /**
     * 已经录了多久（毫秒）喵。
     *
     * 用 [SystemClock.elapsedRealtime] 而不是墙钟：计时不该被用户改系统时间或时区切换影响喵。
     * 没在录时返回 0，界面上的计时器也就自然归零喵。
     */
    fun elapsedMs(): Long = if (recorder == null) 0L else SystemClock.elapsedRealtime() - startedAt

    // ------------------------------------------------------------ 内部喵

    private fun newFile(): File {
        val dir = File(context.cacheDir, DIR_NAME).apply { mkdirs() }
        return File(dir, "voice_${TimeUtil.now()}.m4a")
    }

    /** 按 [AUDIO_SOURCES] 的顺序尝试打开麦克风，全失败时抛出最后一个异常喵。 */
    private fun openRecorder(file: File): MediaRecorder {
        var last: Throwable? = null
        for (source in AUDIO_SOURCES) {
            val attempt = runCatching { prepare(source, file) }
            attempt.getOrNull()?.let { return it }
            last = attempt.exceptionOrNull()
        }
        throw last ?: Failure("麦克风打不开")
    }

    /**
     * 按语音识别的参数配好一个 [MediaRecorder] 并 prepare喵。
     *
     * API 31 起无参构造被弃用，但 minSdk 是 26，只能分版本挑构造喵。
     */
    @Suppress("DEPRECATION")
    private fun prepare(source: Int, file: File): MediaRecorder {
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
        try {
            recorder.setAudioSource(source)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(SAMPLE_RATE_HZ)
            recorder.setAudioChannels(1)
            recorder.setAudioEncodingBitRate(BIT_RATE)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            return recorder
        } catch (t: Throwable) {
            runCatching { recorder.release() }
            throw t
        }
    }

    companion object {
        private const val DIR_NAME = "voice"

        /** 语音识别的标准采样率喵。 */
        private const val SAMPLE_RATE_HZ = 16_000

        /** 16 kHz 单声道下 64 kbps 已经够语音用，再高只是白白多传字节喵。 */
        private const val BIT_RATE = 64_000

        /**
         * 音源按优先级排喵。
         *
         * `VOICE_RECOGNITION` 是给识别场景准备的：它绕开一部分为通话优化的处理，
         * 吐出来的音频更贴近识别模型想要的输入喵；少数设备不认这个音源，prepare 会失败，所以留 MIC 兜底喵。
         */
        private val AUDIO_SOURCES = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
    }
}
