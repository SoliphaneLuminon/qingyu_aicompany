package com.qingyu.app.ui.contacts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingyu.app.core.PinyinInitial
import com.qingyu.app.data.Persona
import com.qingyu.app.ui.components.EmptyState
import com.qingyu.app.ui.components.PersonaAvatar
import kotlinx.coroutines.launch

/**
 * 通讯录：管理所有角色喵。
 *
 * 布局照微信的联系人页：顶部「添加联系人」，下面按拼音首字母分组，
 * 右侧一条字母索引可以按住快速跳转喵。
 *
 * 与主界面的分工喵：**主界面管对话，这里管角色**。
 * 所以这里列出**所有**角色——包括对话已从主界面移除的那些，
 * 否则用户会以为角色被删了喵。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    personas: List<Persona>,
    onOpenPersona: (Persona) -> Unit,
    onAddContact: () -> Unit,
    onOpenChat: (Persona) -> Unit,
    /**
     * 首屏数据是否还在加载喵。
     *
     * 与主界面同理：列表初始为空，不加区分就会先闪一下「通讯录还是空的」
     * 再变成真实列表，看起来像"打开时白了一下"喵。
     */
    loading: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val groups = remember(personas) {
        PinyinInitial.group(personas) { it.name }
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 每个分组的标题在 LazyColumn 里的下标，供右侧索引用喵。
    val headerIndexes = remember(groups) {
        buildMap {
            var cursor = 1 // 第 0 项是「添加联系人」那一行喵。
            groups.forEach { (letter, members) ->
                put(letter, cursor)
                cursor += 1 + members.size
            }
        }
    }

    var pressedLetter by remember { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                title = { Text("通讯录") },
            )
        },
    ) { innerPadding ->
        if (personas.isEmpty() && loading) {
            // 加载中先留白，避免闪一下"通讯录还是空的"这种错误内容喵。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        } else if (personas.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                EmptyState(
                    title = "通讯录还是空的",
                    description = "点上面的「添加联系人」建一个角色。\n建好后就能在主界面和它聊天。",
                    icon = "👤",
                )
            }
            return@Scaffold
        }

        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            LazyColumn(state = listState) {
                item(key = "__add__") {
                    AddContactRow(onClick = onAddContact)
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 68.dp),
                        thickness = 0.6.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                    )
                }
                groups.forEach { (letter, members) ->
                    item(key = "header-$letter") {
                        GroupHeader(letter)
                    }
                    items(
                        items = members,
                        key = { persona -> "persona-${persona.id}" },
                    ) { persona ->
                        ContactRow(
                            persona = persona,
                            onClick = { onOpenPersona(persona) },
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 68.dp),
                            thickness = 0.6.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                        )
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }

            // 右侧字母索引喵。
            AlphabetIndex(
                letters = groups.map { it.first },
                pressed = pressedLetter,
                onPress = { letter ->
                    pressedLetter = letter
                    headerIndexes[letter]?.let { target ->
                        scope.launch { listState.scrollToItem(target) }
                    }
                },
                onRelease = { pressedLetter = null },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 2.dp),
            )

            // 微信那个标志性的索引用气泡：拖动时屏幕正中浮现一个大字母喵。
            IndexBubble(
                letter = pressedLetter,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/**
 * 拖动索引时居中浮现的大字母气泡喵。
 *
 * 这是微信联系人页最容易被记住的一个细节：手指按在右边时，
 * 屏幕正中会有一个深色圆角方块显示当前字母，松手淡出喵。
 * [letter] 为 null 时不渲染，靠 [AnimatedVisibility] 做淡入淡出喵。
 */
@Composable
private fun IndexBubble(letter: String?, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = letter != null,
        enter = fadeIn(animationSpec = tween(90)) +
            scaleIn(animationSpec = spring(dampingRatio = 0.6f, stiffness = 900f), initialScale = 0.8f),
        exit = fadeOut(animationSpec = tween(140)) +
            scaleOut(animationSpec = tween(140), targetScale = 0.9f),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = letter.orEmpty(),
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.surface,
            )
        }
    }
}

/** 顶部「添加联系人」，与微信的「新的朋友」位置一致喵。 */
@Composable
private fun AddContactRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.PersonAdd,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = "添加联系人",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 分组标题：微信是浅灰底加一个字母，这里照做喵。 */
@Composable
private fun GroupHeader(letter: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(
            text = letter,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 通讯录里的一行：头像 + 名字，按下时底色变化喵。 */
@Composable
private fun ContactRow(persona: Persona, onClick: () -> Unit) {
    // 用交互源拿按下状态，而不是自己接手势——自己接会和 clickable 抢事件、
    // 也会弄丢无障碍的点击语义喵。
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(
        targetValue = if (pressed) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surface
        },
        animationSpec = tween(120),
        label = "contactRowBg",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PersonaAvatar(persona = persona, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = persona.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 右侧字母索引喵。
 *
 * 按住上下拖动时切换字母并跳转，松手把高亮去掉喵。
 * 拖动用 [detectVerticalDragGestures] 按相对位置换算成第几个字母——
 * 比给每个字母单独做可点区域更跟手，手指滑过一串字母也能连续跳喵。
 */
@Composable
private fun AlphabetIndex(
    letters: List<String>,
    pressed: String?,
    onPress: (String) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (letters.isEmpty()) return
    var heightPx by remember { mutableStateOf(0) }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(26.dp)
            .pointerInput(letters) {
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        heightPx = size.height
                        if (letters.isNotEmpty() && heightPx > 0) {
                            val index = ((offset.y / heightPx) * letters.size)
                                .toInt().coerceIn(0, letters.size - 1)
                            onPress(letters[index])
                        }
                    },
                    onDragEnd = { onRelease() },
                    onDragCancel = { onRelease() },
                ) { change, _ ->
                    if (heightPx > 0) {
                        val index = ((change.position.y / heightPx) * letters.size)
                            .toInt().coerceIn(0, letters.size - 1)
                        onPress(letters[index])
                    }
                }
            },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        letters.forEach { letter ->
            Box(
                modifier = Modifier
                    .size(15.dp)
                    .clip(CircleShape)
                    .background(
                        if (pressed == letter) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = letter,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (pressed == letter) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
