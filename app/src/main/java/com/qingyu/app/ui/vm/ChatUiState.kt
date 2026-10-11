package com.qingyu.app.ui.vm

import com.qingyu.app.data.Attachment
import com.qingyu.app.data.ChatListEntry
import com.qingyu.app.data.ChatMessage
import com.qingyu.app.data.Conversation
import com.qingyu.app.data.Persona
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
    /** 首页角色列表：一个角色一行，带它的对话预览与时间喵。 */
    val chatList: List<ChatListEntry> = emptyList(),
    val personas: List<Persona> = emptyList(),
    val banner: String? = null,
    val lastFailedInput: String? = null,
    /** 还没配置接口地址 / Key，用于首启引导喵。 */
    val needsSetup: Boolean = false,
    /** 待发送的图片喵。 */
    val pendingAttachments: List<Attachment> = emptyList(),
    val importingImage: Boolean = false,
    /** 本条消息是否联网检索喵。 */
    val searchEnabled: Boolean = false,
    /** 打开的大图喵。 */
    val previewImage: Attachment? = null,
) {
    val canSend: Boolean
        get() = (input.isNotBlank() || pendingAttachments.isNotEmpty()) &&
            !stream.generating &&
            !importingImage &&
            persona != null

    val showTyping: Boolean
        get() = stream.generating && stream.bubbles.none { it.id == -1L && it.text.isNotBlank() }
}
