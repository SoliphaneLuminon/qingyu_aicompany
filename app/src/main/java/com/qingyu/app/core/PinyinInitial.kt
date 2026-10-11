package com.qingyu.app.core

import java.text.Collator
import java.util.Locale

/**
 * 通讯录的分组首字母喵。
 *
 * 做法是拿系统自带的 [Collator]（中文环境下就是按拼音比较）与一串锚点字做二分：
 * 找出"最后一个不大于它的锚点"，那个锚点所在的字母段就是它的拼音首字母喵。
 *
 * **刻意不内置拼音表**：那种表通常几十 KB，而 Collator 是系统自带的，
 * 零体积、零维护，对中文名的首字母判定已经够准（不认多音字，但通讯录不需要）喵。
 *
 * 纯函数、不依赖 Android，所以有单元测试钉住喵。
 */
object PinyinInitial {

    /** 每个拼音首字母取一个代表字。拼音里没有 i / u / v 开头的音节，所以只有 23 个喵。 */
    private val ANCHOR_CHARS = listOf(
        "阿", "芭", "擦", "搭", "蛾", "发", "噶", "哈",
        "击", "喀", "垃", "妈", "拿", "哦", "啪", "期",
        "然", "撒", "塌", "挖", "昔", "压", "匝",
    )

    /** 与 [ANCHOR_CHARS] 一一对应的首字母喵。 */
    private const val ANCHOR_LETTERS = "ABCDEFGHJKLMNOPQRSTWXYZ"

    /** 非中文、非字母的名字统一归到这一组，排在最后喵。 */
    const val OTHER = "#"

    /** 右侧索引上按顺序显示的字母喵。 */
    val INDEX_LETTERS: List<String> = ANCHOR_LETTERS.map { it.toString() } + OTHER

    private val collator: Collator = Collator.getInstance(Locale.CHINA)

    /**
     * 取名字的首字母；中文按拼音，英文按大写首字母，其余归 [OTHER]喵。
     *
     * 名字为空、或首字符既不是汉字也不是字母（emoji、数字、符号）时归到 [OTHER]喵。
     */
    fun of(name: String?): String {
        if (name.isNullOrEmpty()) return OTHER
        val first = name[0]
        if (first in 'a'..'z') return (first - 32).toString()
        if (first in 'A'..'Z') return first.toString()
        // 汉字基本区；扩展区（生僻字）交给 Collator 也没问题，但锚点表覆盖不到，归入 OTHER喵。
        if (first.code !in 0x4E00..0x9FFF) return OTHER
        val firstName = first.toString()
        var matched = OTHER
        for (index in ANCHOR_CHARS.indices) {
            // 锚点不大于首字 → 首字属于这个锚点开启的字母段；一旦锚点比首字大就可以停了喵。
            if (collator.compare(ANCHOR_CHARS[index], firstName) <= 0) {
                matched = ANCHOR_LETTERS[index].toString()
            } else {
                break
            }
        }
        return matched
    }

    /**
     * 把角色名分组并排好序喵。
     *
     * 组内用 [Collator] 按拼音排，组间按字母顺序、[OTHER] 永远最后喵。
     * 抽成泛型是为了能直接单测，不必构造 Persona喵。
     */
    fun <T> group(items: List<T>, nameOf: (T) -> String): List<Pair<String, List<T>>> {
        val grouped = items.groupBy { of(nameOf(it)) }
        return grouped.entries
            .sortedBy { entry ->
                // OTHER 排最后：给它一个比所有字母都大的排序键喵。
                if (entry.key == OTHER) "ZZZ$OTHER" else entry.key
            }
            .map { entry ->
                entry.key to entry.value.sortedWith(
                    Comparator { a, b -> collator.compare(nameOf(a), nameOf(b)) },
                )
            }
    }
}
