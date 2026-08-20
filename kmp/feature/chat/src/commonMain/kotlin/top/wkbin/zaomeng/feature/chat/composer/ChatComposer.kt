package top.wkbin.zaomeng.feature.chat

import top.wkbin.zaomeng.ui.components.ChatBackgroundImage
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.window.core.layout.WindowSizeClass
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.ChatSearchResultDto
import top.wkbin.zaomeng.data.api.TranscriptItemDto
import top.wkbin.zaomeng.data.preferences.ChatDisplayPreferences
import top.wkbin.zaomeng.feature.chat.transcript.EmotionBadge
import top.wkbin.zaomeng.feature.chat.insights.consistencyInsight
import top.wkbin.zaomeng.platform.PlatformBackHandler
import top.wkbin.zaomeng.platform.rememberClipboardTextWriter
import top.wkbin.zaomeng.platform.rememberPlatformImage
import top.wkbin.zaomeng.ui.graphics.decodeImageBitmap
import top.wkbin.zaomeng.ui.format.toLocalDateTimeDisplay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

@Composable
internal fun ChatComposer(
    state: ChatUiState,
    avatarBytes: Map<String, ByteArray>,
    onDraftChange: (String) -> Unit,
    onClearSpeakerOverride: () -> Unit,
    onMessageKindChange: (String) -> Unit,
    onPacingChange: (String) -> Unit,
    onInvokePluginAction: (ChatPluginAction) -> Unit,
    onOpenDirector: () -> Unit,
    onSend: () -> Unit,
    onToggleContinuousObserve: () -> Unit,
    onToggleGenerationEnhancer: (ChatGenerationEnhancer) -> Unit,
    onRecover: () -> Unit,
    onReconcile: () -> Unit,
    onRetry: () -> Unit,
    onDiscardRetry: () -> Unit,
) {
    var mentionsOpen by rememberSaveable(state.sessionId) { mutableStateOf(false) }
    var messageKindMenuOpen by rememberSaveable(state.sessionId) { mutableStateOf(false) }
    var pacingMenuOpen by rememberSaveable(state.sessionId) { mutableStateOf(false) }
    var pluginsOpen by rememberSaveable(state.sessionId) { mutableStateOf(false) }
    var toolsOpen by rememberSaveable(state.sessionId) { mutableStateOf(false) }
    var draftValue by rememberSaveable(state.sessionId, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(state.draft))
    }
    val participants = remember(state.session) {
        val session = state.session ?: return@remember emptyList()
        val present = session.sceneProgress["present_participants"]
            ?.let { value ->
                runCatching {
                    value.jsonArray.mapNotNull { item ->
                        item.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank)
                    }
                }.getOrNull()
            }
            .orEmpty()
        (present.ifEmpty { session.participants })
            .filter { it.isNotBlank() && it != session.controlledCharacter }
            .distinct()
    }
    val inputEnabled = !state.sending && !state.recovering &&
            !state.sendOutcomeUnknown && state.failedOperationId.isBlank()
    val pluginActionBusy = state.toolBusy.startsWith("plugin:")
    val draftInputEnabled = inputEnabled && state.toolBusy != "suggest" && !pluginActionBusy
    val mentionColor = MaterialTheme.colorScheme.primary
    val mentionTransformation = remember(participants, mentionColor) {
        MentionVisualTransformation(participants, mentionColor)
    }
    LaunchedEffect(state.draft) {
        if (state.draft != draftValue.text) {
            val previousSelection = draftValue.selection
            val lastIndex = state.draft.length
            draftValue = TextFieldValue(
                text = state.draft,
                selection = TextRange(
                    previousSelection.start.coerceIn(0, lastIndex),
                    previousSelection.end.coerceIn(0, lastIndex),
                ),
            )
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(
                horizontal = 12.dp,
                vertical = if (state.chatDisplay.compactMode) 6.dp else 10.dp,
            ),
    ) {
        if (participants.isNotEmpty() && mentionsOpen) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 6.dp),
            ) {
                items(participants, key = { it }) { participant ->
                    Column(
                        modifier = Modifier
                            .width(60.dp)
                            .clickable(enabled = inputEnabled) {
                                val next = draftValue.insertMention(participant)
                                draftValue = next
                                onDraftChange(next.text)
                                mentionsOpen = false
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        ChatPersonaAvatar(
                            bytes = avatarBytes[participant],
                            name = participant,
                            modifier = Modifier.size(48.dp),
                        )
                        Text(
                            text = participant,
                            modifier = Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        LazyRow(
            modifier = Modifier.padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (participants.isNotEmpty()) {
                item {
                    OutlinedButton(
                        onClick = { mentionsOpen = !mentionsOpen },
                        enabled = inputEnabled,
                        modifier = Modifier.size(36.dp),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(0.dp),
                    ) { Text("@") }
                }
            }
            item {
                Box {
                    OutlinedButton(
                        onClick = { pluginsOpen = true },
                        enabled = inputEnabled && (
                                state.pluginActions.isNotEmpty() ||
                                        state.generationEnhancers.isNotEmpty()
                                ),
                        modifier = Modifier.height(36.dp),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                    ) {
                        Icon(
                            Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (pluginActionBusy) "插件运行中…" else "插件")
                    }
                    DropdownMenu(
                        expanded = pluginsOpen,
                        onDismissRequest = { pluginsOpen = false },
                    ) {
                        state.generationEnhancers.forEach { enhancer ->
                            val active = enhancer.isActive(state.session)
                            val busyKey = "enhancer:${enhancer.stateKey}"
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (state.toolBusy == busyKey) {
                                            "${enhancer.title} · 保存中…"
                                        } else {
                                            enhancer.title
                                        },
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        if (active) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = if (active) "已开启" else "已关闭",
                                    )
                                },
                                enabled = state.canUseTools,
                                onClick = {
                                    pluginsOpen = false
                                    onToggleGenerationEnhancer(enhancer)
                                },
                            )
                        }
                        state.pluginActions.forEach { action ->
                            val busyKey = "plugin:${action.pluginId}:${action.actionId}"
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (state.toolBusy == busyKey) "${action.title} · 运行中…" else action.title,
                                    )
                                },
                                leadingIcon = {
                                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                                },
                                enabled = state.canUseTools,
                                onClick = {
                                    pluginsOpen = false
                                    onInvokePluginAction(action)
                                },
                            )
                        }
                    }
                }
            }
            item {
                Box {
                    OutlinedButton(
                        onClick = { messageKindMenuOpen = true },
                        enabled = inputEnabled,
                        modifier = Modifier.height(36.dp),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Text(
                            messageKindOptions.firstOrNull { it.value == state.messageKind }
                                ?.label ?: "对话",
                        )
                        Spacer(Modifier.size(4.dp))
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "切换输入模式",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = messageKindMenuOpen,
                        onDismissRequest = { messageKindMenuOpen = false },
                    ) {
                        messageKindOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    onMessageKindChange(option.value)
                                    messageKindMenuOpen = false
                                },
                            )
                        }
                    }
                }
            }
            item {
                Box {
                    OutlinedButton(
                        onClick = { pacingMenuOpen = true },
                        enabled = inputEnabled,
                        modifier = Modifier.height(36.dp),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                    ) {
                        Text(
                            when (state.pacing) {
                                "brief" -> "简洁"
                                "detailed" -> "细腻"
                                else -> "适中"
                            },
                        )
                        Spacer(Modifier.size(4.dp))
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "切换回复节奏",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = pacingMenuOpen,
                        onDismissRequest = { pacingMenuOpen = false },
                    ) {
                        listOf(
                            "brief" to "简洁 · 每人一两句",
                            "normal" to "适中 · 默认节奏",
                            "detailed" to "细腻 · 展开动作环境",
                        ).forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    onPacingChange(value)
                                    pacingMenuOpen = false
                                },
                            )
                        }
                    }
                }
            }
            item {
                Box {
                    OutlinedButton(
                        onClick = { toolsOpen = true },
                        // 连续旁观期间也要能打开工具菜单（暂停入口在里面）
                        enabled = inputEnabled || state.continuousObserveEnabled,
                        modifier = Modifier.height(36.dp),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(2.dp))
                        Text("工具")
                    }
                    DropdownMenu(
                        expanded = toolsOpen,
                        onDismissRequest = { toolsOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("剧情导演") },
                            leadingIcon = { Icon(Icons.Outlined.Movie, contentDescription = null) },
                            enabled = state.canUseTools,
                            onClick = {
                                toolsOpen = false
                                onOpenDirector()
                            },
                        )
                        if (state.session?.mode == "observe") {
                            DropdownMenuItem(
                                text = { Text(if (state.continuousObserveEnabled) "暂停旁观" else "开启旁观") },
                                leadingIcon = {
                                    Icon(
                                        if (state.continuousObserveEnabled) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = null,
                                    )
                                },
                                enabled = state.canToggleContinuousObserve,
                                onClick = {
                                    toolsOpen = false
                                    onToggleContinuousObserve()
                                },
                            )
                        }
                    }
                }
            }
        }

        if (state.messageKind == "plot") {
            Text(
                "导演指令会引导下一拍，不会写成你的角色台词。",
                modifier = Modifier.padding(bottom = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.messageKind == "fourth_wall") {
            Text(
                "角色可以直接回应、违抗或与你谈判。",
                modifier = Modifier.padding(bottom = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.draftSpeakerOverride.isNotBlank()) {
            TextButton(
                onClick = onClearSpeakerOverride,
                enabled = inputEnabled,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
            ) {
                Text("将以「${state.draftSpeakerOverride}」身份发送 · 取消")
            }
        }

        Row(verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = draftValue,
                onValueChange = { next ->
                    val resolved = normalizeMentionDeletion(
                        previous = draftValue,
                        next = next,
                        participants = participants,
                    )
                    draftValue = resolved
                    onDraftChange(resolved.text)
                },
                modifier = Modifier
                    .weight(1f)
                    // PC 端回车直接发送；Shift/修饰键+回车保留默认行为（如换行）
                    .onPreviewKeyEvent { keyEvent ->
                        if (
                            keyEvent.type == KeyEventType.KeyDown &&
                            keyEvent.key == Key.Enter &&
                            !keyEvent.isShiftPressed &&
                            !keyEvent.isCtrlPressed &&
                            !keyEvent.isAltPressed &&
                            !keyEvent.isMetaPressed &&
                            // 输入法组合候选词时回车用于确认候选，不发送
                            draftValue.composition == null
                        ) {
                            if (state.canSend) onSend()
                            true
                        } else {
                            false
                        }
                    },
                enabled = draftInputEnabled,
                visualTransformation = mentionTransformation,
                placeholder = {
                    Text(
                        when (state.messageKind) {
                            "narration" -> "描述一个场景变化…"
                            "plot" -> "交代希望下一拍怎样发展…"
                            "fourth_wall" -> "以作者身份直接对角色下令…"
                            else -> "写下你想说的话…"
                        },
                    )
                },
                minLines = 1,
                maxLines = 4,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (state.canSend) onSend() }),
            )
            Spacer(Modifier.size(2.dp))
            if (state.continuousObserveEnabled) {
                // 连续旁观中：发送区替换为常驻“暂停旁观”，保证随时可以停下
                Button(
                    onClick = onToggleContinuousObserve,
                    modifier = Modifier.height(40.dp),
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                ) {
                    Icon(Icons.Default.Pause, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("暂停")
                }
            } else {
                IconButton(
                    onClick = onSend,
                    enabled = state.canSend,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "发送",
                        tint = if (state.canSend) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        },
                    )
                }
            }
        }

        ComposerStatusBar(
            state = state,
            onRecover = onRecover,
            onReconcile = onReconcile,
            onRetry = onRetry,
            onDiscardRetry = onDiscardRetry,
        )
    }
}
