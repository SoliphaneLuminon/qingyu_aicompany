package com.qingyu.app.media

import android.content.Context
import android.graphics.Bitmap
import com.qingyu.app.core.TimeUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 角色头像图片的落盘与读取喵。
 *
 * 与 [ImageStore] 分开，是因为两者的生命周期完全不同喵：
 * 聊天图片属于某条消息，删消息时就该删图；头像属于**角色**，
 * 换头像时旧文件要删、删角色时头像也要跟着走，与消息毫无关系喵。
 * 混在一个目录里会让 `pruneOrphans` 把头像当孤儿误删喵。
 *
 * 存**文件名**而不是绝对路径：私有目录的绝对路径会随安装位置变化，
 * 备份恢复到另一台机器后就失效了；文件名稳定，取用时再拼目录喵。
 */
class AvatarStore(private val context: Context) {

    class Failure(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

    private val root: File get() = File(context.filesDir, "avatars").apply { mkdirs() }

    /**
     * 裁剪页产出的位图落盘，返回文件名喵。
     *
     * 裁剪页已经把图画成正方形并缩到 512，所以这里**不再缩放**——
     * 再压一次只会让头像更糊，而 512 的 PNG 本来就只有几百 KB 喵。
     */
    suspend fun save(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        val name = "avatar_${TimeUtil.now()}_${(0..9999).random()}.png"
        val file = File(root, name)
        try {
            file.outputStream().use { out ->
                // PNG 而不是 JPEG：头像常有透明边缘，压成 JPEG 会变成黑边喵。
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (t: Throwable) {
            runCatching { file.delete() }
            throw Failure("保存头像失败：${t.message}", t)
        }
        name
    }

    /** 按文件名取文件；文件不存在返回 null喵。 */
    fun fileOf(name: String): File? {
        if (name.isBlank()) return null
        // 只认文件名，挡掉 ".." 之类的路径穿越喵。
        if (name.contains('/') || name.contains('\\') || name.contains("..")) return null
        return File(root, name).takeIf { it.isFile }
    }

    fun exists(name: String): Boolean = fileOf(name) != null

    /** 删除某个头像文件；传空串是空操作喵。 */
    fun delete(name: String) {
        if (name.isBlank()) return
        runCatching { fileOf(name)?.delete() }
    }

    /**
     * 清掉没有任何角色引用的头像文件喵。
     *
     * [referenced] 是当前所有角色的 avatar_image 集合喵。
     */
    suspend fun pruneOrphans(referenced: Set<String>): Int = withContext(Dispatchers.IO) {
        var removed = 0
        root.listFiles()?.forEach { file ->
            if (file.isFile && file.name !in referenced) {
                if (runCatching { file.delete() }.getOrDefault(false)) removed++
            }
        }
        removed
    }
}
