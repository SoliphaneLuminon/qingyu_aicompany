package com.qingyu.app.remote

import com.qingyu.app.data.MemoryItem
import com.qingyu.app.data.MessageKind
import com.qingyu.app.data.MessageRole
import com.qingyu.app.data.Persona
import com.qingyu.app.data.ChatMessage
import com.qingyu.app.data.repo.StickerDirective
import com.qingyu.app.data.repo.StickerEagerness

/**
 * 把角色卡、记忆、历史消息拼成一次请求喵。
 *
 * 这里集中了"轻语像个人"的全部诀窍喵：
 * 1. 人格用第一人称写死，明确禁止自称 AI喵；
 * 2. 记忆以"你记得的事"注入，而不是干巴巴的条目喵；
 * 3. 允许一次输出拆成多条短消息（[burstInstruction]），间隔由前端控制喵，
 *    这样对话节奏接近真人而不是一大段作文喵；
 * 4. 思考过程交给模型自己的 reasoning 字段，不占用正文喵。
 */
object PromptBuilder {

    /**
     * 让模型把回复拆成多条短消息的输出格式约定喵。
     *
     * 这里有一条容易被忽略的耦合：**前端的断句依据是逗号**（见喵
     * [com.qingyu.app.data.repo.BurstAccumulator.splitByCommaAndPunctuation喵]）。
     * 只说"用空行隔开、像在手机上打字一样自然"时，模型容易交出一长串没有逗号的喵
     * 分句（"…能少得了辣我都记得清清楚楚的…"），切分器扫描不到任何标点喵，
     * 只能原样返回一条——既不自然，也没断成多条喵。
     *
     * 所以必须显式要求写逗号，并且告诉模型"逗号是程序用的断句标记、不算书面腔"喵，
     * 否则模型会本能地认为聊天里加标点很生硬而继续省略喵。
     */
    private val BURST_INSTRUCTION = """
        ${SENDING_RULES_MARKER}——必须遵守】
        1. 先说最要紧的：**每一句话里，该停顿的地方都要用逗号隔开**。
           不许出现一长串没有任何标点连到底的句子——那是这条要求最常见的违规形态。
           逗号只是断句标记，不算书面腔，该用就用，但别滥用。
        2. 真正换气的地方再用空行隔开；整段一共 1~3 条短消息，每条都要短（尽量不超过 20 个字）。
        3. 不要编号、不要标题、不要书面分段，也不要用括号写动作。
        4. 只想说一句话就直接一句，不用凑好几条。

        反面例子（绝对不要这样）：今天上班真的好累啊明天还要早起开会真是受不了
        正面例子（要这样）：今天上班真的好累啊，明天还要早起开会，真是受不了
    """.trimIndent()

    /**
     * 发送要求那一段的标题喵。
     *
     * 单独抽成常量是为了让**润色后能验证它还在**喵（见 [keepsSendingRules]）：
     * 润色只被要求"不许改事实与人设"，而这类格式规则既不是事实也不是人设，
     * 小模型很容易把它当成啰嗦的排版说明顺手删掉——那样断句要求就永远到不了主模型喵。
     */
    const val SENDING_RULES_MARKER = "【发送要求"

    /** 润色结果里还留着发送要求吗喵。没留就该退回原生提示词喵。 */
    fun keepsSendingRules(prompt: String): Boolean = prompt.contains(SENDING_RULES_MARKER)

    private val REASONING_INSTRUCTION = """
        【思考】
        先把对方的语气、你们的关系和此刻情境想一遍，再决定怎么回。这段思考写在 reasoning 里，不要出现在正文。
    """.trimIndent()

    /**
     * 禁止复述"系统旁注"喵。
     *
     * 上下文里那些括号标注（「（对方发来一个表情包：开心）」「（图片内容：…）」）
     * 是**给模型看的旁注**，不是它该说的话喵。不点名禁止时，模型偶尔会照着模板
     * 抄一行回来，用户就会在自己的聊天里看到这种字段喵。
     *
     * 实测最难缠的一种是**连占位符一起抄**：「（你发来一个表情包（表情包名字））」
     * ——说明它复述的不是某条具体消息，而是提示词里的模板本身喵。
     * 所以这里点名把「名字」这类占位符也列进禁区喵。
     *
     * 落库前还有一道 [com.qingyu.app.data.repo.ReplySanitizer] 兜底喵：
     * 提示词只能降低概率，挡不住所有网关与模型喵。
     */
    private val NO_ECHO_INSTRUCTION = """
        【绝对不要写出来的东西】
        上下文里凡是带括号的说明（例如「（对方发来一个表情包：名字）」「（图片内容：…）」），
        都是系统给你看的旁注，不是对话内容。
        绝对不要把这类括号说明、也不要把「名字」这种占位符抄进你的回复里。
        更不要向对方播报你收到了几条消息——那是系统的账，不是你要说的话。
    """.trimIndent()

    /**
     * 表情包在对话里怎么对待喵。
     *
     * 这条规则是用户明确要求的：表情包**更多是辅助表达**，不是话题本身喵。
     * 不写清楚的话模型会对着每个表情都郑重回应一句（"哇这个表情好可爱！"），
     * 聊起来像在给它做点评，很出戏喵。
     *
     * 唯一的例外是**对方只发了表情包、没有别的话**——那时表情就是他的全部发言，
     * 不回等于把人晾着喵。
     *
     * @param canSendStickers 这个角色有没有可用表情包；没有就别说"你也可以发"，
     *   否则模型会写出一条指令却被本地丢掉，它自己还以为发出去了喵
     */
    private fun stickerHandlingInstruction(canSendStickers: Boolean): String = buildString {
        appendLine("【表情包怎么对待】")
        appendLine("对方发的表情包多半只是给刚才的话添个情绪，**不要为它单独回一条**，顺着话题继续说就行。")
        appendLine("只有当他只发了表情包、一句话都没说时，才需要你专门回应那个表情。")
        if (canSendStickers) {
            appendLine("你自己也可以发表情包来辅助表达，但同样别把它当成一条独立的消息来「汇报」。")
        }
    }.trimEnd()

    fun systemPrompt(
        persona: Persona,
        memories: List<MemoryItem>,
        suffix: String,
        burstReplies: Boolean,
        searchContext: String = "",
        temporalHint: String = "",
        stickerNames: List<String> = emptyList(),
        stickerEagerness: StickerEagerness.Level = StickerEagerness.Level.EAGER,
    ): String = buildString {
        appendLine("# 你是谁")
        appendLine(persona.systemPrompt.ifBlank { "你是${persona.name}，一个真实的人，不是程序。" })
        if (persona.scenario.isNotBlank()) {
            appendLine()
            appendLine("# 此刻的情境")
            appendLine(persona.scenario.trim())
        }
        if (temporalHint.isNotBlank()) {
            appendLine()
            appendLine("# 现在")
            appendLine(temporalHint.trim())
        }
        if (memories.isNotEmpty()) {
            appendLine()
            appendLine("# 你记得的事（这些是事实，不要复述成清单，只在合适的时候自然用到）")
            memories.forEach { memory ->
                appendLine("- [${memory.category.label}] ${memory.statement.trim()}")
            }
        }
        if (persona.sampleDialogue.isNotBlank()) {
            appendLine()
            appendLine("# 说话示例（模仿语气，不要照抄内容）")
            appendLine(persona.sampleDialogue.trim())
        }
        if (suffix.isNotBlank()) {
            appendLine()
            appendLine("# 附加要求")
            appendLine(suffix.trim())
        }
        if (searchContext.isNotBlank()) {
            appendLine()
            appendLine(searchContext.trim())
        }
        appendLine()
        appendLine(REASONING_INSTRUCTION)
        appendLine()
        appendLine(NO_ECHO_INSTRUCTION)
        // 表情包说明放在发送要求之前：它是"能做什么"，
        // 而下面那段是"发出去长什么样"，顺序上先讲能力更自然喵。
        val stickerInstruction = StickerDirective.instruction(stickerNames)
        if (stickerInstruction.isNotEmpty()) {
            appendLine()
            appendLine(stickerInstruction)
            // 频率说明只在"确实有表情包可发"时才有意义喵：
            // 一个表情都没有还叮嘱它多发，它只会写出一条指令却被本地丢掉喵。
            appendLine()
            appendLine(StickerEagerness.instruction(stickerEagerness))
        }
        // 这条**无条件加**：即使这个角色一个可用表情都没有，
        // 用户仍然可以发表情包，模型也仍然需要知道"别对着表情郑重回应"喵。
        appendLine()
        appendLine(stickerHandlingInstruction(canSendStickers = stickerNames.isNotEmpty()))
        if (burstReplies) {
            appendLine()
            appendLine(BURST_INSTRUCTION)
        } else {
            appendLine()
            appendLine("【发送方式】一次只回一条消息，简洁口语化。")
        }
    }

    /**
     * 组装消息序列喵。
     *
     * 只保留 user / assistant 两种角色：把思考单独存成一条 reasoning 消息再喵
     * 送进上下文会污染对话，所以这里整条丢掉（思考只在本地展示喵）。
     */
    fun turns(
        persona: Persona,
        memories: List<MemoryItem>,
        history: List<ChatMessage>,
        suffix: String,
        burstReplies: Boolean,
        historyLimit: Int,
        searchContext: String = "",
        temporalHint: String = "",
        stickerNames: List<String> = emptyList(),
        stickerEagerness: StickerEagerness.Level = StickerEagerness.Level.EAGER,
    ): List<ChatTurn> {
        val filtered = history
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
            .filter { it.content.isNotBlank() }
            .takeLast(historyLimit.coerceAtLeast(2))

        val turns = ArrayList<ChatTurn>(filtered.size + 1)
        turns.add(
            ChatTurn(
                role = "system",
                content = systemPrompt(
                    persona = persona,
                    memories = memories,
                    suffix = suffix,
                    burstReplies = burstReplies,
                    searchContext = searchContext,
                    temporalHint = temporalHint,
                    stickerNames = stickerNames,
                    stickerEagerness = stickerEagerness,
                ),
            ),
        )
        filtered.forEach { message ->
            val rendered = renderHistoryContent(message)
            // 渲染后为空就整条跳过喵：只有"名字为空的自发表情"会走到这里，
            // 塞一个空 turn 进去会让网关报错、或让模型以为对方发了条空白消息喵。
            if (rendered.isBlank()) return@forEach
            turns.add(
                ChatTurn(
                    if (message.role == MessageRole.USER) "user" else "assistant",
                    rendered,
                ),
            )
        }
        return turns
    }

    /**
     * 把一条历史消息渲染成送给模型的文本喵。
     *
     * 表情包**不能直接把名字当正文**喵。名字是"开心"这种普通词，
     * 和用户真打出来的字在上下文里长得一模一样，模型分不清
     * "他打了一句开心"和"他发了一个叫开心的表情"喵。
     *
     * 更要紧的是**稳定性**喵：以前表情走的是识图通道，每发一次都重新描述一遍，
     * 而识图模型的措辞每次都不完全一样（"一个人在笑" / "笑得很大声"）。
     * 于是同一张表情发三次，模型读到的就是三段不同的描述，
     * 自然表现得像"每次都头一回见"喵。
     * 所以两种角色都用**固定形态**渲染，同一个表情每次的字面完全一致喵。
     *
     * 但**两种角色不能用同一种形态**喵，这是踩过的坑：
     * 助手自己发过的表情原本也渲染成旁注（`（你发了 一个表情包：笑）`），
     * 而它恰恰落在**助手那一侧的历史**里——模型看到"我以前是这么说话的"，
     * 就照着抄回正文，用户于是收到一条 `（你发来一个表情包（表情包名字））`喵。
     *
     * 现在改成：助手发过的表情**直接渲染成它该写的指令本身**喵
     * （`【表情包：笑】`）。这样"照抄自己"与"正确输出"就是同一件事——
     * 抄对了会被本地解析成真表情，抄歪了也只是一行解析失败的指令，
     * 由 [com.qingyu.app.data.repo.ReplySanitizer] 兜掉，不会成为聊天内容喵。
     *
     * 对方发的仍然用旁注转述喵：用户不会写指令，只能由我们告诉模型
     * "这是一个表情，不是两个汉字"喵。
     */
    private fun renderHistoryContent(message: ChatMessage): String =
        if (message.kind == MessageKind.STICKER) {
            val name = message.content.trim()
            when {
                // 对方发来的：转述成旁注。空名字也留个说法，别让这一轮变成空白喵。
                message.role == MessageRole.USER && name.isEmpty() -> "（对方发来一个表情包）"
                message.role == MessageRole.USER -> "（对方发来一个表情包：$name）"
                // 自己发过的：渲染成指令本身（见上面的说明）喵。
                // 名字为空时交不出合法指令，返回空串让 [turns] 整条跳过喵。
                name.isEmpty() -> ""
                else -> "【表情包：$name】"
            }
        } else {
            message.content
        }

    /**
     * 把模型一次输出的长文本拆成多条短消息喵。
     *
     * 同时清理模型偶尔带出来的编号、Markdown 前缀，避免聊天界面出现"1. 2. 3."喵。
     */
    fun splitBurst(raw: String, enabled: Boolean): List<String> {
        val normalized = raw.replace("\r\n", "\n").trim()
        if (normalized.isEmpty()) return emptyList()
        val parts = if (!enabled) listOf(normalized) else normalized.split(Regex("\n\\s*\n+"))
        return parts
            .map { cleanSegment(it) }
            .filter { it.isNotBlank() }
            .take(6)
    }

    /**
     * 兜底拆分：模型没产出空行时，尝试按句号/感叹号/问号切割喵。
     *
     * 只在 [splitBurst] 颗粒无收时使用——这属于"尽力而为"的补丁喵，
     * 不加到主拆分路径里是为了不影响空行拆分的行为（那个已经被单元测试钉住喵）。
     */
    fun sentenceSplit(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n").trim()
        if (normalized.isEmpty()) return emptyList()
        // 在 。！？ 后面且之后至少还有 4 个非空字符才切（避免在 "嗯。" "哦？" 这类短应答处切断喵）
        val parts = normalized.split(Regex("(?<=[。！？])(?=\\s*\\S.{3,})"))
        return parts
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(4)
    }

    /**
     * 最后一道兜底：把没有任何标点的长句按长度切断喵。
     *
     * 为什么必须有这一道喵：切分器唯一的信号是标点，而模型**确实经常一个逗号都不写**
     * （用户反馈「还是经常回复那种既没拆开也没逗号的句子」就是这个）。那时前面几道
     * 全都颗粒无收，整段话会原样变成一条又长又挤的消息喵。
     *
     * 断点优先选在**语气助词后面**喵（了啊呢吧吗呀哦嘛嗯啦…），
     * 因为中文里这些字天然就是一个停顿的位置，在那里断开读起来最像真人换气喵。
     * 窗口内找不到助词才硬切——硬切难看，但总好过一整段糊在一起喵。
     *
     * 长度阈值取 [MAX_RUN_LENGTH] 而不是更小：这一道是**兜底**，
     * 只在明显过长时出手，正常的短句不该被动到喵。
     */
    fun splitLongRun(text: String, maxLength: Int = MAX_RUN_LENGTH): List<String> {
        val normalized = text.trim()
        if (normalized.length <= maxLength) return if (normalized.isEmpty()) emptyList() else listOf(normalized)

        val out = ArrayList<String>()
        var start = 0
        while (start < normalized.length) {
            if (normalized.length - start <= maxLength) {
                out += normalized.substring(start).trim()
                break
            }
            val hardEnd = start + maxLength
            // 从窗口末尾往回找最近的语气助词：越靠后的一段越接近目标长度喵。
            // 下界只留 [MIN_TAIL_LENGTH] 个字，免得把话切得过碎喵。
            val softFloor = start + MIN_TAIL_LENGTH
            var cut = -1
            for (i in hardEnd - 1 downTo softFloor) {
                if (normalized[i] in SOFT_BREAK_TAIL) {
                    cut = i + 1
                    break
                }
            }
            // 找不到助词就硬切；同时保证一定前进，避免死循环喵。
            if (cut <= start) cut = hardEnd
            out += normalized.substring(start, cut).trim()
            start = cut
        }
        val cleaned = out.filter { it.isNotBlank() }.toMutableList()
        // 别在末尾留下光秃秃的一两个字喵：那比不断开还难看（"…清清楚楚" / "的"）喵。
        // 尾部太短就并回上一段——它本来就是紧挨着的，拼回去读起来是原样喵。
        if (cleaned.size >= 2 && cleaned.last().length < MIN_TAIL_LENGTH) {
            val tail = cleaned.removeAt(cleaned.size - 1)
            cleaned[cleaned.size - 1] = cleaned.last() + tail
        }
        return cleaned
    }

    /**
     * 断长句时优先落刀的**语气助词**喵。
     *
     * 只收几乎不可能出现在句子中间的收尾助词喵。反例是 `了`：
     * 它当然常常收句（"我吃过了"），但也频繁出现在词中——
     * "你哪次吃饭能少得**了**辣" 就是一个，在它后面断会得到
     * "…能少得了" / "辣我都记得…"，把"辣"甩到了下一句，读起来是错的喵。
     * 这种模棱两可的字宁可不收：少一个断点只会让那段稍长，
     * 而断错位置会直接把一句话的意思拆歪喵。
     */
    private val SOFT_BREAK_TAIL = setOf(
        '啊', '呢', '吧', '吗', '呀', '哦', '嘛', '嗯', '啦',
        '哟', '喔', '诶', '唉', '哈', '喂', '嘞', '咯', '噢', '嘿',
    )

    /** 一整段没有任何标点时，超过这个长度才动用 [splitLongRun] 喵。 */
    const val MAX_RUN_LENGTH = 18

    /** 末尾剩这么短就并回上一段，不留一两个字的孤零零碎片喵。 */
    const val MIN_TAIL_LENGTH = 4

    private fun cleanSegment(segment: String): String = segment
        .lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
        .removePrefix("- ")
        .trim()
        .let(::stripNullArtifacts)

    /**
     * 最后一道防线：把 `null` 噪音从正文里剔除喵。
     *
     * 协议层已经保证 JSON null 不会变成文本（见 [ChatStreamParser]），但兼容型网关喵
     * 偶尔会把字段值真的写成字符串 `"null"`喵。这种噪音一旦漏进来就是满屏的 null，
     * 所以在落库之前统一清掉喵。
     *
     * 判据刻意收得很紧，只认**重复**的 `null`喵——真故障的形态是 `nullnullnull…`
     * 或满屏的 `null null null`。这样喵：
     * - `nullable` / `annulment` 这类真词不会被误伤（n 前面是字母，不匹配喵）；
     * - 正文里正常写着的 `这个字段是 null，要注意` 原样保留（null 前面是空格喵）；
     * - 单挂一个 `null` 结尾的句子也不动（宁可漏一点噪音，也不吃掉正文喵）。
     */
    fun stripNullArtifacts(text: String): String {
        if (!text.contains("null", ignoreCase = true)) return text
        val pattern = Regex(
            // 前面不是空格，也不是字母：吃掉紧贴的 / 开头的重复 null喵
            "(?<![ \\tA-Za-z])(?:null[ \\t]*){2,}",
            RegexOption.IGNORE_CASE,
        )
        if (!pattern.containsMatchIn(text)) return text
        return text
            .replace(pattern, " ")
            .replace(Regex("[ \\t]{2,}"), " ")
            .trim()
    }

    /** 打印用：把当前上下文渲染成可读文本，方便在调试页自检喵。 */
    fun preview(turns: List<ChatTurn>): String = turns.joinToString("\n\n") { turn ->
        buildString {
            append("【").append(turn.role).append("】")
            if (turn.hasImages) {
                // 图片本体是 base64，铺出来会把正文彻底淹没——这里只报数量喵。
                append("（附 ").append(turn.images.size).append(" 张图片）")
            }
            append('\n').append(turn.content)
        }
    }
}
