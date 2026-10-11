package com.qingyu.app.media

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 从压缩包里读出的一个表情条目喵。
 *
 * [name] 是入库用的表情名，撞车时已经加过序号后缀；[fileName] 是条目在包里的文件名喵。
 * [bytes] 是 `ByteArray`，data class 自动生成的 equals 对它做的是引用比较喵，
 * 所以断言内容相等要用 `contentEquals` 或 `assertArrayEquals`喵。
 */
data class StickerEntry(
    val name: String,
    val fileName: String,
    val bytes: ByteArray,
)

/**
 * 表情包压缩包的导入层：纯 JVM，只用 `java.util.zip` 与 `java.io`，不碰任何 android 类喵。
 *
 * 单独抽一层是为了能被普通单元测试直接跑——导入的输入完全不受我们控制喵：
 * 压缩包可能来自 Windows 资源管理器、macOS 的 Finder、手机文件管理器，
 * 里面混着目录项、`__MACOSX` 影子文件、同名文件、解压炸弹喵。
 * 这些分支在真机上只有拿到坏包才会暴露，做成纯函数才能一条条钉住喵。
 */
object StickerImporter {

    /** 支持的图片扩展名（小写，不含点）喵。 */
    val SUPPORTED_EXTENSIONS: Set<String> = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

    /**
     * 单个条目的字节上限喵。
     *
     * 表情图超过 2 MB 已经不是表情了，而 zip 的 entry 头里写的 size 可以撒谎喵，
     * 所以只按实际读出的字节数封顶：一个几百 KB 的包能声明出几 GB 的条目，
     * 不设上限的话导入会直接把内存吃光喵。
     */
    private const val MAX_ENTRY_BYTES = 2 * 1024 * 1024

    /** 一次读 entry 的缓冲区大小喵。 */
    private const val READ_BUFFER_BYTES = 8 * 1024

    /** 扩展名最多这么长，再长的尾巴就不是扩展名了喵。 */
    private const val MAX_EXTENSION_LENGTH = 5

    /** 前导序号与名字之间的分隔符，必须是其中之一才剥离喵。 */
    private val LEADING_INDEX = Regex("""^(\d+)[-_.\s]+(.+)$""")

    /** 连续空白合并成一个空格喵。 */
    private val WHITESPACE_RUN = Regex("""\s+""")

    /** 这个文件名是不是支持的图片喵。 */
    fun isSupportedImage(fileName: String): Boolean = extensionOf(fileName) in SUPPORTED_EXTENSIONS

    /** 从文件名推出表情名喵。 */
    fun nameFromFileName(fileName: String): String {
        val stem = stemOf(fileName)
        // 序号后面必须紧跟分隔符才算序号：`123` 本身就是名字，`2B小姐姐` 的 2 也不是序号喵。
        val withoutIndex = LEADING_INDEX.find(stem)?.groupValues?.get(2).orEmpty().ifEmpty { stem }
        val spaced = withoutIndex.replace('_', ' ').replace('-', ' ')
        val collapsed = WHITESPACE_RUN.replace(spaced, " ").trim()
        // 整串都是分隔符时会被剥成空串，宁可名字丑一点也不能让表情没有名字喵。
        return collapsed.ifEmpty { stem }
    }

    /**
     * 把一个 zip 的字节读成表情条目列表喵。
     *
     * 逐个 entry 流式读取、读完一条丢一条，所以整包的内存占用只跟单条上限有关喵。
     * 坏包不抛异常：能读出来的条目按包内原顺序返回，读不出来的安静跳过喵。
     */
    fun entriesFromZip(bytes: ByteArray): List<StickerEntry> {
        val entries = mutableListOf<StickerEntry>()
        val usedNames = mutableSetOf<String>()

        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                // 坏包常常在某个 entry 头上就断了，取不到下一条就当读完了，别让整次导入失败喵。
                val entry = runCatching { zip.nextEntry }.getOrNull() ?: break
                if (entry.isDirectory) continue
                val path = entry.name
                if (isJunkEntry(path) || !isSupportedImage(path)) continue
                // 解压到一半断掉的条目只跳过它自己，后面的条目照读喵。
                val data = runCatching { readCapped(zip, MAX_ENTRY_BYTES) }.getOrNull() ?: continue
                val name = uniqueName(nameFromFileName(path), usedNames)
                entries += StickerEntry(name = name, fileName = baseNameOf(path), bytes = data)
            }
        }
        return entries
    }

    // ------------------------------------------------------------ 内部

    /**
     * 取小写扩展名（不含点），没有扩展名时是空串喵。
     *
     * 只看最后一段路径里的最后一个点：`表情/开心.PNG` 要能认出来喵。
     * 开头就是点（`.png`）当隐藏文件看，不算有扩展名喵。
     */
    private fun extensionOf(fileName: String): String {
        val base = baseNameOf(fileName)
        val dot = base.lastIndexOf('.')
        if (dot <= 0 || dot == base.length - 1) return ""
        return base.substring(dot + 1).lowercase()
    }

    /**
     * 去掉扩展名喵。
     *
     * 只有尾巴"像扩展名"（短、纯 ASCII 字母数字）才去掉喵：
     * 否则 `3.开心` 会被截成 `3`——那个点属于名字，不是扩展名喵。
     */
    private fun stemOf(fileName: String): String {
        val base = baseNameOf(fileName)
        val dot = base.lastIndexOf('.')
        if (dot <= 0) return base
        val ext = base.substring(dot + 1)
        val looksLikeExtension = ext.isNotEmpty() &&
            ext.length <= MAX_EXTENSION_LENGTH &&
            ext.all { it.code < 0x80 && it.isLetterOrDigit() }
        return if (looksLikeExtension) base.substring(0, dot) else base
    }

    /** 取路径的最后一段，`/` 与 `\` 都算分隔符喵。 */
    private fun baseNameOf(path: String): String =
        path.substringAfterLast('/').substringAfterLast('\\')

    /**
     * 判断是不是该直接丢掉的条目喵。
     *
     * macOS 的压缩工具会给每个文件塞一份 `._foo.png` 的 AppleDouble 影子文件，
     * 并把它们集中放在 `__MACOSX/` 下喵。影子文件按扩展名会被认成图片，
     * 导进来就是一串打不开的灰块，所以整个 `__MACOSX/` 目录与点开头的文件都不收喵。
     */
    private fun isJunkEntry(path: String): Boolean {
        if (path.contains("__MACOSX/")) return true
        return baseNameOf(path).startsWith(".")
    }

    /**
     * 最多读 [limit] 个字节，超了就返回 null 交给调用方跳过喵。
     *
     * 只能一边读一边数：entry 头里的 size 由压缩包作者决定，信它就会被打包好的炸弹打穿喵。
     */
    private fun readCapped(input: InputStream, limit: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (out.size() + read > limit) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /**
     * 名字撞车时加序号后缀喵。
     *
     * 表情是按名字存的，两个文件都推成"开心"的话后一个会把前一个顶掉喵，
     * 所以第二个叫"开心 2"、第三个叫"开心 3"喵。
     * 后缀本身也可能被别的文件占着（包里同时有 `开心.png` 和 `开心_2.png`）喵，
     * 所以一直往后找到空位为止喵。
     */
    private fun uniqueName(base: String, used: MutableSet<String>): String {
        if (used.add(base)) return base
        var index = 2
        while (true) {
            val candidate = "$base $index"
            if (used.add(candidate)) return candidate
            index++
        }
    }
}
