package com.qingyu.app.ui.debug

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.qingyu.app.AppContainer
import com.qingyu.app.core.TimeUtil
import com.qingyu.app.core.memory.RecallDecision
import com.qingyu.app.core.memory.RerankOutcome
import com.qingyu.app.data.AppSettings
import com.qingyu.app.data.MemoryItem
import com.qingyu.app.data.MessageRole
import com.qingyu.app.data.Persona
import com.qingyu.app.data.repo.ContextSnapshot
import com.qingyu.app.remote.PromptBuilder
import com.qingyu.app.ui.vm.ChatUiState
import com.qingyu.app.ui.theme.Radius

/**
 * 上下文自检页喵。
 *
 * 把"这次请求到底发了什么给模型"原样摊开：最终提示词、注入了哪些记忆、其余候选为什么没被想起来喵。
 * 调角色卡的时候，这一页比反复试聊天快得多喵。
 *
 * **数据来源分两种，页面上标注得很清楚**喵：
 * - 已经聊过至少一轮 → 直接用 [ChatUiState.lastContext] 里的留档。那是发请求时原样存下来的喵，
 *   含润色后的系统提示词、联网资料、挂好的图片，是真正发出去的那一份喵；
 * - 还没有请求（刚打开 App / 刚换会话）→ 退回重算一份预览，并明确标注"预览"喵。
 *
 * 之所以不干脆永远用重算：重算和真实请求必然有出入。此前这一页就是重算的喵，
 * 它看不到润色版提示词、看不到联网资料与图片，记忆也因为没推进轮次而可能选中另一批——喵
 * 对一个用来排查"模型为什么这么答"的页面来说，这是致命的喵。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(state: ChatUiState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val container = AppContainer.get(LocalContext.current)
    val settings = container.settings.current
    val persona = state.persona

    val snapshot = state.lastContext
    val actual = snapshot != null

    val conversationId = state.conversation?.id ?: 0L
    val lastUserText = state.messages
        .lastOrNull { it.role == MessageRole.USER && it.content.isNotBlank() }
        ?.content
        .orEmpty()

    // 只在没有留档时才重算。remember 依然无条件调用，靠 key 里的 snapshot 决定要不要干活喵，
    // 免得 snapshot 由 null 变非 null 时改变组合结构喵。
    val previewSnapshot = remember(
        snapshot,
        persona?.id,
        conversationId,
        lastUserText,
        state.messages.size,
        settings.historyLimit,
        settings.burstReplies,
        settings.systemPromptSuffix,
        settings.injectMemories,
        settings.memoryInjectLimit,
        settings.memoryCooldownTurns,
        settings.memoryDecayEnabled,
        settings.memoryProbabilityEnabled,
        settings.memoryTemporalEnabled,
    ) {
        if (snapshot != null) {
            null
        } else {
            previewWithoutSnapshot(container, state, persona, conversationId, lastUserText, settings)
        }
    }

    // **留档优先，没有留档就用预览顶替整页**喵。
    // 这里原本只看 snapshot，于是没发过请求时「注入记忆」永远显示"本轮没有"、
    // 而「唤醒判定」整段被隐藏（它是 decisions 非空才画的）——
    // 用户看到的是"记忆根本没注入、也没有唤醒判定"，而真相只是**没显示**喵。
    // 预览那一趟本来就算出了记忆与判定，只是被丢掉了，这里把它接回来喵。
    val shown = snapshot ?: previewSnapshot
    val previewText = shown?.let { PromptBuilder.preview(it.turns) }.orEmpty()
    val injectedMemories = shown?.memories.orEmpty()
    val decisions = shown?.decisions.orEmpty()
    val temporalHint = shown?.temporalHint
        ?: if (settings.memoryTemporalEnabled) "（预览模式，见下方提示词）" else ""

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                title = { Text("上下文自检") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = Radius.card,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    // 先讲清楚这份内容是哪来的——它是整页可信度的前提喵。
                    Text(
                        text = if (actual) "● 本轮实际发送" else "○ 预览（本轮还没有发过请求）",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (actual) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (actual && snapshot.at > 0L) {
                        Text(
                            text = TimeUtil.smart(snapshot.at),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    InfoLine("接口", container.settings.chatEndpoint.ifBlank { "（未配置）" })
                    InfoLine("模型", persona?.modelOverride?.takeIf { it.isNotBlank() } ?: settings.model)
                    InfoLine("温度", "%.2f".format(persona?.temperatureOverride ?: settings.temperature))
                    InfoLine("上限", "${settings.maxTokens} tokens")
                    InfoLine(
                        label = "提示词来源",
                        value = if (snapshot == null) {
                            "（预览未润色）"
                        } else {
                            "${snapshot.refineSource.name} · ${snapshot.refineReason.label}"
                        },
                    )
                    InfoLine(
                        label = "发送条数",
                        value = if (snapshot == null) {
                            "预览，最多 ${settings.historyLimit} 条历史"
                        } else {
                            "${snapshot.turns.size} 条消息（含 system）"
                        },
                    )
                    InfoLine(
                        label = "注入记忆",
                        value = when {
                            !settings.injectMemories -> "已关闭"
                            actual -> "${injectedMemories.size} 条 / 上限 ${settings.memoryInjectLimit} 条"
                            else -> "预览模式，见下方唤醒判定"
                        },
                    )
                    InfoLine(
                        label = "联网资料",
                        value = if (actual) "${snapshot!!.searchHitCount} 条" else "预览不含",
                    )
                    InfoLine(
                        label = "附图片",
                        value = if (actual) "${snapshot!!.imageTurnCount} 条消息" else "预览不含",
                    )
                    InfoLine("拆条发送", if (settings.burstReplies) "是" else "否")
                    InfoLine(
                        label = "时空感知",
                        value = when {
                            !settings.memoryTemporalEnabled -> "已关闭"
                            actual -> snapshot!!.temporalHint.ifBlank { "（本轮无）" }
                            else -> temporalHint.ifBlank { "（未启用）" }
                        },
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // ---------------------------------------------------------- 注入的记忆
                if (actual) {
                    item {
                        SectionTitle(
                            if (injectedMemories.isEmpty()) {
                                "注入的记忆（本轮没有）"
                            } else {
                                "注入的记忆（${injectedMemories.size} 条）"
                            },
                        )
                    }
                    items(injectedMemories, key = { "mem-${it.id}" }) { memory ->
                        Block(text = memoryLine(memory))
                    }
                }

                // ---------------------------------------------------------- 最终提示词
                item {
                    SectionTitle(
                        if (actual) {
                            "最终发送内容（${snapshot?.turns?.size ?: 0} 条消息）"
                        } else {
                            "预览发送内容（尚未产生真实请求）"
                        },
                    )
                }
                items(previewText.split("\n\n")) { block ->
                    if (block.isNotBlank()) Block(text = block)
                }

                // ---------------------------------------------------------- 唤醒判定
                if (decisions.isNotEmpty()) {
                    item {
                        SectionTitle(
                            "唤醒判定（共 ${decisions.size} 条参与，实际注入 ${injectedMemories.size} 条）",
                        )
                    }
                    // **精排结论必须显式写出来**喵：用户报过"几乎没有记忆可以注入"，
                    // 而"记忆是空的"有好几种完全不同的原因——没有相关候选、
                    // 精排开关没开、精排超时、模型全判无关喵。
                    // 不把这几种分开，调参就只能靠猜喵。
                    item {
                        Block(text = rerankLine(shown), dim = shown?.rerankOutcome != RerankOutcome.OK)
                    }
                    items(decisions, key = { "dec-${it.memory.id}" }) { decision ->
                        Block(
                            text = decisionLine(decision),
                            dim = !decision.recalled,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 精排那一行喵。
 *
 * 它回答的是"这轮为什么没有记忆"里**最容易被忽略**的一种原因喵：
 * 候选其实找到了，是精排把它们筛掉了、或者精排自己挂了喵。
 */
private fun rerankLine(snapshot: ContextSnapshot?): String {
    val outcome = snapshot?.rerankOutcome ?: RerankOutcome.DISABLED
    val rescued = snapshot?.rescuedCount ?: 0
    val base = "精排：${outcome.label}"
    return when {
        rescued > 0 -> "$base · 其中 $rescued 条是靠模型救回来的"
        outcome == RerankOutcome.UNUSABLE -> "$base（模型把候选全判成无关，已退回统计结果）"
        outcome == RerankOutcome.TIMEOUT -> "$base（模型没在时限内返回，已退回统计结果）"
        outcome == RerankOutcome.NO_CONFIG -> "$base（缺地址或 Key，检查设置里的小模型服务商）"
        // **预览必须自己说明这一条**喵：预览跑在主线程上，所以刻意不发起
        // 精排那次网络请求（否则会 ANR）喵。不写明的话，用户会以为
        // 「精排没工作」，而真相是「这一页没跑精排」喵。
        snapshot == null -> "$base（预览模式不跑精排：它要发网络请求，而这里在主线程上。真实结果见发过消息后的留档）"
        else -> "$base（在设置里打开「Reranker 精排」才会走这一步）"
    }
}

/**
 * 还没有真实留档时（刚打开 App、刚换会话）的兜底预览喵。
 *
 * 和真实那一轮共用同一套检索/唤醒规则，但**一步都不写库**喵，
 * 所以结果只能当参考——页面上也如实标注成"预览"喵。
 *
 * 它返回的是**整份留档**而不只是提示词文本喵：这一趟本来就算出了
 * 注入的记忆与唤醒判定，丢掉它们会让页面看起来像"记忆根本没注入"喵。
 * 而且只能算**一次**——按概率唤醒的规则重算一遍可能选中另一批，
 * 那样页面上的"注入记忆"就和它旁边那份提示词对不上了喵。
 */
private fun previewWithoutSnapshot(
    container: AppContainer,
    state: ChatUiState,
    persona: Persona?,
    conversationId: Long,
    lastUserText: String,
    settings: AppSettings,
): ContextSnapshot? {
    if (persona == null) return null
    val memoryContext = kotlinx.coroutines.runBlocking {
        container.memoryDirector.preview(persona.id, conversationId, lastUserText, settings)
    }
    val turns = PromptBuilder.turns(
        persona = persona,
        memories = memoryContext.memories,
        history = state.messages,
        suffix = settings.systemPromptSuffix,
        burstReplies = settings.burstReplies,
        historyLimit = settings.historyLimit,
        temporalHint = memoryContext.temporal.hint(),
    )
    return ContextSnapshot(
        turns = turns,
        memories = memoryContext.memories,
        decisions = memoryContext.decisions,
        temporalHint = memoryContext.temporal.hint(),
    )
}

/** 标题小节喵。 */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** 等宽正文块喵。 */
@Composable
private fun Block(text: String, dim: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = if (dim) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radius.small)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(12.dp),
    )
}

/** 一条真正注入的记忆：正文 + 决定它"有多重要"的几个数字喵。 */
private fun memoryLine(memory: MemoryItem): String = buildString {
    append("• ").append(memory.statement.trim())
    append("\n  ").append(memory.category.name.lowercase())
    append(" · 重要度 ").append(memory.importance)
    append(" · 情绪 ").append("%.2f".format(memory.emotionalWeight))
    append(" · 鲜活 ").append("%.2f".format(memory.vividness))
    if (memory.isPermanent) append(" · 永久人设")
}

/** 一条记忆本轮的判定：醒没醒、什么原因、三个关键数字喵。 */
private fun decisionLine(decision: RecallDecision): String = buildString {
    append(if (decision.recalled) "✓ 注入" else "✗ 屏蔽")
    decision.reason?.let { append("（").append(it.label).append("）") }
    append('\n').append(decision.memory.statement.trim())
    append("\n相似度 ").append("%.2f".format(decision.similarity))
    append(" · 概率 ").append("%.2f".format(decision.probability))
    append(" · 骰子 ").append("%.2f".format(decision.roll))
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(text = value, style = MaterialTheme.typography.labelMedium)
    }
}
