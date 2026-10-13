package com.qingyu.app.ui.search

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
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.qingyu.app.AppContainer
import com.qingyu.app.core.TimeUtil
import com.qingyu.app.data.ChatMessage
import com.qingyu.app.data.MessageRole
import com.qingyu.app.ui.components.EmptyState
import kotlinx.coroutines.launch
import com.qingyu.app.ui.theme.Radius

/** 聊天记录搜索：跨会话找一句话喵。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    conversationId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = AppContainer.get(LocalContext.current)
    val scope = rememberCoroutineScope()
    var keyword by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var conversationTitles by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }

    fun runSearch(text: String) {
        keyword = text
        if (text.isBlank()) {
            results = emptyList()
            return
        }
        scope.launch {
            val found = container.database.messages.search(text.trim())
            val titles = found.map { it.conversationId }.distinct()
                .mapNotNull { id -> container.database.conversations.get(id)?.let { id to it.title } }
                .toMap()
            conversationTitles = titles
            results = found
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                title = { Text("搜索聊天记录") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            OutlinedTextField(
                value = keyword,
                onValueChange = { runSearch(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("输入关键词，比如某个名字或一件事") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                singleLine = true,
                shape = Radius.card,
            )

            when {
                keyword.isBlank() -> EmptyState(
                    title = "搜点什么",
                    description = "所有对话里的内容都能搜到，包括很久以前说过的话。",
                    icon = "🔍",
                    modifier = Modifier.fillMaxSize(),
                )

                results.isEmpty() -> EmptyState(
                    title = "没有找到",
                    description = "换个说法再试试。",
                    icon = "🫥",
                    modifier = Modifier.fillMaxSize(),
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Text(
                            text = "找到 ${results.size} 条",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(results, key = { it.id }) { message ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = Radius.card,
                            colors = CardDefaults.cardColors(
                                containerColor = if (message.conversationId == conversationId) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainer
                                },
                            ),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = if (message.role == MessageRole.USER) "我" else "对方",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .clip(Radius.chip)
                                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = conversationTitles[message.conversationId].orEmpty(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = TimeUtil.smart(message.timestamp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                HighlightedText(text = message.content, keyword = keyword)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 把命中的关键词标出来喵。 */
@Composable
private fun HighlightedText(text: String, keyword: String) {
    val scheme = MaterialTheme.colorScheme
    if (keyword.isBlank()) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }
    val annotated = remember(text, keyword, scheme.primaryContainer) {
        buildAnnotatedString {
            var index = 0
            val lower = text.lowercase()
            val target = keyword.lowercase()
            while (index < text.length) {
                val hit = lower.indexOf(target, index)
                if (hit < 0) {
                    append(text.substring(index))
                    break
                }
                append(text.substring(index, hit))
                withStyle(
                    SpanStyle(
                        background = scheme.primaryContainer,
                        fontWeight = FontWeight.SemiBold,
                    ),
                ) {
                    append(text.substring(hit, (hit + target.length).coerceAtMost(text.length)))
                }
                index = hit + target.length
            }
        }
    }
    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 6,
        overflow = TextOverflow.Ellipsis,
    )
}
