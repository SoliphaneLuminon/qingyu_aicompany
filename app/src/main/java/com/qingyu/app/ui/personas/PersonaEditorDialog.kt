package com.qingyu.app.ui.personas

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import com.qingyu.app.data.Persona
import com.qingyu.app.ui.components.LabelWithHelp
import com.qingyu.app.ui.components.PersonaAvatar
import com.qingyu.app.ui.settings.HelpTexts

/**
 * 可选头像 emoji 喵。
 *
 * 与"从相册上传"并存而不是取代：emoji 零成本、不占空间，
 * 随手建个角色时比翻相册快得多；上传头像留给认真捏的角色喵。
 */
private val EMOJI_CHOICES = listOf(
    "🌙", "🌊", "🔥", "🌸", "🍃", "⭐", "🫧", "🐱", "🐰", "🦊",
    "☕", "🎧", "📚", "🕯️", "🌧️", "🧸", "🍀", "🎐",
)

/**
 * 角色编辑器喵。
 *
 * 用对话框而不是独立页面：角色卡字段虽多，但都是纯文本，一屏滚动就能填完喵，
 * 留在列表页上更符合"边看边改"的习惯喵。
 */
@Composable
fun PersonaEditorDialog(
    draft: Persona,
    isNew: Boolean,
    onChange: ((Persona) -> Persona) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    /** 点「从相册上传」时回调；由外壳去拉起系统相册与裁剪页喵。 */
    onPickAvatar: () -> Unit = {},
    /** 点「用 emoji」时清掉自定义头像喵。 */
    onClearAvatar: () -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "新建角色" else "编辑「${draft.name}」") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = { value -> onChange { it.copy(name = value) } },
                        label = { Text("名字") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    // 头像预览跟着选择变，轻轻弹一下，反馈更即时喵。
                    val avatarBump = remember { Animatable(1f) }
                    LaunchedEffect(draft.avatarEmoji, draft.avatarImage) {
                        avatarBump.snapTo(1.15f)
                        avatarBump.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 600f))
                    }
                    Box(
                        modifier = Modifier
                            .scale(avatarBump.value)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                    ) {
                        // 有自定义头像就显示它，否则退回 emoji 喵。
                        if (draft.avatarImage.isNotBlank()) {
                            PersonaAvatar(persona = draft, size = 34.dp, emphasize = true)
                        } else {
                            Text(
                                text = draft.avatarEmoji.ifBlank { "🌙" },
                                style = MaterialTheme.typography.titleLarge,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        text = "头像",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    // 上传与 emoji 两条路并存：emoji 快，上传像微信那样能捏出真头像喵。
                    TextButton(onClick = onPickAvatar) { Text("从相册上传") }
                    if (draft.avatarImage.isNotBlank()) {
                        TextButton(onClick = onClearAvatar) { Text("用 emoji") }
                    }
                }
                Spacer(Modifier.height(6.dp))
                // 两行 emoji 选择器喵
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    EMOJI_CHOICES.chunked(9).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { emoji ->
                                val selected = draft.avatarEmoji == emoji
                                val bg by animateColorAsState(
                                    targetValue = if (selected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainer
                                    },
                                    animationSpec = tween(160),
                                    label = "emojiBg",
                                )
                                val emojiScale by animateFloatAsState(
                                    targetValue = if (selected) 1.12f else 1f,
                                    animationSpec = spring(dampingRatio = 0.6f, stiffness = 800f),
                                    label = "emojiScale",
                                )
                                Box(
                                    modifier = Modifier
                                        .scale(emojiScale)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(bg)
                                        .clickable { onChange { it.copy(avatarEmoji = emoji) } }
                                        .padding(horizontal = 8.dp, vertical = 5.dp),
                                ) {
                                    Text(text = emoji)
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = draft.tagline,
                    onValueChange = { value -> onChange { it.copy(tagline = value) } },
                    label = { LabelWithHelp("一句话简介", HelpTexts.personaTagline) },
                    placeholder = { Text("温柔但有点小别扭的邻家女孩") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = draft.systemPrompt,
                    onValueChange = { value -> onChange { it.copy(systemPrompt = value) } },
                    label = { LabelWithHelp("人格设定", HelpTexts.personaSystemPrompt) },
                    supportingText = { Text("他是谁、和用户什么关系、说话什么风格、绝对不要做什么") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = draft.scenario,
                    onValueChange = { value -> onChange { it.copy(scenario = value) } },
                    label = { LabelWithHelp("当前情境（可留空）", HelpTexts.personaScenario) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = draft.greeting,
                    onValueChange = { value -> onChange { it.copy(greeting = value) } },
                    label = { LabelWithHelp("开场白（可留空）", HelpTexts.personaGreeting) },
                    supportingText = { Text("第一次打开这个角色时自动发出的第一条消息") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = draft.sampleDialogue,
                    onValueChange = { value -> onChange { it.copy(sampleDialogue = value) } },
                    label = { LabelWithHelp("说话示例（可留空）", HelpTexts.personaSampleDialogue) },
                    supportingText = { Text("格式：用户：…… / 角色：……") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = draft.modelOverride,
                        onValueChange = { value -> onChange { it.copy(modelOverride = value) } },
                        label = { LabelWithHelp("模型覆盖", HelpTexts.personaModelOverride) },
                        placeholder = { Text("留空用全局") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = draft.temperatureOverride?.toString().orEmpty(),
                    onValueChange = { value ->
                        val parsed = value.toDoubleOrNull()
                        onChange { it.copy(temperatureOverride = parsed) }
                    },
                    label = { LabelWithHelp("温度覆盖（可留空）", HelpTexts.personaTemperature) },
                    placeholder = { Text("0.0 ~ 2.0") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { Button(onClick = onSave) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
