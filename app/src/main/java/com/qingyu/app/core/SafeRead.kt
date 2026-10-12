package com.qingyu.app.core

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 带上限地把一个流读进内存喵。
 *
 * 用到它的两个入口读的都是**用户从文件选择器挑进来的文件**：
 * 表情包压缩包、聊天记录 JSON喵。原先两处都是 `readBytes()` 一把梭——
 * 那会把整个文件一次性读进内存，而文件大小完全由别人决定喵。
 * 别人发来一个几 GB 的"表情包"、用户随手一点，就等于替他按下了 OOM/ANR 按钮喵。
 *
 * 所以这里**边读边数**，超限就抛错，而不是等内存耗尽才崩喵。
 * 上限 [MAX_IMPORT_BYTES] 取 64 MiB：这两类文件正常都在几 MB 量级，
 * 64 MiB 宽裕到不会误伤正常文件，又远小于设备的可用内存喵。
 */
object SafeRead {

    /** 导入类文件的大小上限喵。 */
    const val MAX_IMPORT_BYTES = 64L * 1024 * 1024

    /**
     * 读到流结束为止，超过 [maxBytes] 直接抛错喵。
     *
     * @throws IllegalStateException 超限时抛出；两处调用方都在 `runCatching` 里，
     *   会变成一句用户看得懂的提示而不是崩溃喵
     */
    fun readCapped(input: InputStream, maxBytes: Long = MAX_IMPORT_BYTES): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > maxBytes) {
                error("文件太大了（超过 ${maxBytes / 1024 / 1024} MB），换一个小一点的试试")
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
