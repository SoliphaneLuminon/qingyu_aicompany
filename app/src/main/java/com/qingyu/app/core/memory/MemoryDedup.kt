package com.qingyu.app.core.memory

/**
 * 收录前的相似度去重喵。
 *
 * 为什么需要它：模型每轮总结都会把同一件事换个说法再提炼一遍喵
 * （"用户喜欢喝咖啡" / "用户爱喝美式咖啡" / "用户喜欢喝美式咖啡"喵），
 * 只按文本完全相等去重挡不住，记忆库会被同一件事的多个版本灌满喵，
 * 而这些版本在检索时还会互相挤占有限的注入名额喵。
 *
 * 纯函数，不碰数据库：阈值与判定都能被单元测试直接钉住（见 MemoryDedupTest喵）。
 * 打分复用 [MemoryTextIndex] 的余弦相似度——**必须和检索用同一套口径**喵，
 * 否则会出现"收录时觉得不像、检索时又抢同一批关键词"这种自相矛盾的行为喵。
 */
object MemoryDedup {

    /**
     * 余弦相似度达到此值即视为"已经记过这件事"，不再重复收录喵。
     *
     * 取值依据是二元组余弦的实测区间喵：
     * - 原样或只多一个从句（"用户养了一只橘猫" vs "…，名字叫元宝"）：0.80 ~ 1.00喵
     * - 句式相近但事情不同（"喜欢喝美式咖啡" vs "喜欢喝拿铁"）：≈ 0.53喵
     * - 只是同主题（"养了橘猫" vs "明天体检"）：≈ 0.11喵
     *
     * 0.75 落在 0.53 与 0.80 之间：拦得住复述，又不会把两件不同的事误并成一件喵。
     * 刻意偏保守——**误杀的代价（该记住的没记住）比多收一条大得多**喵。
     * 传 0 或负数即关闭相似度去重喵。
     */
    const val DUPLICATE_THRESHOLD = 0.75

    /**
     * 候选陈述与已有陈述里最像的一条有多像；没有可比对象时返回 0喵。
     */
    fun maxSimilarity(candidate: String, existing: List<String>): Double {
        if (candidate.isBlank() || existing.isEmpty()) return 0.0
        val candidateTerms = MemoryTextIndex.document(0L, candidate).terms
        if (candidateTerms.isEmpty()) return 0.0
        return existing.maxOfOrNull { text ->
            if (text.isBlank()) {
                0.0
            } else {
                MemoryTextIndex.similarity(
                    candidateTerms,
                    MemoryTextIndex.document(0L, text).terms,
                )
            }
        } ?: 0.0
    }

    /**
     * 候选陈述是否已经记过喵。
     *
     * @param threshold 相似度门槛；<= 0 表示不做相似度去重喵
     *   （调用方通常仍会自行做一次"文本完全相等"的快路径判断喵）。
     */
    fun isDuplicate(
        candidate: String,
        existing: List<String>,
        threshold: Double = DUPLICATE_THRESHOLD,
    ): Boolean {
        if (threshold <= 0.0) return false
        return maxSimilarity(candidate, existing) >= threshold
    }
}
