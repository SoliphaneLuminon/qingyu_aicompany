package com.qingyu.app.core.memory

import com.qingyu.app.core.textOrEmpty
import com.qingyu.app.data.MemoryCategory
import com.qingyu.app.data.MemoryItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * 模型提炼出来、且**已通过强类型校验**的一条记忆喵。
 *
 * [statement] 是给人看的整句；三元组是给检索和将来做推理用的结构化形态喵。
 * 两者允许不同时存在——老格式只有 content，新格式可能只给三元组喵。
 */
data class ExtractedMemory(
    val statement: String,
    val subject: String = "",
    val predicate: String = "",
    /** 对应 JSON 里的 `object` 字段喵。 */
    val obj: String = "",
    val context: String = "",
    /** 情绪权重 E，0..1喵。 */
    val emotionalWeight: Double = 0.5,
    val category: MemoryCategory = MemoryCategory.PROFILE,
    /** 1..10。10=核心身份，1=琐碎细节喵。 */
    val importance: Int = 3,
) {

    /** 三元组是否已经完整喵。 */
    val hasTriple: Boolean get() = subject.isNotBlank() && predicate.isNotBlank() && obj.isNotBlank()

    fun toItem(personaId: Long): MemoryItem = MemoryItem(
        personaId = personaId,
        category = category,
        content = statement,
        importance = importance,
        subject = subject,
        predicate = predicate,
        obj = obj,
        context = context,
        emotionalWeight = emotionalWeight,
        vividness = MemoryDecay.initialVividness(),
        isUserEdited = false,
        isPermanent = false,
    )
}

/**
 * 提炼结果的强类型校验喵。
 *
 * 这条防线存在的理由：模型是"尽力"输出 JSON 的，偶尔会给出喵
 * `{"content":null}`、把 importance 写成 "5"（字符串）、或者干脆在 JSON 外面裹一段话喵。
 * 这些**一律丢弃**，而不是尝试修补后落库——宁可丢一条，也不污染数据库喵。
 *
 * 兼容两种形状喵：
 * - 数组：`[{"category":…,"content":…,"importance":…}, …]`（现有提示词在用喵）
 * - 对象：`{"has_memory":true,"memories":[...]}` 或 `{"has_memory":true,"subject":…,…}`喵
 */
object MemoryTripleValidator {

    /** 一次提炼最多接受几条，防止模型一口气吐出几十条喵。 */
    const val MAX_ITEMS = 8

    /**
     * 解析并返回**全部合法**的条目；任何一条不合法只影响它自己喵。
     *
     * @return 合法条目列表；整体无法解析时返回空列表喵。
     */
    fun parse(raw: String): List<ExtractedMemory> {
        val array = extractArray(raw) ?: return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<ExtractedMemory>(MAX_ITEMS)

        for (index in 0 until array.length()) {
            val element = array.optJSONObject(index) ?: continue
            val parsed = parseOne(element) ?: continue
            // 同一批里去重，避免模型重复输出同一件事喵。
            if (!seen.add(parsed.statement)) continue
            out.add(parsed)
            if (out.size >= MAX_ITEMS) break
        }
        return out
    }

    /** 单条校验。返回值非空即表示这条可以直接落库喵。 */
    fun parseOne(element: JSONObject): ExtractedMemory? {
        val subject = element.textOrEmpty("subject").trim()
        val predicate = element.textOrEmpty("predicate").trim()
        val obj = element.textOrEmpty("object").trim()
        val content = element.textOrEmpty("content").trim()

        val hasTriple = subject.isNotEmpty() && predicate.isNotEmpty() && obj.isNotEmpty()
        val statement = when {
            content.isNotEmpty() -> content
            hasTriple -> "$subject$predicate$obj"
            else -> return null
        }
        if (statement.isBlank()) return null
        // 太短的陈述通常太泛，达不到"独立可理解的具体事实"门槛喵。
        // 4 个字是最短的具体事实（"用户养猫""用户怕黑"），再短就基本是残句喵，
        // 与其污染记忆库不如丢弃喵。
        if (statement.length < MIN_STATEMENT_LENGTH) return null

        // 情绪权重：缺字段用 0.5；给了但解析不出数字 → 整条丢弃喵。
        val emotional = numeric(element, "emotional_weight")
        if (emotional == null && element.has("emotional_weight")) return null

        // 重要度：缺字段取 3；给了但非法 → 整条丢弃（宁缺毋滥喵）。
        val importance = integer(element, "importance")
        if (importance == null && element.has("importance")) return null
        if (importance != null && importance !in 1..10) return null

        // content 缺失时用三元组反过来补全，保证老字段永不为空喵。
        return ExtractedMemory(
            statement = statement,
            subject = subject,
            predicate = predicate,
            obj = obj,
            context = element.textOrEmpty("context").trim(),
            emotionalWeight = (emotional ?: DEFAULT_EMOTIONAL_WEIGHT).coerceIn(0.0, 1.0),
            category = MemoryCategory.fromName(element.textOrEmpty("category")),
            importance = importance ?: DEFAULT_IMPORTANCE,
        )
    }

    private const val MIN_STATEMENT_LENGTH = 4
    private const val DEFAULT_EMOTIONAL_WEIGHT = 0.5
    private const val DEFAULT_IMPORTANCE = 3

    /** 从"可能裹着散文的"回复里抠出 JSON 数组喵。 */
    private fun extractArray(raw: String): JSONArray? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        val direct = runCatching { JSONArray(text) }.getOrNull()
        if (direct != null) return direct

        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start >= 0 && end > start) {
            runCatching { JSONArray(text.substring(start, end + 1)) }.getOrNull()?.let { return it }
        }
        return extractFromObject(text)
    }

    /** 对象格式：`{"has_memory":true,"memories":[…]}`喵。 */
    private fun extractFromObject(text: String): JSONArray? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull() ?: return null

        val explicit = obj.optJSONArray("memories") ?: obj.optJSONArray("items")
        if (explicit != null) return explicit

        // 没有数组时，看看对象本身是不是一条记忆（has_memory 为真喵）。
        val hasMemory = if (obj.has("has_memory")) obj.optBoolean("has_memory", false) else true
        if (!hasMemory) return JSONArray()
        val single = parseOne(obj) ?: return JSONArray()
        return JSONArray().put(
            JSONObject()
                .put("subject", single.subject)
                .put("predicate", single.predicate)
                .put("object", single.obj)
                .put("context", single.context)
                .put("content", single.statement)
                .put("emotional_weight", single.emotionalWeight)
                .put("category", single.category.name)
                .put("importance", single.importance),
        )
    }

    /** 取浮点：数字或纯数字字符串都可以，其余返回 null喵。 */
    private fun numeric(element: JSONObject, key: String): Double? {
        if (element.isNull(key)) return null
        return when (val value = element.opt(key)) {
            is Number -> value.toDouble().takeIf { !it.isNaN() && !it.isInfinite() }
            is String -> value.trim().toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }
            else -> null
        }
    }

    /** 取整数：必须是整数值或全数字字符串，其余返回 null喵。 */
    private fun integer(element: JSONObject, key: String): Int? {
        if (element.isNull(key)) return null
        return when (val value = element.opt(key)) {
            is Number -> value.toDouble()
                .takeIf { !it.isNaN() && !it.isInfinite() && it % 1.0 == 0.0 }
                ?.toInt()

            is String -> value.trim().toIntOrNull()
            else -> null
        }
    }
}
