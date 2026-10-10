package com.qingyu.app.core.memory

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 倒排索引里的一条：`memory_terms` 表的一行喵。
 */
data class MemoryTermRow(val memoryId: Long, val term: String, val weight: Double)

/**
 * 一份"记住的事"的可检索形态喵。
 */
data class MemoryDocument(val id: Long, val terms: Map<String, Double>)

/**
 * 常用的中日韩统一表意文字区间；够覆盖简体中文的日常用词喵。
 *
 * 写成十六进制而不是 `'一'.code`：`code` 是 getter 调用，不是常量表达式喵，
 * 用它初始化 `const val` 会报 "Const 'val' initializer should be a constant value"喵。
 */
private const val CJK_START = 0x4E00 // “一喵”
private const val CJK_END = 0x9FFF

/** 拉丁字母、数字与常见技术符号连成的词喵。 */
private val LATIN_TOKEN = Regex("[a-z0-9_+#.]{2,}")

/**
 * 本地词法检索 —— 双库里的"第二个库"喵。
 *
 * 不引入向量库：Android 端塞不下嵌入模型，也不该为了记一句话就发一次网络请求喵，
 * 所以这里用**纯本地的倒排索引 + 余弦相似度**喵：
 * - 中文没有空格，按"相邻二元组"切开（"喜欢喝美式" → 喜欢/欢喝/喝美/美式…喵），
 *   这比等分词库更轻，且对错别字、口语缩写的容忍度反而更高喵；
 * - 拉丁词与数字整体成 token，长度小于 2 的丢掉，避免把标点当信号喵；
 * - 权重用次线性词频 `1 + ln(tf)` 再做 L2 归一化，这样长记忆不会因为喵
 *   "写了三遍同一个词"就永远排第一喵。
 *
 * 索引与主库在同一个 SQLite 事务里写入（见 `MemoryStore`喵），
 * 也就是"双库强制同步"喵。
 */
object MemoryTextIndex {

    /**
     * 一条记忆最多索引多少个词喵。
     *
     * 截断是**存储侧**的优化，不是为了省计算——关键是它必须两条检索路径都走喵。
     * 否则"用持久化索引打分"和"用正文现算打分"会给出不同的结果喵，
     * 同样一段对话换个时机就想不起不同的事，这种 bug 极难复现喵。
     */
    const val MAX_INDEX_TERMS = 64

    fun tokenize(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val lower = text.lowercase()
        val tokens = ArrayList<String>(64)
        LATIN_TOKEN.findAll(lower).forEach { tokens.add(it.value) }

        // 中文按"连续的一段汉字"分别成组，避免跨标点造出无意义的二元组喵。
        val run = StringBuilder()
        for (ch in lower) {
            if (ch.isCjk()) {
                run.append(ch)
            } else {
                emitCjk(tokens, run)
                run.setLength(0)
            }
        }
        emitCjk(tokens, run)
        return tokens
    }

    /**
     * 把 token 列表变成 L2 归一化后的稀疏向量喵。
     *
     * 打分只用**具备区分度**的词：中文二元组、拉丁词、数字喵。
     * 单字（「了」「的」「是」「我」）几乎出现在任何句子里，一旦参与打分喵，
     * "用户养了一只橘猫"和"他今天喝咖啡了吗"就会仅凭共同的一个「了」拿到非零相似度喵，
     * 检索于是把八竿子打不着的记忆也捞出来喵。
     *
     * [tokenize] 依旧保留单字（调试与短词兜底都用得上），只是不计分喵；
     * 只有整段确实只剩单字时（例如查询就是一个字），才退回用单字，免得完全搜不到喵。
     */
    fun weigh(tokens: List<String>): Map<String, Double> {
        if (tokens.isEmpty()) return emptyMap()
        val discriminative = tokens.filter { it.length > 1 }
        val effective = discriminative.ifEmpty { tokens }
        val counts = HashMap<String, Double>()
        effective.forEach { token -> counts[token] = (counts[token] ?: 0.0) + 1.0 }

        val weights = HashMap<String, Double>(counts.size)
        var squaredSum = 0.0
        counts.forEach { (term, count) ->
            val value = 1.0 + ln(count)
            weights[term] = value
            squaredSum += value * value
        }
        if (squaredSum <= 0.0) return weights

        val length = sqrt(squaredSum)
        // 建新 map 而不是原地改：迭代中改值虽然安全，但一旦将来有人往里加 key 就会炸喵。
        return weights.mapValues { (_, value) -> value / length }
    }

    /** 建一份文档；权重已归一化并裁到 [MAX_INDEX_TERMS] 个词喵。 */
    fun document(id: Long, text: String): MemoryDocument =
        MemoryDocument(id = id, terms = clip(weigh(tokenize(text))))

    /**
     * 只留权重最高的若干词喵。
     *
     * 丢掉的是长尾词（在一句话里只出现过一次、且归一化后权重很低喵），
     * 对排序的影响可以忽略；但长记忆的索引体积能压下来一大截喵。
     */
    private fun clip(weights: Map<String, Double>): Map<String, Double> {
        if (weights.size <= MAX_INDEX_TERMS) return weights
        return weights.entries
            .sortedByDescending { it.value }
            .take(MAX_INDEX_TERMS)
            .associate { it.key to it.value }
    }

    /** 从数据库里读回来的行拼成文档，一行一条记录时也不会丢喵。 */
    fun fromRows(rows: List<MemoryTermRow>): List<MemoryDocument> {
        val grouped = LinkedHashMap<Long, HashMap<String, Double>>()
        rows.forEach { row ->
            if (row.term.isBlank()) return@forEach
            grouped.getOrPut(row.memoryId) { HashMap() }[row.term] = row.weight
        }
        return grouped.map { (id, terms) -> MemoryDocument(id, terms) }
    }

    /** 稀疏余弦相似度，落在 0..1喵。 */
    fun similarity(query: Map<String, Double>, document: Map<String, Double>): Double {
        if (query.isEmpty() || document.isEmpty()) return 0.0
        val (small, large) = if (query.size <= document.size) query to document else document to query
        var dot = 0.0
        small.forEach { (term, weight) -> dot += weight * (large[term] ?: 0.0) }
        return dot.coerceIn(0.0, 1.0)
    }
}

/** 是否落在中日韩统一表意文字区间喵。 */
private fun Char.isCjk(): Boolean = code in CJK_START..CJK_END

private fun emitCjk(out: MutableList<String>, run: StringBuilder) {
    if (run.isEmpty()) return
    val text = run.toString()
    text.forEach { out.add(it.toString()) }
    for (i in 0 until text.length - 1) {
        out.add(text.substring(i, i + 2))
    }
}
