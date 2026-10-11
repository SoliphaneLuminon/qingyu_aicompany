package com.qingyu.app.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CallEnd
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.qingyu.app.data.Persona
import com.qingyu.app.ui.components.PersonaAvatar

/**
 * 实时语音通话页喵。
 *
 * 工作方式是一轮一轮的半双工对话，而不是全双工流式音频喵：
 * **按住说话 → 松手转文字 → 发给角色 → 角色回复用语音念出来**，
 * 循环往复。这样做的理由是它完全复用了已有的三条链路
 * （录音、语音识别、语音合成），行为可预测、出错也有明确落点喵。
 *
 * 真正的全双工需要回声消除与实时音频流，那套东西对"和角色打电话"这个
 * 使用场景收益有限——人打电话本来也是一句一句轮着说的喵。
 */
@Composable
fun CallScreen(
    persona: Persona,
    /** 正在录音（用户按住时）喵。 */
    recording: Boolean,
    /** 正在等角色回应喵。 */
    thinking: Boolean,
    /** 角色正在说话喵。 */
    speaking: Boolean,
    /** 最近一句识别到的用户原话，用于确认"它听清了没有"喵。 */
    lastUserText: String,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    onHangUp: () -> Unit,
) {
    val status = when {
        recording -> "正在聆听…"
        thinking -> "对方正在想…"
        speaking -> "对方正在说…"
        else -> "按住下方按钮说话"
    }

    Surface(color = Color(0xFF14161C)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))

            // 头像在说话时轻微呼吸，给一个"它还在"的活气喵。
            val breathe = rememberInfiniteTransition(label = "callBreathe")
            val pulse by breathe.animateFloat(
                initialValue = 1f,
                targetValue = if (speaking) 1.06f else 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(900),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "callPulse",
            )
            Box(modifier = Modifier.scale(pulse)) {
                PersonaAvatar(persona = persona, size = 132.dp, emphasize = true)
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text = persona.name,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
            )

            // 用户刚说的话回显出来：语音识别偶尔会听错，
            // 看得见原话才能判断"对方答非所问"到底是识别问题还是模型问题喵。
            if (lastUserText.isNotBlank()) {
                Spacer(Modifier.height(24.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = "你说：$lastUserText",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // 录音时显示一圈跳动的波纹，明确"正在听"喵。
            if (recording) {
                Waveform(color = Color(0xFF7ED9A0))
                Spacer(Modifier.height(20.dp))
            }

            HoldToTalkCallButton(
                recording = recording,
                onHoldStart = onHoldStart,
                onHoldEnd = onHoldEnd,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = "按住说话，松手发送",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.5f),
            )

            Spacer(Modifier.height(28.dp))

            // 挂断：红色圆钮，位置与微信一致（底部居中）喵。
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE0574A)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.CallEnd,
                    contentDescription = "挂断",
                    tint = Color.White,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .pointerInput(Unit) { detectTapGestures(onTap = { onHangUp() }) }
                        .padding(18.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "挂断",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(36.dp))
        }
    }
}

/** 通话页的按住说话按钮：比聊天页那个更大，因为这是整页唯一的主操作喵。 */
@Composable
private fun HoldToTalkCallButton(
    recording: Boolean,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(92.dp)
            .clip(CircleShape)
            .background(
                if (recording) {
                    Brush.linearGradient(listOf(Color(0xFF7ED9A0), Color(0xFF4FB477)))
                } else {
                    Brush.linearGradient(listOf(Color(0xFF3A3F4B), Color(0xFF2A2E38)))
                },
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onHoldStart()
                        // 松手或被取消都该结束这一轮，两种情况都要收尾喵。
                        tryAwaitRelease()
                        onHoldEnd()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Mic,
            contentDescription = "按住说话",
            tint = if (recording) Color(0xFF10331F) else Color.White,
            modifier = Modifier.size(40.dp),
        )
    }
}

/** 三根高低跳动的竖条，表示正在拾音喵。 */
@Composable
private fun Waveform(color: Color) {
    val transition = rememberInfiniteTransition(label = "wave")
    val heights = (0..2).map { index ->
        transition.animateFloat(
            initialValue = 8f,
            targetValue = 30f,
            animationSpec = infiniteRepeatable(
                // 三根错开相位，看起来才像声波而不是整体闪烁喵。
                animation = tween(durationMillis = 420 + index * 130),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "wave$index",
        )
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(32.dp),
    ) {
        heights.forEach { height ->
            Box(
                modifier = Modifier
                    .size(width = 5.dp, height = height.value.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color),
            )
        }
    }
}
