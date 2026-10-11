package com.qingyu.app.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 表情包导入的回归测试喵。
 *
 * 这里的 zip 字节都是现造的，不是 fixture 文件：导入的失败模式全都长在
 * "包里混进了什么奇怪条目"上（macOS 影子文件、同名文件、坏条目、解压炸弹）喵，
 * 现造才能把每一种混法单独钉住喵。
 */
class StickerImporterTest {

    // ------------------------------------------------------------ 扩展名

    @Test
    fun `六种支持的图片扩展名都认得`() {
        listOf("png", "jpg", "jpeg", "gif", "webp", "bmp").forEach { ext ->
            assertTrue("「$ext」应当是支持的图片", StickerImporter.isSupportedImage("开心.$ext"))
        }
        assertEquals(6, StickerImporter.SUPPORTED_EXTENSIONS.size)
    }

    @Test
    fun `扩展名大小写不敏感`() {
        assertTrue(StickerImporter.isSupportedImage("开心.PNG"))
        assertTrue(StickerImporter.isSupportedImage("开心.JpEg"))
        assertTrue(StickerImporter.isSupportedImage("开心.GIF"))
    }

    @Test
    fun `没有扩展名不算图片`() {
        assertFalse("没有扩展名应当返回 false", StickerImporter.isSupportedImage("开心"))
        assertFalse("只有点也不算", StickerImporter.isSupportedImage("开心."))
    }

    @Test
    fun `不支持的扩展名不算图片`() {
        assertFalse(StickerImporter.isSupportedImage("说明.txt"))
        assertFalse(StickerImporter.isSupportedImage("图标.svg"))
        assertFalse(StickerImporter.isSupportedImage("动图.mp4"))
        assertFalse(StickerImporter.isSupportedImage("图.png.bak"))
    }

    @Test
    fun `带目录的路径也按扩展名判断`() {
        assertTrue(StickerImporter.isSupportedImage("表情/开心.png"))
        assertTrue(StickerImporter.isSupportedImage("a/b/c/开心.webp"))
    }

    // ------------------------------------------------------------ 名字推导

    @Test
    fun `去掉前导序号得到表情名`() {
        assertEquals("「01_开心」应得到开心", "开心", StickerImporter.nameFromFileName("01_开心.png"))
        assertEquals("「02-开心」应得到开心", "开心", StickerImporter.nameFromFileName("02-开心.png"))
        assertEquals("「3.开心」应得到开心", "开心", StickerImporter.nameFromFileName("3.开心.png"))
        assertEquals("「04 开心」应得到开心", "开心", StickerImporter.nameFromFileName("04 开心.png"))
    }

    @Test
    fun `纯数字名字不会被序号规则吃掉`() {
        assertEquals("「123.png」应原样保留", "123", StickerImporter.nameFromFileName("123.png"))
        assertEquals("「7.gif」应原样保留", "7", StickerImporter.nameFromFileName("7.gif"))
    }

    @Test
    fun `序号后面没有分隔符时不剥离`() {
        assertEquals(
            "「2B小姐姐」不该被截成 B小姐姐",
            "2B小姐姐",
            StickerImporter.nameFromFileName("2B小姐姐.png"),
        )
        assertEquals("「404猫」不是序号", "404猫", StickerImporter.nameFromFileName("404猫.jpg"))
    }

    @Test
    fun `去掉目录部分只取最后一段`() {
        assertEquals("表情", "开心", StickerImporter.nameFromFileName("表情/开心.png"))
        assertEquals("多级目录", "开心", StickerImporter.nameFromFileName("a/b/c/开心.png"))
        assertEquals("反斜杠目录", "开心", StickerImporter.nameFromFileName("表情\\开心.png"))
    }

    @Test
    fun `下划线和连字符换成空格`() {
        assertEquals("开心 大笑", StickerImporter.nameFromFileName("开心_大笑.png"))
        assertEquals("开心 大笑", StickerImporter.nameFromFileName("开心-大笑.png"))
        assertEquals("开心 大 笑", StickerImporter.nameFromFileName("开心_大-笑.png"))
    }

    @Test
    fun `连续空白合并成一个并去掉首尾`() {
        assertEquals("开心 大笑", StickerImporter.nameFromFileName("开心 _- 大笑.png"))
        assertEquals("开心", StickerImporter.nameFromFileName("  开心  .png"))
    }

    @Test
    fun `结果为空时回退成原文件名`() {
        assertEquals("全是分隔符时回退原文", "___", StickerImporter.nameFromFileName("___.png"))
        assertEquals("「-」也回退原文", "-", StickerImporter.nameFromFileName("-.png"))
    }

    @Test
    fun `没有真扩展名时不该把点后面当成扩展名切掉`() {
        assertEquals(
            "「猫猫.表情」的点后面不是扩展名，得整段留下",
            "猫猫.表情",
            StickerImporter.nameFromFileName("猫猫.表情"),
        )
    }

    // ------------------------------------------------------------ zip 读取

    @Test
    fun `从 zip 里读出表情条目`() {
        val png = byteArrayOf(1, 2, 3, 4)
        val entries = StickerImporter.entriesFromZip(
            zipOf("开心.png" to png, "难过.jpg" to byteArrayOf(9)),
        )
        assertEquals("应当读出两个条目", 2, entries.size)
        assertEquals("开心", entries[0].name)
        assertEquals("难过", entries[1].name)
        assertEquals("开心.png", entries[0].fileName)
        assertArrayEquals("字节应当原样读出", png, entries[0].bytes)
    }

    @Test
    fun `保持压缩包内的原始顺序`() {
        val entries = StickerImporter.entriesFromZip(
            zipOf(
                "03_丙.png" to byteArrayOf(3),
                "01_甲.png" to byteArrayOf(1),
                "02_乙.png" to byteArrayOf(2),
            ),
        )
        assertEquals("顺序应与包内一致", listOf("丙", "甲", "乙"), entries.map { it.name })
    }

    @Test
    fun `跳过目录条目`() {
        val entries = StickerImporter.entriesFromZip(
            zipOf("表情/" to ByteArray(0), "表情/开心.png" to byteArrayOf(1)),
        )
        assertEquals("目录条目不该产出表情", 1, entries.size)
        assertEquals("开心", entries[0].name)
    }

    @Test
    fun `跳过 __MACOSX 里的影子文件`() {
        val entries = StickerImporter.entriesFromZip(
            zipOf(
                "__MACOSX/._开心.png" to byteArrayOf(1),
                "__MACOSX/开心.png" to byteArrayOf(2),
                "开心.png" to byteArrayOf(3),
            ),
        )
        assertEquals("只应留下真实的那一个", 1, entries.size)
        assertEquals("开心", entries[0].name)
        assertArrayEquals("留下的应当是真实字节", byteArrayOf(3), entries[0].bytes)
    }

    @Test
    fun `跳过点开头的隐藏文件`() {
        val entries = StickerImporter.entriesFromZip(
            zipOf(
                "._开心.png" to byteArrayOf(1),
                ".DS_Store" to byteArrayOf(2),
                ".hidden.png" to byteArrayOf(3),
                "开心.png" to byteArrayOf(4),
            ),
        )
        assertEquals("隐藏文件全部跳过", 1, entries.size)
        assertEquals("开心", entries[0].name)
    }

    @Test
    fun `跳过不支持的扩展名`() {
        val entries = StickerImporter.entriesFromZip(
            zipOf(
                "说明.txt" to byteArrayOf(1),
                "开心.png" to byteArrayOf(2),
                "图标.svg" to byteArrayOf(3),
            ),
        )
        assertEquals("只留图片", 1, entries.size)
        assertEquals("开心", entries[0].name)
    }

    @Test
    fun `支持子目录里的条目`() {
        val entries = StickerImporter.entriesFromZip(
            zipOf("表情包/可爱/01_开心.png" to byteArrayOf(7)),
        )
        assertEquals(1, entries.size)
        assertEquals("名字取最后一段并剥掉序号", "开心", entries[0].name)
        assertEquals("fileName 只留文件名", "01_开心.png", entries[0].fileName)
    }

    @Test
    fun `名字撞车时加序号后缀`() {
        val entries = StickerImporter.entriesFromZip(
            zipOf(
                "开心.png" to byteArrayOf(1),
                "开心.jpg" to byteArrayOf(2),
                "开心.webp" to byteArrayOf(3),
            ),
        )
        assertEquals("撞车的名字依次加后缀", listOf("开心", "开心 2", "开心 3"), entries.map { it.name })
        assertArrayEquals("后缀不影响字节", byteArrayOf(2), entries[1].bytes)
    }

    @Test
    fun `后缀被占用时继续往后找`() {
        val entries = StickerImporter.entriesFromZip(
            zipOf(
                "开心.png" to byteArrayOf(1),
                "开心_2.png" to byteArrayOf(2),
                "开心.jpg" to byteArrayOf(3),
            ),
        )
        assertEquals(
            "「开心 2」被占了就该跳到「开心 3」",
            listOf("开心", "开心 2", "开心 3"),
            entries.map { it.name },
        )
    }

    @Test
    fun `空 zip 返回空列表`() {
        assertTrue("没有条目的 zip 应当返回空列表", StickerImporter.entriesFromZip(zipOf()).isEmpty())
        assertTrue("空字节数组也不该崩", StickerImporter.entriesFromZip(ByteArray(0)).isEmpty())
    }

    @Test
    fun `不是 zip 的字节不崩只是读不出东西`() {
        val garbage = ByteArray(64) { (it * 7).toByte() }
        assertTrue("垃圾字节应当返回空列表", StickerImporter.entriesFromZip(garbage).isEmpty())
    }

    @Test
    fun `截断的 zip 能读多少算多少`() {
        // 从第二个条目的头切断：第一条完整，第二条连头都没有喵。
        val full = zipOf("开心.png" to byteArrayOf(1, 2, 3, 4), "难过.png" to byteArrayOf(5, 6, 7, 8))
        val entries = StickerImporter.entriesFromZip(full.copyOf(offsetOfSecondEntry(full)))
        assertEquals("前面完好的条目应当留下", 1, entries.size)
        assertEquals("开心", entries[0].name)
        assertArrayEquals("留下的字节应当完整", byteArrayOf(1, 2, 3, 4), entries[0].bytes)
    }

    @Test
    fun `超过 2MB 的条目被跳过`() {
        val tooBig = ByteArray(2 * 1024 * 1024 + 1)
        val entries = StickerImporter.entriesFromZip(
            zipOf("炸弹.png" to tooBig, "正常.png" to byteArrayOf(1)),
        )
        assertEquals("超大条目应当被跳过", 1, entries.size)
        assertEquals("正常", entries[0].name)
    }

    @Test
    fun `刚好 2MB 的条目可以读`() {
        val justFit = ByteArray(2 * 1024 * 1024) { 7 }
        val entries = StickerImporter.entriesFromZip(zipOf("大图.png" to justFit))
        assertEquals("上限本身是允许的", 1, entries.size)
        assertEquals(2 * 1024 * 1024, entries[0].bytes.size)
    }

    @Test
    fun `字节用 contentEquals 比较才准`() {
        val entries = StickerImporter.entriesFromZip(zipOf("开心.png" to byteArrayOf(1, 2, 3)))
        assertTrue("内容相等就该为 true", entries[0].bytes.contentEquals(byteArrayOf(1, 2, 3)))
        assertArrayEquals(byteArrayOf(1, 2, 3), entries[0].bytes)
    }

    // ------------------------------------------------------------ 造 zip

    /** 用默认的 deflate 造一个 zip 喵。 */
    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /**
     * 找出第二个条目的本地头偏移，用来把 zip 从那里切断喵。
     *
     * 本地头固定以 `PK\3\4` 开头，而测试里的条目数据只有几个字节，不会撞上这个魔数喵。
     */
    private fun offsetOfSecondEntry(zip: ByteArray): Int {
        val signature = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        for (start in 4..zip.size - signature.size) {
            if (signature.indices.all { zip[start + it] == signature[it] }) return start
        }
        throw AssertionError("造出来的 zip 里没找到第二个条目")
    }
}
