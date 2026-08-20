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
@OptIn(ExperimentalFoundationApi::class)
internal fun TranscriptBubble(
    item: TranscriptItemDto,
    avatarBytes: Map<String, ByteArray>,
    displayPreferences: ChatDisplayPreferences,
    actionsEnabled: Boolean,
    includeInnerThoughts: Boolean,
    streaming: Boolean = false,
    canRegenerate: Boolean = false,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onBranch: () -> Unit,
) {
    val isUser = item.role == "user"
    val isScene = item.role == "scene"
    val isDirector = item.role == "director"
    val canBranch = item.turnId.isNotBlank()
    var menuExpanded by remember(item.turnId, item.message) { mutableStateOf(false) }
    val verticalPadding = if (displayPreferences.compactMode) 6.dp else 9.dp
    val messageStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = MaterialTheme.typography.bodyMedium.fontSize * displayPreferences.fontSizeScale,
    )

    if (isScene) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box {
                Surface(
                    modifier = Modifier
                        .widthIn(max = 520.dp)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { if (!streaming) menuExpanded = true },
                        ),
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = verticalPadding)) {
                        Text(
                            text = "旁白${
                                item.speaker.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
                            }",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            fontWeight = FontWeight.SemiBold,
                        )
                        ParentheticalMessageText(
                            text = item.message,
                            modifier = Modifier.padding(top = 3.dp),
                            style = messageStyle.copy(fontStyle = FontStyle.Italic),
                            baseColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
                MessageContextMenu(
                    expanded = menuExpanded,
                    onDismiss = { menuExpanded = false },
                    actionsEnabled = actionsEnabled,
                    canRegenerate = false,
                    canBranch = canBranch,
                    onCopy = onCopy,
                    onRegenerate = onRegenerate,
                    onBranch = onBranch,
                )
            }
        }
        return
    }

    if (isDirector) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box {
                Surface(
                    modifier = Modifier
                        .widthIn(max = 520.dp)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { if (!streaming) menuExpanded = true },
                        ),
                    color = androidx.compose.ui.graphics.Color.Transparent,
                    shape = RoundedCornerShape(0.dp),
                ) {
                    Column(
                        Modifier.padding(horizontal = 14.dp, vertical = verticalPadding),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        androidx.compose.material3.HorizontalDivider()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "导演${
                                    item.speaker.takeIf(String::isNotBlank)?.let { " · $it" }
                                        .orEmpty()
                                }",
                                modifier = Modifier.padding(start = 5.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        ParentheticalMessageText(
                            text = item.message,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 3.dp),
                            style = messageStyle,
                            baseColor = MaterialTheme.colorScheme.onSurface,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
                MessageContextMenu(
                    expanded = menuExpanded,
                    onDismiss = { menuExpanded = false },
                    actionsEnabled = actionsEnabled,
                    canRegenerate = false,
                    canBranch = canBranch,
                    onCopy = onCopy,
                    onRegenerate = onRegenerate,
                    onBranch = onBranch,
                )
            }
        }
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (!isUser) {
            Box(Modifier.size(40.dp)) {
                ChatPersonaAvatar(
                    bytes = avatarBytes[item.speaker],
                    name = item.speaker,
                    modifier = Modifier.size(40.dp),
                )
                EmotionBadge(
                    innerThought = item.innerThought,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        Column {
            if (!isUser) {
                Text(
                    text = item.speaker.ifBlank { "人物" },
                    modifier = Modifier.padding(bottom = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                Surface(
                    modifier = Modifier
                        .widthIn(max = 340.dp)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { if (!streaming) menuExpanded = true },
                        ),
                    color = if (isUser) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    shape = RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = if (isUser) 18.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 18.dp,
                    ),
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = verticalPadding)) {
                        if (isUser) Text(
                            text = if (item.speaker == "作者") "作者" else "你 · ${item.speaker}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                            fontWeight = FontWeight.SemiBold,
                        )
                        ParentheticalMessageText(
                            text = item.message,
                            modifier = Modifier.padding(top = 3.dp),
                            style = messageStyle,
                            baseColor = if (isUser) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        if (includeInnerThoughts && item.innerThought.isNotBlank() && !isUser) {
                            androidx.compose.material3.HorizontalDivider(
                                modifier = Modifier.padding(top = 6.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                            Text(
                                text = "内心独白：${item.innerThought}",
                                modifier = Modifier.padding(top = 6.dp),
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontStyle = FontStyle.Italic,
                                ),
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        if (streaming) {
                            Text(
                                "正在生成",
                                modifier = Modifier.padding(top = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                MessageContextMenu(
                    expanded = menuExpanded,
                    onDismiss = { menuExpanded = false },
                    actionsEnabled = actionsEnabled,
                    canRegenerate = canRegenerate,
                    canBranch = canBranch,
                    onCopy = onCopy,
                    onRegenerate = onRegenerate,
                    onBranch = onBranch,
                )
            }
        }
    }
}
