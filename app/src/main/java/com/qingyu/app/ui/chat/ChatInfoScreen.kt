package com.qingyu.app.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qingyu.app.core.TimeUtil
import com.qingyu.app.data.Conversation
import com.qingyu.app.data.Persona
import com.qingyu.app.ui.components.PersonaAvatar

/**
 * 聊天信息页喵。
 *
 * 从对话右上角的「⋯」进来，替代原来的下拉菜单喵——菜单项一多就该有独立页面，
 * 而且这里能顺手把角色头像放上去，点它进这个角色的资料页喵。
 *
 * 与角色资料页的分工喵：**这里管这段对话，那里管这个人**。
 * 所以「删除对话」在这里（只隐藏，角色与记录都留着），
 * 「删除联系人」在资料页里（会真的连记录一起清掉）喵。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatInfoScreen(
    persona: Persona,
    conversation: Conversation?,
    personaAvatar: @Composable () -> Unit = { PersonaAvatar(emoji = persona.avatarEmoji, size = 52.dp, emphasize = true) },
    onBack: () -> Unit,
    onOpenPersona: () -> Unit,
    onFavoriteConversation: () -> Unit,
    onOpenModules: () -> Unit,
    onOpenDebug: () -> Unit,
    onOpenMemory: () -> Unit,
    onHideChat: () -> Unit,
    onClearMessages: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                title = { Text("聊天信息") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            // ------------------------------------------------ 角色名片（点进资料页）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenPersona)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                personaAvatar()
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = persona.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = persona.tagline.ifBlank { "查看与管理这个角色" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))

            // ------------------------------------------------ 这段对话的概况
            if (conversation != null) {
                Text(
                    text = "这段对话 ${conversation.messageCount} 条消息 · 最近 ${TimeUtil.relative(conversation.updatedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(12.dp))
            }

            // ------------------------------------------------ 功能行
            InfoRow(
                icon = { Icon(Icons.Outlined.FavoriteBorder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                label = "收藏这段对话",
                onClick = onFavoriteConversation,
            )
            InfoRow(
                icon = { Icon(Icons.Outlined.Psychology, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                label = "这个角色的记忆",
                onClick = onOpenMemory,
            )
            InfoRow(
                icon = { Icon(Icons.Outlined.Extension, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                label = "模块",
                onClick = onOpenModules,
            )
            InfoRow(
                icon = { Icon(Icons.Outlined.Terminal, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                label = "上下文调试",
                onClick = onOpenDebug,
            )

            Spacer(Modifier.height(20.dp))

            // ------------------------------------------------ 移除（可恢复）
            InfoRow(
                icon = { Icon(Icons.Outlined.VisibilityOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                label = "删除对话",
                description = "只是从主界面移除，角色与聊天记录都会保留；发一条消息就会回来",
                onClick = onHideChat,
            )
            // ------------------------------------------------ 清空（不可恢复）
            InfoRow(
                icon = { Icon(Icons.Outlined.DeleteSweep, null, tint = MaterialTheme.colorScheme.error) },
                label = "清空聊天记录",
                labelColor = MaterialTheme.colorScheme.error,
                description = "真的删掉这段对话里的所有消息，无法恢复；角色会保留",
                onClick = onClearMessages,
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * 一行功能项喵。
 *
 * [description] 用来把「删除对话」与「清空聊天记录」的区别讲清楚——
 * 这两件事看着像，后果完全不同，必须在点之前就说明白喵。
 */
@Composable
private fun InfoRow(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
    description: String? = null,
    labelColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(
        targetValue = if (pressed) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surface
        },
        animationSpec = tween(120),
        label = "infoRowBg",
    )
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(background)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.Center) { icon() }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, style = MaterialTheme.typography.bodyLarge, color = labelColor)
                if (description != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 56.dp),
            thickness = 0.6.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        )
    }
}
