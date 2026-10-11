package com.qingyu.app.data

import com.qingyu.app.core.memory.MemoryDecay

/**
 * 消息角色喵。
 *
 * [wire] 必须与"早期导出格式"的聊天记录 JSON 保持一致喵：
 * `{"content":"在吗","role":"user","timestamp":1791261040298}`喵
 * 其中思考过程是一条独立的 `reasoning` 消息，并带 `reasoningDuration`喵。
 */
enum class MessageRole(val wire: String) {
    USER("user"),
    ASSISTANT("assistant"),
    REASONING("reasoning"),
    SYSTEM("system");

    companion object {
        fun fromWire(raw: String?): MessageRole =
            entries.firstOrNull { it.wire.equals(raw, ignoreCase = true) } ?: ASSISTANT
    }
}

/**
 * 消息的**结构类型**喵。
 *
 * 与 [MessageRole] 正交：role 说的是"谁发的"，kind 说的是"这是什么内容"喵。
 * 表情包与红包都不是纯文本，界面上要渲染成卡片、送给模型时也要转写成一句人话，
 * 所以必须能一眼分辨，不能靠解析 content 里的标记喵。
 */
enum class MessageKind(val wire: String) {
    TEXT("text"),
    STICKER("sticker"),
    RED_PACKET("red_packet");

    companion object {
        fun fromWire(raw: String?): MessageKind =
            entries.firstOrNull { it.wire.equals(raw, ignoreCase = true) } ?: TEXT
    }
}

/** 一条聊天消息。[id] 为 0 表示尚未落库喵。 */
data class ChatMessage(
    val id: Long = 0L,
    val conversationId: Long,
    val role: MessageRole,
    val content: String,
    val timestamp: Long,
    val reasoningDuration: Long = 0L,
    /** 该条助手消息是否已经完成。流式生成中为 false喵。 */
    val completed: Boolean = true,
    /** 已收藏到收藏空间喵。 */
    val favorite: Boolean = false,
    /** 失败原因，用于在气泡上展示重试喵。 */
    val error: String? = null,
    /** 随这条消息发出的图片（用户消息喵）。 */
    val attachments: List<Attachment> = emptyList(),
    /** 这条回复用到的联网搜索结果（助手消息喵）。 */
    val searchResults: List<SearchHit> = emptyList(),
    /** 这条消息是普通文字、表情包还是红包喵。 */
    val kind: MessageKind = MessageKind.TEXT,
    /** 表情包消息对应的表情 id；[MessageKind.STICKER] 时有效喵。 */
    val stickerId: Long = 0L,
    /**
     * 结构化消息的原始数据喵。
     *
     * 红包的金额与留言存在这里，**而不是从 [content] 里反解**喵。
     * content 是给模型看的自然语言（「（发了一个 8.88 元的红包…）」），
     * 它的措辞随时可能调整；界面却要拿金额去画卡片。
     * 让界面去正则匹配自己刚写下的那句话，等于把两件事绑死：
     * 以后改一个字的文案，红包卡片就会显示错喵。
     */
    val payload: String = "",
) {
    val hasImages: Boolean get() = attachments.any { it.isImage }
}

/**
 * 一个表情包喵。
 *
 * [name] 会作为文字内容参与对话，所以它是这个表情"是什么意思"的载体喵；
 * [filePath] 指向应用私有目录里的图片文件喵。
 */
data class Sticker(
    val id: Long = 0L,
    val name: String,
    val filePath: String,
    val mimeType: String = "image/png",
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Long = 0L,
    val useCount: Int = 0,
    val sortOrder: Int = 0,
    val createdAt: Long = 0L,
)

/**
 * 红包喵。
 *
 * **纯虚拟**：没有充值、没有支付、不接任何真实货币，金额由用户自己填喵。
 * 它的意义是把"我给你发了多少钱"这件事作为一条结构化消息送进上下文，
 * 让角色能像真的收到红包那样回应喵。
 */
data class RedPacket(
    /** 金额（元）。允许小数喵。 */
    val amount: Double,
    /** 红包上的祝福语，可空喵。 */
    val wish: String = "",
) {
    /** 送给模型看的描述，例如「（发了一个 8.88 元的红包，留言：请你喝奶茶）」喵。 */
    fun toModelText(): String {
        val amountText = formatAmount(amount)
        return if (wish.isBlank()) {
            "（发了一个 $amountText 元的红包）"
        } else {
            "（发了一个 $amountText 元的红包，留言：$wish）"
        }
    }

    companion object {
        /** 金额显示：整数不带小数点，小数保留两位喵。 */
        fun formatAmount(amount: Double): String =
            if (amount % 1.0 == 0.0) amount.toLong().toString() else "%.2f".format(amount)

        /** 红包金额上限，避免手滑输入天文数字让角色反应失真喵。 */
        const val MAX_AMOUNT = 99999.0

        /** 序列化成消息的 payload 字段；用 JSON 而不是自定义分隔符，金额与留言里出现分隔符也不会串味喵。 */
        fun toPayload(packet: RedPacket): String =
            org.json.JSONObject()
                .put("amount", packet.amount)
                .put("wish", packet.wish)
                .toString()

        /** 从 payload 还原；解析不出来返回 null，界面据此退化成一句纯文本喵。 */
        fun fromPayload(payload: String): RedPacket? {
            if (payload.isBlank()) return null
            return runCatching {
                val json = org.json.JSONObject(payload)
                RedPacket(amount = json.optDouble("amount", 0.0), wish = json.optString("wish"))
            }.getOrNull()?.takeIf { it.amount > 0.0 }
        }
    }
}

/** 附件种类。图片是当前唯一的实现，留出枚举是为了后续加语音/文件喵。 */
enum class AttachmentKind(val label: String) {
    IMAGE("图片");

    companion object {
        fun fromName(raw: String?): AttachmentKind =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: IMAGE
    }
}

/**
 * 随消息发出的附件喵。
 *
 * 图片文件存在应用私有目录（`files/images/`），数据库里只记路径与元信息喵。
 * [description] 保存识图模型给出的描述——当主模型看不了图时，这段文字就是它"看到"的内容喵。
 */
data class Attachment(
    val id: Long = 0L,
    val messageId: Long = 0L,
    val conversationId: Long = 0L,
    val kind: AttachmentKind = AttachmentKind.IMAGE,
    /** 应用私有目录内的绝对路径喵。 */
    val localPath: String,
    val mimeType: String = "image/jpeg",
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Long = 0L,
    /** 识图模型给出的描述；为空表示还没识别过喵。 */
    val description: String = "",
    val createdAt: Long = 0L,
) {
    val isImage: Boolean get() = kind == AttachmentKind.IMAGE
}

/** 一条联网搜索结果喵。 */
data class SearchHit(
    val title: String,
    val url: String,
    val snippet: String = "",
    val publishedAt: String = "",
)

/** 一次会话（对应一个角色唯一的那段聊天喵）。 */
data class Conversation(
    val id: Long = 0L,
    val personaId: Long,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastMessagePreview: String = "",
    val messageCount: Int = 0,
    val pinned: Boolean = false,
    /**
     * 已从主界面移除喵。
     *
     * 这是个**隐藏**标记，不是删除：角色、消息、记忆全都还在喵。
     * 从通讯录点进这个角色、或发出/收到新消息，它就会回到主界面喵。
     */
    val hidden: Boolean = false,
)

/**
 * 角色列表（首页）的一行喵。
 *
 * 一个角色就是一段对话，所以这里把角色与它那段对话合成一个视图对象喵。
 * [conversation] 为 null 表示这个角色**还没聊过**——列表照样显示它，
 * 但预览位置留白，不为了显示预览而凭空建一个空对话喵。
 */
data class ChatListEntry(
    val persona: Persona,
    val conversation: Conversation?,
) {
    /** 还没聊过：没有预览，时间用角色的创建时间喵。 */
    val neverChatted: Boolean get() = conversation == null || conversation.messageCount == 0

    /** 排序与右侧时间要用的时间戳喵。 */
    val sortTime: Long get() = conversation?.updatedAt ?: persona.createdAt
}

/** 角色卡（AI 人格喵）。 */
data class Persona(
    val id: Long = 0L,
    val name: String,
    val avatarEmoji: String = "🌙",
    /**
     * 自定义头像的文件名（存在应用私有目录 `files/avatars/`），空串表示仍用 [avatarEmoji] 喵。
     *
     * 存**文件名**而不是绝对路径：应用私有目录的绝对路径会随安装位置变化，
     * 升级或迁移后老路径就失效了；文件名是稳定的，取的时候再拼目录喵。
     */
    val avatarImage: String = "",
    /** 一句话简介喵。 */
    val tagline: String = "",
    /** 系统提示词：决定说话风格与人格喵。 */
    val systemPrompt: String = "",
    /** 世界观 / 背景设定，可留空喵。 */
    val scenario: String = "",
    /** 示例对话，帮助模型对齐语气，格式为 `用户: ...\n角色: ...`喵。 */
    val sampleDialogue: String = "",
    /** 触发式开场白喵。 */
    val greeting: String = "",
    /** 覆盖全局的模型名，留空则用设置里的喵。 */
    val modelOverride: String = "",
    /** 温度覆盖，null 表示用全局喵。 */
    val temperatureOverride: Double? = null,
    val ttsVoiceHint: String = "",
    val builtIn: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val sortOrder: Int = 0,
)

/** 收藏类型喵。 */
enum class FavoriteKind(val label: String) {
    MESSAGE("消息"),
    CONVERSATION("对话"),
    PERSONA("角色"),
    NOTE("随手记");

    companion object {
        fun fromName(raw: String?): FavoriteKind =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: MESSAGE
    }
}

/**
 * 收藏项：收藏空间的条目喵。
 *
 * 设计上刻意做成"带快照"的结构——收藏一条消息时把当时的角色名、会话标题一并存下来喵，
 * 这样即使原会话被删除，收藏仍可完整阅读与导出喵。
 */
data class Favorite(
    val id: Long = 0L,
    val kind: FavoriteKind,
    val title: String,
    val content: String,
    /** 附加的长文本（例如整段对话的完整记录喵）。 */
    val detail: String = "",
    val personaName: String = "",
    val conversationTitle: String = "",
    val sourceMessageId: Long = 0L,
    val tags: List<String> = emptyList(),
    val createdAt: Long = 0L,
    /** 收藏夹内的排序权重，越小越靠前喵。 */
    val sortOrder: Int = 0,
)

/** 记忆分类喵。 */
enum class MemoryCategory(val label: String) {
    PROFILE("基本资料"),
    PREFERENCE("喜好"),
    EVENT("经历"),
    RELATION("关系"),
    RULE("约定");

    companion object {
        fun fromName(raw: String?): MemoryCategory =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: PROFILE
    }
}

/**
 * 长期记忆条目：会被注入到系统提示词里喵。
 *
 * 结构上分两层喵：
 * - **老字段**（[category] / [content] / [importance] / [enabled]）是手工录入与自然语言的形态喵，
 *   记忆页、导入导出、系统提示词都还在用它，不动喵；
 * - **新字段**（三元组 + 衰减 + 冷却）是遗忘曲线与唤醒概率需要的状态喵，
 *   由 `MemoryStore` 与 `MemoryRecall` 维护喵。
 *
 * 三元组缺失时 [statement] 退化为 [content喵]，所以提炼出来的旧记忆
 * 不需要回填也能正常参与检索与唤醒喵。
 */
data class MemoryItem(
    val id: Long = 0L,
    val personaId: Long,
    val category: MemoryCategory,
    val content: String,
    /** 1..10，越大越优先注入喵。 */
    val importance: Int = 3,
    val enabled: Boolean = true,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,

    // ------------------------------------------------------------ 三元组（文档 schema 的 subject/predicate/object）
    val subject: String = "",
    val predicate: String = "",
    /** Kotlin 里 `object` 是关键字，字段名用 obj，对应 SQL 列 `object`喵。 */
    val obj: String = "",
    /** 这条记忆发生的场景喵。 */
    val context: String = "",

    // ------------------------------------------------------------ 遗忘曲线状态
    /** 情绪权重 E，0..1，越大越容易被想起喵。 */
    val emotionalWeight: Double = 0.5,
    /** 鲜活度 V，0..1，随时间衰减；低于阈值就不再参与唤醒喵。 */
    val vividness: Double = 1.0,
    /** 被唤醒过的次数 R，衰减公式的分母喵。 */
    val recallCount: Int = 0,
    /** 上一次被唤醒是在第几轮；[MemoryDecay.NEVER_RECALLED_TURN] 表示从未喵。 */
    val lastRecalledTurn: Int = MemoryDecay.NEVER_RECALLED_TURN,
    /** 用户手动改过：衰减减半、鲜活度补满喵。 */
    val isUserEdited: Boolean = false,
    /** 伴侣人设等核心节点：免衰减、免冷却、100% 优先唤醒喵。 */
    val isPermanent: Boolean = false,
    /** 鲜活度跌破剪枝阈值后自动压缩为残影喵。 */
    val pruned: Boolean = false,
    /** 剪枝前的完整正文，苏醒时从这里还原喵。 */
    val originalContent: String = "",
    /** 压缩质量：0=机械压缩（未调用模型），1=模型优化过，-1=模型尝试失败喵。 */
    val pruneEnhanced: Int = 0,
) {

    /** 注入提示词 / 列表展示用的一句话喵。 */
    val statement: String
        get() = content.ifBlank {
            listOf(subject, predicate, obj).filter { it.isNotBlank() }.joinToString("")
        }

    /**
     * 检索用的文本：三元组与正文都算进去喵。
     *
     * 只写一条也能被搜到（三元组常常是空的），写全了两边都能命中喵。
     */
    val searchText: String
        get() = listOf(subject, predicate, obj, context, content)
            .filter { it.isNotBlank() }
            .joinToString(" ")
}

