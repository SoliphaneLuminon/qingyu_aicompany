package com.qingyu.app.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * `org.json` 的安全取值喵。
 *
 * 存在的理由是：**`JSONObject.optString(key)` 在字段存在且值为 JSON `null`喵
 * 时返回字符串 `"null"`，而不是空串**。上游报文里的 `{"content":null}` 因此会变成正文喵
 * "null"，界面上就是满屏的 null喵。
 *
 * 凡是"取一段文本"的地方都应当走这里喵：
 * - 字段缺失 → `""`喵
 * - 字段是 JSON null → `""`喵
 * - 字段是被字面化的 `"null"` / `"NULL"` → `""`（上游把 null 写成字符串的情况喵）
 */
fun JSONObject.textOrEmpty(key: String): String {
    if (!has(key) || isNull(key)) return ""
    val value = optString(key, "")
    return if (value.equals("null", ignoreCase = true)) "" else value
}

/** 同上，但把"没有内容"折叠成 null，方便配合 `?:` 使用喵。 */
fun JSONObject.textOrNull(key: String): String? = textOrEmpty(key).takeIf { it.isNotEmpty() }

/** 数组里按位取字符串，null 元素算空串喵。 */
fun JSONArray.textOrEmpty(index: Int): String {
    if (index < 0 || index >= length() || isNull(index)) return ""
    val value = optString(index, "")
    return if (value.equals("null", ignoreCase = true)) "" else value
}
