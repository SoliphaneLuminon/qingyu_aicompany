package com.qingyu.app.media

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 图片缩放的算术测试喵。
 *
 * 缩放公式是纯函数，但它在真实使用中"错了也看不出来"——不过是多花 token喵、
 * 或者图片被拉变形。所以把长边约束与宽高比保持钉住喵。
 */
class ImageScalingTest {

    @Test
    fun `横图按长边缩小并保持比例`() {
        val (w, h) = ImageStore.scaledSize(4000, 3000, 1280)
        assertEquals(1280, w)
        assertEquals(960, h)
    }

    @Test
    fun `竖图按长边缩小`() {
        val (w, h) = ImageStore.scaledSize(3000, 4000, 1280)
        assertEquals(960, w)
        assertEquals(1280, h)
    }

    @Test
    fun `正方形图`() {
        val (w, h) = ImageStore.scaledSize(2000, 2000, 1280)
        assertEquals(1280, w)
        assertEquals(1280, h)
    }

    @Test
    fun `已经够小的图不动它`() {
        assertEquals(600 to 400, ImageStore.scaledSize(600, 400, 1280))
        assertEquals(1280 to 720, ImageStore.scaledSize(1280, 720, 1280))
    }

    @Test
    fun `极端宽高比也不会缩成 0`() {
        val (w, h) = ImageStore.scaledSize(4000, 1, 1280)
        assertEquals(1280, w)
        assertEquals(1, h)
    }

    @Test
    fun `尺寸为 0 时不崩`() {
        assertEquals(0 to 0, ImageStore.scaledSize(0, 0, 1280))
    }

    @Test
    fun `发送上限是 1280`() {
        assertEquals(1280, ImageStore.MAX_SEND_EDGE)
    }

    @Test
    fun `字节数显示成好读的单位`() {
        assertEquals("512 B", ImageStore.readableSize(512))
        assertEquals("1 KB", ImageStore.readableSize(1024))
        assertEquals("1.5 MB", ImageStore.readableSize((1.5 * 1024 * 1024).toLong()))
    }
}
