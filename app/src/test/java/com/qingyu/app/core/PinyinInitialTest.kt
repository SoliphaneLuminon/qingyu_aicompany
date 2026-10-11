package com.qingyu.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 通讯录首字母分组的测试喵。
 *
 * 这里用的是系统 [java.text.Collator] 而不是内置拼音表，所以必须钉住
 * "锚点法确实能判对常见姓氏"，否则整个通讯录的分组会乱掉喵。
 */
class PinyinInitialTest {

    @Test
    fun `常见姓氏的拼音首字母`() {
        val cases = mapOf(
            "张三" to "Z", "李四" to "L", "王五" to "W", "赵六" to "Z",
            "钱七" to "Q", "孙八" to "S", "周九" to "Z", "吴十" to "W",
            "郑一" to "Z", "陈二" to "C", "刘三" to "L", "黄四" to "H",
            "徐五" to "X", "马六" to "M", "朱七" to "Z", "胡八" to "H",
            "郭九" to "G", "林十" to "L", "何一" to "H", "高八" to "G",
        )
        cases.forEach { (name, expected) ->
            assertEquals("「$name」的首字母", expected, PinyinInitial.of(name))
        }
    }

    @Test
    fun `每个锚点字母段都能被命中`() {
        // 锚点字本身必须各自落回自己那一段，否则二分就偏了喵。
        val anchors = mapOf(
            "阿" to "A", "芭" to "B", "擦" to "C", "搭" to "D", "蛾" to "E",
            "发" to "F", "噶" to "G", "哈" to "H", "击" to "J", "喀" to "K",
            "垃" to "L", "妈" to "M", "拿" to "N", "哦" to "O", "啪" to "P",
            "期" to "Q", "然" to "R", "撒" to "S", "塌" to "T", "挖" to "W",
            "昔" to "X", "压" to "Y", "匝" to "Z",
        )
        anchors.forEach { (char, expected) ->
            assertEquals("锚点「$char」", expected, PinyinInitial.of(char))
        }
    }

    @Test
    fun `英文名按大写首字母`() {
        assertEquals("A", PinyinInitial.of("alice"))
        assertEquals("B", PinyinInitial.of("Bob"))
        assertEquals("Z", PinyinInitial.of("zoe"))
    }

    @Test
    fun `非中文非字母归入其他组`() {
        assertEquals("#", PinyinInitial.of("🌙月光"))
        assertEquals("#", PinyinInitial.of("123"))
        assertEquals("#", PinyinInitial.of("-无名-"))
        assertEquals("#", PinyinInitial.of(""))
        assertEquals("#", PinyinInitial.of(null))
    }

    @Test
    fun `分组按字母顺序且其他组排最后`() {
        val names = listOf("张三", "李四", "阿昭", "🌙", "王五", "Bob")
        val groups = PinyinInitial.group(names) { it }
        assertEquals(listOf("A", "B", "L", "W", "Z", "#"), groups.map { it.first })
    }

    @Test
    fun `同组内按拼音排序`() {
        val names = listOf("周九", "张三", "赵六", "郑一")
        val groups = PinyinInitial.group(names) { it }
        assertEquals(1, groups.size)
        assertEquals("Z", groups.first().first)
        // 同是 Z 开头，拼音顺序应为 zhang < zhao < zheng < zhou 喵。
        assertEquals(listOf("张三", "赵六", "郑一", "周九"), groups.first().second)
    }

    @Test
    fun `索引字母包含全部 23 个字母加其他`() {
        val letters = PinyinInitial.INDEX_LETTERS
        // 拼音里没有 I / U / V 开头的音节，所以索引上也不该出现它们喵。
        assertEquals(24, letters.size)
        listOf("I", "U", "V").forEach {
            assertEquals("索引不该有 $it", false, letters.contains(it))
        }
        assertEquals("#", letters.last())
        assertEquals("A", letters.first())
    }

    @Test
    fun `空列表不报错`() {
        assertEquals(emptyList<Pair<String, List<String>>>(), PinyinInitial.group(emptyList<String>()) { it })
    }
}
