package com.qingyu.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 未读红点里那串文案的边界喵。
 *
 * 它看着简单，但四个边界各有各的错法喵：0 画出来是一个空红圈、
 * 100 画出来会把圆点撑成一大坨、负数（理论上不该出现）画出来是一条负号喵。
 */
class UnreadBadgeTest {

    @Test
    fun `零条与负数都不画徽标`() {
        assertNull("0 条就该整个不画", unreadBadgeLabel(0))
        assertNull("负数是个不该出现的情况，但也别画出个负号来", unreadBadgeLabel(-3))
    }

    @Test
    fun `一位数原样显示`() {
        assertEquals("1", unreadBadgeLabel(1))
        assertEquals("9", unreadBadgeLabel(9))
    }

    @Test
    fun `两位数原样显示`() {
        assertEquals("99", unreadBadgeLabel(99))
    }

    @Test
    fun `超过九十九只报到九十九加`() {
        assertEquals("100 起就不再报具体数字", "99+", unreadBadgeLabel(100))
        assertEquals("很大的数也一样", "99+", unreadBadgeLabel(4321))
    }
}
