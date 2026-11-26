package top.wkbin.zaomeng.feature.redistill

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardDefaults
import io.github.suqi8.coui.kmp.basic.Checkbox
import io.github.suqi8.coui.kmp.basic.CircularProgressIndicator
import io.github.suqi8.coui.kmp.basic.DropdownImpl
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.ListPopupColumn
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Create
import io.github.suqi8.coui.kmp.icon.extended.UploadCloud
import io.github.suqi8.coui.kmp.overlay.OverlayListPopup
import io.github.suqi8.coui.kmp.theme.COUITheme
import org.koin.compose.koinInject
import top.wkbin.zaomeng.data.api.RedistillSegmentDto
import top.wkbin.zaomeng.platform.DistillationForeground
import top.wkbin.zaomeng.platform.formatOneDecimal
import top.wkbin.zaomeng.platform.rememberDocumentPicker
import top.wkbin.zaomeng.platform.rememberNotificationPermissionRequester
import top.wkbin.zaomeng.ui.coui.CouiAlertDialog
import top.wkbin.zaomeng.ui.coui.CouiPageScaffold
import top.wkbin.zaomeng.ui.coui.CouiTextField
import top.wkbin.zaomeng.ui.coui.CouiTopBarStyle
import top.wkbin.zaomeng.ui.coui.couiPageScroll
import top.wkbin.zaomeng.ui.coui.isWideWindow

@Composable
fun RedistillScreen(
    viewModel: RedistillViewModel,
    onBack: () -> Unit,
    onStarted: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showEstimateConfirmation by remember { mutableStateOf(false) }
    val distillationForeground: DistillationForeground = koinInject()
    val documentPicker = rememberDocumentPicker { name, bytes ->
        viewModel.loadDocument(name, bytes)
    }
    val requestNotificationPermission = rememberNotificationPermissionRequester {
        viewModel.submit()
    }

    fun submitRedistill() {
        if (distillationForeground.hasNotificationPermission()) {
            viewModel.submit()
        } else {
            requestNotificationPermission()
        }
    }

    fun requestRedistill() {
        if (state.samplingPlan != null) {
            showEstimateConfirmation = true
        } else {
            submitRedistill()
        }
    }

    LaunchedEffect(state.completed) {
        if (state.completed) {
            onStarted()
            viewModel.consumeCompleted()
        }
    }

    LaunchedEffect(state.samplingPlan) {
        if (state.samplingPlan == null) showEstimateConfirmation = false
    }

    CouiPageScaffold(
        title = "继续蒸馏",
        topBarStyle = CouiTopBarStyle.Small,
        onBack = onBack,
        wide = isWideWindow(),
    ) { scope ->
        if (showEstimateConfirmation) state.samplingPlan?.let { plan ->
            CouiAlertDialog(
                onDismissRequest = { showEstimateConfirmation = false },
                title = "确认开始本轮蒸馏",
                confirmText = "确认开始",
                onConfirm = {
                    showEstimateConfirmation = false
                    submitRedistill()
                },
                dismissText = "返回调整",
                onDismiss = { showEstimateConfirmation = false },
                body = {
                    Text(
                        "本轮预计调用约 ${plan.totalCalls} 次模型，消耗 ${plan.tokenLow.readableCount()}–${plan.tokenHigh.readableCount()} tokens，预计耗时 ${plan.timeLowSeconds.readableDuration()}–${plan.timeHighSeconds.readableDuration()}。",
                    )
                },
            )
        }
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(scope.contentPadding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@CouiPageScaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(scope.contentPadding).couiPageScroll(scope),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text(
                    "可以沿用当前正文，换入新的 TXT 书段，或从原文中选择推荐片段。已有资料会作为基线继续补充。",
                    color = COUITheme.colorScheme.labelSecondary,
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CouiTextField(
                        value = state.characters,
                        onValueChange = viewModel::updateCharacters,
                        modifier = Modifier.fillMaxWidth(),
                        label = "本轮人物",
                        singleLine = false,
                        minLines = 2,
                    )
                    Text(
                        "可新增人物，用逗号、顿号或换行分隔。",
                        style = COUITheme.textStyles.bodyXS,
                        color = COUITheme.colorScheme.labelSecondary,
                    )
                }
            }
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("换入新书段", style = COUITheme.textStyles.headlineXS)
                        Button(
                            onClick = { documentPicker() },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.submitting && !state.readingFile,
                            colors = ButtonDefaults.buttonColors(),
                        ) {
                            if (state.readingFile) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), size = 18.dp, strokeWidth = 2.dp)
                            } else {
                                Icon(COUIIcons.UploadCloud, contentDescription = null)
                            }
                            Spacer(Modifier.size(8.dp))
                            Text(
                                when {
                                    state.readingFile -> "正在读取…"
                                    state.fileName.isBlank() -> "选择 TXT"
                                    else -> state.fileName
                                },
                            )
                        }
                        if (state.fileName.isNotBlank()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${state.sourceCharCount.readableCount()} 字 · ${state.sourceSentenceCount} 句",
                                    Modifier.weight(1f),
                                )
                                TextButton(
                                    text = "移除",
                                    onClick = viewModel::clearFile,
                                    minWidth = 0.dp,
                                    colors = ButtonDefaults.textButtonColors(textColor = COUITheme.colorScheme.error),
                                )
                            }
                        }
                    }
                }
            }
            item {
                RecommendationHeader(state = state, viewModel = viewModel)
            }
            state.suggestions.forEach { group ->
                item(key = "recommendation-${group.character}") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(group.character, style = COUITheme.textStyles.headlineXS, fontWeight = FontWeight.SemiBold)
                        if (group.suggestions.weakFieldLabels.isNotEmpty()) {
                            Text(
                                "建议优先补充：${group.suggestions.weakFieldLabels.joinToString("、")}",
                                style = COUITheme.textStyles.bodyXS,
                                color = COUITheme.colorScheme.labelSecondary,
                            )
                        }
                    }
                }
                items(
                    group.suggestions.segments,
                    key = { segment -> redistillSegmentKey(group.character, segment.segmentId) },
                ) { segment ->
                    val key = redistillSegmentKey(group.character, segment.segmentId)
                    SegmentCard(
                        segment = segment,
                        selected = key in state.selectedSegmentKeys,
                        onSelect = { viewModel.selectSegment(group.character, segment.segmentId) },
                    )
                }
            }
            if (state.suggestions.isNotEmpty()) {
                item {
                    Text(
                        "已选择 ${state.selectedSegments.size} 段；使用推荐片段时，本轮每位人物至少需要一段。",
                        style = COUITheme.textStyles.bodyXS,
                        color = COUITheme.colorScheme.labelSecondary,
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CouiTextField(
                        value = state.maxSentences,
                        onValueChange = viewModel::updateMaxSentences,
                        modifier = Modifier.weight(1f),
                        label = "每批句数",
                    )
                    CouiTextField(
                        value = state.maxChars,
                        onValueChange = viewModel::updateMaxChars,
                        modifier = Modifier.weight(1f),
                        label = "每批字符",
                    )
                }
            }
            item {
                RedistillSamplingPlanCard(
                    state = state,
                    onRetry = viewModel::refreshSamplingEstimate,
                )
            }
            if (state.error.isNotBlank()) {
                item { Text(state.error, color = COUITheme.colorScheme.error) }
            }
            item {
                Button(
                    onClick = ::requestRedistill,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.submitting,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    if (state.submitting) CircularProgressIndicator(modifier = Modifier.size(20.dp), size = 20.dp, strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text(if (state.submitting) "正在启动…" else "开始这一轮蒸馏")
                }
            }
        }
    }
}

@Composable
private fun RedistillSamplingPlanCard(
    state: RedistillUiState,
    onRetry: () -> Unit,
) {
    when {
        state.estimatingSampling -> Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), size = 18.dp, strokeWidth = 2.dp)
            Text("正在估算本轮蒸馏…", color = COUITheme.colorScheme.labelSecondary)
        }

        state.samplingPlan != null -> state.samplingPlan.let { plan ->
            Card(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("本轮蒸馏预估", style = COUITheme.textStyles.bodyM)
                    Text(
                        "约 ${plan.totalCalls} 次模型调用 · ${plan.tokenLow.readableCount()}–${plan.tokenHigh.readableCount()} tokens",
                        style = COUITheme.textStyles.bodyM,
                    )
                    Text(
                        "预计 ${plan.timeLowSeconds.readableDuration()}–${plan.timeHighSeconds.readableDuration()}；推荐 ${plan.suggestedMaxSentences} 句 / ${plan.suggestedMaxChars.readableCount()} 字取样",
                        style = COUITheme.textStyles.bodyXS,
                        color = COUITheme.colorScheme.labelSecondary,
                    )
                }
            }
        }

        state.samplingEstimateError.isNotBlank() -> Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                state.samplingEstimateError,
                modifier = Modifier.weight(1f),
                style = COUITheme.textStyles.bodyXS,
                color = COUITheme.colorScheme.error,
            )
            TextButton(text = "重试", onClick = onRetry, minWidth = 0.dp)
        }
    }
}

@Composable
private fun RecommendationHeader(state: RedistillUiState, viewModel: RedistillViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val characters = parseRedistillCharacters(state.characters)
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("从原文推荐片段", style = COUITheme.textStyles.headlineXS)
            Box(Modifier.fillMaxWidth()) {
                Button(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth(),
                    minWidth = 0.dp,
                    colors = ButtonDefaults.buttonColors(),
                ) {
                    Text(
                        if (state.recommendationCharacters.isEmpty()) "选择人物"
                        else "已选 ${state.recommendationCharacters.size}/${characters.size} 人",
                        Modifier.weight(1f),
                    )
                }
                OverlayListPopup(show = expanded, onDismissRequest = { expanded = false }) {
                    ListPopupColumn {
                        characters.forEachIndexed { index, character ->
                            DropdownImpl(
                                text = character,
                                optionSize = characters.size,
                                isSelected = character in state.recommendationCharacters,
                                index = index,
                                // 多选：勾选后保持菜单打开。
                                onSelectedIndexChange = {
                                    viewModel.toggleRecommendationCharacter(character)
                                },
                            )
                        }
                    }
                }
            }
            Button(
                onClick = viewModel::recommendSegments,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.recommendationCharacters.isNotEmpty() && !state.recommending,
                colors = ButtonDefaults.buttonColors(),
            ) {
                if (state.recommending) CircularProgressIndicator(modifier = Modifier.size(18.dp), size = 18.dp, strokeWidth = 2.dp)
                else Icon(COUIIcons.Create, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(if (state.recommending) "正在挑选…" else "为选中人物分别推荐三段")
            }
        }
    }
}

@Composable
private fun SegmentCard(segment: RedistillSegmentDto, selected: Boolean, onSelect: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = if (selected) COUITheme.colorScheme.containerThemeHalftone
            else COUITheme.colorScheme.backgroundWithCard,
        ),
        onClick = onSelect,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(state = ToggleableState(selected), onClick = null)
                Text(
                    "第 ${segment.startSentence}–${segment.endSentence} 句",
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(segment.preview)
            Text(
                listOf(segment.reason, segment.estimatedFieldLabels.joinToString("、"))
                    .filter(String::isNotBlank)
                    .joinToString(" · "),
                style = COUITheme.textStyles.bodyXS,
                color = COUITheme.colorScheme.labelSecondary,
            )
        }
    }
}

private fun Int.readableCount(): String = when {
    this >= 10_000 -> "${formatOneDecimal(this / 10_000.0)} 万"
    else -> toString()
}

private fun Int.readableDuration(): String {
    val minutes = coerceAtLeast(0) / 60
    val seconds = coerceAtLeast(0) % 60
    return when {
        minutes == 0 -> "${seconds}秒"
        seconds == 0 || minutes >= 10 -> "${minutes}分钟"
        else -> "${minutes}分${seconds}秒"
    }
}
