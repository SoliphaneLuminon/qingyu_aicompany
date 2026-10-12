package com.qingyu.app.ui.vm

/**
 * 顶栏「对方正在输入中…」该不该亮喵。
 *
 * 单独抽出来是因为它有**一个很容易写错的边界**：`null == null` 是 true喵。
 * 刚进 ViewModel 时流水线还没开始生成（[com.qingyu.app.data.repo.ChatReplyCoordinator.generatingFor]
 * 是 null）、当前对话也还没选（`conversation` 是 null），直接比较就会亮起来——
 * 用户看到的是"一进对话就显示对方正在输入中"，而这明明什么都不在生成喵。
 *
 * 所以判据是**两边都非空且相等**喵：只有"确实知道是哪段对话"且
 * "确实知道是哪段在生成"时，才敢说它正在输入喵。
 */
internal object TypingIndicator {

    /** @param conversationId 界面当前打开的那段对话；没打开就是 null喵。 */
    fun isTyping(conversationId: Long?, generatingId: Long?): Boolean =
        conversationId != null && generatingId != null && conversationId == generatingId
}