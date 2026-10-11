package com.qingyu.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingyu.app.data.RedPacket

/** 红包的经典红金配色喵。 */
private val PacketRed = Color(0xFFE0574A)
private val PacketRedDeep = Color(0xFFC0392B)
private val PacketGold = Color(0xFFF5C46B)

/** 几个常用金额，点一下就不用键盘了喵。 */
private val QUICK_AMOUNTS = listOf(5.20, 8.88, 13.14, 52.0, 66.66, 99.99)

/**
 * 发红包弹窗喵。
 *
 * **这是纯虚拟的**：没有充值、没有支付、不接任何真实货币，金额由用户自己填喵。
 * 它做的事只有一件——把"我给你发了多少钱"作为一条结构化消息送进对话，
 * 让角色能像真的收到红包那样回应喵。所以这里既不需要登录也不需要余额喵。
 *
 * 金额上限 [RedPacket.MAX_AMOUNT] 不是防作弊（本来就虚拟），而是防手滑：
 * 输入 99999999 会让角色的反应失真得没法看喵。
 */
@Composable
fun RedPacketDialog(
    onDismiss: () -> Unit,
    onSend: (Double, String) -> Unit,
) {
    var amountText by remember { mutableStateOf("8.88") }
    var wish by remember { mutableStateOf("") }
    val parsed = amountText.toDoubleOrNull()
    val valid = parsed != null && parsed > 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Brush.verticalGradient(listOf(PacketRed, PacketRedDeep))),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("¥", color = PacketGold, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.size(10.dp))
                Text("发红包")
            }
        },
        text = {
            Column {
                Text(
                    text = "金额是虚拟的，不涉及任何真实支付。角色会当作真的收到红包来回应。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { input ->
                        // 只留数字与一个小数点，免得用户输入负号或字母喵。
                        val cleaned = input.filter { it.isDigit() || it == '.' }
                        if (cleaned.count { it == '.' } <= 1) amountText = cleaned
                    },
                    label = { Text("金额（元）") },
                    singleLine = true,
                    isError = amountText.isNotBlank() && !valid,
                    supportingText = {
                        if (amountText.isNotBlank() && !valid) {
                            Text("填一个大于 0 的数字")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                // 常用金额：省得每次都敲键盘喵。
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    QUICK_AMOUNTS.take(3).forEach { value ->
                        QuickAmount(value) { amountText = RedPacket.formatAmount(value) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    QUICK_AMOUNTS.drop(3).forEach { value ->
                        QuickAmount(value) { amountText = RedPacket.formatAmount(value) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = wish,
                    onValueChange = { if (it.length <= 30) wish = it },
                    label = { Text("留言（可留空）") },
                    placeholder = { Text("请你喝奶茶") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (valid) onSend(parsed!!, wish) },
                enabled = valid,
            ) { Text("塞进红包") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun QuickAmount(value: Double, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = RedPacket.formatAmount(value),
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 红包气泡：聊天里那张卡片喵。
 *
 * 做成卡片而不是普通文字，是因为红包在微信里就是一张能点的卡片——
 * 用户对它的预期是"看得见金额与留言"，而不是读到一句话喵。
 */
@Composable
fun RedPacketBubble(
    amount: Double,
    wish: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.verticalGradient(listOf(PacketRed, PacketRedDeep)))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(PacketGold),
                contentAlignment = Alignment.Center,
            ) {
                Text("¥", color = PacketRedDeep, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.size(8.dp))
            Text(
                text = "${RedPacket.formatAmount(amount)} 元",
                color = PacketGold,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = wish.ifBlank { "恭喜发财，大吉大利" },
            color = PacketGold.copy(alpha = 0.9f),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
