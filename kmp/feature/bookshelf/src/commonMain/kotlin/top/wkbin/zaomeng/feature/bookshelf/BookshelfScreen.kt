package top.wkbin.zaomeng.feature.bookshelf

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardDefaults
import io.github.suqi8.coui.kmp.basic.CircularProgressIndicator
import io.github.suqi8.coui.kmp.basic.EmptyState
import io.github.suqi8.coui.kmp.basic.EmptyStateSizeType
import io.github.suqi8.coui.kmp.basic.FloatingActionButton
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.LinearProgressIndicator
import io.github.suqi8.coui.kmp.basic.TagView
import io.github.suqi8.coui.kmp.basic.TagViewDefaults
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Add
import io.github.suqi8.coui.kmp.theme.COUITheme
import top.wkbin.zaomeng.backend.BackendState
import top.wkbin.zaomeng.data.api.RunManifestDto
import top.wkbin.zaomeng.platform.formatOneDecimal
import top.wkbin.zaomeng.ui.coui.CouiMotion.couiContentFade
import top.wkbin.zaomeng.ui.coui.CouiPageScaffold
import top.wkbin.zaomeng.ui.coui.couiPageScroll
import top.wkbin.zaomeng.ui.coui.isWideWindow
import top.wkbin.zaomeng.ui.format.toLocalDateTimeDisplay
import top.wkbin.zaomeng.ui.theme.AppDimens

@Composable
fun BookshelfScreen(
    viewModel: BookshelfViewModel,
    onImport: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRun: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    // NavHost retains this destination while import/detail is displayed. Refresh the
    // list when it returns to the foreground so a new book appears immediately.
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshWhenResumed()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    CouiPageScaffold(
        title = "书架",
        modifier = modifier,
        onBack = null,
        wide = isWideWindow(),
        floatingActionButton = {
            FloatingActionButton(
                onClick = onImport,
                visible = state.backendState is BackendState.Ready && (state.loadingRuns || state.runs.isNotEmpty()),
            ) {
                Icon(COUIIcons.Add, contentDescription = "导入书卷")
            }
        },
    ) { scope ->
        AnimatedContent(
            targetState = state.backendState is BackendState.Ready,
            transitionSpec = { couiContentFade() },
            label = "backendReadyTransition",
        ) { backendReady ->
            if (backendReady) {
                ReadyBookshelf(
                    state = state,
                    onRefresh = viewModel::refresh,
                    onStopAll = viewModel::stopRunningTasks,
                    onResumeRecovered = viewModel::resumeRecoveredRun,
                    onDismissRecovered = viewModel::dismissRecoveredRun,
                    onDismissError = viewModel::dismissError,
                    onOpenSettings = onOpenSettings,
                    onImport = onImport,
                    onOpenRun = onOpenRun,
                    contentPadding = scope.contentPadding,
                    modifier = Modifier.couiPageScroll(scope),
                )
            } else {
                when (val backendState = state.backendState) {
                    BackendState.Idle -> BackendLoading(
                        message = "正在准备本地故事工坊…",
                        modifier = Modifier.padding(scope.contentPadding),
                    )

                    is BackendState.Starting -> BackendLoading(
                        message = backendState.message,
                        modifier = Modifier.padding(scope.contentPadding),
                    )

                    is BackendState.Failed -> BackendFailure(
                        message = backendState.message,
                        onRetry = viewModel::retryBackend,
                        modifier = Modifier.padding(scope.contentPadding),
                    )

                    is BackendState.Ready -> Unit
                }
            }
        }
    }
}

@Composable
private fun ReadyBookshelf(
    state: BookshelfUiState,
    onRefresh: () -> Unit,
    onStopAll: () -> Unit,
    onResumeRecovered: (String) -> Unit,
    onDismissRecovered: (String) -> Unit,
    onDismissError: () -> Unit,
    onOpenSettings: () -> Unit,
    onImport: () -> Unit,
    onOpenRun: (String) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val visibleRuns = state.runs
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 320.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = AppDimens.screenPadding,
            top = contentPadding.calculateTopPadding() + AppDimens.screenPadding,
            end = AppDimens.screenPadding,
            bottom = contentPadding.calculateBottomPadding() + 104.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing),
        horizontalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing),
    ) {
        val activeRuns = state.runs.filter { it.status == "running" }
        if (state.modelConfigured == false) {
            item(span = { GridItemSpan(maxLineSpan) }) { ModelRequiredCard(onOpenSettings) }
        }
        if (activeRuns.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ActiveDistillationTasksCard(
                    runs = activeRuns,
                    stopping = state.stoppingTasks,
                    onStopAll = onStopAll,
                )
            }
        }
        if (state.recoveredRuns.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                RecoveredDistillationCard(
                    runs = state.recoveredRuns,
                    resumingRunId = state.resumingRecoveredRunId,
                    onResume = onResumeRecovered,
                    onDismiss = onDismissRecovered,
                )
            }
        }

        if (state.error.isNotBlank()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ErrorCard(
                    message = state.error,
                    onRetry = onRefresh,
                    onDismiss = onDismissError,
                )
            }
        }

        if (state.loadingRuns) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(size = 40.dp)
                        Spacer(Modifier.height(12.dp))
                        Text("正在整理书架…", color = COUITheme.colorScheme.labelSecondary)
                    }
                }
            }
        } else if (state.runs.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { EmptyBookshelf(onImport) }
        } else {
            items(visibleRuns, key = RunManifestDto::runId) { run ->
                RunCard(run = run, onClick = { onOpenRun(run.runId) })
            }
        }
    }
}

@Composable
private fun RecoveredDistillationCard(
    runs: List<RunManifestDto>,
    resumingRunId: String,
    onResume: (String) -> Unit,
    onDismiss: (String) -> Unit,
) {
    Card(
        colors = CardDefaults.defaultColors(
            color = COUITheme.colorScheme.containerViolet,
            contentColor = COUITheme.colorScheme.violet,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "上次蒸馏被中断",
                style = COUITheme.textStyles.headlineXS,
                fontWeight = FontWeight.SemiBold,
                color = COUITheme.colorScheme.violet,
            )
            Text(
                "进程退出导致任务停止，已有进度仍然保留；可以从未完成人物继续蒸馏。",
                style = COUITheme.textStyles.bodyXS,
                color = COUITheme.colorScheme.violet,
            )
            runs.take(3).forEach { run ->
                val resuming = resumingRunId == run.runId
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = run.title,
                        modifier = Modifier.weight(1f),
                        style = COUITheme.textStyles.bodyM,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = COUITheme.colorScheme.violet,
                    )
                    TextButton(
                        text = "忽略",
                        onClick = { onDismiss(run.runId) },
                        enabled = resumingRunId.isBlank(),
                    )
                    Button(
                        onClick = { onResume(run.runId) },
                        enabled = resumingRunId.isBlank(),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        if (resuming) {
                            CircularProgressIndicator(strokeWidth = 2.dp, size = 16.dp)
                        } else {
                            Text("继续蒸馏", style = COUITheme.textStyles.buttonM)
                        }
                    }
                }
            }
            if (runs.size > 3) {
                Text(
                    "另有 ${runs.size - 3} 本可续跑书卷",
                    style = COUITheme.textStyles.bodyXS,
                    color = COUITheme.colorScheme.violet,
                )
            }
        }
    }
}

@Composable
private fun ModelRequiredCard(onOpenSettings: () -> Unit) {
    Card(
        colors = CardDefaults.defaultColors(color = COUITheme.colorScheme.cardBackground),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = AppDimens.cardPadding, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(AppDimens.iconTextGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("尚未配置模型", style = COUITheme.textStyles.headlineXS)
                Text(
                    "配置后可提取人物并开始聊天。",
                    style = COUITheme.textStyles.bodyXS,
                    color = COUITheme.colorScheme.labelSecondary,
                )
            }
            TextButton(
                text = "配置模型",
                onClick = onOpenSettings,
                colors = ButtonDefaults.textButtonColors(textColor = COUITheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun ActiveDistillationTasksCard(
    runs: List<RunManifestDto>,
    stopping: Boolean,
    onStopAll: () -> Unit,
) {
    Card(
        colors = CardDefaults.defaultColors(
            color = COUITheme.colorScheme.containerBlue,
            contentColor = COUITheme.colorScheme.primaryText,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "后台蒸馏任务",
                style = COUITheme.textStyles.headlineXS,
                fontWeight = FontWeight.SemiBold,
                color = COUITheme.colorScheme.primaryText,
            )
            Text(
                "${runs.size} 个任务正在手机后台运行；可离开应用，进度会显示在通知栏。",
                color = COUITheme.colorScheme.primaryText,
            )
            runs.take(3).forEach { run ->
                Text(
                    "• ${run.title} · ${run.progress.message.ifBlank { "正在蒸馏" }}",
                    style = COUITheme.textStyles.bodyXS,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (runs.size > 3) Text("另有 ${runs.size - 3} 个任务", style = COUITheme.textStyles.bodyXS)
            TextButton(
                text = if (stopping) "正在停止…" else "停止全部蒸馏",
                onClick = onStopAll,
                enabled = !stopping,
            )
        }
    }
}

@Composable
private fun RunCard(run: RunManifestDto, onClick: () -> Unit) {
    val totalCharacters = maxOf(run.progress.totalCharacters, run.lockedCharacters.size)
    val completedCharacters = maxOf(run.progress.completedCount, run.availableCharacters.size)
    val progress = if (totalCharacters > 0) {
        (completedCharacters.toFloat() / totalCharacters).coerceIn(0f, 1f)
    } else {
        null
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.defaultColors(color = COUITheme.colorScheme.cardBackground),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = run.title,
                        style = COUITheme.textStyles.headlineXS,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (run.updatedAt.isNotBlank()) {
                        Text(
                            text = "更新于 ${run.updatedAt.toLocalDateTimeDisplay("时间未记录")}",
                            style = COUITheme.textStyles.bodyXS,
                            color = COUITheme.colorScheme.labelSecondary,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (run.betaFeature?.kind == "cross_book_crossover") {
                        TagView(
                            text = "Beta 共演",
                            colors = TagViewDefaults.tagViewColors(
                                containerColor = COUITheme.colorScheme.containerViolet,
                                contentColor = COUITheme.colorScheme.violet,
                            ),
                        )
                    }
                    BookStatusTag(run.status)
                }
            }

            if (run.status == "running") {
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Text(
                text = if (run.betaFeature?.kind == "cross_book_crossover") {
                    "独立人物快照空间，不会改动来源书卷。"
                } else {
                    run.progress.message.ifBlank { statusDescription(run.status) }
                },
                style = COUITheme.textStyles.bodyXS,
                color = COUITheme.colorScheme.labelSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            run.novelSources.lastOrNull()?.sourceName?.takeIf(String::isNotBlank)?.let { sourceName ->
                Text(
                    text = if (run.novelSources.size > 1) {
                        "当前来源：$sourceName · 共 ${run.novelSources.size} 份正文"
                    } else {
                        "来源：$sourceName"
                    },
                    style = COUITheme.textStyles.bodyXS,
                    color = COUITheme.colorScheme.labelSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = when {
                        run.status == "running" && totalCharacters > 0 -> "$completedCharacters / $totalCharacters 位人物"
                        run.availableCharacters.isNotEmpty() -> "${run.availableCharacters.size} 位人物"
                        else -> "等待人物资料"
                    },
                    style = COUITheme.textStyles.bodyXS,
                    color = COUITheme.colorScheme.primary,
                )
                run.novelSources.firstOrNull()?.charCount?.takeIf { it > 0 }?.let { count ->
                    Text(
                        text = "${count.formatCount()} 字",
                        style = COUITheme.textStyles.bodyXS,
                        color = COUITheme.colorScheme.labelSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun BookStatusTag(status: String) {
    val ready = status == "ready"
    val failed = status == "failed"
    val container = when {
        ready -> COUITheme.colorScheme.containerBlue
        failed -> COUITheme.colorScheme.containerRed
        else -> COUITheme.colorScheme.containerViolet
    }
    val content = when {
        ready -> COUITheme.colorScheme.blue
        failed -> COUITheme.colorScheme.red
        else -> COUITheme.colorScheme.violet
    }
    TagView(
        text = statusLabel(status),
        colors = TagViewDefaults.tagViewColors(containerColor = container, contentColor = content),
    )
}

@Composable
private fun EmptyBookshelf(onImport: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = COUITheme.colorScheme.cardBackground),
    ) {
        EmptyState(
            title = "书架还是空的",
            subtitle = "导入小说、造梦书卷包或 SillyTavern 角色卡。",
            actionText = "导入书卷",
            onActionClick = onImport,
            sizeType = EmptyStateSizeType.Small,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        )
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.defaultColors(
            color = COUITheme.colorScheme.containerRed,
            contentColor = COUITheme.colorScheme.red,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(message, color = COUITheme.colorScheme.red)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text("重试", style = COUITheme.textStyles.buttonM)
                }
                TextButton(
                    text = "忽略",
                    onClick = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun BackendLoading(message: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(size = 40.dp)
            Spacer(Modifier.height(14.dp))
            Text(message, style = COUITheme.textStyles.bodyXL)
            Spacer(Modifier.height(6.dp))
            Text(
                "首次启动需要准备本地 Ktor 服务",
                style = COUITheme.textStyles.bodyXS,
                color = COUITheme.colorScheme.labelSecondary,
            )
        }
    }
}

@Composable
private fun BackendFailure(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Card(
            colors = CardDefaults.defaultColors(
                color = COUITheme.colorScheme.containerRed,
                contentColor = COUITheme.colorScheme.red,
            ),
        ) {
            Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "本地服务没有启动成功",
                    style = COUITheme.textStyles.headlineM,
                    fontWeight = FontWeight.SemiBold,
                    color = COUITheme.colorScheme.red,
                )
                Text(message, color = COUITheme.colorScheme.red)
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text("重新启动", style = COUITheme.textStyles.buttonM)
                }
            }
        }
    }
}

private fun statusLabel(status: String): String = when (status) {
    "ready" -> "可使用"
    "running" -> "蒸馏中"
    "draft" -> "待蒸馏"
    "failed" -> "失败"
    "stopped" -> "已停止"
    else -> status.ifBlank { "未知" }
}

private fun statusDescription(status: String): String = when (status) {
    "ready" -> "人物资料已经可以校对，也可以开始新的会话。"
    "running" -> "人物和关系正在这台手机上逐步整理。"
    "draft" -> "正文已经导入，可以打开书卷开始蒸馏。"
    "failed" -> "这次处理没有完成，可以打开书卷查看详情。"
    "stopped" -> "蒸馏已经停止，已有结果仍会保留。"
    else -> "打开书卷查看当前状态。"
}

private fun Int.formatCount(): String = when {
    this >= 10_000 -> "${formatOneDecimal(this / 10_000.0)}万"
    else -> toString()
}
