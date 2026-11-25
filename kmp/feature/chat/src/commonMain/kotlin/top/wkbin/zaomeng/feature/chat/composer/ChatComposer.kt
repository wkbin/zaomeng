package top.wkbin.zaomeng.feature.chat

import io.github.suqi8.coui.kmp.theme.COUITheme
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
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.DropdownImpl
import io.github.suqi8.coui.kmp.basic.DropdownItem
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.ListPopupColumn
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.basic.TextField
import io.github.suqi8.coui.kmp.basic.TextFieldMode
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Create
import io.github.suqi8.coui.kmp.icon.extended.ExpandMore
import io.github.suqi8.coui.kmp.icon.extended.Hide
import io.github.suqi8.coui.kmp.icon.extended.More
import io.github.suqi8.coui.kmp.icon.extended.Pause
import io.github.suqi8.coui.kmp.icon.extended.Photos
import io.github.suqi8.coui.kmp.icon.extended.Play
import io.github.suqi8.coui.kmp.icon.extended.Send
import io.github.suqi8.coui.kmp.icon.extended.Show
import io.github.suqi8.coui.kmp.overlay.OverlayListPopup
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
    val mentionColor = COUITheme.colorScheme.primary
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
                            style = COUITheme.textStyles.description,
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
                    Button(
                        onClick = { mentionsOpen = !mentionsOpen },
                        enabled = inputEnabled,
                        modifier = Modifier.size(36.dp),
                        cornerRadius = 10.dp,
                        minWidth = 36.dp,
                        minHeight = 36.dp,
                        insideMargin = PaddingValues(0.dp),
                    ) { Text("@") }
                }
            }
            item {
                Box {
                    Button(
                        onClick = { messageKindMenuOpen = true },
                        enabled = inputEnabled,
                        modifier = Modifier.height(36.dp),
                        cornerRadius = 10.dp,
                        minWidth = ButtonDefaults.MinWidthSmall,
                        minHeight = 36.dp,
                        insideMargin = PaddingValues(horizontal = 12.dp),
                    ) {
                        Text(
                            messageKindOptions.firstOrNull { it.value == state.messageKind }
                                ?.label ?: "对话",
                        )
                        Spacer(Modifier.size(4.dp))
                        Icon(
                            imageVector = COUIIcons.ExpandMore,
                            contentDescription = "切换输入模式",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    OverlayListPopup(
                        show = messageKindMenuOpen,
                        onDismissRequest = { messageKindMenuOpen = false },
                    ) {
                        ListPopupColumn {
                            messageKindOptions.forEachIndexed { index, option ->
                                DropdownImpl(
                                    text = option.label,
                                    optionSize = messageKindOptions.size,
                                    isSelected = false,
                                    index = index,
                                    onSelectedIndexChange = {
                                        messageKindMenuOpen = false
                                        onMessageKindChange(option.value)
                                    },
                                )
                            }
                        }
                    }
                }
            }
            item {
                val toolEntries: List<Pair<DropdownItem, () -> Unit>> = buildList {
                    add(
                        DropdownItem(
                            text = "剧情导演",
                            enabled = state.canUseTools,
                            icon = { Icon(COUIIcons.Photos, contentDescription = null) },
                        ) to {
                            toolsOpen = false
                            onOpenDirector()
                        },
                    )
                    add(
                        DropdownItem(
                            text = "回复节奏",
                            enabled = inputEnabled,
                        ) to {
                            toolsOpen = false
                            pacingMenuOpen = true
                        },
                    )
                    if (state.pluginActions.isNotEmpty() || state.generationEnhancers.isNotEmpty()) {
                        add(
                            DropdownItem(
                                text = if (pluginActionBusy) "插件运行中…" else "插件",
                                enabled = inputEnabled,
                            ) to {
                                toolsOpen = false
                                pluginsOpen = true
                            },
                        )
                    }
                    if (state.session?.mode == "observe") {
                        add(
                            DropdownItem(
                                text = if (state.continuousObserveEnabled) "暂停旁观" else "开启旁观",
                                enabled = state.canToggleContinuousObserve,
                                icon = {
                                    Icon(
                                        if (state.continuousObserveEnabled) {
                                            COUIIcons.Pause
                                        } else {
                                            COUIIcons.Play
                                        },
                                        contentDescription = null,
                                    )
                                },
                            ) to {
                                toolsOpen = false
                                onToggleContinuousObserve()
                            },
                        )
                    }
                }
                val pluginEntries: List<Pair<DropdownItem, () -> Unit>> = buildList {
                    state.generationEnhancers.forEach { enhancer ->
                        val active = enhancer.isActive(state.session)
                        val busyKey = "enhancer:${enhancer.stateKey}"
                        add(
                            DropdownItem(
                                text = if (state.toolBusy == busyKey) {
                                    "${enhancer.title} · 保存中…"
                                } else {
                                    enhancer.title
                                },
                                enabled = state.canUseTools,
                                icon = {
                                    Icon(
                                        if (active) {
                                            COUIIcons.Show
                                        } else {
                                            COUIIcons.Hide
                                        },
                                        contentDescription = if (active) "已开启" else "已关闭",
                                    )
                                },
                            ) to {
                                pluginsOpen = false
                                onToggleGenerationEnhancer(enhancer)
                            },
                        )
                    }
                    state.pluginActions.forEach { action ->
                        val busyKey = "plugin:${action.pluginId}:${action.actionId}"
                        add(
                            DropdownItem(
                                text = if (state.toolBusy == busyKey) {
                                    "${action.title} · 运行中…"
                                } else {
                                    action.title
                                },
                                enabled = state.canUseTools,
                                icon = { Icon(COUIIcons.Create, contentDescription = null) },
                            ) to {
                                pluginsOpen = false
                                onInvokePluginAction(action)
                            },
                        )
                    }
                }
                Box {
                    Button(
                        onClick = { toolsOpen = true },
                        // 连续旁观期间也要能打开工具菜单（暂停入口在里面）
                        enabled = inputEnabled || state.continuousObserveEnabled,
                        modifier = Modifier.height(36.dp),
                        cornerRadius = 10.dp,
                        minWidth = ButtonDefaults.MinWidthSmall,
                        minHeight = 36.dp,
                        insideMargin = PaddingValues(horizontal = 10.dp),
                    ) {
                        Icon(
                            COUIIcons.More,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(2.dp))
                        Text("工具")
                    }
                    OverlayListPopup(
                        show = toolsOpen,
                        onDismissRequest = { toolsOpen = false },
                    ) {
                        ListPopupColumn {
                            toolEntries.forEachIndexed { index, (item, action) ->
                                DropdownImpl(
                                    item = item,
                                    optionSize = toolEntries.size,
                                    isSelected = false,
                                    index = index,
                                    onSelectedIndexChange = { action() },
                                )
                            }
                        }
                    }
                    OverlayListPopup(
                        show = pacingMenuOpen,
                        onDismissRequest = { pacingMenuOpen = false },
                    ) {
                        ListPopupColumn {
                            listOf(
                                "brief" to "简洁 · 每人一两句",
                                "normal" to "适中 · 默认节奏",
                                "detailed" to "细腻 · 展开动作环境",
                            ).forEachIndexed { index, (value, label) ->
                                DropdownImpl(
                                    text = label,
                                    optionSize = 3,
                                    isSelected = false,
                                    index = index,
                                    onSelectedIndexChange = {
                                        onPacingChange(value)
                                        pacingMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                    OverlayListPopup(
                        show = pluginsOpen,
                        onDismissRequest = { pluginsOpen = false },
                    ) {
                        ListPopupColumn {
                            pluginEntries.forEachIndexed { index, (item, action) ->
                                DropdownImpl(
                                    item = item,
                                    optionSize = pluginEntries.size,
                                    isSelected = false,
                                    index = index,
                                    onSelectedIndexChange = { action() },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (state.messageKind == "plot") {
            Text(
                "导演指令会引导下一拍，不会写成你的角色台词。",
                modifier = Modifier.padding(bottom = 6.dp),
                style = COUITheme.textStyles.description,
                color = COUITheme.colorScheme.labelSecondary,
            )
        }
        if (state.messageKind == "fourth_wall") {
            Text(
                "角色可以直接回应、违抗或与你谈判。",
                modifier = Modifier.padding(bottom = 6.dp),
                style = COUITheme.textStyles.description,
                color = COUITheme.colorScheme.labelSecondary,
            )
        }
        if (state.draftSpeakerOverride.isNotBlank()) {
            TextButton(
                text = "将以「${state.draftSpeakerOverride}」身份发送 · 取消",
                onClick = onClearSpeakerOverride,
                enabled = inputEnabled,
                minWidth = ButtonDefaults.MinWidthSmall,
                insideMargin = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
            )
        }

        Row(verticalAlignment = Alignment.Bottom) {
            TextField(
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
                label = when (state.messageKind) {
                    "narration" -> "描述一个场景变化…"
                    "plot" -> "交代希望下一拍怎样发展…"
                    "fourth_wall" -> "以作者身份直接对角色下令…"
                    else -> "写下你想说的话…"
                },
                minLines = 1,
                maxLines = 4,
                backgroundMode = TextFieldMode.Rectangle,
                cornerRadius = 24.dp,
                // Rectangle 模式的默认字重是粗体（COUI HintAnim.Rectangle），聊天草稿要保持常规字重
                textStyle = COUITheme.textStyles.bodyXL,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (state.canSend) onSend() }),
            )
            Spacer(Modifier.width(8.dp))
            if (state.continuousObserveEnabled) {
                // 连续旁观中：发送区替换为常驻“暂停旁观”，保证随时可以停下
                Button(
                    onClick = onToggleContinuousObserve,
                    modifier = Modifier.height(40.dp),
                    cornerRadius = 20.dp,
                    minWidth = ButtonDefaults.MinWidthSmall,
                    minHeight = 40.dp,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    insideMargin = PaddingValues(horizontal = 14.dp),
                ) {
                    Icon(COUIIcons.Pause, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("暂停")
                }
            } else {
                IconButton(
                    onClick = onSend,
                    enabled = state.canSend,
                    backgroundColor = COUITheme.colorScheme.primary,
                ) {
                    Icon(
                        imageVector = COUIIcons.Send,
                        contentDescription = "发送",
                        tint = COUITheme.colorScheme.onPrimary,
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
