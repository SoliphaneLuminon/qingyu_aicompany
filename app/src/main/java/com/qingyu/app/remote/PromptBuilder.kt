package com.qingyu.app.remote

import com.qingyu.app.data.MemoryItem
import com.qingyu.app.data.MessageKind
import com.qingyu.app.data.MessageRole
import com.qingyu.app.data.Persona
import com.qingyu.app.data.ChatMessage
import com.qingyu.app.data.repo.StickerDirective

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
        【发送要求——必须遵守】
        句内该停顿的地方一定要用逗号，不要一长串连到底——前端会按逗号把它断成更短的几条消息。
        逗号只是断句标记，不算书面腔，该用就用，但别滥用。
        真正换气的地方再用空行隔开；整段一共 1~3 条短消息，每条都要短。
        不要编号、不要标题、不要书面分段，也不要用括号写动作。
        只想说一句话就直接一句，不用凑好几条。
    """.trimIndent()

    private val REASONING_INSTRUCTION = """
        【思考】
        先把对方的语气、你们的关系和此刻情境想一遍，再决定怎么回。这段思考写在 reasoning 里，不要出现在正文。
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
        // 表情包说明放在发送要求之前：它是"能做什么"，
        // 而下面那段是"发出去长什么样"，顺序上先讲能力更自然喵。
        val stickerInstruction = StickerDirective.instruction(stickerNames)
        if (stickerInstruction.isNotEmpty()) {
            appendLine()
            appendLine(stickerInstruction)
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
                ),
            ),
        )
        filtered.forEach { message ->
            turns.add(
                ChatTurn(
                    if (message.role == MessageRole.USER) "user" else "assistant",
                    renderHistoryContent(message),
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
     *
     * 现在统一渲染成一个固定形态的标记，同一个表情每次的字面完全一致，
     * 模型就能一眼看出"这个刚才发过了"喵。
     */
    private fun renderHistoryContent(message: ChatMessage): String =
        if (message.kind == MessageKind.STICKER) {
            val name = message.content.trim()
            // 谁发的要分清喵：助手自己发过的表情若也写成"对方发来"，
            // 模型会以为那是用户发的，可能回一句"你发的这个好可爱"——自己夸自己喵。
            val who = if (message.role == MessageRole.USER) "对方发来" else "你发了"
            val suffix = if (name.isEmpty()) "" else "：$name"
            "（$who 一个表情包$suffix）"
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
     * 最后一道兜底：按单换行切割聊天式短句喵。
     *
     * 有些模型会在输出里换行但不打空行——这在手机聊天风格里很常见喵。
     * 只在句号拆也颗粒无收时才调用，且要求每段 ≥ 6 字以避免拆碎喵。
     */
    fun lineSplit(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n").trim()
        if (normalized.isEmpty()) return emptyList()
        val lines = normalized.split('\n').map { it.trim() }.filter { it.length >= 6 }
        return if (lines.size > 1) lines.take(4) else emptyList()
    }

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
