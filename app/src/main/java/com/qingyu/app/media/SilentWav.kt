package com.qingyu.app.media

import java.io.ByteArrayOutputStream

/**
 * 生成极短静音 WAV，用来探测语音识别接口是否可用喵。
 *
 * 设置页的「测试语音识别」需要一个**格式合法、内容为空**的音频样本：
 * 目的是让服务端走完"收下文件 → 解析 → 返回结果"的全过程，
 * 从而验证地址、Key、模型名这三样是否被接受喵。
 * 静音本来就该转不出文字，所以返回空文本是**成功**而不是失败喵。
 *
 * 抽成独立对象而不是留在 ViewModel 里，是因为它是一段纯字节拼装：
 * WAV 头只要有一个字段写错，服务端就会报"格式不支持"，
 * 而那种错误在真机上只能靠反复试出来喵。放在这里就能被单元测试逐字段钉住喵。
 *
 * 手写 44 字节的 RIFF/WAVE 头而不是引入音频库：为了一个探活样本
 * 拖进一个音频依赖，代价远大于这二十行喵。
 */
object SilentWav {

    /** 采样率固定 16 kHz——与 [AudioRecorder] 的录音参数一致，识别服务最认这个喵。 */
    const val SAMPLE_RATE = 16_000

    /** 声道数固定单声道喵。 */
    const val CHANNELS = 1

    /** 位深固定 16 bit喵。 */
    const val BITS_PER_SAMPLE = 16

    /** 默认时长：0.2 秒，够服务端走完流程又不浪费流量喵。 */
    const val DEFAULT_MILLIS = 200

    /**
     * 造一段静音 WAV 喵。
     *
     * @param millis 时长（毫秒），下限 1 毫秒——长度为 0 的音频会被部分网关直接拒掉喵
     */
    fun build(millis: Int = DEFAULT_MILLIS): ByteArray {
        val safeMillis = millis.coerceAtLeast(1)
        val bytesPerSample = BITS_PER_SAMPLE / 8
        val sampleCount = SAMPLE_RATE * safeMillis / 1000
        val dataSize = sampleCount * bytesPerSample * CHANNELS
        val byteRate = SAMPLE_RATE * CHANNELS * bytesPerSample
        val blockAlign = CHANNELS * bytesPerSample

        val out = ByteArrayOutputStream(44 + dataSize)
        fun ascii(text: String) = out.write(text.toByteArray(Charsets.US_ASCII))
        // 小端写入：RIFF 规定多字节字段一律小端，写反了服务端会读成天文数字喵。
        fun le32(value: Int) = repeat(4) { out.write((value shr (it * 8)) and 0xFF) }
        fun le16(value: Int) = repeat(2) { out.write((value shr (it * 8)) and 0xFF) }

        ascii("RIFF")
        le32(36 + dataSize)          // 之后所有字节数喵
        ascii("WAVE")
        ascii("fmt ")
        le32(16)                     // PCM 的 fmt 块固定 16 字节喵
        le16(1)                      // 编码格式 1 = PCM喵
        le16(CHANNELS)
        le32(SAMPLE_RATE)
        le32(byteRate)
        le16(blockAlign)
        le16(BITS_PER_SAMPLE)
        ascii("data")
        le32(dataSize)
        // 静音：采样值全 0喵。
        repeat(dataSize) { out.write(0) }
        return out.toByteArray()
    }
}
