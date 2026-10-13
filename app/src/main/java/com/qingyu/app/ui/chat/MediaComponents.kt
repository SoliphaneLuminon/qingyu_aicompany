package com.qingyu.app.ui.chat

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.qingyu.app.data.Attachment
import com.qingyu.app.data.SearchHit
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.qingyu.app.ui.theme.FixedColors
import com.qingyu.app.ui.theme.Radius
import com.qingyu.app.ui.theme.Alpha
import com.qingyu.app.ui.components.pressableImage

/**
 * 在**后台线程**解码一张本地图；还没解好时返回 null，调用方先画占位喵。
 *
 * 原来两处图都是在 composition 里直接 `BitmapFactory.decodeFile` 喵。
 * `remember` 只保证"同一张图不重复解"，**第一次那一解仍然发生在主线程上**——
 * 点进一段带图的对话时，可视区每张图都要在主线程上读文件、采样、缩放，
 * 几十毫秒乘以张数，掉帧就是这么来的喵。
 *
 * 放到 IO 线程之后，主线程只负责把已经解好的位图画上去喵。
 * 结果依然是 remember 住的（produceState 按 key 缓存），滚动来回不会重复解码喵。
 */
@Composable
private fun rememberDecodedBitmap(
    path: String,
    key: Any?,
    full: Boolean,
): ImageBitmap? {
    val decoded by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = key,
        key2 = path,
        key3 = full,
    ) {
        value = withContext(Dispatchers.IO) {
            if (full) decodeFull(path) else decodeSampled(path)
        }
    }
    return decoded
}

/**
 * 图片缩略图喵。
 *
 * 刻意不用图片加载库（Coil 之类）：这些图**全都已经在本地文件里**喵，
 * 没有网络、没有缓存策略、没有生命周期问题，`BitmapFactory` 解一次就够了喵。
 * 为此多背一个依赖不划算喵。
 *
 * 解码会按目标宽度采样，避免把 2048 长边的原图整张读进内存喵。
 */
@Composable
fun AttachmentThumbnail(
    attachment: Attachment,
    modifier: Modifier = Modifier,
) {
    val bitmap = rememberDecodedBitmap(attachment.localPath, attachment.id, full = false)
    if (bitmap == null) {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Text("图片", style = MaterialTheme.typography.labelSmall)
        }
        return
    }
    Image(
        bitmap = bitmap,
        contentDescription = attachment.description.ifBlank { "图片" },
        modifier = modifier,
        contentScale = ContentScale.Crop,
    )
}

/** 消息里的图片条：最多一行，点开看大图喵。 */
@Composable
fun MessageImages(
    attachments: List<Attachment>,
    onOpen: (Attachment) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        attachments.take(3).forEach { attachment ->
            Box {
                AttachmentThumbnail(
                    attachment = attachment,
                    modifier = Modifier
                        .size(120.dp)
                        .clip(Radius.small)
                        .pressableImage { onOpen(attachment) },
                )
            }
        }
        if (attachments.size > 3) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(Radius.small)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Text("+${attachments.size - 3}", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** 全屏看图。点任意处关闭喵。 */
@Composable
fun ImageViewerDialog(attachment: Attachment, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(FixedColors.photoBackdrop)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = rememberDecodedBitmap(attachment.localPath, attachment.id, full = true)
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = attachment.description.ifBlank { "图片" },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text("图片读取失败", color = MaterialTheme.colorScheme.inverseOnSurface)
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(20.dp),
            ) {
                if (attachment.description.isNotBlank()) {
                    Text(
                        text = attachment.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = Alpha.muted),
                        modifier = Modifier
                            .clip(Radius.small)
                            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = Alpha.muted))
                            .padding(12.dp),
                    )
                }
            }

            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "关闭",
                tint = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(20.dp)
                    .size(24.dp),
            )
        }
    }
}

/**
 * 联网来源喵。
 *
 * 折叠成一行「已联网 · N 条来源」，点开才展开——资料来源是给用户核对的喵，
 * 平时不该占聊天版面喵。
 */
@Composable
fun SearchSources(
    hits: List<SearchHit>,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
) {
    if (hits.isEmpty()) return
    var expanded = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(initiallyExpanded) }

    Column(
        modifier = modifier
            .widthIn(max = 300.dp)
            .clip(Radius.small)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable { expanded.value = !expanded.value }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "🌐", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.width(6.dp))
            Text(
                text = "已联网 · ${hits.size} 条来源",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded.value) {
            hits.forEachIndexed { index, hit ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "${index + 1}. ${hit.title.ifBlank { hit.url }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                )
                Text(
                    text = hit.url,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = Alpha.muted),
                    maxLines = 1,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 解码工具

private fun decodeSampled(path: String, targetWidth: Int = 320): ImageBitmap? {
    val file = File(path)
    if (!file.exists()) return null
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(path, options)?.asImageBitmap()
    }.getOrNull()
}

/** 看大图时限制在 1600 长边，够清晰又不至于 OOM喵。 */
private fun decodeFull(path: String, maxEdge: Int = 1600): ImageBitmap? {
    val file = File(path)
    if (!file.exists()) return null
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(path, options)?.asImageBitmap()
    }.getOrNull()
}
