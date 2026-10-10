package com.qingyu.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import com.qingyu.app.core.TimeUtil
import com.qingyu.app.data.Attachment
import com.qingyu.app.data.AttachmentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 图片附件：落盘、缩放、方向校正、编码喵。
 *
 * 这几件事放在一起是因为它们共享同一组取舍喵：
 * - **落盘**：图片存进应用私有目录（`files/images`），数据库只记路径。卸载即清，也不污染相册喵。
 * - **方向校正**：手机竖拍的照片大多以旋转 90° 存储、靠 EXIF 标记方向。不校正的话喵，
 *   发给识图模型的图是躺着的，模型认人认物都会出错喵。
 * - **缩放**：发给模型前压到长边 [MAX_SEND_EDGE]。一张 4000×3000 的原图光采样就上万 token喵，
 *   既慢又贵；1280 长边约 1600 token，认东西足够了喵。
 * - **编码**：OpenAI 兼容接口要 data URL（`data:image/jpeg;base64,…`喵）。
 *
 * 注意这里用的是 `android.util.Base64`，不是 Java 8 的 `java.util.Base64`——后者在 minSdk 26 上不可用喵。
 */
class ImageStore(private val context: Context) {

    class Failure(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

    private val root: File get() = File(context.filesDir, "images").apply { mkdirs() }

    /** 存盘时的长边上限——比发送上限宽，保留一点将来重看的画质喵。 */
    private val maxStoreEdge = 2048

    /**
     * 把用户选中的图片存成附件喵。
     *
     * 顺序很关键：**先按 EXIF 校正方向，再缩放**。反过来会在旋转后重新引入形变喵。
     */
    suspend fun importFromUri(uri: Uri): Attachment = withContext(Dispatchers.IO) {
        val orientation = readOrientation(uri)
        val decoded = decodeScaled(uri) ?: throw Failure("这张图片读不出来，换一张试试")
        val upright = applyOrientation(decoded, orientation)
        val scaled = scaleDown(upright, maxStoreEdge)

        val width = scaled.width
        val height = scaled.height
        val file = File(root, "img_${TimeUtil.now()}_${(0..9999).random()}.jpg")
        val bytes = try {
            file.outputStream().use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, 88, out) }
            file.length()
        } catch (t: Throwable) {
            throw Failure("保存图片失败：${t.message}", t)
        } finally {
            // 只在不是同一个对象时回收，避免重复 recycle喵
            if (scaled != upright) scaled.recycle()
            if (upright != decoded) upright.recycle()
            decoded.recycle()
        }

        Attachment(
            kind = AttachmentKind.IMAGE,
            localPath = file.absolutePath,
            mimeType = "image/jpeg",
            width = width,
            height = height,
            sizeBytes = bytes,
            createdAt = TimeUtil.now(),
        )
    }

    /** 读出图片用于发送：缩放到 [MAX_SEND_EDGE] 后编码成 data URL喵。 */
    suspend fun toDataUrl(attachment: Attachment): String = withContext(Dispatchers.IO) {
        val file = File(attachment.localPath)
        if (!file.exists()) throw Failure("图片文件不见了：${file.name}")
        val decoded = decodeScaledFile(file) ?: throw Failure("图片解码失败")
        val scaled = scaleDown(decoded, MAX_SEND_EDGE)
        val bytes = try {
            ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
                out.toByteArray()
            }
        } finally {
            if (scaled != decoded) scaled.recycle()
            decoded.recycle()
        }
        "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /** 删除附件对应的文件喵。 */
    fun deleteFile(attachment: Attachment) {
        runCatching { File(attachment.localPath).delete() }
    }

    /** 清掉没有被任何记录引用的图片文件喵。 */
    suspend fun pruneOrphans(referencedPaths: Set<String>): Int = withContext(Dispatchers.IO) {
        var removed = 0
        root.listFiles()?.forEach { file ->
            if (file.isFile && file.absolutePath !in referencedPaths) {
                if (runCatching { file.delete() }.getOrDefault(false)) removed++
            }
        }
        removed
    }

    // ------------------------------------------------------------ 内部

    private fun readOrientation(uri: Uri): Int = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun decodeScaled(uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun decodeScaledFile(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    /** 按 EXIF 方向把图转正。已经正的就原样返回喵。 */
    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }

            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    /** 把长边压到上限以内喵。 */
    private fun scaleDown(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge || longest == 0) return bitmap
        val scale = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    /** 采样率取 2 的幂：尽量贴近目标尺寸但不小于它喵。 */
    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= maxStoreEdge) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    companion object {
        /** 发给识图模型的图片长边上限喵。 */
        const val MAX_SEND_EDGE = 1280

        /** 供测试：按长边上限计算缩放后的尺寸喵。 */
        fun scaledSize(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
            val longest = maxOf(width, height)
            if (longest <= maxEdge || longest == 0) return width to height
            val scale = maxEdge.toFloat() / longest
            return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
        }

        /** 供 UI 显示：把字节数变成 "1.2 MB"喵。 */
        fun readableSize(bytes: Long): String = when {
            bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
            bytes >= 1024L -> "${bytes / 1024} KB"
            else -> "$bytes B"
        }
    }
}
