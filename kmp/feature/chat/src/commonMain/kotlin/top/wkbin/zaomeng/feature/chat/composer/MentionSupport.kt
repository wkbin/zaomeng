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

internal class MentionVisualTransformation(
    private val participants: List<String>,
    private val mentionColor: androidx.compose.ui.graphics.Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val annotated = buildAnnotatedString {
            append(text)
            text.text.mentionRanges(participants).forEach { range ->
                addStyle(
                    SpanStyle(color = mentionColor, fontWeight = FontWeight.SemiBold),
                    range.first,
                    range.last + 1,
                )
            }
        }
        return TransformedText(annotated, OffsetMapping.Identity)
    }
}

internal fun TextFieldValue.insertMention(participant: String): TextFieldValue {
    val name = participant.trim()
    if (name.isBlank() || text.mentionRanges(listOf(name)).isNotEmpty()) return this

    val start = selection.min
    val end = selection.max
    val before = text.substring(0, start)
    val after = text.substring(end)
    val prefix = if (before.isNotEmpty() && !before.last().isWhitespace()) " " else ""
    val suffix = if (after.isEmpty() || !after.first().isWhitespace()) " " else ""
    val inserted = "$prefix@$name$suffix"
    val nextText = before + inserted + after
    val cursor = before.length + inserted.length
    return TextFieldValue(nextText, TextRange(cursor))
}

internal fun normalizeMentionDeletion(
    previous: TextFieldValue,
    next: TextFieldValue,
    participants: List<String>,
): TextFieldValue {
    if (next.text.length >= previous.text.length) return next

    val change = textDeletionRange(previous.text, next.text) ?: return next
    val affected = previous.text.mentionRanges(participants).filter { mention ->
        (mention.first < change.end && mention.last + 1 > change.start) ||
                (change.start == mention.last + 1 &&
                        change.end == mention.last + 2 &&
                        previous.text.getOrNull(mention.last + 1)?.isWhitespace() == true)
    }
    if (affected.isEmpty()) return next

    val start = minOf(change.start, affected.minOf { it.first })
    val end = maxOf(
        change.end,
        affected.maxOf { mention ->
            val afterMention = mention.last + 1
            if (previous.text.getOrNull(afterMention)?.isWhitespace() == true) {
                afterMention + 1
            } else {
                afterMention
            }
        },
    )
    return TextFieldValue(
        text = previous.text.removeRange(start, end),
        selection = TextRange(start),
    )
}

internal fun String.mentionRanges(participants: List<String>): List<IntRange> = participants
    .asSequence()
    .map(String::trim)
    .filter(String::isNotBlank)
    .distinct()
    .flatMap { name ->
        Regex("(?<!\\S)@${Regex.escape(name)}(?=\\s|$)")
            .findAll(this)
            .map { it.range }
    }
    .distinct()
    .sortedBy { it.first }
    .toList()

internal fun textDeletionRange(previous: String, next: String): TextRange? {
    var start = 0
    val sharedLength = minOf(previous.length, next.length)
    while (start < sharedLength && previous[start] == next[start]) start += 1

    var suffix = 0
    while (
        suffix < previous.length - start &&
        suffix < next.length - start &&
        previous[previous.length - suffix - 1] == next[next.length - suffix - 1]
    ) {
        suffix += 1
    }
    val end = previous.length - suffix
    return if (start < end) TextRange(start, end) else null
}

internal fun String.chineseMode(): String = when (this) {
    "act" -> "扮演人物"
    "insert" -> "自设入场"
    else -> "旁观群聊"
}

/**
 * 稳定的消息 key：不依赖列表下标，向上加载历史前插时不会导致 key 漂移。
 * （同一 turn 内同 speaker 的重复文本也几乎不可能冲突。）
 */
internal fun TranscriptItemDto.transcriptKey(): String =
    "$turnId|$speaker|$timestamp|${message.hashCode()}"
