package top.wkbin.zaomeng.feature.bookshelf

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wkbin.zaomeng.backend.BackendState
import top.wkbin.zaomeng.data.api.RunManifestDto
import top.wkbin.zaomeng.ui.theme.AppDimens
import top.wkbin.zaomeng.ui.format.toLocalDateTimeDisplay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(
    viewModel: BookshelfViewModel,
    onImport: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCards: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenCrossover: () -> Unit,
    onOpenRun: (String) -> Unit,
    showTopBarActions: Boolean = true,
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

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "造梦",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                actions = {
                    if (showTopBarActions) {
                        IconButton(onClick = onOpenCards) {
                            Icon(Icons.Outlined.CollectionsBookmark, contentDescription = "创作资料库")
                        }
                        IconButton(onClick = onOpenSessions) {
                            Icon(Icons.Outlined.Forum, contentDescription = "查看会话")
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Outlined.Settings, contentDescription = "模型设置")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.backendState is BackendState.Ready) {
                ExtendedFloatingActionButton(
                    onClick = onImport,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("导入小说") },
                )
            }
        },
    ) { innerPadding ->
        AnimatedContent(
            targetState = state.backendState is BackendState.Ready,
            transitionSpec = {
                fadeIn(animationSpec = tween(durationMillis = 260)) togetherWith
                    fadeOut(animationSpec = tween(durationMillis = 180))
            },
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
                    onOpenCrossover = onOpenCrossover,
                    onOpenRun = onOpenRun,
                    modifier = Modifier.padding(innerPadding),
                )
            } else {
                when (val backendState = state.backendState) {
                    BackendState.Idle -> BackendLoading(
                        message = "正在准备本地故事工坊…",
                        modifier = Modifier.padding(innerPadding),
                    )

                    is BackendState.Starting -> BackendLoading(
                        message = backendState.message,
                        modifier = Modifier.padding(innerPadding),
                    )

                    is BackendState.Failed -> BackendFailure(
                        message = backendState.message,
                        onRetry = viewModel::retryBackend,
                        modifier = Modifier.padding(innerPadding),
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
    onOpenCrossover: () -> Unit,
    onOpenRun: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visibleRuns = state.runs
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = AppDimens.screenPadding,
            top = AppDimens.screenPadding,
            end = AppDimens.screenPadding,
            bottom = 104.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing),
    ) {
        val activeRuns = state.runs.filter { it.status == "running" }
        if (state.modelConfigured == false) {
            item { ModelRequiredCard(onOpenSettings) }
        }
        if (state.modelConfigured == true) {
            item { ActiveModelCard(state.activeModelLabel, onOpenSettings) }
        }
        if (state.runs.count { it.betaFeature == null && it.availableCharacters.isNotEmpty() } >= 2) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenCrossover),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                ) {
                    Box(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 42.dp, bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("跨书卷共演", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text("从不同书卷复制人物快照，建立不会改动原书卷的独立共演空间。测试功能的角色表现和剧情连续性可能不稳定。", style = MaterialTheme.typography.bodySmall)
                        }
                        BetaCornerBadge(Modifier.align(Alignment.TopEnd))
                    }
                }
            }
        }
        if (activeRuns.isNotEmpty()) {
            item {
                ActiveDistillationTasksCard(
                    runs = activeRuns,
                    stopping = state.stoppingTasks,
                    onStopAll = onStopAll,
                )
            }
        }
        if (state.recoveredRuns.isNotEmpty()) {
            item {
                RecoveredDistillationCard(
                    runs = state.recoveredRuns,
                    resumingRunId = state.resumingRecoveredRunId,
                    onResume = onResumeRecovered,
                    onDismiss = onDismissRecovered,
                )
            }
        }

        if (state.error.isNotBlank()) {
            item {
                ErrorCard(
                    message = state.error,
                    onRetry = onRefresh,
                    onDismiss = onDismissError,
                )
            }
        }

        if (state.loadingRuns) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("正在整理书架…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else if (state.runs.isEmpty()) {
            item { EmptyBookshelf(onImport) }
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
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "上次蒸馏被中断",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                "进程退出导致任务停止，已有进度仍然保留；可以从未完成人物继续蒸馏。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
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
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    TextButton(
                        onClick = { onDismiss(run.runId) },
                        enabled = resumingRunId.isBlank(),
                    ) {
                        Text("忽略")
                    }
                    Button(
                        onClick = { onResume(run.runId) },
                        enabled = resumingRunId.isBlank(),
                    ) {
                        if (resuming) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("继续蒸馏")
                        }
                    }
                }
            }
            if (runs.size > 3) {
                Text(
                    "另有 ${runs.size - 3} 本可续跑书卷",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

@Composable
private fun BetaCornerBadge(modifier: Modifier = Modifier) {
    val badgeColor = MaterialTheme.colorScheme.tertiary
    val textColor = MaterialTheme.colorScheme.onTertiary
    Box(modifier.size(44.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            drawPath(
                Path().apply {
                    moveTo(size.width, 0f)
                    lineTo(size.width, size.height)
                    lineTo(0f, 0f)
                    close()
                },
                color = badgeColor,
            )
        }
        Text(
            text = "Beta",
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 3.dp),
            color = textColor,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ActiveModelCard(modelLabel: String, onOpenSettings: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Settings, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("当前模型", style = MaterialTheme.typography.labelMedium)
                Text(
                    modelLabel.ifBlank { "已配置" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onOpenSettings) { Text("切换") }
        }
    }
}

@Composable
private fun ModelRequiredCard(onOpenSettings: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "还没有配置模型",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Text(
                "蒸馏人物、AI 校对和聊天需要先连接一个模型。导入与浏览本地书卷仍可使用。",
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Button(onClick = onOpenSettings) { Text("现在配置模型") }
        }
    }
}

@Composable
private fun ActiveDistillationTasksCard(
    runs: List<RunManifestDto>,
    stopping: Boolean,
    onStopAll: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("后台蒸馏任务", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "${runs.size} 个任务正在手机后台运行；可离开应用，进度会显示在通知栏。",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            runs.take(3).forEach { run ->
                Text(
                    "• ${run.title} · ${run.progress.message.ifBlank { "正在蒸馏" }}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (runs.size > 3) Text("另有 ${runs.size - 3} 个任务", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onStopAll, enabled = !stopping) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (stopping) "正在停止…" else "停止全部蒸馏")
            }
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
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = run.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (run.updatedAt.isNotBlank()) {
                        Text(
                            text = "更新于 ${run.updatedAt.toLocalDateTimeDisplay("时间未记录")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (run.betaFeature?.kind == "cross_book_crossover") {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            shape = RoundedCornerShape(999.dp),
                        ) {
                            Text("Beta 共演", Modifier.padding(horizontal = 9.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    StatusPill(run.status)
                }
            }

            if (run.status == "running") {
                if (progress == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
            }

            Text(
                text = if (run.betaFeature?.kind == "cross_book_crossover") {
                    "独立人物快照空间，不会改动来源书卷。"
                } else {
                    run.progress.message.ifBlank { statusDescription(run.status) }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = if (totalCharacters > 0) "$completedCharacters / $totalCharacters 位人物" else "等待人物资料",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                run.novelSources.firstOrNull()?.charCount?.takeIf { it > 0 }?.let { count ->
                    Text(
                        text = "${count.formatCount()} 字",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusPill(status: String) {
    val ready = status == "ready"
    val failed = status == "failed"
    val container = when {
        ready -> MaterialTheme.colorScheme.primaryContainer
        failed -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val content = when {
        ready -> MaterialTheme.colorScheme.onPrimaryContainer
        failed -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(999.dp)) {
        Text(
            text = statusLabel(status),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun EmptyBookshelf(onImport: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("书架还是空的", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "放入 TXT 正文开始蒸馏人物，或者导入以前导出的书卷包。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onImport) { Text("导入第一本书") }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRetry) { Text("重试") }
                OutlinedButton(onClick = onDismiss) { Text("忽略") }
            }
        }
    }
}

@Composable
private fun BackendLoading(message: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(14.dp))
            Text(message, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                "首次启动需要准备本地 Ktor 服务",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BackendFailure(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "本地服务没有启动成功",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
                Button(onClick = onRetry) { Text("重新启动") }
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
    this >= 10_000 -> "%.1f万".format(this / 10_000f)
    else -> toString()
}
