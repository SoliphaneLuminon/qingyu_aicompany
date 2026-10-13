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

    /**
     * 给界面看的尾巴：**把还没闭合的表情包指令藏掉**喵。
     *
     * 模型是流式吐字的，写到 `【表情包：笑】` 的中途必然出现
     * `【表情包：` 这种半截形态喵。那是解析中的中间态，既不是话也不是表情，
     * 让用户看到半个方括号纯属噪音喵。
     */
    val displayTail: String get() = StickerDirective.hideOpenDirective(currentTail)

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

        // **表情包指令一闭合就立刻交出去**喵，不等空行、也不等流结束喵。
        //
        // 用户报的现象是「角色回复时先挂一条表情包名称的文字消息，
        // 最后生成完才显示图像」喵。根因就是：模型写指令通常只用一个换行
        // （`说句话\n【表情包：笑】`），而空行拆分要等 `finish()` 才收尾，
        // 于是那一行 `【表情包：笑】` 会先以**原文**上屏，过一会儿才变成图喵。
        //
        // 这里在指令闭合的那一刻就把前半段和指令一起交出去，
        // 用户看到的就是「文字一条、表情一条」按顺序逐条出现喵。
        val directiveRange = StickerDirective.findComplete(full)
        if (directiveRange != null) {
            val head = full.substring(0, directiveRange.first)
            val directive = full.substring(directiveRange.first, directiveRange.last + 1)
            if (head.isNotBlank()) emitSegment(head)
            // 指令整条交给 emitSegment：那里的括号保护会让它**不被切断**喵。
            emitSegment(directive)
        }

        // 待处理的整段：有闭合指令时是它后面那一截，否则就是 `full` 本身喵。
        // **不能读 `buffer`**：它还是这一轮之前的值，新到的 chunk 会整个丢掉喵。
        val pending = if (directiveRange != null) {
            full.substring(directiveRange.last + 1).trimStart()
        } else {
            full
        }

        if (pending.isBlank()) {
            buffer = ""
            return
        }

        val segments = pending.split(Regex("\n\\s*\n+"))
        if (segments.size <= 1) {
            // 还没有出现空行边界：整段都是尾巴喵。
            buffer = if (pending.endsWith("\n")) pending.trimEnd() + "\n" else pending
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
         * 1. 只扫描**逗号、句号、叹号、问号、分号**（含它们的美式写法）与换行符喵；
         * 2. 当标点前面的文本累积字数 **> [minLength]** 时，在此断开作为一条独立消息喵；
         * 3. 若字数 <= [minLength]（如"好的，"、"嗯嗯，"），则保留标点并与下一句合并，避免过于碎片化喵。
         *
         * 断开处的标点是**有意丢弃**的，不是漏加：真人用手机连发时几乎不会给每条都补句号喵，
         * 留着反而像在念稿子。只有"攒着不拆"的短应答才会把标点带进下一段喵。
         *
         * **标点集合只有这五个**，这是用户明确要求的规则，起因是一个真实的 bug 喵：
         * 早先集合里还有冒号，而「发表情包」指令长这样——`【表情包：笑】`喵。
         * 那个全角冒号正好落在切分点上，指令被切成「【表情包」与「笑】」两半，
         * 于是表情发不出去，聊天里还凭空多出两段乱字喵。
         *
         * 早先为了救「模型不写逗号」把顿号、冒号、省略号、波浪号、破折号都塞了进来喵。
         * 那是**治标**：切分依据越杂，行为越难预测，最后连自己发出去的指令都切喵。
         * 现在改成治本——切分只管真正的句读，**逗号由提示词强制模型写**喵
         * （见 [PromptBuilder.BURST_INSTRUCTION]）；
         * 一个标点都没有的长句仍由 [PromptBuilder.splitLongRun] 兜底喵。
         *
         * 另有一条不变式：**括号里绝不切**（见下面循环里的括号深度判断）喵。
         */
        fun splitByCommaAndPunctuation(text: String, minLength: Int = MIN_SEGMENT_LENGTH): List<String> {
            if (text.isBlank()) return emptyList()

            val result = mutableListOf<String>()
            var currentBuffer = StringBuilder()

            val matcher = DELIMITERS.toPattern().matcher(text)
            var lastEnd = 0
            // 括号深度：`depth[i]` 是第 i 个字符**之前**未闭合的括号数喵。
            // 与 [PromptBuilder.splitLongRun] 共用同一份定义，
            // 这样"括号里绝不切"在两条拆分路径上是同一条不变式喵。
            val depth = PromptBuilder.bracketDepth(text)

            while (matcher.find()) {
                val matchStart = matcher.start()
                val matchEnd = matcher.end()

                // **括号里绝不切**喵：括号内的标点是"内容的一部分"，不是句读喵。
                // 表情名里带标点（`【表情包：笑，打滚】`）时若从中间断开，指令就废了——
                // 表情发不出去，用户还会多看到两段乱字喵。
                // 旁注「（…，…）」同理：从中间断开会把一句说明拆成两半喵。
                //
                // 这里直接 continue、**不推进 lastEnd**，那个标点就会留在正文里喵。
                if (depth[matchStart] > 0) continue

                val part = text.substring(lastEnd, matchStart)
                val punc = text.substring(matchStart, matchEnd)

                currentBuffer.append(part)

                val currentText = currentBuffer.toString().trim()
                // 强停顿（句号、问号、叹号、换行）**出现就断**喵：
                // 哪怕前面只有两三个字也该收尾，而不是因为前缀短就硬把两句粘在一起喵。
                val isStrong = punc.any { it in STRONG_PAUSES }

                // 强停顿一律断；弱停顿（逗号、分号）要攒够字数才断，避免碎片化喵。
                if (isStrong || currentText.length > minLength) {
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

            val pieces = if (result.isNotEmpty()) result else listOf(text)
            // 兜底再过一道长句切分：没有任何标点的长句只有这一道能救喵。
            return pieces.flatMap { PromptBuilder.splitLongRun(it) }
        }

        /**
         * 强停顿符喵：出现就断，不受 [MIN_SEGMENT_LENGTH] 约束。
         *
         * 逗号与分号是**弱**停顿——它们太密，一遇就断会把话切碎喵。
         * 而句号、叹号、问号在聊天里基本就等于"这一条说完了"，
         * 前面哪怕只有两三个字也该收尾喵。
         */
        private val STRONG_PAUSES = setOf('\n', '。', '！', '？', '!', '?')

        /**
         * 段内断句用的标点喵。
         *
         * **只有这五个**（外加它们的美式写法与换行）喵：逗号、句号、叹号、问号、分号。
         * 为什么收得这么窄，见 [splitByCommaAndPunctuation] 的说明喵。
         */
        private val DELIMITERS = Regex("([，,。！？!?；;\n]+)")
    }
}