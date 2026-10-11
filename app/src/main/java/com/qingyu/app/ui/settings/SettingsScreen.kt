package com.qingyu.app.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qingyu.app.AppContainer
import com.qingyu.app.core.memory.UtilityProvider
import com.qingyu.app.data.AppSettings
import com.qingyu.app.data.CustomVoice
import com.qingyu.app.data.CustomVoiceParser
import com.qingyu.app.data.PRESET_SPEECH_VOICES
import com.qingyu.app.data.SearchProvider
import com.qingyu.app.data.ThemeMode
import com.qingyu.app.data.TtsProvider
import com.qingyu.app.data.selectedCustomVoice
import com.qingyu.app.pet.PetService
import com.qingyu.app.ui.components.HelpContent
import com.qingyu.app.ui.components.HelpDot
import com.qingyu.app.ui.components.LabelWithHelp
import com.qingyu.app.ui.components.RowLabelWithHelp
import com.qingyu.app.ui.vm.DataViewModel
import com.qingyu.app.ui.vm.SettingsViewModel
import android.provider.Settings as AndroidSettings

/** Android 11 起不再需要这个权限，低版本必须检查喵。 */
private fun canDrawOverlays(context: android.content.Context): Boolean =
    android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M ||
        AndroidSettings.canDrawOverlays(context)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    modifier: Modifier = Modifier,
) {
    val application = androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application
    val context = androidx.compose.ui.platform.LocalContext.current
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(application))
    val dataViewModel: DataViewModel = viewModel(factory = DataViewModel.factory(application))
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val dataState by dataViewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // 从系统设置页回来后重新判断悬浮窗权限喵。
    var overlayGranted by remember { mutableStateOf(canDrawOverlays(context)) }
    val canOverlay = overlayGranted
    val overlayLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { overlayGranted = canDrawOverlays(context) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) dataViewModel.importChat(uri)
    }

    LaunchedEffect(state.toast) {
        state.toast?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearToast()
        }
    }

    LaunchedEffect(dataState.message) {
        dataState.message?.let {
            snackbarHostState.showSnackbar(it)
            dataViewModel.clearMessage()
        }
    }

    // 导入外部音色的弹窗开关喵。
    var showVoiceImport by remember { mutableStateOf(false) }
    if (showVoiceImport) {
        VoiceImportDialog(
            onDismiss = { showVoiceImport = false },
            onConfirm = { raw ->
                showVoiceImport = false
                viewModel.importCustomVoice(raw)
            },
        )
    }

    // 填好接口地址和 API Key 后自动拉取可用模型；被清空则收起列表喵。
    LaunchedEffect(settings.apiBaseUrl, settings.apiKey) {
        if (settings.apiBaseUrl.isNotBlank() && settings.apiKey.isNotBlank()) {
            viewModel.fetchModels(auto = true)
        } else {
            viewModel.clearModels()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                title = { Text("设置") },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            CollapsibleSettingsSection(title = "接口", initialExpanded = true, trailing = {
                if (state.testing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    TextButton(onClick = { viewModel.testConnection() }) { Text("测试") }
                }
            }) {
                SettingsCard {
                OutlinedTextField(
                    value = settings.apiBaseUrl,
                    onValueChange = { value -> viewModel.update { it.copy(apiBaseUrl = value) } },
                    label = { LabelWithHelp("接口地址", HelpTexts.apiBaseUrl) },
                    placeholder = { Text("https://api.deepseek.com/v1") },
                    supportingText = { Text("OpenAI 兼容端点。填到 /v1 即可，路径会自动补全。") },
                    leadingIcon = { Icon(Icons.Outlined.Cloud, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = settings.apiKey,
                    onValueChange = { value -> viewModel.update { it.copy(apiKey = value) } },
                    label = { LabelWithHelp("API Key", HelpTexts.apiKey) },
                    placeholder = { Text("sk-…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = settings.model,
                    onValueChange = { value -> viewModel.update { it.copy(model = value) } },
                    label = { LabelWithHelp("模型", HelpTexts.model) },
                    placeholder = { Text("deepseek-chat") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.fetchingModels) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("正在拉取可用模型…", style = MaterialTheme.typography.labelSmall)
                    } else {
                        TextButton(onClick = { viewModel.fetchModels() }) { Text("拉取可用模型") }
                    }
                }
                if (state.modelsError != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = state.modelsError.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (state.modelList.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("可用模型（点选即填入）：", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.modelList.forEach { model ->
                            SelectableChip(
                                text = model,
                                selected = settings.model == model,
                                onClick = { viewModel.update { it.copy(model = model) } },
                            )
                        }
                    }
                }
                if (state.testResult != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = state.testResult.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.testOk) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
                    }
            }

            CollapsibleSettingsSection(title = "对话", initialExpanded = false) {
                SettingsCard {
                SliderRow(
                    label = "温度",
                    valueText = "%.2f".format(settings.temperature),
                    value = settings.temperature.toFloat(),
                    range = 0f..2f,
                    onChange = { value -> viewModel.update { it.copy(temperature = value.toDouble()) } },
                    help = HelpTexts.temperature,
                )
                SliderRow(
                    label = "最大生成长度",
                    valueText = "${settings.maxTokens}",
                    value = settings.maxTokens.toFloat(),
                    range = 256f..8192f,
                    onChange = { value -> viewModel.update { it.copy(maxTokens = value.toInt()) } },
                    help = HelpTexts.maxTokens,
                )
                SliderRow(
                    label = "携带历史条数",
                    valueText = "${settings.historyLimit}",
                    value = settings.historyLimit.toFloat(),
                    range = 4f..100f,
                    onChange = { value -> viewModel.update { it.copy(historyLimit = value.toInt()) } },
                    help = HelpTexts.historyLimit,
                )
                SwitchRow(
                    label = "拆成多条短消息",
                    description = "模拟真人连发几条，而不是一次一大段",
                    checked = settings.burstReplies,
                    onChange = { value -> viewModel.update { it.copy(burstReplies = value) } },
                    help = HelpTexts.burstReplies,
                )
                SliderRow(
                    label = "打字机速度",
                    valueText = if (settings.typingSpeedMs == 0) "即时" else "${settings.typingSpeedMs} ms/字",
                    value = settings.typingSpeedMs.toFloat(),
                    range = 0f..500f,
                    onChange = { value -> viewModel.update { it.copy(typingSpeedMs = value.toInt()) } },
                    help = HelpTexts.typingSpeedMs,
                )
                SwitchRow(
                    label = "流式输出",
                    description = "边生成边显示",
                    checked = settings.streamResponse,
                    onChange = { value -> viewModel.update { it.copy(streamResponse = value) } },
                    help = HelpTexts.streamResponse,
                )
                SwitchRow(
                    label = "显示思考过程",
                    description = "展示模型的 reasoning 内容",
                    checked = settings.showReasoning,
                    onChange = { value -> viewModel.update { it.copy(showReasoning = value) } },
                    help = HelpTexts.showReasoning,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = settings.systemPromptSuffix,
                    onValueChange = { value -> viewModel.update { it.copy(systemPromptSuffix = value) } },
                    label = { LabelWithHelp("附加要求（追加到所有角色的设定后）", HelpTexts.systemPromptSuffix) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                }
            }

            CollapsibleSettingsSection(title = "记忆", initialExpanded = false, trailing = {
                Icon(Icons.Outlined.Psychology, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }) {
                SettingsCard {
                SwitchRow(
                    label = "注入长期记忆",
                    description = "按相关度与遗忘曲线挑选记忆带进上下文",
                    checked = settings.injectMemories,
                    onChange = { value -> viewModel.update { it.copy(injectMemories = value) } },
                    help = HelpTexts.injectMemories,
                )
                SwitchRow(
                    label = "遗忘曲线",
                    description = "鲜活度随时间衰减，太久不提就会彻底淡忘",
                    checked = settings.memoryDecayEnabled,
                    onChange = { value -> viewModel.update { it.copy(memoryDecayEnabled = value) } },
                    help = HelpTexts.memoryDecay,
                )
                SwitchRow(
                    label = "概率唤醒",
                    description = "相关记忆按概率浮上来，而不是每次都全说一遍",
                    checked = settings.memoryProbabilityEnabled,
                    onChange = { value -> viewModel.update { it.copy(memoryProbabilityEnabled = value) } },
                    help = HelpTexts.memoryProbability,
                )
                SwitchRow(
                    label = "时空与生物感知",
                    description = "把此刻的时段状态与距上次对话多久注入提示词",
                    checked = settings.memoryTemporalEnabled,
                    onChange = { value -> viewModel.update { it.copy(memoryTemporalEnabled = value) } },
                    help = HelpTexts.memoryTemporal,
                )
                SliderRow(
                    label = "唤醒冷却轮数",
                    valueText = "${settings.memoryCooldownTurns} 轮",
                    value = settings.memoryCooldownTurns.toFloat(),
                    range = 0f..50f,
                    onChange = { value -> viewModel.update { it.copy(memoryCooldownTurns = value.toInt()) } },
                    help = HelpTexts.memoryCooldownTurns,
                )
                SliderRow(
                    label = "每轮最多注入",
                    valueText = "${settings.memoryInjectLimit} 条",
                    value = settings.memoryInjectLimit.toFloat(),
                    range = 1f..20f,
                    onChange = { value -> viewModel.update { it.copy(memoryInjectLimit = value.toInt()) } },
                    help = HelpTexts.memoryInjectLimit,
                )
                SliderRow(
                    label = "最低重要度门槛",
                    valueText = if (settings.memoryMinImportance == 0) "不限" else "≥ ${settings.memoryMinImportance}",
                    value = settings.memoryMinImportance.toFloat(),
                    range = 0f..10f,
                    onChange = { value -> viewModel.update { it.copy(memoryMinImportance = value.toInt()) } },
                    help = HelpTexts.memoryMinImportance,
                )
                SwitchRow(
                    label = "Reranker 精排",
                    description = "唤醒后用后台小模型交叉比对，关掉即回退到多因子打分",
                    checked = settings.memoryRerankerEnabled,
                    onChange = { enabled -> viewModel.update { it.copy(memoryRerankerEnabled = enabled) } },
                    help = HelpTexts.memoryReranker,
                )
                    }
            }

            CollapsibleSettingsSection(title = "后台小模型", initialExpanded = false, trailing = {
                Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }) {
                SettingsCard {
                SwitchRow(
                    label = "自动提炼记忆",
                    description = "每轮结束后异步整理新记忆，失败静默忽略",
                    checked = settings.autoMemoryExtraction,
                    onChange = { value -> viewModel.update { it.copy(autoMemoryExtraction = value) } },
                    help = HelpTexts.autoMemoryExtraction,
                )
                SwitchRow(
                    label = "Prompt 润色",
                    description = "用小模型顺滑措辞，超过 ${settings.utilityTimeoutMs}ms 自动降级为原生提示词",
                    checked = settings.promptRefineEnabled,
                    onChange = { value -> viewModel.update { it.copy(promptRefineEnabled = value) } },
                    help = HelpTexts.promptRefine,
                )
                RowLabelWithHelp(label = "服务商", content = HelpTexts.utilityProvider)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UtilityProvider.entries.forEach { provider ->
                        SelectableChip(
                            text = provider.label,
                            selected = settings.utilityProvider == provider,
                            onClick = { viewModel.update { it.copy(utilityProvider = provider) } },
                        )
                    }
                }
                if (settings.utilityProvider != UtilityProvider.MAIN) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = settings.utilityBaseUrl,
                        onValueChange = { value -> viewModel.update { it.copy(utilityBaseUrl = value) } },
                        label = { LabelWithHelp("小模型接口地址", HelpTexts.utilityBaseUrl) },
                        placeholder = { Text(settings.utilityProvider.defaultBaseUrl) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = settings.utilityModel,
                        onValueChange = { value -> viewModel.update { it.copy(utilityModel = value) } },
                        label = { LabelWithHelp("小模型名称", HelpTexts.utilityModel) },
                        placeholder = { Text(settings.utilityProvider.defaultModel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (settings.utilityProvider.requiresKey) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = settings.utilityApiKey,
                            onValueChange = { value -> viewModel.update { it.copy(utilityApiKey = value) } },
                            label = { LabelWithHelp("小模型 API Key", HelpTexts.utilityApiKey) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                }
            }

            CollapsibleSettingsSection(title = "外观", initialExpanded = false, trailing = {
                Icon(Icons.Outlined.Palette, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }) {
                SettingsCard {
                RowLabelWithHelp(label = "主题", content = HelpTexts.themeMode)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        SelectableChip(
                            text = mode.label,
                            selected = settings.themeMode == mode,
                            onClick = { viewModel.update { it.copy(themeMode = mode) } },
                        )
                    }
                }
                }
            }

            CollapsibleSettingsSection(title = "识图", initialExpanded = false, trailing = {
                if (state.testingKind == "vision") {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    TextButton(onClick = { viewModel.testVision() }) { Text("测试") }
                }
            }) {
                SettingsCard {
                SwitchRow(
                    label = "主模型本身能看图",
                    description = "开启后图片直接随对话发出（多模态模型）；关闭则先用下面的识图模型转成文字",
                    checked = settings.visionMainModelSupportsImages,
                    onChange = { value -> viewModel.update { it.copy(visionMainModelSupportsImages = value) } },
                    help = HelpTexts.visionNative,
                )
                if (!settings.visionMainModelSupportsImages) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = settings.vision.baseUrl,
                        onValueChange = { value -> viewModel.update { it.copy(vision = it.vision.copy(baseUrl = value)) } },
                        label = { LabelWithHelp("识图接口地址", HelpTexts.visionBaseUrl) },
                        placeholder = { Text("https://api.siliconflow.cn/v1") },
                        supportingText = { Text("OpenAI 兼容端点，路径自动补 /v1/chat/completions") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = settings.vision.apiKey,
                        onValueChange = { value -> viewModel.update { it.copy(vision = it.vision.copy(apiKey = value)) } },
                        label = { LabelWithHelp("识图 API Key", HelpTexts.visionApiKey) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = settings.vision.model,
                        onValueChange = { value -> viewModel.update { it.copy(vision = it.vision.copy(model = value)) } },
                        label = { LabelWithHelp("识图模型", HelpTexts.visionModel) },
                        placeholder = { Text("Qwen/Qwen2.5-VL-32B-Instruct") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TestResultLine(text = state.visionTestResult, ok = state.visionTestOk)
                    }
            }

            CollapsibleSettingsSection(title = "语音", initialExpanded = false, trailing = {
                Icon(Icons.Outlined.RecordVoiceOver, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }) {
                SettingsCard {
                RowLabelWithHelp(label = "音色来源", content = HelpTexts.ttsProvider)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TtsProvider.entries.forEach { provider ->
                        SelectableChip(
                            text = provider.label,
                            selected = settings.ttsProvider == provider,
                            onClick = { viewModel.update { it.copy(ttsProvider = provider) } },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (settings.ttsProvider == TtsProvider.SYSTEM) {
                        "系统语音：零配置、完全离线，但音色比较机械。"
                    } else {
                        "AI 语音：走 OpenAI 兼容的 /v1/audio/speech，音色自然；失败会自动退回系统语音。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (settings.ttsProvider == TtsProvider.OPENAI) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = settings.speech.baseUrl,
                        onValueChange = { value -> viewModel.update { it.copy(speech = it.speech.copy(baseUrl = value)) } },
                        label = { LabelWithHelp("语音接口地址", HelpTexts.speechBaseUrl) },
                        placeholder = { Text("https://api.openai.com/v1") },
                        supportingText = { Text("也支持硅基流动、MiniMax 等 OpenAI 兼容实现") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = settings.speech.apiKey,
                        onValueChange = { value -> viewModel.update { it.copy(speech = it.speech.copy(apiKey = value)) } },
                        label = { LabelWithHelp("语音 API Key", HelpTexts.speechApiKey) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = settings.speech.model,
                        onValueChange = { value -> viewModel.update { it.copy(speech = it.speech.copy(model = value)) } },
                        label = { LabelWithHelp("语音模型", HelpTexts.speechModel) },
                        placeholder = { Text("tts-1") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    RowLabelWithHelp(label = "音色", content = HelpTexts.speechVoice)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PRESET_SPEECH_VOICES.take(6).forEach { voice ->
                            SelectableChip(
                                text = voice,
                                selected = settings.speechVoice == voice,
                                onClick = { viewModel.update { it.copy(speechVoice = voice) } },
                            )
                        }
                    }

                    // -------------------------------------------- 自定义音色（外部导入）
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("自定义音色", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.width(6.dp))
                        HelpDot(content = HelpTexts.customVoices, size = 15.dp)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { showVoiceImport = true }) { Text("导入音色") }
                    }
                    if (settings.customVoices.isEmpty()) {
                        Text(
                            text = "音色克隆服务会给你一段「音色名称 / URI / 模型 / 参考文本」，" +
                                "整段粘进来就能用；不同服务商的音色靠 URI 区分。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        settings.customVoices.forEach { voice ->
                            CustomVoiceRow(
                                voice = voice,
                                selected = settings.speechVoice == voice.uri,
                                onSelect = {
                                    viewModel.update {
                                        it.copy(speechVoice = voice.uri, ttsProvider = TtsProvider.OPENAI)
                                    }
                                },
                                onDelete = { viewModel.removeCustomVoice(voice.uri) },
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                        val active = settings.selectedCustomVoice()
                        if (active != null && active.hasModel) {
                            Text(
                                text = "当前音色自带模型：合成时用 ${active.model}，不用上面的语音模型。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (state.testingKind == "speech") {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("正在合成…", style = MaterialTheme.typography.labelSmall)
                        }
                    } else {
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { viewModel.testSpeech() }) { Text("测试语音合成") }
                    }
                    TestResultLine(text = state.speechTestResult, ok = state.speechTestOk)
                }

                Spacer(Modifier.height(12.dp))
                SwitchRow(
                    label = "启用朗读",
                    description = "开启后消息气泡上的朗读按钮才会出声",
                    checked = settings.ttsEnabled,
                    onChange = { value -> viewModel.update { it.copy(ttsEnabled = value) } },
                    help = HelpTexts.ttsEnabled,
                )
                SwitchRow(
                    label = "自动朗读回复",
                    checked = settings.autoSpeakReplies,
                    onChange = { value -> viewModel.update { it.copy(autoSpeakReplies = value) } },
                    help = HelpTexts.autoSpeakReplies,
                )
                SliderRow(
                    label = if (settings.ttsProvider == TtsProvider.OPENAI) "语速（传给语音接口）" else "语速",
                    valueText = "%.2f".format(settings.ttsRate),
                    value = settings.ttsRate,
                    range = 0.5f..2f,
                    onChange = { value -> viewModel.update { it.copy(ttsRate = value) } },
                    help = HelpTexts.ttsRate,
                )
                if (settings.ttsProvider == TtsProvider.SYSTEM) {
                    SliderRow(
                        label = "音调",
                        valueText = "%.2f".format(settings.ttsPitch),
                        value = settings.ttsPitch,
                        range = 0.5f..1.8f,
                        onChange = { value -> viewModel.update { it.copy(ttsPitch = value) } },
                        help = HelpTexts.ttsPitch,
                    )
                }
                    }
            }

            CollapsibleSettingsSection(title = "联网搜索", initialExpanded = false, trailing = {
                if (state.testingKind == "search") {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    TextButton(onClick = { viewModel.testSearch() }) { Text("测试") }
                }
            }) {
                SettingsCard {
                RowLabelWithHelp(label = "服务商", content = HelpTexts.searchProvider)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SearchProvider.entries.forEach { provider ->
                        SelectableChip(
                            text = provider.label,
                            selected = settings.searchProvider == provider,
                            onClick = { viewModel.update { it.copy(searchProvider = provider) } },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = settings.searchProvider.hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = when (settings.searchProvider) {
                        SearchProvider.BOCHA -> settings.searchKeyBocha
                        SearchProvider.TAVILY -> settings.searchKeyTavily
                    },
                    onValueChange = { value ->
                        viewModel.update {
                            when (it.searchProvider) {
                                SearchProvider.BOCHA -> it.copy(searchKeyBocha = value)
                                SearchProvider.TAVILY -> it.copy(searchKeyTavily = value)
                            }
                        }
                    },
                    label = { LabelWithHelp("${settings.searchProvider.label} API Key", HelpTexts.searchApiKey) },
                    supportingText = { Text("两家各存一份，切换服务商不用重填") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = settings.searchBaseUrlOverride,
                    onValueChange = { value -> viewModel.update { it.copy(searchBaseUrlOverride = value) } },
                    label = { LabelWithHelp("自定义地址（可留空）", HelpTexts.searchBaseUrlOverride) },
                    placeholder = { Text("留给自建网关或代理") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SliderRow(
                    label = "每次取回条数",
                    valueText = "${settings.searchResultCount}",
                    value = settings.searchResultCount.toFloat(),
                    range = 1f..10f,
                    onChange = { value -> viewModel.update { it.copy(searchResultCount = value.toInt()) } },
                    help = HelpTexts.searchResultCount,
                )
                SwitchRow(
                    label = "新对话默认开启联网",
                    description = "聊天输入框旁的联网按钮可随时单独切换",
                    checked = settings.searchDefaultOn,
                    onChange = { value -> viewModel.update { it.copy(searchDefaultOn = value) } },
                    help = HelpTexts.searchDefaultOn,
                )
                TestResultLine(text = state.searchTestResult, ok = state.searchTestOk)
                    }
            }

            CollapsibleSettingsSection(title = "桌宠与主动消息", initialExpanded = false, trailing = {
                Icon(Icons.Outlined.Pets, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }) {
                SettingsCard {
                SwitchRow(
                    label = "桌面悬浮宠物",
                    description = if (canOverlay) {
                        "一只可以拖动的小挂件，点它进入聊天，长按收起"
                    } else {
                        "需要先授予「显示在其他应用上层」权限"
                    },
                    checked = settings.petEnabled,
                    onChange = { value ->
                        viewModel.update { it.copy(petEnabled = value) }
                        if (value) {
                            if (canOverlay) {
                                PetService.start(context)
                            } else {
                                overlayLauncher.launch(
                                    android.content.Intent(
                                        AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        android.net.Uri.parse("package:${context.packageName}"),
                                    ),
                                )
                            }
                        } else {
                            PetService.stop(context)
                        }
                    },
                    help = HelpTexts.petEnabled,
                )
                if (!canOverlay && settings.petEnabled) {
                    Text(
                        text = "权限已授权后请再切换一次开关。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                SwitchRow(
                    label = "让角色主动找你",
                    description = "按设定间隔推送一条消息提醒",
                    checked = settings.proactiveMessages,
                    onChange = { value -> viewModel.update { it.copy(proactiveMessages = value) } },
                    help = HelpTexts.proactiveMessages,
                )
                SliderRow(
                    label = "间隔（分钟）",
                    valueText = "${settings.proactiveIntervalMinutes}",
                    value = settings.proactiveIntervalMinutes.toFloat(),
                    range = 15f..720f,
                    onChange = { value -> viewModel.update { it.copy(proactiveIntervalMinutes = value.toInt()) } },
                    help = HelpTexts.proactiveInterval,
                )
                    }
            }

            CollapsibleSettingsSection(title = "数据", initialExpanded = false, trailing = {
                Icon(Icons.Outlined.Save, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }) {
                SettingsCard {
                Text(
                    text = "聊天记录与收藏都会导出到「下载/轻语」目录，JSON 与 Markdown 都是通用格式；" +
                        "同类应用导出的聊天记录也能直接导入回来。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*")) },
                        enabled = !dataState.busy,
                    ) { Text("导入聊天记录") }
                    Button(
                        onClick = { dataViewModel.exportEverything() },
                        enabled = !dataState.busy,
                    ) { Text("导出全部") }
                }
                if (dataState.message != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = dataState.message.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                    }
            }

            CollapsibleSettingsSection(title = "关于", initialExpanded = false, trailing = {
                Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }) {
                SettingsCard {
                Text(text = "轻语 v${state.appVersion}", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "一个把「聊天」当作正经事来做的陪伴应用：本地存储、无账号、无追踪，接口由你自己提供。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "完全本地运行的 AI 陪伴应用，代码与界面均为独立实现。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * 设置卡片：一个分组的内容容器喵。
 *
 * 间距上做了一件事：外层 6dp、内层 16dp，让卡片之间"松"、卡片内部"紧"喵。
 * 之前外层 4dp、内层 14dp 时，相邻卡片的间隔和卡片内部的行距看起来差不多，
 * 扫一眼分不清"这是同一组还是两组"喵。
 */
@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) { content() }
    }
}

/**
 * 可折叠的设置分组：点整行切换展开/收起，收起时仍保留 [trailing喵]（如"测试"按钮）。
 *
 * 展开状态由 [initialExpanded] 决定，首次进入设置页时生效喵；
 * 用户手动切换后由内部 [remember] 状态接管，页面内切走再切回来仍保持喵。
 *
 * 箭头用旋转而不是换图标，配合展开动画一起看更连贯喵。
 */
@Composable
private fun CollapsibleSettingsSection(
    title: String,
    initialExpanded: Boolean,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(initialExpanded) }
    val scheme = MaterialTheme.colorScheme
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 700f),
        label = "sectionArrow",
    )
    // 展开时标题行底色略深，收起时透明：让"哪一段是打开的"一眼可辨喵。
    val headerTint by animateColorAsState(
        targetValue = if (expanded) scheme.primary.copy(alpha = 0.06f) else Color.Transparent,
        animationSpec = tween(durationMillis = 200),
        label = "sectionHeaderTint",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(headerTint)
            .clickable { expanded = !expanded }
            .padding(start = 10.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = if (expanded) "收起" else "展开",
            tint = scheme.onSurfaceVariant,
            modifier = Modifier
                .size(16.dp)
                .rotate(arrowRotation),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = scheme.primary,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f),
            expandFrom = Alignment.Top,
        ) + fadeIn(animationSpec = tween(140)),
        exit = shrinkVertically(
            animationSpec = spring(dampingRatio = 0.9f, stiffness = 600f),
            shrinkTowards = Alignment.Top,
        ) + fadeOut(animationSpec = tween(90)),
    ) {
        Column { content() }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    description: String? = null,
    help: HelpContent? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = label, style = MaterialTheme.typography.bodyMedium)
                if (help != null && !help.isEmpty) {
                    Spacer(Modifier.width(6.dp))
                    HelpDot(content = help, size = 15.dp)
                }
            }
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    help: HelpContent? = null,
    /** 步进；大于 0 时拖动会吸附到整数刻度，避免"温度 0.73"这种读起来费劲的值喵。 */
    steps: Int = 0,
) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            if (help != null && !help.isEmpty) {
                Spacer(Modifier.width(6.dp))
                HelpDot(content = help, size = 15.dp)
            }
            Spacer(Modifier.weight(1f))
            // 数值一变就轻微弹一下，让"我改的就是它"更明确喵。
            val bump = remember { Animatable(1f) }
            LaunchedEffect(valueText) {
                bump.snapTo(1.18f)
                bump.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 650f))
            }
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.scale(bump.value),
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
        )
    }
}

/** 测试结果一行：成功用主色，失败用错误色喵。 */
@Composable
private fun TestResultLine(text: String?, ok: Boolean) {
    if (text.isNullOrBlank()) return
    Spacer(Modifier.height(8.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
    )
}

/** OpenAI 兼容语音接口的常用音色见 [PRESET_SPEECH_VOICES]喵。 */

/**
 * 一个导入进来的外部音色：点一下选中，右边的垃圾桶删掉喵。
 *
 * 副标题优先显示该音色自带的模型，没有才显示"跟随语音模型"——
 * 用户最需要核对的就是"这条音色到底会走哪个模型"喵。
 */
@Composable
private fun CustomVoiceRow(
    voice: CustomVoice,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) scheme.primaryContainer else scheme.surfaceContainerHigh)
            .clickable(onClick = onSelect)
            .padding(start = 14.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = voice.name,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
            )
            Text(
                text = voice.model.ifBlank { "跟随语音模型" },
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Text(
                text = "使用中",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onPrimaryContainer,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, contentDescription = "删除音色", tint = scheme.onSurfaceVariant)
        }
    }
}

/**
 * 导入外部音色：整段粘贴，解析交给 [CustomVoiceParser]喵。
 *
 * 不做成四个输入框，是因为服务商那边本来就是"一整块信息"给你复制喵；
 * 让用户手动拆成四个字段既啰嗦、又容易漏掉模型那一行喵。
 */
@Composable
private fun VoiceImportDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var raw by remember { mutableStateOf("") }
    val preview = remember(raw) { CustomVoiceParser.parse(raw) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入外部音色") },
        text = {
            Column {
                OutlinedTextField(
                    value = raw,
                    onValueChange = { raw = it },
                    label = { Text("粘贴音色信息") },
                    placeholder = {
                        Text(
                            "音色名称: wansu\n" +
                                "URI: speech:wansu:xxxx:yyyy\n" +
                                "模型: FunAudioLLM/CosyVoice2-0.5B\n" +
                                "参考文本: 我哪有你说的那么好啊",
                        )
                    },
                    minLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = when {
                        raw.isBlank() -> "只给一条音色 URI 也能导入，名称会自动从 URI 里取。"
                        preview == null -> "还没找到音色标识，检查一下是否粘贴完整。"
                        else -> buildString {
                            append("将导入：")
                            append(preview.name)
                            if (preview.hasModel) {
                                append(" · ")
                                append(preview.model)
                            }
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (raw.isNotBlank() && preview == null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(raw) }, enabled = preview != null) { Text("导入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 设置页通用的可选芯片喵。
 *
 * 选中/取消用颜色过渡 + 轻微缩放，`animateColorAsState` 让"点中了哪个"更明确喵；
 * 文字颜色也跟着渐变，避免切换瞬间出现一层灰字压在绿底上喵。
 */
@Composable
private fun SelectableChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        targetValue = if (selected) scheme.primaryContainer else scheme.surfaceContainerHigh,
        animationSpec = tween(durationMillis = 180),
        label = "chipContainer",
    )
    val content by animateColorAsState(
        targetValue = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 180),
        label = "chipContent",
    )
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.96f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 700f),
        label = "chipScale",
    )
    Box(
        modifier = Modifier
            .scale(scale)
            .clip(RoundedCornerShape(50))
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = content,
        )
    }
}
