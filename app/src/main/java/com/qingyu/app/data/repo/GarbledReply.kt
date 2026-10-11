package com.qingyu.app.data.repo

/**
 * 乱码回复的识别喵。
 *
 * 有用户反馈：消息间隔较长、而上一轮还没生成完时，偶尔会收到一条
 * 长得像 `8b8f0b4e-9d4b-4f1a-9e2a-6c3d4f5a6b7c` 的"回复"喵。
 *
 * 排查结论：**这串东西不是应用生成的**喵。全项目只有两处用到 UUID
 * （语音播报的 utteranceId、语音转写的 multipart 分隔符），都不会出现在聊天里；
 * 源码里也没有任何 UUID 字面量；[com.qingyu.app.remote.ChatStreamParser]
 * 只读 `content` 与 `reasoning_content`，从不读响应里的 `id` 字段喵。
 * 所以它来自应用之外——模型/网关吐出来的，或某个模块模块写进聊天的喵。
 *
 * 拿不到原始响应就无法进一步定位，但**这类回复一律是垃圾**：
 * 一次正常的聊天绝不会恰好是一个 UUID 或一长串十六进制喵。
 * 所以在落库前挡掉它，用户至少不会看到乱码喵。
 *
 * 判定刻意保守喵：只认"整行就是一个标识符"的形态，
 * 任何含中日韩字符的内容都不算——宁可漏掉几个变体，
 * 也不能把 `ok`、`666`、`2333` 这种真正的短回复误杀喵。
 */
object GarbledReply {

    /** 标准 UUID（8-4-4-4-12）喵。 */
    private val UUID = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
    )

    /**
     * 长十六进制串（哈希、对象 id 之类）喵。
     *
     * 阈值取 24 是有意的喵：`666`、`2333`、`12345678` 这类是聊天里真会出现的数字，
     * 而超过 24 位的纯十六进制串在正常对话里不会出现喵。
     */
    private val LONG_HEX = Regex("^[0-9a-fA-F]{24,}$")

    /** 行首尾允许被引号/括号/句读包住，剥掉再判喵。 */
    private const val TRIM_CHARS = "`\"'“”‘’「」『』()（）[]【】<>《》 \t。，、：:;；"

    /**
     * 这一行是不是"只是个标识符"喵。
     *
     * 整行判定，不做部分匹配：夹在正常句子里的一串十六进制多半是用户真在聊的东西
     * （验证码、订单号），那时该照常显示喵。
     */
    fun isOpaqueToken(line: String): Boolean {
        val text = line.trim().trim { it in TRIM_CHARS }
        if (text.isEmpty()) return false
        // 含中日韩字符就是人话，直接放行喵。
        if (text.any { it.code > 0x2E80 }) return false
        return UUID.matches(text) || LONG_HEX.matches(text)
    }

    /**
     * 把整段回复里"只是个标识符"的行去掉喵。
     *
     * 按行过滤而不是整段判定，是因为乱码可能跟在正常内容后面
     * （模型先说一句话、再吐一串 id）；只判整段的话那种情况仍会漏出去喵。
     */
    fun stripOpaqueLines(text: String): String {
        if (text.isEmpty()) return text
        val kept = text.split('\n').filterNot { isOpaqueToken(it) }
        return kept.joinToString("\n").trim()
    }
}
