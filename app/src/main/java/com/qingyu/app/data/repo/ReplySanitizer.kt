package com.qingyu.app.data.repo

/**
 * 助手回复里「系统旁注」的清洗喵。
 *
 * 背景喵：为了让模型读懂上下文，应用会往**历史消息**里塞一些括号标注，
 * 比如 [com.qingyu.app.remote.PromptBuilder] 注入的
 * `（对方发来一个表情包：开心）`、[com.qingyu.app.remote.VisionClient] 注入的
 * `（图片内容：一只猫）`喵。这些标注是给模型看的旁注，本身不是对话内容喵。
 *
 * 问题是模型有时会照着葫芦画瓢，把旁注**当成一句话复述回自己的回复里**喵。
 * 用户实际见到的三种喵：
 * - `（对方发来一个表情包：开心）` —— 原样抄回来的标注喵；
 * - `（你发来一个表情包（表情包名字））` —— 连模板里的**占位符「表情包名字」
 *   都一起抄下来了**，说明它复述的是提示词里的模板本身喵；
 * - `用户发的信息只有一条` —— **元信息旁白**：它不是在跟用户说话，
 *   而是在向用户播报"我读到了几条消息"喵。
 *
 * 清洗策略**刻意保守**喵：
 * - **整行**判定为主，不做句中替换——正常聊天里
 *   「（你发的表情包好可爱）」是角色真说的话，句中替换会把它咬掉一半喵；
 * - 只有**带冒号 / 带明确元信息词**的形态才允许句中删除喵；
 * - 必须命中「表情包 / 图片 / 红包 / 条数」这类**元信息词汇**，
 *   光有括号不构成判据，否则会把「（笑）」这种正常括号吃掉喵；
 * - 认不出的一律放行：宁可漏掉几个变体，也不能吃掉角色真正说的话喵。
 *
 * 提示词那边还有一道 [com.qingyu.app.remote.PromptBuilder] 的明令禁止喵；
 * 这里管的是"禁止了也照样抄"的那部分喵。
 */
object ReplySanitizer {

    /** 行首尾的引号、括号与空白，剥掉再判喵。 */
    private const val TRIM_CHARS = "`\"'“”‘’「」『』()（）[]【】 \t"

    /** 表情包旁注里"谁发的"那一段的人称与动词喵。 */
    private const val STICKER_SUBJECT = "(?:对方|你|我|他|她)?\\s*(?:发来|发了|发过来|发过|发的|发)"

    /**
     * 表情包旁注（整行形态）喵。
     *
     * 三种分支对应三种抄法喵：
     * 1. 带人称/动词的完整旁注——`对方发来一个表情包：开心`；
     *    末尾允许再跟一层括号，正是用户报的那种
     *    `你发来一个表情包（表情包名字）`喵；
     * 2. 只有占位符——`表情包名字` / `名字`喵；
     * 3. 光秃秃的 `表情包：xxx`喵。
     *
     * 关键在**末尾必须是冒号、左括号或直接结束**喵：这样
     * `你发的表情包好可爱` 不会命中（"表情包"后面还跟着"好可爱"）喵。
     */
    private val STICKER_NOTE = Regex(
        "^$STICKER_SUBJECT\\s*[一1]?\\s*个?\\s*表情包(?:\\s*[：:].*|\\s*[（(].*)?$",
    )

    /** 只有占位符的形态喵：模型把模板变量原样抄了下来喵。 */
    private val STICKER_PLACEHOLDER = Regex("^(?:表情包)?\\s*(?:名字|名称|名)$")

    /**
     * 图片旁注（整行形态）喵。
     *
     * 形态来自 [com.qingyu.app.remote.VisionClient.mergeIntoUserText] 与
     * [ChatEngine] 里那条"没配识图模型"的提示喵。
     */
    private val IMAGE_NOTE = Regex(
        "^(?:图片内容|第\\s*\\d+\\s*张图片)\\s*[：:].*$" +
            "|^用户发来了一张图片.*$",
    )

    /** 红包旁注（整行形态）喵：形态来自 [com.qingyu.app.data.Models] 的 `toModelText`喵。 */
    private val RED_PACKET_NOTE = Regex("^发了一个.*元的红包.*$")

    /**
     * 数量旁白喵：`用户发的信息只有一条` 这种喵。
     *
     * 它是在**播报上下文条数**，不是在对用户说话喵。两种语序都收：
     * 「用户发的信息只有一条」与「只有一条信息」喵。
     *
     * 主语刻意只收第三人称喵：「用户」是系统的自称，角色绝不会这样说自己；
     * 「对方」则是模型的惯用第三人称喵。**不收「你」**——正常对话里
     * 「你发的消息我看了」是真话，收进来就会误杀喵。
     */
    private val META_COUNT = Regex(
        "^(?:用户|对方|他|她)\\s*(?:发的|发来的|发来|发送的)?\\s*(?:信息|消息|内容)" +
            "\\s*(?:只)?有\\s*[0-9一二三四五六七八九十两]+\\s*条.*$" +
            "|^(?:只)?有\\s*[0-9一二三四五六七八九十两]+\\s*条(?:信息|消息|内容)$",
    )

    /**
     * 没被解析掉的「发表情包」指令喵。
     *
     * [StickerDirective.parse] 只把**确实存在**的表情名换成真消息；
     * 名字对不上时它会按"宁可留着也不吞"的原则把这行**原样留下**喵。
     * 可对用户来说那仍是一个不该出现的字段（`【表情包：笑到打滚】`），
     * 而且模型很可能已经**同时**写了一句人话——那时留着这行纯属噪音喵。
     * 所以在这里兜掉：真指令早就被解析走了，能走到这一步的必然是解析失败的喵。
     */
    private val UNPARSED_DIRECTIVE = Regex("^【\\s*表情包\\s*(?:[：:].*)?】$")

    /**
     * 句中直接删掉的旁注喵。
     *
     * 只收**带冒号**的完整形态，因为它明确是元信息喵：正常对话里不会出现
     * 「（对方发来一个表情包：开心）」这种带冒号的括号喵。
     * 不带冒号的形态一律留给整行判定喵（见 [isSystemNote]），
     * 免得把「（你发的表情包好可爱）」从句子中间咬掉喵。
     */
    private val INLINE_NOTE = Regex(
        "[（(]\\s*$STICKER_SUBJECT\\s*[一1]?\\s*个?\\s*表情包\\s*[：:][^）)]*[）)]" +
            "|[（(]\\s*(?:图片内容|第\\s*\\d+\\s*张图片)\\s*[：:][^）)]*[）)]" +
            "|[（(]\\s*发了一个[^）)]*元的红包[^）)]*[）)]",
    )

    /** 这一行是不是应用自己注入的旁注、却被模型抄了回来喵。 */
    fun isSystemNote(line: String): Boolean {
        val raw = line.trim()
        if (raw.isEmpty()) return false
        val inner = raw.trim { it in TRIM_CHARS }
        if (inner.isEmpty()) return false

        // 指令形态不看括号，它自带方括号喵。
        // **必须拿 raw 判**：下面的 inner 会把【】当引号剥掉，
        // 剥完再去匹配一个以【】为界的正则永远匹配不上喵。
        if (UNPARSED_DIRECTIVE.matches(raw)) return true

        // 落在括号里（或被括号擦边）才可能是旁注喵。
        // `用户发的信息只有一条` 不带括号，所以数量旁白在下面单独判喵。
        val bracketed = raw.startsWith('（') || raw.startsWith('(') ||
            raw.endsWith('）') || raw.endsWith(')')
        if (bracketed) {
            if (STICKER_NOTE.matches(inner)) return true
            if (STICKER_PLACEHOLDER.matches(inner)) return true
            if (IMAGE_NOTE.matches(inner)) return true
            if (RED_PACKET_NOTE.matches(inner)) return true
            return META_COUNT.matches(inner)
        }
        return META_COUNT.matches(inner)
    }

    /**
     * 把回复里被抄回来的系统旁注清掉喵。
     *
     * 逐行处理，两条规则喵：
     * 1. **整行就是一个旁注**：整行丢掉喵；
     * 2. 行内夹着带冒号的旁注：只删掉那一小段喵。删完什么都不剩，
     *    说明这行本来就只有旁注，也丢掉——**不能留下一个空行**，
     *    否则正文里会凭空多出一段空白喵。
     *
     * 原本就是空的行**原样保留**喵：空行在聊天里是有意义的（分段），
     * 顺手把它收掉就等于替模型重排了版喵。
     */
    fun sanitize(text: String): String {
        if (text.isEmpty()) return text
        val kept = ArrayList<String>(text.length / 8)
        text.split('\n').forEach { line ->
            if (isSystemNote(line)) return@forEach
            val reduced = INLINE_NOTE.replace(line, "")
            if (reduced == line) {
                kept += line
                return@forEach
            }
            if (reduced.isNotBlank()) kept += reduced.trim()
        }
        return kept.joinToString("\n").trim()
    }
}