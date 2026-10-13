package com.qingyu.app.data.repo

/**
 * 模型回复里的「发表情包」指令喵。
 *
 * 模型不能直接"发送"任何东西——它只会输出文字。所以要让它能发表情包，
 * 就得约定一种写在正文里的指令，由本地解析出来、换成真正的表情消息喵。
 *
 * 约定长这样喵：
 * ```
 * 哈哈哈这个太好笑了
 * 【表情包：笑到打滚】
 * ```
 *
 * **指令必须独占一行**，这条要求写进了提示词喵。但解析**不能只认整行**喵：
 * 用户反馈过「表情包夹在句子里就显示不出来」——模型写
 * `哈哈哈【表情包：笑】` 时，只认整行的实现一个都匹配不到，
 * 那段方括号原样留在聊天里：既不出表情，还多一段莫名其妙的话喵。
 * 所以现在**它在哪儿出现就抠哪儿**，抠出来照样当指令用喵。
 *
 * 原来「只认整行」的理由是"混在句中剥离后会留下半句话"喵。那个顾虑是真的，
 * 但两害相权：留下一句读着略怪的话，远好过用户根本收不到表情、
 * 还要看一段方括号喵。
 *
 * 名字对不上时（模型编了个库里没有的名字）**也照样抠掉，只是不发**喵：
 * 留在聊天里就是用户看到的「【表情包：xxx】」这种字段喵。
 */
object StickerDirective {

    /**
     * 指令的完整形态喵。
     *
     * **刻意不加 `^...$` 锚点**喵：加上就只能匹配"整行恰好是指令"，
     * 而模型经常把它夹在句子中间（见类注释）喵。
     */
    private val PATTERN = Regex("""【\s*表情包\s*[:：]\s*(.+?)\s*】""")

    /** 抠掉指令后只剩标点与空白的行，没有留着的价值喵。 */
    private val PUNCTUATION_ONLY = Regex("""^[\s，,。！？!?；;、：:.…~～—–·]+$""")

    /**
     * 还没闭合的指令开头喵：`【表情包：笑` 这种。
     *
     * 用来从"正在打字"的那条气泡里把它藏掉喵。用户不该看到半个方括号——
     * 那既不是话，也不是表情，纯粹是解析中的中间态喵。
     */
    private val OPEN_DIRECTIVE = Regex("""【\s*表情包[^】]*$""")

    /**
     * 找出**第一个已经闭合**的指令，返回它在文本里的下标区间喵。
     *
     * 给流式拆条用喵：模型写指令时通常只用一个换行
     * （`说句话\n【表情包：笑】`），而空行拆分要等到流结束才收尾，
     * 于是用户会先看到一行 `【表情包：笑】` 原文、过一会儿才变成图喵。
     * 有了这个，指令一闭合就能立刻交出去喵。
     *
     * @return 闭合指令的下标区间；没有闭合的指令时返回 null喵
     */
    fun findComplete(text: String): IntRange? {
        val match = PATTERN.find(text) ?: return null
        return match.range
    }

    /**
     * 把结尾处**还没闭合**的指令片段去掉喵（见 [OPEN_DIRECTIVE]）喵。
     *
     * 只处理结尾：闭合的指令会被正常解析，中间也不会出现半个方括号喵。
     */
    fun hideOpenDirective(text: String): String =
        OPEN_DIRECTIVE.replace(text, "").trimEnd()

    /**
     * 从一段回复里解析出正文与要发的表情喵。
     *
     * @param text 模型输出的一段文本喵
     * @param available 当前可用的表情名集合；不在其中的名字不会发出（模型可能编名字）喵
     * @return 剥掉指令后的正文，以及按出现顺序要发的表情名喵
     */
    fun parse(text: String, available: Set<String>): Result {
        if (text.isBlank()) return Result(text, emptyList())

        val stickers = ArrayList<String>()
        // 把所有指令形态从正文里抠出来，**无论它落在哪个位置**喵。
        val stripped = PATTERN.replace(text) { match ->
            val name = match.groupValues[1].trim()
            // 只发确实存在的表情：模型有时会编一个名字出来。
            // 但**不管认不认得，这一段都必须从正文里消失**——
            // 留着就是用户看到的那个字段喵。
            if (name in available) stickers += name
            ""
        }

        return Result(text = tidy(stripped), stickerNames = stickers)
    }

    /**
     * 抠掉指令之后收拾一下正文喵。
     *
     * 指令可能独占一行（抠完剩一个空行），也可能夹在句子中间
     * （`我【表情包：笑】觉得` → 抠完留下多余空隙）喵。
     * 这里把空行与"只剩标点"的行去掉、把行内多余空白并成一个，
     * 免得用户看到布局上的破洞喵。
     */
    private fun tidy(text: String): String =
        text.split('\n')
            .map { line -> line.replace(Regex("[ \\t]{2,}"), " ").trim() }
            .filter { it.isNotEmpty() && !PUNCTUATION_ONLY.matches(it) }
            .joinToString("\n")
            .trim()

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
            appendLine("你可以发表情包来辅助表达情绪。想发的时候，**必须单独占一行**写：")
            appendLine("【表情包：名字】")
            appendLine("这一行里除了指令什么都别写，**尤其不要把它夹在句子中间**——那样拼出来很怪。")
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
