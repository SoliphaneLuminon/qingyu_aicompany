package com.qingyu.app.ui.components

import android.content.ClipData
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.coroutines.launch

/**
 * 返回一个"把文本放进剪贴板"的函数喵。
 *
 * Compose 1.10 起 [androidx.compose.ui.platform.LocalClipboardManager] 被弃用喵，
 * 新的 `Clipboard` 接口用的是挂起式的 `setClipEntry(ClipEntry)`喵，
 * 每次调用都要把字符串包成 `ClipData` 再包成 `ClipEntry`——所以在这里收一次喵，
 * 调用点只写 `copyText("...")`喵。
 */
@Composable
fun rememberCopyText(): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return remember(clipboard, scope) {
        { text: String ->
            scope.launch {
                val clipData = ClipData.newPlainText(CLIP_LABEL, text)
                clipboard.setClipEntry(ClipEntry(clipData))
            }
        }
    }
}

private const val CLIP_LABEL = "轻语"
