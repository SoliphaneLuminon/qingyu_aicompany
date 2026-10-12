package com.qingyu.app.ui.vm

import com.qingyu.app.data.Attachment
import com.qingyu.app.data.ChatListEntry
import com.qingyu.app.data.ChatMessage
import com.qingyu.app.data.Conversation
import com.qingyu.app.data.Persona
import com.qingyu.app.data.Sticker
import com.qingyu.app.data.repo.ChatStreamState
import com.qingyu.app.data.repo.ContextSnapshot

/** 聊天页状态喵。 */
data class ChatUiState(
    val loading: Boolean = true,
    val persona: Persona? = null,
    val conversation: Conversation? = null,
    val messages: List<ChatMessage> = emptyList(),
    val stream: ChatStreamState = ChatStreamState(),
    /**
     * 最近一轮真正发出去的提示词与注入的记忆喵。
     *
     * 自检页只读这一份，不再自己重算——重算与真实请求必然有出入喵
     * （漏联网资料、漏图片、看不到润色版提示词，记忆也可能选中另一批喵）。
     */
    val lastContext: ContextSnapshot? = null,
    /** 顶栏是否显示「对方正在输入中…」——流式进行中或段间延迟队列非空时点亮喵。 */
    val topBarTyping: Boolean = false,
    val input: String = "",
    /** 主界面的对话列表：只含有对话、且没被移除的那些。 */
    val chatList: List<ChatListEntry> = emptyList(),
    /**
     * 全部会话的未读条数之和喵，底部「聊天」标签上那个红点用它喵。
     *
     * 它比 [chatList] 里各条 `unread` 之和**只多不少**喵：被移除的会话不在列表里，
     * 但它没读过的消息照样是"没看过的消息"，漏掉它红点会少报喵。
     */
    val unreadTotal: Int = 0,
    /** 主界面用：这些是 chatList 里的角色。 */
    val personas: List<Persona> = emptyList(),
    /** 通讯录用：**全部**角色，含对话已被移除的。 */
    val allPersonas: List<Persona> = emptyList(),
    /**
     * 角色 → 它的那段对话，**含被移除的**喵。
     *
     * 通讯录与角色资料页要用它显示"聊过多少条、最近什么时候"，
     * 所以不能只依赖 [chatList]（那里面已经滤掉了被移除的）喵。
     */
    val conversationByPersona: Map<Long, Conversation> = emptyMap(),
    val banner: String? = null,
    /** 还没配置接口地址 / Key，用于首启引导喵。 */
    val needsSetup: Boolean = false,
    /** 待发送的图片喵。 */
    val pendingAttachments: List<Attachment> = emptyList(),
    val importingImage: Boolean = false,
    /** 本条消息是否联网检索喵。 */
    val searchEnabled: Boolean = false,
    /** 打开的大图喵。 */
    val previewImage: Attachment? = null,
    /** 表情包面板是否展开喵。 */
    val stickerPanelOpen: Boolean = false,
    /** 「＋」扩展面板是否展开喵。 */
    val plusPanelOpen: Boolean = false,
    /** 表情包列表，常用的在前喵。 */
    val stickers: List<Sticker> = emptyList(),
    /** 正在导入表情包喵。 */
    val importingStickers: Boolean = false,
    /** 正在录音（按住说话）喵。 */
    val recording: Boolean = false,
    /** 录音已开始的时刻，用于显示秒数喵。 */
    val recordingStartedAt: Long = 0L,
    /** 是否处于实时语音通话中喵。 */
    val inCall: Boolean = false,
    /** 红包编辑弹窗是否打开喵。 */
    val redPacketOpen: Boolean = false,
) {
    /**
     * 现在能不能发送喵。
     *
     * **刻意不看 `stream.generating`** 喵：发消息这件事与后端彻底解绑，
     * 模型正在回答时用户照样可以继续发，那些消息会并进同一个合并窗口，
     * 或排在当前这一轮之后喵。以前这里挡着生成状态，用户想补一句
     * 就只能等它说完——那正是"连发多条会出问题"的来源之一喵。
     *
     * 仍然挡的是 [importingImage]：图片还在落盘、附件还没就绪，
     * 这时发出去会是一条没有图的空消息喵。
     */
    val canSend: Boolean
        get() = (input.isNotBlank() || pendingAttachments.isNotEmpty()) &&
            !importingImage &&
            persona != null
}
