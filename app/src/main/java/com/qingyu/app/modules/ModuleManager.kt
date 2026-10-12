package com.qingyu.app.modules

import android.content.Context
import com.qingyu.app.core.TimeUtil
import com.qingyu.app.core.textOrEmpty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 一个轻语模块包喵。
 *
 * 打包格式（一个 zip，后缀任意喵）：
 * ```
 * manifest.json      # {"id":"heart","name":"心动值","version":"1.0.0","entry":"index.js","description":"…"喵}
 * index.js           # 模块逻辑，可访问 qingyu.* 接口喵
 * ```
 */
data class ModuleManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val entry: String,
    val author: String = "",
    /** 模块索要的能力，仅作为提示展示给用户，实际调用由宿主决定是否放行喵。 */
    val permissions: List<String> = emptyList(),
    val installedAt: Long = 0L,
    val enabled: Boolean = true,
    val directory: String = "",
)

data class ModuleRunResult(val ok: Boolean, val output: String)

/**
 * 模块系统喵。
 *
 * 用内置的 JS 子集解释器执行模块包，并把执行环境收得很紧喵：
 * 没有网络、没有文件系统、只有宿主显式注入的几个函数喵，
 * 且单次执行有指令数与时间上限，避免一个写坏的插件把应用卡死或把数据带走喵。
 */
class ModuleManager(private val context: Context) {

    private val root: File get() = File(context.filesDir, "modules").apply { mkdirs() }
    private val registryFile: File get() = File(root, "modules.json")
    private val engine = SimpleJsEngine()

    /** 读取已安装模块清单喵。 */
    suspend fun list(): List<ModuleManifest> = withContext(Dispatchers.IO) {
        val array = runCatching { JSONArray(registryFile.readText(Charsets.UTF_8)) }.getOrNull()
            ?: return@withContext emptyList()
        (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            obj.toManifest()
        }
    }

    /** 从输入流安装或升级一个模块喵。 */
    suspend fun install(input: InputStream): ModuleManifest = withContext(Dispatchers.IO) {
        val staging = File(root, "staging_${TimeUtil.now()}").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            unzip(input, staging)
            val manifestFile = File(staging, "manifest.json")
            if (!manifestFile.exists()) error("压缩包里缺少 manifest.json")

            val json = JSONObject(manifestFile.readText(Charsets.UTF_8))
            // 一律走 textOrEmpty：manifest 里写成 null 的字段不能变成字面量 "null"喵
            val id = json.textOrEmpty("id").ifBlank { json.textOrEmpty("name") }
            if (id.isBlank()) error("manifest.json 里必须要有 id")
            val entry = json.textOrEmpty("entry").ifBlank { "index.js" }
            // **入口也要做路径校验**喵：unzip 挡住了压缩包里的 zip-slip，
            // 但 `entry` 是 manifest 里另写的一个字符串，可以写成
            // `../../../shared_prefs/qingyu_settings.xml` 去读应用私有文件喵。
            // 所以和 unzip 用同一套判据：解析成绝对路径后必须真的落在模块目录里，
            // 而且必须是个**文件**（目录会让后面 readText 直接抛异常）喵。
            val entryFile = File(staging, entry)
            val stagingPath = staging.canonicalPath
            if (!entryFile.canonicalPath.startsWith("$stagingPath${File.separator}")) {
                error("入口文件必须放在模块目录里：$entry")
            }
            if (!entryFile.isFile) error("找不到入口文件 $entry")

            // 目录名只允许字母数字与下划线，避免路径穿越喵
            val safeId = id.replace(Regex("[^A-Za-z0-9_\\-]"), "_")
            val target = File(root, safeId)
            if (target.exists()) target.deleteRecursively()
            if (!staging.renameTo(target)) {
                staging.copyRecursively(target, overwrite = true)
                staging.deleteRecursively()
            }

            val manifest = ModuleManifest(
                id = safeId,
                name = json.textOrEmpty("name").ifBlank { safeId },
                version = json.textOrEmpty("version").ifBlank { "1.0.0" },
                description = json.textOrEmpty("description"),
                entry = entry,
                author = json.textOrEmpty("author"),
                permissions = json.optJSONArray("permissions")?.let { array ->
                    (0 until array.length()).mapNotNull { index ->
                        array.textOrEmpty(index).takeIf { it.isNotEmpty() }
                    }
                }.orEmpty(),
                installedAt = TimeUtil.now(),
                enabled = true,
                directory = target.absolutePath,
            )
            upsert(manifest)
            manifest
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val list = list().map { if (it.id == id) it.copy(enabled = enabled) else it }
        writeRegistry(list)
    }

    /** 随应用内置的示例模块（assets/sample_modules 下的 zip喵）。 */
    suspend fun bundledSamples(): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            context.assets.list("sample_modules")?.filter { it.endsWith(".zip") }.orEmpty().sorted()
        }.getOrDefault(emptyList())
    }

    /** 安装一个内置示例。已经装过则跳过喵。 */
    suspend fun installBundled(fileName: String): ModuleManifest? = withContext(Dispatchers.IO) {
        val already = list().any { it.id == fileName.substringBeforeLast('.') }
        if (already) return@withContext null
        val stream = context.assets.open("sample_modules/$fileName")
        install(stream)
    }

    suspend fun uninstall(id: String) = withContext(Dispatchers.IO) {
        val list = list()
        list.firstOrNull { it.id == id }?.let { manifest ->
            runCatching { File(manifest.directory).deleteRecursively() }
        }
        writeRegistry(list.filterNot { it.id == id })
    }

    /**
     * 运行模块入口喵。
     *
     * 注入的宿主接口是刻意做小的：只有 [ModuleHost] 暴露的那几个喵，
     * 模块拿不到 Context，也就拿不到文件与网络喵。
     */
    suspend fun run(manifest: ModuleManifest, host: ModuleHost, input: String = ""): ModuleRunResult =
        withContext(Dispatchers.IO) {
            val entryFile = File(manifest.directory, manifest.entry)
            // **isFile 而不是 exists** 喵：`exists` 对目录也返回 true，
            // 于是一个把入口写成目录的恶意模块会让 readText 抛异常——
            // 而这条异常没有任何一层接住，会直接崩掉整个应用喵。
            if (!entryFile.isFile) return@withContext ModuleRunResult(false, "入口文件不见了")
            val code = entryFile.readText(Charsets.UTF_8)
            val console = StringBuilder()
            val bridge = JsBridge(host, console)
            val result = engine.execute(
                code = code,
                bridge = bridge,
                input = input,
                timeoutMs = 2_000,
                maxSteps = 400_000,
            )
            ModuleRunResult(
                ok = result.error == null,
                output = buildString {
                    if (console.isNotEmpty()) append(console)
                    result.error?.let { append("运行出错：").append(it) }
                    result.returnValue?.let {
                        if (isNotEmpty()) append('\n')
                        append(it)
                    }
                }.trim(),
            )
        }

    private suspend fun upsert(manifest: ModuleManifest) {
        val list = list().filterNot { it.id == manifest.id } + manifest
        writeRegistry(list)
    }

    private fun writeRegistry(list: List<ModuleManifest>) {
        val array = JSONArray()
        list.forEach { manifest ->
            array.put(
                JSONObject().apply {
                    put("id", manifest.id)
                    put("name", manifest.name)
                    put("version", manifest.version)
                    put("description", manifest.description)
                    put("entry", manifest.entry)
                    put("author", manifest.author)
                    put("permissions", JSONArray(manifest.permissions))
                    put("installedAt", manifest.installedAt)
                    put("enabled", manifest.enabled)
                    put("directory", manifest.directory)
                },
            )
        }
        registryFile.writeText(array.toString(2), Charsets.UTF_8)
    }

    private fun JSONObject.toManifest() = ModuleManifest(
        id = textOrEmpty("id"),
        name = textOrEmpty("name"),
        version = textOrEmpty("version"),
        description = textOrEmpty("description"),
        entry = textOrEmpty("entry").ifBlank { "index.js" },
        author = textOrEmpty("author"),
        permissions = optJSONArray("permissions")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                array.textOrEmpty(index).takeIf { it.isNotEmpty() }
            }
        }.orEmpty(),
        installedAt = optLong("installedAt"),
        enabled = optBoolean("enabled", true),
        directory = textOrEmpty("directory"),
    )

    /**
     * 解包喵。三层防护缺一不可喵：
     * 1. **条目路径必须落在目标目录内**（防 zip-slip）；
     * 2. **条目数上限** [MAX_ENTRIES]；
     * 3. **解压后的实际字节上限**：单条 [MAX_ENTRY_BYTES]、整包 [MAX_TOTAL_BYTES]喵。
     *
     * 第 3 条是补上的喵：原先只限了条目数，于是"几百个高度可压缩条目"能解出上 GB
     * 数据、把应用私有目录塞满——而内部存储是整机共享的，会连累别的应用喵。
     *
     * 判据用**实际读出的字节**，不看 `entry.size`喵：那是压缩包头里攻击者可以随便填的
     * 声明值，正是 zip bomb 用来骗人的字段喵。
     */
    private fun unzip(input: InputStream, target: File) {
        val targetPath = target.canonicalPath
        var total = 0L
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            var count = 0
            while (entry != null) {
                if (++count > MAX_ENTRIES) error("压缩包条目过多")
                val name = entry.name.replace('\\', '/')
                if (name.contains("..")) error("压缩包里有非法路径：$name")
                val outFile = File(target, name)
                val outPath = outFile.canonicalPath
                if (outPath != targetPath && !outPath.startsWith("$targetPath${File.separator}")) {
                    error("压缩包里有非法路径：$name")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { out ->
                        val buffer = ByteArray(8 * 1024)
                        var written = 0L
                        while (true) {
                            val read = zip.read(buffer)
                            if (read <= 0) break
                            written += read
                            total += read
                            if (written > MAX_ENTRY_BYTES) error("压缩包里有个条目过大：$name")
                            if (total > MAX_TOTAL_BYTES) error("压缩包解开后过大")
                            out.write(buffer, 0, read)
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private companion object {
        /** 压缩包条目数上限喵。 */
        const val MAX_ENTRIES = 512

        /** 单个条目解压后的上限喵。 */
        const val MAX_ENTRY_BYTES = 8L * 1024 * 1024

        /** 整个压缩包解压后的总上限喵。 */
        const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
    }
}

/**
 * 模块能看到的全部宿主能力喵。
 *
 * 只有这几个方法，而且是白名单式的：没有反射、没有 Context、没有任意文件读写喵。
 */
interface ModuleHost {
    /** 返回当前角色名喵。 */
    fun personaName(): String

    /** 返回当前会话最近 [count] 条消息的预览文本喵。 */
    fun recentMessages(count: Int): String

    /** 往聊天里追加一条由模块生成的系统风格消息喵。 */
    fun pushMessage(text: String)

    /** 简单键值存储，只对当前模块可见喵。 */
    fun getValue(key: String): String

    fun setValue(key: String, value: String)

    /** 当前时间戳（毫秒喵）。 */
    fun now(): Long = TimeUtil.now()
}

/** JS 引擎与宿主之间的桥：把 qingyu.* 的调用翻译成 ModuleHost 的方法喵。 */
class JsBridge(private val host: ModuleHost, private val console: StringBuilder) {

    fun log(message: String) {
        console.append(message).append('\n')
    }

    fun call(name: String, args: List<String>): String = when (name) {
        "personaName" -> host.personaName()
        "recentMessages" -> host.recentMessages(args.firstOrNull()?.toIntOrNull() ?: 10)
        "pushMessage" -> {
            host.pushMessage(args.firstOrNull().orEmpty())
            ""
        }

        "getValue" -> host.getValue(args.firstOrNull().orEmpty())
        "setValue" -> {
            host.setValue(args.getOrNull(0).orEmpty(), args.getOrNull(1).orEmpty())
            ""
        }

        "now" -> host.now().toString()
        else -> throw IllegalArgumentException("模块无权调用 qingyu.$name")
    }
}
