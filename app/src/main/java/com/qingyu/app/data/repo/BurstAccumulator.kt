package com.qingyu.app.data.repo

import com.qingyu.app.remote.PromptBuilder

/**
 * 流式"拆成多条消息"的拼接状态机喵。
 *
 * 不变式（[feed] 返回后始终成立喵）：
 * 1. [buffer] 里最多只有一段内容，且**不以换行开头或结尾**喵；
 * 2. 每个段落只会通过 `onSegment` 交出去**一次**喵。
 */
class BurstAccumulator(
    /** 每段成型时回调一次，绝不重复。允许挂起：调用方要在这里落库并推事件喵。 */
    private val onSegment: suspend (String) -> Unit,
) {

    /** 还没定型的尾巴（保留原始尾部空白，空行边界就是靠它体现的喵）。 */
    private var buffer: String = ""

    /** 本次回复已经交出去的段数喵。 */
    var segmentCount: Int = 0
        private set

    /** 当前正在增长的尾巴，供 UI 做乐观渲染喵。 */
    val currentTail: String get() = buffer.trim()

    suspend fun feed(chunk: String) {
        // 协议层已保证 JSON null 不会变成文本；这里再清一次喵，
        // 防止兼容型网关把字段值写成字符串 "null" 时漏进数据库喵。
        val incoming = PromptBuilder.stripNullArtifacts(chunk)
        if (incoming.isEmpty()) return

        // 只去掉前导空白：**尾部空白必须留着**，否则单独到达的 "\n\n" 喵
        // 会被当成尾部空白吃掉，该断的句子就断不开了喵。
        val full = (buffer + incoming).replace("\r\n", "\n").trimStart()
        if (full.isBlank()) {
            buffer = ""
            return
        }

        val segments = full.split(Regex("\n\\s*\n+"))
        if (segments.size <= 1) {
            // 还没有出现空行边界：整段都是尾巴喵。
            buffer = if (full.endsWith("\n")) full.trimEnd() + "\n" else full
            return
        }

        // 除最后一段外都算成型；每交出一段就从缓冲区切掉喵。
        segments.dropLast(1).forEach { segment -> emitSegment(segment) }
        buffer = segments.last().trim()
    }

    /**
     * 交出一段成型的内容：先按逗号/句读断句，再逐条回调喵。
     *
     * 空行只决定"消息边界"，**一条内部**的断句一律走 [splitByCommaAndPunctuation喵]。
     * 两条到达路径（流式走空行、非流式一次收完）必须同一套口径喵，
     * 否则同一句话会因为到达方式不同而断成不同条数，这种差异极难复现喵。
     *
     * 这也正是"提示词必须要求模型写逗号"的原因：切分器唯一的信号就是标点喵。
     */
    private suspend fun emitSegment(raw: String) {
        val cleaned = PromptBuilder.splitBurst(raw.trim(), enabled = false)
            .firstOrNull()?.trim().orEmpty()
        if (cleaned.isEmpty()) return
        splitByCommaAndPunctuation(cleaned, minLength = MIN_SEGMENT_LENGTH).forEach { part ->
            val text = part.trim()
            if (text.isEmpty()) return@forEach
            segmentCount++
            onSegment(text)
        }
    }

    /**
     * 流结束：把尾巴也交出去喵。
     *
     * 尾巴同样要过 [emitSegment]——它可能是一整段带逗号的长句喵
     * （流式过程中始终没出现空行，整段都留在缓冲区里喵）。
     */
    suspend fun finish() {
        val text = PromptBuilder.stripNullArtifacts(buffer).trim()
        buffer = ""
        if (text.isEmpty()) return
        emitSegment(text)
    }

    companion object {

        /**
         * 段内断句的最小长度喵。
         *
         * 标点前面的文字**超过**这个数才在此断成独立消息；不足则保留标点继续往后攒喵，
         * 免得"好的，""嗯嗯，"这类短应答被切得七零八落喵。
         */
        const val MIN_SEGMENT_LENGTH = 5

        /**
         * 拟真打字切分算法喵：
         * 1. 扫描中英文逗号（，,）、句号（。）、问号（！？!?）、分号（；;）以及换行符喵；
         * 2. 当标点前面的文本累积字数 **> [minLength]** 时，在此断开作为一条独立消息喵；
         * 3. 若字数 <= [minLength]（如"好的，"、"嗯嗯，"），则保留标点并与下一句合并，避免过于碎片化喵。
         *
         * 断开处的标点是**有意丢弃**的，不是漏加：真人用手机连发时几乎不会给每条都补句号喵，
         * 留着反而像在念稿子。只有"攒着不拆"的短应答才会把标点带进下一段喵。
         */
        fun splitByCommaAndPunctuation(text: String, minLength: Int = MIN_SEGMENT_LENGTH): List<String> {
            if (text.isBlank()) return emptyList()

            val delimiters = Regex("([，,。！？!?；;\n]+)")
            val result = mutableListOf<String>()
            var currentBuffer = StringBuilder()

            val matcher = delimiters.toPattern().matcher(text)
            var lastEnd = 0

            while (matcher.find()) {
                val matchStart = matcher.start()
                val matchEnd = matcher.end()

                val part = text.substring(lastEnd, matchStart)
                val punc = text.substring(matchStart, matchEnd)

                currentBuffer.append(part)

                val hasNewline = punc.contains("\n")
                val currentText = currentBuffer.toString().trim()

                // 遇到换行，或累积字数已超过阈值：断开成一条独立消息，标点就此丢弃喵。
                if (hasNewline || currentText.length > minLength) {
                    if (currentText.isNotEmpty()) {
                        result.add(currentText)
                    }
                    currentBuffer = StringBuilder()
                } else {
                    // 不足阈值的短应答保留标点，继续向后攒，避免碎片化喵。
                    currentBuffer.append(punc)
                }

                lastEnd = matchEnd
            }

            // 处理末尾剩余的文本喵
            if (lastEnd < text.length) {
                currentBuffer.append(text.substring(lastEnd))
            }

            val remaining = currentBuffer.toString().trim()
            if (remaining.isNotEmpty()) {
                result.add(remaining)
            }

            return if (result.isNotEmpty()) result else listOf(text)
        }
    }
}