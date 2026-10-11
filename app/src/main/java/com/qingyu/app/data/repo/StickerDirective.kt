package com.qingyu.app.data.repo

/**
 * 模型回复里的「发表情包」指令喵。
 *
 * 模型不能直接"发送"任何东西——它只会输出文字。所以要让它能发表情包，
 * 就得约定一种写在正文里的指令，由本地解析出来、换成真正的表情消息喵。
 *
 * 约定长这样（**必须独占一行**）喵：
 * ```
 * 哈哈哈这个太好笑了
 * 【表情包：笑到打滚】
 * ```
 *
 * 为什么用整行 + 全角方括号喵：
 * - **整行**：指令混在句子中间时，剥离后容易留下半句话（"我【表情包：笑】觉得"→"我觉得"读起来怪）喵
 * - **全角方括号**：正常聊天里几乎不会出现这种组合，不会把用户或模型真正想说的话误当成指令喵
 *
 * 解析不出可用的表情名时**原样保留那一行**：宁可让它显示成一句怪话，
 * 也不能悄悄吞掉模型说的话喵。
 */
object StickerDirective {

    /** 指令行的完整形态喵。 */
    private val PATTERN = Regex("""^\s*【表情包[:：]\s*(.+?)\s*】\s*$""")

    /**
     * 从一段回复里解析出正文与要发的表情喵。
     *
     * @param text 模型输出的一段文本喵
     * @param available 当前可用的表情名集合；不在其中的名字会被忽略（模型可能编名字）喵
     * @return 剥掉指令后的正文，以及按出现顺序要发的表情名喵
     */
    fun parse(text: String, available: Set<String>): Result {
        if (text.isBlank()) return Result(text, emptyList())

        val kept = ArrayList<String>()
        val stickers = ArrayList<String>()

        text.split('\n').forEach { line ->
            val match = PATTERN.matchEntire(line)
            if (match == null) {
                kept += line
                return@forEach
            }
            // 只认确实存在的表情：模型有时会编一个名字出来，
            // 那时留着这行会让人看到一句莫名其妙的话，不如当作普通文字喵。
            val name = match.groupValues[1].trim()
            if (name in available) {
                stickers += name
            } else {
                kept += line
            }
        }

        return Result(
            // 剥掉指令行后可能留下多余空行，收一下尾，但**保留段内换行**喵。
            text = kept.joinToString("\n").trim(),
            stickerNames = stickers,
        )
    }

    /**
     * 告诉模型"你可以发这些表情包"的那段说明喵。
     *
     * 没有可用表情时返回空串，调用方据此不注入这段喵。
     *
     * 表情很多时只列前 [MAX_LISTED] 个喵：这段是**每一轮**都要塞进系统提示词的，
     * 一次导入几百张表情就会白白多烧几百个 token，而那些冷门表情本来也很少用喵。
     * 解析时用的仍是完整名单，所以没被列出来的表情依然认得出来喵。
     */
    fun instruction(available: List<String>): String {
        if (available.isEmpty()) return ""
        val listed = available.take(MAX_LISTED)
        val truncated = available.size - listed.size
        return buildString {
            appendLine("【表情包】")
            appendLine("你可以发表情包来辅助表达情绪。想发的时候，单独占一行写：")
            appendLine("【表情包：名字】")
            appendLine("可用的是：${listed.joinToString("、")}")
            if (truncated > 0) {
                appendLine("（还有 $truncated 个没列出来，从上面这些里挑最贴切的就够用了）")
            }
            appendLine(
                "表情包是**辅助**，不要为它单独回一条消息——" +
                    "通常顺着刚才的话题继续说，把表情包夹在话里就够了。" +
                    "除非对方只发了表情包、没别的话，那才需要你专门回应它。",
            )
        }.trimEnd()
    }

    /** 系统提示词里最多列几个表情名喵。 */
    const val MAX_LISTED = 60

    /** 解析结果喵。 */
    data class Result(
        /** 剥掉指令行之后的正文喵。 */
        val text: String,
        /** 要按顺序发出的表情名喵。 */
        val stickerNames: List<String>,
    )
}
