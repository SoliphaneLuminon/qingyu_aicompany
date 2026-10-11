package com.qingyu.app.ui.contacts

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.sp
import com.qingyu.app.core.TimeUtil
import com.qingyu.app.data.Conversation
import com.qingyu.app.data.Persona
import com.qingyu.app.ui.components.PersonaAvatar

/**
 * 角色资料页（联系人详情）喵。
 *
 * 从通讯录点进来、或从聊天信息页点头像进来，落在**同一个页面**喵。
 * 结构照微信的联系人资料页：上面是名片（大头像 + 名字 + 简介），
 * 下面是可点的功能行，最后是删除喵。
 *
 * 它同时承担"这个角色聊过什么"的概览：有对话时显示条数与最后时间喵。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaDetailScreen(
    persona: Persona,
    conversation: Conversation?,
    onBack: () -> Unit,
    onOpenChat: () -> Unit,
    onEdit: () -> Unit,
    onOpenMemory: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                title = { Text("联系人") },
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
            Spacer(Modifier.height(8.dp))
            // ------------------------------------------------ 名片
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PersonaAvatar(persona = persona, size = 64.dp, emphasize = true)
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = persona.name,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = persona.tagline.ifBlank { "还没有写简介" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onOpenChat,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.Chat, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("发消息")
            }

            Spacer(Modifier.height(20.dp))

            // ------------------------------------------------ 对话概览
            DetailLabel("这段对话")
            if (conversation == null || conversation.messageCount == 0) {
                DetailValue("还没有聊过")
            } else {
                DetailValue(
                    "${conversation.messageCount} 条消息 · 最近 ${TimeUtil.relative(conversation.updatedAt)}",
                )
                if (conversation.hidden) {
                    // 从主界面移除过的角色，这里要点明——否则用户会疑惑"怎么主界面没有它"喵。
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "已从主界面移除；发一条消息就会回到主界面。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // ------------------------------------------------ 功能行
            ActionRow(
                icon = { Icon(Icons.Outlined.Edit, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                label = "编辑角色资料",
                onClick = onEdit,
            )
            ActionRow(
                icon = { Icon(Icons.Outlined.Psychology, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                label = "这个角色的记忆",
                onClick = onOpenMemory,
            )

            Spacer(Modifier.height(24.dp))

            // ------------------------------------------------ 危险操作
            OutlinedButton(
                onClick = onDelete,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            ) {
                Icon(
                    Icons.Outlined.Delete,
                    null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(8.dp))
                Text("删除联系人", color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "会连同这段对话、聊天记录与它记住的事一起删除，无法恢复。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun DetailLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun DetailValue(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = 20.dp),
    )
}

/** 一行可点的功能项：左边图标，右边文字，与微信的功能行一致喵。 */
@Composable
private fun ActionRow(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
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
        label = "actionRowBg",
    )
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(background)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.Center) { icon() }
            Spacer(Modifier.width(14.dp))
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 56.dp),
            thickness = 0.6.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        )
    }
}
