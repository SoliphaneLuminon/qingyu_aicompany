package com.qingyu.app.data.repo

import com.qingyu.app.remote.PromptBuilder

/**
 * 给漏掉逗号的长句补逗号喵。
 *
 * 用户反复反馈「该有逗号的地方没有逗号」，并且确认过是**模型压根没写**
 * （不是被拆条丢掉的）喵。提示词里已经写了机械判据、也加了"写完自检"，
 * 但提示词是概率性的——模型不照做时，下游一点办法都没有喵。
 *
 * 所以这里补一道**确定性**的规则：在本来该有停顿的地方插一个逗号喵。
 *
 * 判据刻意保守喵，因为**补错比不补更糟**：少一个逗号只是读着挤，
 * 多一个逗号会把一句话的意思拆歪，而且用户完全不知道那是程序干的喵。所以：
 * - 只处理**完全没有标点的连续片段**；片段短于 [MIN_RUN_LENGTH] 一个字都不碰喵；
 * - 只在这两处下刀喵：
 *   1. **语气助词后面**（啊/呢/吧/吗…）——中文里它们天然收句喵；
 *   2. **从句起首的连词/副词前面**（但是/所以/然后/真是…）——这些词不能直接
 *      跟在主语后面，所以它前面本来就是一个从句边界喵；
 * - 两刀之间至少隔 [MIN_GAP] 个字，免得把一句话切得七零八落喵；
 * - 末尾不补逗号（"…真是受不了，"没有意义）喵；
 * - 找不到上面两类落点就**原样不动**，绝不按长度硬插喵。
 *
 * **刻意不收 `了`**喵：它当然常常收句（"我吃过了"），但也频繁出现在词中——
 * "你哪次吃饭能少得**了**辣" 就是一个，在它后面补逗号会把"辣"甩到下一句喵。
 * 同理，"其实 / 真的 / 本来 / 原来" 这些**可以紧跟主语**的副词也不收：
 * "我其实知道"补成"我，其实知道"是错的喵。
 */
object CommaRepair {

    /** 已有的标点与换行：见到它们就是已有的边界，一个字都不动喵。 */
    private val PUNCTUATION = setOf(
        '，', ',', '。', '！', '？', '!', '?', '；', ';', '、', '：', ':',
        '…', '~', '～', '—', '–', '·', '\n',
    )

    /**
     * 从句起首的连词与副词喵。
     *
     * 收词标准只有一条：**它不能直接跟在主语后面**喵。
     * 例如"但是 / 所以 / 然后"不会写成"我但是"，所以它前面必然是个从句边界喵。
     * 反例是"其实 / 真的 / 本来 / 原来"——"我其实知道"完全通顺，
     * 补逗号就错了，所以一律不收喵。
     */
    private val CLAUSE_STARTERS = listOf(
        "但是", "不过", "可是", "所以", "然后", "因为", "而且", "并且", "虽然",
        "于是", "反正", "另外", "再说", "况且", "不然", "否则",
        "真是", "简直", "算了", "还好", "难怪", "可惜", "居然", "竟然", "果然",
        "幸亏", "对了",
    )

    /** 片段短于这个长度就整段不动喵：短句本来就不需要逗号喵。 */
    const val MIN_RUN_LENGTH = 10

    /** 两刀之间至少隔这么多字喵。 */
    const val MIN_GAP = 6

    /**
     * 给整段文本补逗号喵。
     *
     * 先按现有标点切成一个个"无标点片段"，只对片段内部动手，
     * 再把原有的标点原样接回去——这样**已有的标点绝不会被挪动或吃掉**喵。
     */
    fun repair(text: String): String {
        if (text.isEmpty()) return text
        val out = StringBuilder(text.length + 8)
        val run = StringBuilder()
        text.forEach { c ->
            if (c in PUNCTUATION) {
                if (run.isNotEmpty()) {
                    out.append(repairRun(run.toString()))
                    run.setLength(0)
                }
                out.append(c)
            } else {
                run.append(c)
            }
        }
        if (run.isNotEmpty()) out.append(repairRun(run.toString()))
        return out.toString()
    }

    /** 处理一个无标点片段喵。 */
    private fun repairRun(run: String): String {
        if (run.length < MIN_RUN_LENGTH) return run
        val out = StringBuilder(run.length + 4)
        var lastCut = 0
        var i = 0
        while (i < run.length) {
            // 先把当前字写出去，再判断"能不能在它后面下刀"喵。
            out.append(run[i])
            i++
            if (i - lastCut < MIN_GAP) continue
            // 片段末尾不补逗号喵。
            if (i >= run.length) break
            if (!isBoundaryBefore(run, i)) continue
            out.append('，')
            lastCut = i + 1
        }
        return out.toString()
    }

    /**
     * 第 `i` 个字**之前**是不是一个天然的停顿处喵。
     *
     * @param i 切点，取值 1..run.length-1（末尾已由调用方排除）喵
     */
    private fun isBoundaryBefore(run: String, i: Int): Boolean {
        // 语气助词收句：切点紧跟在它后面喵。
        if (PromptBuilder.isSoftBreakTail(run[i - 1])) return true
        // 从句起首词：切点就在它前面喵。
        return CLAUSE_STARTERS.any { run.startsWith(it, i) }
    }
}