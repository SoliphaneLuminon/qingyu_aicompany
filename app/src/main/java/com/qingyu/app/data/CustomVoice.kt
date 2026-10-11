package com.qingyu.app.data

/**
 * 一个从外部导入的音色喵。
 *
 * 有些服务商支持音色克隆：先上传一段参考音频，它返回一个音色 URI喵，
 * 之后合成时把这个 URI 当作 `voice` 参数传下去就行喵。这条链路不需要新的接口，
 * 所以这里只存"要用哪个音色"，不存"它是怎么克隆出来的"喵。
 */
data class CustomVoice(
    val name: String,
    /** 服务商给的音色标识，直接作为 `/v1/audio/speech` 的 voice 传下去喵。 */
    val uri: String,
    /**
     * 这个音色配套的合成模型，留空则用设置里的语音模型喵。
     *
     * 音色是绑在具体模型上的：`FunAudioLLM/CosyVoice2-0.5B` 克隆出来的音色喵，
     * 换别的模型多半不认。所以它跟着音色一起存，而不是让用户在两处各填一次、还容易填不一致喵。
     */
    val model: String = "",
    /** 克隆时用的参考文本，仅作记录，不参与合成请求喵。 */
    val referenceText: String = "",
) {
    /** 有没有自带模型喵。 */
    val hasModel: Boolean get() = model.isNotBlank()
}

/**
 * 把服务商给的一段音色信息解析成 [CustomVoice]喵。
 *
 * 各家控制台给你的东西长得都差不多，复制下来大概是这样喵：
 * ```
 * 音色名称: wansu
 * URI: speech:wansu:daojl2tsssvc739t29kg:dndkmulxmkworqocghrx
 * 模型: FunAudioLLM/CosyVoice2-0.5B
 * 参考文本: 我哪有你说的那么好啊
 * ```
 * 所以按"标签 + 值"逐行读，标签不认识就跳过；一个标签都不认识时，
 * 退化成"整段里挑出最像音色标识的那一行"喵。
 *
 * 纯函数，有单元测试钉住喵——它处理的正是"用户从别处复制过来的文本"，
 * 格式完全不受我们控制喵。
 */
object CustomVoiceParser {

    /** 解析结果里各个字段的目标位置喵。 */
    private enum class Field { NAME, URI, MODEL, REFERENCE }

    private fun labelOf(label: String): Field? = when (label.trim().lowercase().replace(" ", "")) {
        "名称", "音色名称", "音色名", "音色", "name", "voicename" -> Field.NAME
        "uri", "音色uri", "voiceuri", "url", "地址", "链接" -> Field.URI
        "模型", "模型名", "模型名称", "model" -> Field.MODEL
        "参考文本", "参考音频文本", "参考文本内容", "文本", "参考", "reference", "referencetext", "reftext", "prompt" -> Field.REFERENCE
        else -> null
    }

    /**
     * 从 [raw] 里解析出一个音色；连音色标识都没有就返回 null喵。
     *
     * 只有 URI 是必需的：名称可以从 URI 里推出来（`speech:wansu:…` → `wansu`喵），
     * 模型与参考文本都可以缺喵。
     */
    fun parse(raw: String): CustomVoice? {
        var name = ""
        var uri = ""
        var model = ""
        var reference = ""

        val lines = raw.lines().map { it.trim().trimStart('-', '*', '•', '#').trim() }.filter { it.isNotEmpty() }

        lines.forEach { line ->
            val colon = line.indexOfFirst { it == ':' || it == '：' }
            if (colon <= 0) return@forEach
            val value = line.substring(colon + 1).trim()
            if (value.isEmpty()) return@forEach
            when (labelOf(line.substring(0, colon))) {
                Field.NAME -> if (name.isEmpty()) name = value
                Field.URI -> if (uri.isEmpty()) uri = value
                Field.MODEL -> if (model.isEmpty()) model = value
                Field.REFERENCE -> if (reference.isEmpty()) reference = value
                null -> Unit
            }
        }

        // 一个标签都没对上：整段里挑最像标识与模型的两行喵。
        if (uri.isEmpty()) {
            uri = lines.firstOrNull { looksLikeUri(it) && !it.contains(' ') }.orEmpty()
        }
        if (model.isEmpty()) {
            model = lines.firstOrNull { it != uri && looksLikeModel(it) }.orEmpty()
        }
        if (uri.isBlank()) return null

        return CustomVoice(
            name = name.ifBlank { nameFromUri(uri) }.ifBlank { "自定义音色" },
            uri = uri,
            model = model,
            referenceText = reference,
        )
    }

    /**
     * 从音色标识里推出名称喵。
     *
     * `speech:wansu:daojl…` 里的第二段就是用户在控制台填的名字喵；
     * 认不出形态就返回空串，交给调用方决定兜底喵。
     */
    fun nameFromUri(uri: String): String {
        val scheme = uri.substringBefore(':').trim().lowercase()
        if (scheme != "speech" && scheme != "voice") return ""
        return uri.split(':').getOrNull(1)?.trim().orEmpty()
    }

    /** 像不像一条音色标识：带 scheme、没有空格、不是 http 地址喵。 */
    private fun looksLikeUri(value: String): Boolean {
        val v = value.trim()
        if (v.isEmpty() || v.contains(' ')) return false
        if (v.startsWith("http://") || v.startsWith("https://")) return false
        return v.startsWith("speech:") || v.startsWith("voice:") || v.count { it == ':' } >= 2
    }

    /** 像不像一个模型名：`组织/模型` 形态喵。 */
    private fun looksLikeModel(value: String): Boolean {
        val v = value.trim()
        if (v.isEmpty() || v.contains(' ') || v.contains(':')) return false
        return v.contains('/') && v.length > 3
    }
}
