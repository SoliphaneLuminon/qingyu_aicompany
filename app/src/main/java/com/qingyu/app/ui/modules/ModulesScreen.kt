package com.qingyu.app.ui.modules

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qingyu.app.modules.ModuleManifest
import com.qingyu.app.ui.components.EmptyState
import com.qingyu.app.ui.vm.ModulesViewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import com.qingyu.app.ui.theme.Radius

/**
 * 模块页喵。
 *
 * 模块是一个 zip：里面一个 manifest.json 加一个入口 JS。装进来之后可以在这里启用、运行、卸载喵。
 * 运行结果（console 输出）直接显示在卡片下方，方便写插件时调试喵。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModulesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val application = LocalContext.current.applicationContext as android.app.Application
    val viewModel: ModulesViewModel = viewModel(factory = ModulesViewModel.factory(application))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val picker = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) viewModel.install(uri) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                title = { Text("模块") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.installSamples() }, enabled = !state.busy) {
                        Text("装示例")
                    }
                    IconButton(
                        onClick = {
                            picker.launch(arrayOf("application/zip", "application/octet-stream", "*"))
                        },
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = "安装本地模块")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        if (state.modules.isEmpty()) {
            Column(modifier = Modifier.padding(innerPadding)) {
                EmptyState(
                    title = "还没有装模块",
                    description = "模块是一个 zip：manifest.json 描述它是什么，入口 JS 用 qingyu.* 接口做点事。" +
                        "例如读最近几条消息、算一个数值、往聊天里发一条消息。",
                    icon = "🧩",
                    modifier = Modifier.fillMaxSize(),
                    action = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Button(onClick = { viewModel.installSamples() }, enabled = !state.busy) {
                                Text("装上内置示例")
                            }
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = {
                                picker.launch(arrayOf("application/zip", "application/octet-stream", "*"))
                            }) {
                                Text("或选择自己的 zip")
                            }
                        }
                    },
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.modules, key = { it.id }) { manifest ->
                ModuleCard(
                    manifest = manifest,
                    running = state.runningId == manifest.id,
                    output = if (state.lastOutputId == manifest.id) state.lastOutput else "",
                    onToggle = { viewModel.toggle(manifest) },
                    onRun = { viewModel.run(manifest) },
                    onUninstall = { viewModel.uninstall(manifest) },
                )
            }
            item {
                Text(
                    text = "模块没有网络和文件权限，只能调用宿主注入的几个接口；" +
                        "单次运行有 2 秒与指令数上限，写坏了也不会卡住应用。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ModuleCard(
    manifest: ModuleManifest,
    running: Boolean,
    output: String,
    onToggle: () -> Unit,
    onRun: () -> Unit,
    onUninstall: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = Radius.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = manifest.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "v${manifest.version}${if (manifest.author.isBlank()) "" else " · ${manifest.author}"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = manifest.enabled, onCheckedChange = { onToggle() })
            }

            if (manifest.description.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = manifest.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (manifest.permissions.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "申请能力：${manifest.permissions.joinToString("、")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRun, enabled = manifest.enabled && !running) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(if (running) "运行中" else "运行")
                }
                TextButton(onClick = onUninstall) {
                    Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(4.dp))
                    Text("卸载", color = MaterialTheme.colorScheme.error)
                }
            }

            if (output.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = output,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(Radius.small)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(10.dp),
                )
            }
        }
    }
}
