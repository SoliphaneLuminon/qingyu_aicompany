package com.qingyu.app.ui.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import android.app.Application
import com.qingyu.app.data.MemoryCategory
import com.qingyu.app.data.MemoryItem
import com.qingyu.app.data.db.MemoryStats
import com.qingyu.app.ui.components.EmptyState
import com.qingyu.app.ui.vm.MemoryViewModel

/** 长期记忆：这里写下的东西会被注入到对话里，并且会随时间慢慢淡忘喵。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    personaId: Long,
    modifier: Modifier = Modifier,
) {
    val application = LocalContext.current.applicationContext as Application
    val viewModel: MemoryViewModel = viewModel(factory = MemoryViewModel.factory(application))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(personaId) { viewModel.load(personaId) }

    LaunchedEffect(state.toast) {
        state.toast?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearToast()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                title = {
                    Column {
                        Text("记忆")
                        Text(
                            text = state.personaName.ifBlank { "未选择角色" },
                            style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (state.extracting || state.tidying) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 16.dp)
                                .size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (personaId > 0L) {
                FloatingActionButton(onClick = { viewModel.openEditor(null) }) {
                    Icon(Icons.Outlined.Add, contentDescription = "添加记忆")
            }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            MemoryHint(
                canExtract = personaId > 0L && !state.extracting && !state.tidying,
                canTidy = personaId > 0L && !state.tidying && !state.extracting,
                busy = state.tidying,
                stats = state.stats,
                onExtract = viewModel::extractFromChat,
                onTidy = viewModel::tidy,
            )
            if (state.items.isEmpty()) {
                EmptyState(
                    title = "还没有记住任何事",
                    description = "记忆会作为事实注入到对话里，让角色越聊越懂你。可以手动添加，也可以让模型从最近的对话里自动整理。",
                    icon = "🧠",
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                MemoryGroups(
                    items = state.items,
                    onToggle = viewModel::toggleEnabled,
                    onEdit = viewModel::openEditor,
                    onDelete = viewModel::delete,
                    onTogglePermanent = viewModel::togglePermanent,
                    onRefresh = viewModel::refresh,
                )
            }
        }
    }

    if (state.editorOpen) {
        MemoryEditorDialog(
            category = state.draftCategory,
            content = state.draftContent,
            importance = state.draftImportance,
            subject = state.draftSubject,
            predicate = state.draftPredicate,
            obj = state.draftObject,
            context = state.draftContext,
            permanent = state.draftPermanent,
            isNew = state.editorTarget == null,
            onCategory = { viewModel.onDraftChange(category = it) },
            onContent = { viewModel.onDraftChange(content = it) },
            onImportance = { viewModel.onDraftChange(importance = it) },
            onSubject = { viewModel.onDraftChange(subject = it) },
            onPredicate = { viewModel.onDraftChange(predicate = it) },
            onObject = { viewModel.onDraftChange(obj = it) },
            onContext = { viewModel.onDraftChange(context = it) },
            onPermanent = { viewModel.onDraftChange(permanent = it) },
            onDismiss = viewModel::closeEditor,
            onConfirm = viewModel::saveDraft,
        )
    }
}

@Composable
private fun MemoryGroups(
    items: List<MemoryItem>,
    onToggle: (MemoryItem) -> Unit,
    onEdit: (MemoryItem) -> Unit,
    onDelete: (MemoryItem) -> Unit,
    onTogglePermanent: (MemoryItem) -> Unit,
    onRefresh: (MemoryItem) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MemoryCategory.entries.forEach { category ->
            val group = items.filter { it.category == category }
            if (group.isEmpty()) return@forEach
            item(key = "header-${category.name}") {
                Text(
                    text = "${category.label} · ${group.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                )
            }
            items(group, key = { it.id }) { item ->
                MemoryCard(
                    item = item,
                    onToggle = { onToggle(item) },
                    onEdit = { onEdit(item) },
                    onDelete = { onDelete(item) },
                    onTogglePermanent = { onTogglePermanent(item) },
                    onRefresh = { onRefresh(item) },
                )
            }
        }
        item { Spacer(Modifier.height(72.dp)) }
    }
}

@Composable
private fun MemoryHint(
    canExtract: Boolean,
    canTidy: Boolean,
    busy: Boolean,
    stats: MemoryStats,
    onExtract: () -> Unit,
    onTidy: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "让轻语自己记住",
                    style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "读一遍最近的对话，自动挑出值得长期记住的事。",
                    style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                )
                if (stats.total > 0) {
                    Spacer(Modifier.height(6.dp))
                    val percent = (stats.averageVividness * 100).toInt().coerceIn(0, 100)
                    Text(
                        text = "共 ${stats.total} 条 · 人设 ${stats.permanent} 条 · 平均鲜活度 $percent% · 累计想起 ${stats.totalRecalls} 次",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                // 「提炼」= 再记点新的；「整理记忆库」= 收拾旧的重复喵。
                // 两个都叫"整理"会让人分不清，所以这里用词刻意岔开喵。
                Button(onClick = onExtract, enabled = canExtract) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("提炼")
                }
                TextButton(onClick = onTidy, enabled = canTidy) {
                    Text(
                        text = if (busy) "整理中…" else "整理记忆库",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryCard(
    item: MemoryItem,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTogglePermanent: () -> Unit,
    onRefresh: () -> Unit,
) {
    val progress = item.vividness.coerceIn(0.0, 1.0).toFloat()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (item.enabled) {
                MaterialTheme.colorScheme.surfaceContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f)
            },
        ),
    ) {
        Column(modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.statement,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "重要度 ${item.importance}",
                            style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "·",
                            style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "想起 ${item.recallCount} 次",
                            style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (item.isPermanent) {
                            MemoryBadge("核心人设", MaterialTheme.colorScheme.primary)
                        }
                        if (!item.enabled) {
                            MemoryBadge("已停用", MaterialTheme.colorScheme.error)
                        }
                    }
                }
                Switch(checked = item.enabled, onCheckedChange = { onToggle() })
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "鲜活度 ${(item.vividness.coerceIn(0.0, 1.0) * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                VividnessBar(progress = progress, modifier = Modifier.weight(1f))
            }
            Row {
                IconButton(onClick = onTogglePermanent) {
                    Icon(
                        imageVector = if (item.isPermanent) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                        contentDescription = "设为核心人设",
                        modifier = Modifier.size(18.dp),
                        tint = if (item.isPermanent) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                IconButton(onClick = onRefresh) {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = "鲜活度补满",
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Outlined.Edit, contentDescription = "编辑", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "删除",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * 鲜活度进度条喵。
 *
 * 手写而不用 `LinearProgressIndicator`：那个重载的 `progress` 参数在喵
 * Material3 各版本间签名为 `Float` / `() -> Float` 变过喵。
 */
@Composable
private fun VividnessBar(progress: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(6.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress)
                .height(6.dp)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun MemoryBadge(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
        .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
private fun MemoryEditorDialog(
    category: MemoryCategory,
    content: String,
    importance: Int,
    subject: String,
    predicate: String,
    obj: String,
    context: String,
    permanent: Boolean,
    isNew: Boolean,
    onCategory: (MemoryCategory) -> Unit,
    onContent: (String) -> Unit,
    onImportance: (Int) -> Unit,
    onSubject: (String) -> Unit,
    onPredicate: (String) -> Unit,
    onObject: (String) -> Unit,
    onContext: (String) -> Unit,
    onPermanent: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val invalid = content.isBlank() &&
        (subject.isBlank() || predicate.isBlank() || obj.isBlank())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "添加记忆" else "编辑记忆") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MemoryCategory.entries.forEach { entry ->
                        Text(
                            text = entry.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (entry == category) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (entry == category) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainer
                                    },
                                )
                                .clickable { onCategory(entry) }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
                OutlinedTextField(
                    value = content,
                    onValueChange = onContent,
                    label = { Text("记住的事（一句话）") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = subject,
                        onValueChange = onSubject,
                        label = { Text("主语") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = predicate,
                        onValueChange = onPredicate,
                        label = { Text("关系") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = obj,
                        onValueChange = onObject,
                        label = { Text("客体") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = context,
                        onValueChange = onContext,
                        label = { Text("场景") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    text = "重要度 $importance",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                )
                Slider(
                    value = importance.toFloat(),
                    onValueChange = { onImportance(it.toInt().coerceIn(1, 10)) },
                    valueRange = 1f..10f,
                    steps = 8,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("核心人设", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "不衰减、不冷却、每轮都记得",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = permanent, onCheckedChange = onPermanent)
                }
                if (invalid) {
                    Text(
                        text = "至少写明内容，或把主语、关系、客体填完整。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !invalid) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
