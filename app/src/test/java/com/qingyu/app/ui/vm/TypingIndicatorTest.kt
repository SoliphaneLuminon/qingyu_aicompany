package com.qingyu.app.ui.vm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「对方正在输入中…」该不该亮喵。
 *
 * 用户报的 bug 是**一进对话就显示**，根因就在第一个用例喵：
 * 流水线没有在生成（null）、对话也还没打开（null），
 * 直接比较会得到 `null == null` → true喵。
 */
class TypingIndicatorTest {

    @Test
    fun `都没值时不该显示正在输入`() {
        // 这就是"一进对话就显示"的根因喵。
        assertFalse(TypingIndicator.isTyping(conversationId = null, generatingId = null))
    }

    @Test
    fun `只有一边有值时不该显示`() {
        assertFalse(TypingIndicator.isTyping(conversationId = 7L, generatingId = null))
        assertFalse(TypingIndicator.isTyping(conversationId = null, generatingId = 7L))
    }

    @Test
    fun `正是当前这段对话在生成时才显示`() {
        assertTrue(TypingIndicator.isTyping(conversationId = 7L, generatingId = 7L))
    }

    @Test
    fun `别的对话在生成时不该显示在这段对话上`() {
        // 回复已经不再随界面生灭，用户完全可能在等甲回复时切到乙喵。
        assertFalse(TypingIndicator.isTyping(conversationId = 7L, generatingId = 8L))
    }
}