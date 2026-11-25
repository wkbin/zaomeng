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
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.LoadingButton
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Reset
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
internal fun ComposerStatusBar(
    state: ChatUiState,
    onRecover: () -> Unit,
    onReconcile: () -> Unit,
    onRetry: () -> Unit,
    onDiscardRetry: () -> Unit,
) {
        if (state.pendingUserMessage == null && state.failedOperationId.isNotBlank() && !state.sending) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    when {
                        state.sendOutcomeUnknown ->
                            "连接中断，先核对本地结果；重试会沿用本次发送标识，不会重复生成。"

                        state.session?.status != "ready" ->
                            "回复在本机仍处于待处理状态，可重试同一次生成或恢复会话。"

                        else ->
                            "本次生成失败。重试会沿用原发送标识，也可以保留输入后修改。"
                    },
                    style = COUITheme.textStyles.description,
                    color = COUITheme.colorScheme.error,
                )
                Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    if (state.sendOutcomeUnknown || state.session?.status != "ready") {
                        TextButton(
                            text = if (state.session?.status == "ready") "核对状态" else "恢复会话",
                            onClick = if (state.session?.status == "ready") onReconcile else onRecover,
                            enabled = !state.recovering,
                            minWidth = ButtonDefaults.MinWidthSmall,
                        )
                    } else {
                        TextButton(
                            text = "编辑消息",
                            onClick = onDiscardRetry,
                            enabled = !state.recovering,
                            minWidth = ButtonDefaults.MinWidthSmall,
                        )
                    }
                    Button(
                        onClick = onRetry,
                        enabled = !state.recovering,
                        minWidth = ButtonDefaults.MinWidthSmall,
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Icon(
                            COUIIcons.Reset,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                        )
                        Text("重试", modifier = Modifier.padding(start = 5.dp))
                    }
                }
            }
        } else if (state.pendingUserMessage == null && state.sendOutcomeUnknown) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (state.session?.status == "ready") {
                        "上一次发送结果尚未核对，确认前不会重复发送。"
                    } else {
                        "上一次发送仍处于待处理状态，可以放弃这轮并恢复会话。"
                    },
                    modifier = Modifier.weight(1f),
                    style = COUITheme.textStyles.description,
                    color = COUITheme.colorScheme.error,
                )
                LoadingButton(
                    text = if (state.session?.status == "ready") "核对结果" else "恢复会话",
                    onClick = if (state.session?.status == "ready") onReconcile else onRecover,
                    isLoading = state.recovering,
                    enabled = !state.sending && !state.recovering,
                    minWidth = ButtonDefaults.MinWidthSmall,
                )
            }
        } else if (state.session?.status != "ready") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "上一次生成没有正常结束，恢复后可以继续发送。",
                    modifier = Modifier.weight(1f),
                    style = COUITheme.textStyles.description,
                    color = COUITheme.colorScheme.error,
                )
                LoadingButton(
                    text = "恢复会话",
                    onClick = onRecover,
                    isLoading = state.recovering,
                    enabled = !state.sending && !state.recovering,
                    minWidth = ButtonDefaults.MinWidthSmall,
                )
            }
        }
}
