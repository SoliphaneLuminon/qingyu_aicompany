package com.qingyu.app.ui.personas

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.qingyu.app.data.Persona

/** 可选头像：只用 emoji，省掉图床和裁剪，也更容易做出个性喵。 */
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
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                    ) {
                        Text(text = draft.avatarEmoji.ifBlank { "🌙" }, style = MaterialTheme.typography.titleLarge)
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text("头像", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                // 两行 emoji 选择器喵
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    EMOJI_CHOICES.chunked(9).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { emoji ->
                                val selected = draft.avatarEmoji == emoji
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (selected) {
                                                MaterialTheme.colorScheme.primaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.surfaceContainer
                                            },
                                        )
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
                    label = { Text("一句话简介") },
                    placeholder = { Text("温柔但有点小别扭的邻家女孩") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = draft.systemPrompt,
                    onValueChange = { value -> onChange { it.copy(systemPrompt = value) } },
                    label = { Text("人格设定") },
                    supportingText = { Text("他是谁、和用户什么关系、说话什么风格、绝对不要做什么") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = draft.scenario,
                    onValueChange = { value -> onChange { it.copy(scenario = value) } },
                    label = { Text("当前情境（可留空）") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = draft.greeting,
                    onValueChange = { value -> onChange { it.copy(greeting = value) } },
                    label = { Text("开场白（可留空）") },
                    supportingText = { Text("第一次打开这个角色时自动发出的第一条消息") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = draft.sampleDialogue,
                    onValueChange = { value -> onChange { it.copy(sampleDialogue = value) } },
                    label = { Text("说话示例（可留空）") },
                    supportingText = { Text("格式：用户：…… / 角色：……") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = draft.modelOverride,
                        onValueChange = { value -> onChange { it.copy(modelOverride = value) } },
                        label = { Text("模型覆盖") },
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
                    label = { Text("温度覆盖（可留空）") },
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
