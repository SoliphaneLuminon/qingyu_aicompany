package com.qingyu.app.ui.personas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 整页用近黑而不是纯黑：纯黑在 OLED 上与系统栏糊成一片，看不出页面边界喵。
private val CROP_BACKGROUND = Color(0xFF111111)

// 遮罩只压暗取景框外侧，框内保持原图亮度，用户判断"留下哪一块"靠的就是框内那片像素喵。
private val CROP_MASK = Color(0x99000000)
private val CROP_FRAME = Color(0xF2FFFFFF)
private val CROP_GRID = Color(0x4DFFFFFF)

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 5f
private const val MAX_DECODE_EDGE = 2048
private const val OUTPUT_SIZE = 512

private val VIEWPORT_MARGIN = 20.dp
private val MIN_VIEWPORT = 120.dp
private val FRAME_STROKE = 1.dp
private val CORNER_STROKE = 3.dp
private val CORNER_LENGTH = 22.dp
private val GRID_STROKE = 0.8.dp

/**
 * 头像裁剪页，交互照微信来：正方形取景框固定在屏幕中央，图片在框下拖动与缩放喵。
 *
 * 为什么是"框不动、图动"而不是"框可缩放"喵。
 * 头像的输出比例恒为 1:1，把比例从交互里拿掉之后，用户只需要理解一件事喵：
 * 把想留下的部分挪进框里，心智负担比"先调框、再调图"低一档喵。
 *
 * 为什么缩放下限锁在 1x 而不是允许缩小喵。
 * 1x 在这里的定义就是"图片刚好完整盖住取景框"，再往下就必然露出底色喵，
 * 而露出底色的区域裁出来是黑边，属于用户不会想要的结果，所以直接从可交互范围里剔除喵。
 *
 * 解码策略与平移夹紧的取舍分别见 [decodeSampled] 与 [clampOffset] 喵。
 *
 * @param source 待裁剪图片的 URI，通常来自系统相册或拍照返回喵。
 * @param onCancel 用户放弃裁剪时回调喵。
 * @param onConfirm 裁剪完成时回调，回传 512x512 的位图，回收与落盘由调用方负责喵。
 * @param modifier 由调用方决定这一页在导航容器里的占位方式喵。
 */
@Composable
fun AvatarCropScreen(
    source: android.net.Uri,
    onCancel: () -> Unit,
    onConfirm: (android.graphics.Bitmap) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // 换图时整页回到初始态，否则上一张图的缩放与平移会被当成新图的初始变换喵。
    var decoded by remember(source) { mutableStateOf<Bitmap?>(null) }
    var loadFailed by remember(source) { mutableStateOf(false) }

    LaunchedEffect(source) {
        decoded = null
        loadFailed = false
        // 解码放在 IO 上：几十兆的原图解码要几百毫秒，放主线程就是一段可见的卡顿喵。
        val result = withContext(Dispatchers.IO) { decodeSampled(context, source, MAX_DECODE_EDGE) }
        decoded = result
        loadFailed = result == null
    }

    val image = decoded
    var scale by remember(image) { mutableFloatStateOf(MIN_SCALE) }
    var offset by remember(image) { mutableStateOf(Offset.Zero) }
    var saving by remember(image) { mutableStateOf(false) }

    // 取景框边长由下方布局算出，而"完成"按钮在顶栏、拿不到那个布局作用域喵，
    // 所以由取景框把边长回传到这里，裁剪时才有正确的映射基准喵。
    var viewportPx by remember(image) { mutableFloatStateOf(0f) }

    Surface(modifier = modifier.fillMaxSize(), color = CROP_BACKGROUND) {
        Column(Modifier.fillMaxSize()) {
            CropTopBar(
                confirmVisible = image != null,
                canConfirm = image != null && viewportPx > 0f && !saving,
                onCancelClick = onCancel,
                onConfirmClick = {
                    val target = image
                    if (target != null && viewportPx > 0f && !saving) {
                        saving = true
                        // 点下按钮的这一刻把变换拍成快照，之后手指再动也不影响这次裁剪的结果喵。
                        val totalScale = maxOf(
                            viewportPx / target.width,
                            viewportPx / target.height,
                        ) * scale
                        val snapshot = clampOffset(
                            offset = offset,
                            totalScale = totalScale,
                            imageWidth = target.width,
                            imageHeight = target.height,
                            viewportPx = viewportPx,
                        )
                        scope.launch {
                            // 512x512 的重采样同样是几十毫秒级，留在主线程会掉帧喵。
                            val cropped = withContext(Dispatchers.IO) {
                                cropToSquare(target, viewportPx, totalScale, snapshot)
                            }
                            onConfirm(cropped)
                            saving = false
                        }
                    }
                },
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                if (image != null) {
                    // 提前收敛成非空局部量，后续所有 lambda 里都不必再依赖智能转换喵。
                    val bitmap = image
                    BoxWithConstraints(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        // 取景框是正方形，边长由可用空间的短边决定，再留一点边距避免贴边喵。
                        val available = if (maxWidth < maxHeight) maxWidth else maxHeight
                        val side = (available - VIEWPORT_MARGIN * 2).coerceAtLeast(MIN_VIEWPORT)
                        val sidePx = with(density) { side.toPx() }

                        // 1x 的定义：恰好完整盖住取景框，所以取两个方向所需缩放的较大者喵。
                        val baseScale = maxOf(sidePx / bitmap.width, sidePx / bitmap.height)
                        val totalScale = baseScale * scale
                        // 渲染前再夹一次：窗口尺寸变化会让旧的 offset 落到合法区间之外喵。
                        val visibleOffset = clampOffset(
                            offset = offset,
                            totalScale = totalScale,
                            imageWidth = bitmap.width,
                            imageHeight = bitmap.height,
                            viewportPx = sidePx,
                        )
                        val drawnWidth = bitmap.width * baseScale
                        val drawnHeight = bitmap.height * baseScale
                        val preview = remember(bitmap) { bitmap.asImageBitmap() }

                        SideEffect { viewportPx = sidePx }

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(bitmap, sidePx) {
                                    // 平移量以取景框中心为原点，所以先把手指坐标换算成相对中心的向量喵。
                                    val centerX = size.width / 2f
                                    val centerY = size.height / 2f
                                    detectTransformGestures { centroid, pan, zoom, _ ->
                                        val nextScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                                        // 用夹紧后的实际倍率算枢轴，否则捏到上下限时图片会在指下滑动喵。
                                        val ratio = nextScale / scale
                                        // 基准取实际渲染用的夹紧值：窗口变窄后状态里的旧 offset 可能已越界喵，
                                        // 直接拿它当起点会让图片在第一次触摸时跳一下喵。
                                        val base = clampOffset(
                                            offset = offset,
                                            totalScale = baseScale * scale,
                                            imageWidth = bitmap.width,
                                            imageHeight = bitmap.height,
                                            viewportPx = sidePx,
                                        )
                                        val pivotX = centroid.x - centerX
                                        val pivotY = centroid.y - centerY
                                        val nextOffset = Offset(
                                            x = pivotX * (1f - ratio) + base.x * ratio + pan.x,
                                            y = pivotY * (1f - ratio) + base.y * ratio + pan.y,
                                        )
                                        scale = nextScale
                                        offset = clampOffset(
                                            offset = nextOffset,
                                            totalScale = baseScale * nextScale,
                                            imageWidth = bitmap.width,
                                            imageHeight = bitmap.height,
                                            viewportPx = sidePx,
                                        )
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(side)
                                    .clipToBounds(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Image(
                                    bitmap = preview,
                                    contentDescription = null,
                                    contentScale = ContentScale.FillBounds,
                                    modifier = Modifier
                                        // requiredSize 让图片能超出取景框，超出的部分交给外层裁剪喵。
                                        .requiredSize(
                                            width = with(density) { drawnWidth.toDp() },
                                            height = with(density) { drawnHeight.toDp() },
                                        )
                                        .graphicsLayer {
                                            // 缩放交给图层，避免每帧重新布局；平移量是未缩放的父坐标系像素喵。
                                            scaleX = scale
                                            scaleY = scale
                                            translationX = visibleOffset.x
                                            translationY = visibleOffset.y
                                        },
                                )
                            }
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                drawCropOverlay(sidePx)
                            }
                        }
                    }
                } else if (loadFailed) {
                    Text(
                        text = "图片加载失败，换一张试试",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                    )
                } else {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }
    }
}

/** 顶栏固定三件套：左取消、中标题、右完成，深色底配白字喵。 */
@Composable
private fun CropTopBar(
    confirmVisible: Boolean,
    canConfirm: Boolean,
    onCancelClick: () -> Unit,
    onConfirmClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // 本页是 edge-to-edge，系统栏不替我们避让；外层若已消费 inset，这里自然退化为 0 喵。
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        TextButton(
            onClick = onCancelClick,
            modifier = Modifier.align(Alignment.CenterStart),
            colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
        ) {
            Text("取消")
        }
        Text(
            text = "裁剪头像",
            modifier = Modifier.align(Alignment.Center),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
        if (confirmVisible) {
            TextButton(
                onClick = onConfirmClick,
                enabled = canConfirm,
                modifier = Modifier.align(Alignment.CenterEnd),
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
            ) {
                Text("完成")
            }
        }
    }
}

/**
 * 在取景框外侧铺遮罩，并给框本身描边、加角标与三分线喵。
 *
 * 遮罩分四块画而不是"整屏遮罩加框内挖洞"，四块矩形不需要离屏合成或混合模式喵，
 * 每帧的绘制成本恒定，捏合缩放时不会因为多一层合成而掉帧喵。
 *
 * 角标取 L 形而不是完整直角边框，是为了让边框在深色照片上依然能读出边界喵，
 * 又不会像粗框那样压住画面边缘的像素喵。
 */
private fun DrawScope.drawCropOverlay(viewportPx: Float) {
    val canvasSize = size
    val left = (canvasSize.width - viewportPx) / 2f
    val top = (canvasSize.height - viewportPx) / 2f
    val frame = Rect(left, top, left + viewportPx, top + viewportPx)

    drawRect(CROP_MASK, topLeft = Offset(0f, 0f), size = Size(canvasSize.width, frame.top))
    drawRect(
        CROP_MASK,
        topLeft = Offset(0f, frame.bottom),
        size = Size(canvasSize.width, canvasSize.height - frame.bottom),
    )
    drawRect(CROP_MASK, topLeft = Offset(0f, frame.top), size = Size(frame.left, frame.height))
    drawRect(
        CROP_MASK,
        topLeft = Offset(frame.right, frame.top),
        size = Size(canvasSize.width - frame.right, frame.height),
    )

    // 三分线只作构图参考，透明度压得很低，避免在明亮照片上抢走边框的注意力喵。
    val gridStroke = GRID_STROKE.toPx()
    for (step in 1..2) {
        val x = frame.left + frame.width * step / 3f
        val y = frame.top + frame.height * step / 3f
        drawLine(CROP_GRID, Offset(x, frame.top), Offset(x, frame.bottom), gridStroke)
        drawLine(CROP_GRID, Offset(frame.left, y), Offset(frame.right, y), gridStroke)
    }

    drawRect(
        color = CROP_FRAME,
        topLeft = frame.topLeft,
        size = frame.size,
        style = Stroke(width = FRAME_STROKE.toPx()),
    )

    val cornerLength = CORNER_LENGTH.toPx()
    val cornerStroke = CORNER_STROKE.toPx()
    // 角标统一由"起点 + 两条边各自的方向"生成，四个角共用一份几何逻辑喵。
    fun corner(originX: Float, originY: Float, dirX: Float, dirY: Float) {
        drawLine(
            color = CROP_FRAME,
            start = Offset(originX, originY),
            end = Offset(originX + cornerLength * dirX, originY),
            strokeWidth = cornerStroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = CROP_FRAME,
            start = Offset(originX, originY),
            end = Offset(originX, originY + cornerLength * dirY),
            strokeWidth = cornerStroke,
            cap = StrokeCap.Round,
        )
    }
    corner(frame.left, frame.top, 1f, 1f)
    corner(frame.right, frame.top, -1f, 1f)
    corner(frame.left, frame.bottom, 1f, -1f)
    corner(frame.right, frame.bottom, -1f, -1f)
}

/**
 * 只读头部尺寸拿到原图宽高，再把 inSampleSize 取成 2 的幂，让长边落在 [maxEdge] 以内才真正分配像素喵。
 *
 * 直接全尺寸解码在相机原图上会瞬间吃掉上百兆堆内存，而头像预览和 512 的输出都用不到那些像素喵。
 *
 * 采样步长必须是 2 的幂，BitmapFactory 对非 2 的幂会向下取整到最近的幂喵，
 * 写一个看似精确的步长只会得到与预期不符的尺寸，不如自己按幂递推、把结果算准喵。
 *
 * 打开输入流可能因为权限、文件被删、内容不是图片而抛异常，统一收敛成 null 喵，
 * 由调用方决定提示文案，解码这一层不承担 UI 语义喵。
 */
private fun decodeSampled(context: Context, uri: Uri, maxEdge: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxEdge) sample *= 2

    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    // 每次解码都重新开流：输入流被消费过后不可复用，读两遍比把整张图缓存在内存里更省喵。
    context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, options)
    }
}.getOrNull()

/**
 * 把当前变换映射回解码后的位图坐标，裁出取景框覆盖的正方形并缩放到 [output] 见方喵。
 *
 * 映射基准是图片中心：图片恒以取景框中心为原点摆放，[offset] 与 [totalScale] 都相对这个原点度量喵，
 * 于是"取景框左上角落在图片的哪个像素上"可以纯解析地解出来，不需要读任何 View 的全局坐标喵。
 *
 * 边界必须显式夹紧：手指的浮点误差、边长取整、极端宽高比都会让结果落到图片之外喵，
 * 而 createBitmap 越界会直接抛 IllegalArgumentException，属于用户操作能触发的崩溃喵。
 * 边长先向下取整，保证裁出的是可见区域的真子集，宁可少一个像素也不要越界喵。
 */
private fun cropToSquare(
    source: Bitmap,
    viewportPx: Float,
    totalScale: Float,
    offset: Offset,
    output: Int = OUTPUT_SIZE,
): Bitmap {
    val width = source.width
    val height = source.height
    val sidePx = (viewportPx / totalScale).toInt().coerceIn(1, minOf(width, height))
    val left = (width / 2f - (viewportPx / 2f + offset.x) / totalScale)
        .toInt()
        .coerceIn(0, width - sidePx)
    val top = (height / 2f - (viewportPx / 2f + offset.y) / totalScale)
        .toInt()
        .coerceIn(0, height - sidePx)
    val cropped = Bitmap.createBitmap(source, left, top, sidePx, sidePx)
    return Bitmap.createScaledBitmap(cropped, output, output, true)
}

/**
 * 把平移量夹进"图片仍然完整盖住取景框"的范围喵。
 *
 * 不夹的话用户能把图拖到一边，框里露出底色，裁出来就是一条黑边，而且没有任何办法救回来喵。
 *
 * 单轴可移动的距离恰好是"图片该轴长度减去取景框边长"的一半喵：
 * 平移以图片中心为原点向两侧对称展开，两侧各能移动的距离相同喵。
 * 图片在该轴上比取景框短时距离为 0，直接把该轴锁死，这也是 1x 下限所保证的形态喵。
 */
private fun clampOffset(
    offset: Offset,
    totalScale: Float,
    imageWidth: Int,
    imageHeight: Int,
    viewportPx: Float,
): Offset {
    val maxX = ((imageWidth * totalScale - viewportPx) / 2f).coerceAtLeast(0f)
    val maxY = ((imageHeight * totalScale - viewportPx) / 2f).coerceAtLeast(0f)
    return Offset(
        x = offset.x.coerceIn(-maxX, maxX),
        y = offset.y.coerceIn(-maxY, maxY),
    )
}
