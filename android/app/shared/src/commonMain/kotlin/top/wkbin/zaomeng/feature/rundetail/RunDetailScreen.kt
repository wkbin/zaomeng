package top.wkbin.zaomeng.feature.rundetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import top.wkbin.zaomeng.ui.theme.AppDimens
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import top.wkbin.zaomeng.platform.DistillationForeground
import top.wkbin.zaomeng.platform.rememberDocumentPicker
import top.wkbin.zaomeng.platform.rememberFileExporter
import top.wkbin.zaomeng.platform.rememberNotificationPermissionRequester
import top.wkbin.zaomeng.platform.rememberOpenExternalUrl
import top.wkbin.zaomeng.ui.graphics.decodeImageBitmap
import top.wkbin.zaomeng.data.api.ExportedRunPackage
import top.wkbin.zaomeng.data.api.NovelSourceDto
import top.wkbin.zaomeng.data.api.OnlineLibrarySourceDto
import top.wkbin.zaomeng.data.api.BetaFeatureDto
import top.wkbin.zaomeng.data.api.PersonaIndexDto
import top.wkbin.zaomeng.data.api.RunManifestDto
import top.wkbin.zaomeng.ui.format.toLocalDateTimeDisplay

private const val AVATAR_CROP_FRAME_FRACTION = 0.8f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunDetailScreen(
    viewModel: RunDetailViewModel,
    onBack: () -> Unit,
    onOpenPersona: (runId: String, character: String) -> Unit,
    onOpenSessions: (runId: String) -> Unit,
    onOpenChapters: (runId: String) -> Unit,
    onOpenRelations: (runId: String) -> Unit,
    onOpenWorldTimeline: (runId: String) -> Unit,
    onOpenRedistill: (runId: String) -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val distillationForeground: DistillationForeground = koinInject()
    var confirmStop by rememberSaveable { mutableStateOf(false) }
    var confirmRedistill by rememberSaveable { mutableStateOf(false) }
    var confirmResume by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var confirmExport by rememberSaveable { mutableStateOf(false) }
    var selectedAvatarPersona by remember { mutableStateOf<PersonaIndexDto?>(null) }
    var avatarCropBytes by remember { mutableStateOf<ByteArray?>(null) }
    val avatarPicker = rememberDocumentPicker { name, bytes ->
        val persona = selectedAvatarPersona
        if (persona != null) avatarCropBytes = bytes
    }
    var resumeAfterPermission by rememberSaveable { mutableStateOf(false) }
    val requestNotificationPermission = rememberNotificationPermissionRequester { granted ->
        if (granted) {
            if (resumeAfterPermission) viewModel.resumeUnfinishedCharacters()
            else viewModel.redistillOriginalCharacters()
        }
        resumeAfterPermission = false
    }
    val fileExporter = rememberFileExporter(
        onSave = { sink -> viewModel.saveExportedPackage(sink) },
        onCancelled = { viewModel.retryExportDestination() },
    )
    fun startOriginalRedistill() {
        resumeAfterPermission = false
        if (distillationForeground.hasNotificationPermission()) {
            viewModel.redistillOriginalCharacters()
        } else {
            requestNotificationPermission()
        }
    }

    fun startResumeDistill() {
        resumeAfterPermission = true
        if (distillationForeground.hasNotificationPermission()) {
            viewModel.resumeUnfinishedCharacters()
            resumeAfterPermission = false
        } else {
            requestNotificationPermission()
        }
    }

    LaunchedEffect(state.exportRequestId) {
        state.exportedPackage?.let { exported ->
            fileExporter(exported.filename, "application/zip")
        }
    }

    LaunchedEffect(state.deleted) {
        if (state.deleted) onDeleted()
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("停止这次蒸馏？") },
            text = { Text("已经完成的人物资料会保留，当前步骤结束后停止。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    viewModel.stop()
                }) { Text("停止蒸馏") }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("继续等待") } },
        )
    }

    if (confirmRedistill) {
        val characterCount = state.run
            ?.let { it.lockedCharacters.ifEmpty { it.availableCharacters }.size }
            ?: 0
        AlertDialog(
            onDismissRequest = { confirmRedistill = false },
            title = { Text("按原人物重新蒸馏？") },
            text = { Text("将沿用当前正文和 $characterCount 位原人物，重新生成资料与关系。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRedistill = false
                    startOriginalRedistill()
                }) { Text("重新开始") }
            },
            dismissButton = { TextButton(onClick = { confirmRedistill = false }) { Text("取消") } },
        )
    }

    if (confirmResume) {
        val characterCount = state.run
            ?.let { run -> run.lockedCharacters.count { character -> character !in run.progress.completedCharacters } }
            ?: 0
        AlertDialog(
            onDismissRequest = { confirmResume = false },
            title = { Text("继续未完成人物") },
            text = { Text("将保留已完成人物，只继续蒸馏剩余 $characterCount 位人物。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmResume = false
                    startResumeDistill()
                }) { Text("继续蒸馏") }
            },
            dismissButton = { TextButton(onClick = { confirmResume = false }) { Text("取消") } },
        )
    }

    if (confirmExport) {
        ExportRunPackageDialog(
            onDismiss = { confirmExport = false },
            onConfirm = { includeDialogue ->
                confirmExport = false
                viewModel.exportRun(includeDialogue)
            },
        )
    }


    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { if (!state.deleting) confirmDelete = false },
            title = { Text("删除整本书卷？") },
            text = { Text("人物资料、关系图和这本书的全部会话都会一起删除，且无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        viewModel.deleteRun()
                    },
                    enabled = !state.deleting,
                ) { Text("确认删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }, enabled = !state.deleting) { Text("取消") }
            },
        )
    }

    selectedAvatarPersona?.let { persona ->
        AlertDialog(
            onDismissRequest = { if (state.updatingAvatar.isBlank()) selectedAvatarPersona = null },
            title = { Text(persona.name) },
            text = {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    PersonaAvatar(
                        bytes = state.avatarBytes[persona.name],
                        name = persona.name,
                        modifier = Modifier.size(144.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { avatarPicker() },
                    enabled = state.updatingAvatar.isBlank(),
                ) { Text(if (state.updatingAvatar == persona.name) "正在保存" else "修改头像") }
            },
            dismissButton = {
                TextButton(onClick = { selectedAvatarPersona = null }, enabled = state.updatingAvatar.isBlank()) {
                    Text("取消")
                }
            },
        )
    }

    avatarCropBytes?.let { bytes ->
        AvatarCropDialog(
            bytes = bytes,
            onDismiss = { avatarCropBytes = null },
            onConfirm = { crop ->
                selectedAvatarPersona?.let { persona ->
                    avatarCropBytes = null
                    viewModel.updatePersonaAvatar(persona.name, bytes, crop)
                }
            },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.run?.title ?: "书卷详情",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = viewModel::refresh,
                        enabled = !state.refreshing && !state.loading,
                    ) {
                        if (state.refreshing) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新详情")
                        }
                    }
                    IconButton(onClick = { confirmExport = true }, enabled = state.run != null && !state.exporting) {
                        Icon(Icons.Default.Download, contentDescription = "导出书卷")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        when {
            state.loading -> DetailLoading(Modifier.padding(innerPadding))
            state.run == null -> DetailFailure(
                message = state.error.ifBlank { "没有找到这份书卷。" },
                onRetry = viewModel::load,
                modifier = Modifier.padding(innerPadding),
            )
            else -> RunDetailContent(
                state = state,
                onDismissNotice = viewModel::dismissNotice,
                onRetryExportDestination = viewModel::retryExportDestination,
                onStop = { confirmStop = true },
                onResume = { confirmResume = true },
                onRedistill = { confirmRedistill = true },
                onExport = { confirmExport = true },
                onOpenPersona = { character -> onOpenPersona(viewModel.runId, character) },
                onAvatarClick = { selectedAvatarPersona = it },
                onOpenSessions = { onOpenSessions(viewModel.runId) },
                onOpenChapters = { onOpenChapters(viewModel.runId) },
                onOpenRelations = { onOpenRelations(viewModel.runId) },
                onOpenWorldTimeline = { onOpenWorldTimeline(viewModel.runId) },
                onOpenRedistill = { onOpenRedistill(viewModel.runId) },
                onDelete = { confirmDelete = true },
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

@Composable
private fun RunDetailContent(
    state: RunDetailUiState,
    onDismissNotice: () -> Unit,
    onRetryExportDestination: () -> Unit,
    onStop: () -> Unit,
    onResume: () -> Unit,
    onRedistill: () -> Unit,
    onExport: () -> Unit,
    onOpenPersona: (String) -> Unit,
    onAvatarClick: (PersonaIndexDto) -> Unit,
    onOpenSessions: () -> Unit,
    onOpenChapters: () -> Unit,
    onOpenRelations: () -> Unit,
    onOpenWorldTimeline: () -> Unit,
    onOpenRedistill: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val run = checkNotNull(state.run)
    var showAllSources by rememberSaveable(run.runId) { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(AppDimens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing),
    ) {
        item { RunHero(run) }

        run.betaFeature?.takeIf { it.kind == "cross_book_crossover" }?.let { beta ->
            item { CrossoverBetaCard(beta) }
        }

        run.importedFrom.onlineLibrary?.takeIf { it.id.isNotBlank() }?.let { source ->
            item { OnlinePackageSourceCard(source) }
        }

        if (state.error.isNotBlank() || state.message.isNotBlank()) {
            item {
                NoticeCard(
                    message = state.error.ifBlank { state.message },
                    error = state.error.isNotBlank(),
                    onDismiss = onDismissNotice,
                    actionLabel = if (state.error.isNotBlank() && state.exportedPackage != null) {
                        "重新选择位置"
                    } else {
                        ""
                    },
                    onAction = onRetryExportDestination,
                )
            }
        }

        item {
            NextActionCard(
                run = run,
                onResume = onResume,
                onOpenRedistill = onOpenRedistill,
                onOpenPersona = onOpenPersona,
                onOpenRelations = onOpenRelations,
                onOpenSessions = onOpenSessions,
            )
        }

        if (state.reviewLoading || state.reviewOverview != null) {
            item {
                BookReviewCard(
                    loading = state.reviewLoading,
                    overview = state.reviewOverview,
                    onOpenPersona = onOpenPersona,
                    onOpenRelations = onOpenRelations,
                    onOpenWorldTimeline = onOpenWorldTimeline,
                )
            }
        }

        item {
            ActionCard(
                run = run,
                stopping = state.stopping,
                redistilling = state.redistilling,
                exporting = state.exporting,
                deleting = state.deleting,
                onStop = onStop,
                onResume = onResume,
                onRedistill = onRedistill,
                onExport = onExport,
                onOpenSessions = onOpenSessions,
                onOpenChapters = onOpenChapters,
                onOpenRelations = onOpenRelations,
                onOpenWorldTimeline = onOpenWorldTimeline,
                onOpenRedistill = onOpenRedistill,
                onDelete = onDelete,
            )
        }

        if (run.novelSources.isNotEmpty()) {
            item {
                SourceHistoryCard(
                    run = run,
                    expanded = showAllSources,
                    onToggleExpanded = { showAllSources = !showAllSources },
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("可用人物", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    text = if (run.availableCharacters.isEmpty()) {
                        if (run.status == "running") "人物资料完成后会陆续出现在这里。" else "目前没有可用的人物资料。"
                    } else {
                        "${run.availableCharacters.size} 位人物可以查看和校对"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (run.artifactIndex.characters.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Text(
                        text = if (run.status == "running") "正在等待第一位人物完成…" else "还没有人物档案。",
                        modifier = Modifier.padding(20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(run.artifactIndex.characters, key = PersonaIndexDto::name) { persona ->
                PersonaCard(
                    persona = persona,
                    avatarBytes = state.avatarBytes[persona.name],
                    onAvatarClick = { onAvatarClick(persona) },
                    onClick = { onOpenPersona(persona.name) },
                )
            }
        }

        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun NextActionCard(
    run: RunManifestDto,
    onResume: () -> Unit,
    onOpenRedistill: () -> Unit,
    onOpenPersona: (String) -> Unit,
    onOpenRelations: () -> Unit,
    onOpenSessions: () -> Unit,
) {
    val action = nextActionFor(run)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("推荐下一步", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(action.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                action.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Button(
                onClick = {
                    when (action.target) {
                        RunNextActionTarget.ResumeDistillation -> onResume()
                        RunNextActionTarget.OpenRedistill -> onOpenRedistill()
                        RunNextActionTarget.OpenPersona -> onOpenPersona(action.character)
                        RunNextActionTarget.OpenRelations -> onOpenRelations()
                        RunNextActionTarget.OpenSessions -> onOpenSessions()
                    }
                },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(action.label)
            }
        }
    }
}

@Composable
private fun BookReviewCard(
    loading: Boolean,
    overview: RunReviewOverview?,
    onOpenPersona: (String) -> Unit,
    onOpenRelations: () -> Unit,
    onOpenWorldTimeline: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("作品体检", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("正在汇总人物、关系与时间线的待校对项…", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                val report = checkNotNull(overview)
                val hasIssues = report.relationConflictCount > 0 || report.timelineWarningCount > 0 ||
                    report.charactersNeedingReview.isNotEmpty()
                if (!hasIssues) {
                    Text("当前没有发现需要优先处理的关系冲突或一致性提醒。", style = MaterialTheme.typography.bodyMedium)
                } else {
                    if (report.charactersNeedingReview.isNotEmpty()) {
                        ReviewActionRow(
                            text = "${report.charactersNeedingReview.size} 位人物资料建议校对",
                            label = "校对 ${report.charactersNeedingReview.first()}",
                            onClick = { onOpenPersona(report.charactersNeedingReview.first()) },
                        )
                    }
                    if (report.relationConflictCount > 0) {
                        ReviewActionRow(
                            text = "${report.relationConflictCount} 组人物关系需要留意",
                            label = "校对关系",
                            onClick = onOpenRelations,
                        )
                    }
                    if (report.timelineWarningCount > 0) {
                        ReviewActionRow(
                            text = "${report.timelineWarningCount} 条故事时间线提醒",
                            label = "查看时间线",
                            onClick = onOpenWorldTimeline,
                        )
                    }
                }
                if (report.checkedCharacterCount > 0) {
                    Text(
                        "已检查 ${report.checkedCharacterCount} 位人物的资料质量。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReviewActionRow(text: String, label: String, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = onClick) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CrossoverBetaCard(beta: BetaFeatureDto) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("跨书卷共演 · Beta", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("这是独立人物快照空间。这里产生的关系、记忆、修正和会话不会写回来源书卷；测试功能的角色表现可能不稳定。", style = MaterialTheme.typography.bodySmall)
            if (beta.worldSetting.isNotBlank()) {
                Text("世界设定：${beta.worldSetting}", style = MaterialTheme.typography.bodySmall)
            }
            if (beta.sourceSnapshots.isNotEmpty()) {
                Text("人物来源：${beta.sourceSnapshots.joinToString(" · ") { it.character }}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun OnlinePackageSourceCard(source: OnlineLibrarySourceDto) {
    val openExternalUrl = rememberOpenExternalUrl()
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("在线书卷包", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                buildList {
                    if (source.title.isNotBlank()) add(source.title)
                    if (source.version.isNotBlank()) add("v${source.version}")
                    if (source.id.isNotBlank()) add(source.id)
                }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (source.downloadUrl.isNotBlank()) {
                TextButton(onClick = {
                    openExternalUrl(source.downloadUrl)
                }) { Text("打开书卷包来源") }
            }
        }
    }
}

@Composable
private fun SourceHistoryCard(
    run: RunManifestDto,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    val sortedSources = run.novelSources.sortedByDescending(NovelSourceDto::timestamp)
    val visibleSources = if (expanded) sortedSources else sortedSources.take(3)
    val currentPath = run.novelPath.trim()

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("正文来源", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "初始正文和后续增量书段都会保留在这里。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            visibleSources.forEachIndexed { index, source ->
                if (index > 0) HorizontalDivider()
                val isCurrent = currentPath.isNotBlank() && source.sourcePath == currentPath
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            source.sourceName.ifBlank { "未命名书页" },
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (isCurrent) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(6.dp),
                            ) {
                                Text(
                                    "当前",
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                    Text(
                        sourceMeta(source),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (sortedSources.size > 3) {
                TextButton(onClick = onToggleExpanded, modifier = Modifier.align(Alignment.End)) {
                    Text(if (expanded) "收起" else "查看全部 ${sortedSources.size} 份")
                }
            }
        }
    }
}

@Composable
private fun RunHero(run: RunManifestDto) {
    val total = maxOf(run.progress.totalCharacters, run.lockedCharacters.size)
    val completed = maxOf(run.progress.completedCount, run.availableCharacters.size)
    val progress = if (total > 0) (completed.toFloat() / total).coerceIn(0f, 1f) else null

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        run.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        run.novelId.ifBlank { run.runId },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(12.dp))
                RunStatus(run.status)
            }

            if (run.status == "running") {
                if (progress == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
            }

            Text(
                text = run.progress.message.ifBlank { run.summary.statusText.ifBlank { statusCopy(run.status) } },
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Metric("人物", if (total > 0) "$completed / $total" else "未开始", Modifier.weight(1f))
                Metric(
                    "关系图",
                    graphLabel(run.progress.graphStatus.ifBlank { run.summary.graphStatus }),
                    Modifier.weight(1f),
                )
                Metric("用时", run.timing.elapsedText.ifBlank { "—" }, Modifier.weight(1f))
            }
            if (run.progress.currentCharacter.isNotBlank()) {
                Text(
                    "正在处理：${run.progress.currentCharacter}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
        )
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ExportRunPackageDialog(
    onDismiss: () -> Unit,
    onConfirm: (Boolean) -> Unit,
) {
    var includeDialogue by rememberSaveable { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出书卷包") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("选择是否把已有聊天会话一起写入小说包。")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Checkbox(
                        checked = includeDialogue,
                        onCheckedChange = { includeDialogue = it },
                    )
                    Text("携带会话记录")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(includeDialogue) }) {
                Text("生成分享包")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun ActionCard(
    run: RunManifestDto,
    stopping: Boolean,
    redistilling: Boolean,
    exporting: Boolean,
    deleting: Boolean,
    onStop: () -> Unit,
    onResume: () -> Unit,
    onRedistill: () -> Unit,
    onExport: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenChapters: () -> Unit,
    onOpenRelations: () -> Unit,
    onOpenWorldTimeline: () -> Unit,
    onOpenRedistill: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("书卷操作", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            Button(onClick = onOpenSessions, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Forum, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("查看会话与开始聊天")
            }

            OutlinedButton(onClick = onOpenChapters, modifier = Modifier.fillMaxWidth()) {
                Text("章节工作台与全书导出")
            }

            HorizontalDivider()
            Text(
                "故事资料",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onOpenRelations, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.People, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("查看与校对人物关系")
            }

            OutlinedButton(onClick = onOpenWorldTimeline, modifier = Modifier.fillMaxWidth()) {
                Text("查看故事时间线与剧情事实")
            }

            HorizontalDivider()
            Text(
                "书卷管理",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = onExport,
                modifier = Modifier.fillMaxWidth(),
                enabled = !exporting,
            ) {
                if (exporting) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Download, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (exporting) "正在打包…" else "导出书卷包")
            }

            val showAdvancedManagement = rememberSaveable(run.runId) { mutableStateOf(false) }
            TextButton(
                onClick = { showAdvancedManagement.value = !showAdvancedManagement.value },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (showAdvancedManagement.value) "收起高级操作" else "更多书卷操作")
            }
            if (showAdvancedManagement.value) {
            TextButton(
                onClick = onOpenRedistill,
                modifier = Modifier.fillMaxWidth(),
                enabled = !redistilling && run.status != "running" &&
                    (run.lockedCharacters.isNotEmpty() || run.availableCharacters.isNotEmpty()),
            ) {
                if (redistilling) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("继续蒸馏或换入新书段")
            }

            val unfinishedCount = run.lockedCharacters.count { it !in run.progress.completedCharacters }
            if (unfinishedCount > 0 && run.status != "running") {
                TextButton(
                    onClick = onResume,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !redistilling,
                ) {
                    if (redistilling) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("继续未完成人物（$unfinishedCount 人）")
                }
            }

            TextButton(
                onClick = onRedistill,
                modifier = Modifier.fillMaxWidth(),
                enabled = !redistilling && run.status != "running" &&
                    (run.lockedCharacters.isNotEmpty() || run.availableCharacters.isNotEmpty()),
            ) {
                Text(if (redistilling) "正在重新开始…" else "直接沿用原人物与正文重跑")
            }

            if (run.status == "running") {
                HorizontalDivider()
                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !stopping && !run.control.stopRequested,
                ) {
                    if (stopping) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.StopCircle, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            run.control.stopRequested -> "正在停止…"
                            stopping -> "正在发送请求…"
                            else -> "停止蒸馏"
                        },
                    )
                }
            } else {
                HorizontalDivider()
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !deleting,
                ) {
                    if (deleting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (deleting) "正在删除…" else "删除这本书及其会话",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            }
        }
    }
}

@Composable
private fun PersonaCard(
    persona: PersonaIndexDto,
    avatarBytes: ByteArray?,
    onAvatarClick: () -> Unit,
    onClick: () -> Unit,
) {
    val preview = persona.preview
    val summary = listOf(preview.coreIdentity, preview.storyRole, preview.soulGoal)
        .firstOrNull(String::isNotBlank)
        .orEmpty()
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onAvatarClick, modifier = Modifier.size(44.dp)) {
                PersonaAvatar(bytes = avatarBytes, name = persona.name, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(persona.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = summary.ifBlank { "资料已生成，点此查看和校对。" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PersonaAvatar(
    bytes: ByteArray?,
    name: String,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.clip(CircleShape), shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
        val bitmap = bytes?.let { decodeImageBitmap(it) }
        if (bitmap == null) {
            Box(contentAlignment = Alignment.Center) {
                val initial = name.trim().take(1)
                if (initial.isBlank()) {
                    Icon(Icons.Outlined.Person, contentDescription = "人物头像")
                } else {
                    Text(
                        text = initial,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        } else {
            androidx.compose.foundation.Image(
                bitmap = checkNotNull(bitmap),
                contentDescription = "人物头像",
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun AvatarCropDialog(
    bytes: ByteArray,
    onDismiss: () -> Unit,
    onConfirm: (AvatarCrop) -> Unit,
) {
    val bitmap = remember(bytes) { decodeImageBitmap(bytes) }
    if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("无法读取图片") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        )
        return
    }

    var zoom by remember(bytes) { mutableStateOf(1f) }
    var offsetX by remember(bytes) { mutableStateOf(0f) }
    var offsetY by remember(bytes) { mutableStateOf(0f) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        zoom = (zoom * zoomChange).coerceIn(1f, 4f)
        val baseScale = minOf(
            viewport.width.toFloat() / bitmap.width,
            viewport.height.toFloat() / bitmap.height,
        )
        val cropDiameter = minOf(viewport.width, viewport.height) * AVATAR_CROP_FRAME_FRACTION
        val scaledWidth = bitmap.width * baseScale * zoom
        val scaledHeight = bitmap.height * baseScale * zoom
        val maxX = ((scaledWidth - cropDiameter) / 2f).coerceAtLeast(0f)
        val maxY = ((scaledHeight - cropDiameter) / 2f).coerceAtLeast(0f)
        offsetX = (offsetX + panChange.x).coerceIn(-maxX, maxX)
        offsetY = (offsetY + panChange.y).coerceIn(-maxY, maxY)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("调整头像取景") },
        text = {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clipToBounds()
                        .transformable(transformableState)
                        .onSizeChanged { viewport = it },
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.foundation.Image(
                        bitmap = checkNotNull(bitmap),
                        contentDescription = "头像取景预览",
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = zoom,
                                scaleY = zoom,
                                translationX = offsetX,
                                translationY = offsetY,
                            ),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    )
                    Canvas(Modifier.fillMaxSize()) {
                        drawCircle(
                            color = Color.White.copy(alpha = 0.92f),
                            radius = size.minDimension * AVATAR_CROP_FRAME_FRACTION / 2f,
                            center = center,
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val cropDiameter = minOf(viewport.width, viewport.height) * AVATAR_CROP_FRAME_FRACTION
                    val baseScale = minOf(
                        viewport.width.toFloat() / bitmap.width,
                        viewport.height.toFloat() / bitmap.height,
                    )
                    val imageScale = baseScale * zoom
                    val selectedSide = (cropDiameter / imageScale).toInt().coerceAtLeast(1)
                    val left = ((bitmap.width - selectedSide) / 2f - offsetX / imageScale).toInt()
                    val top = ((bitmap.height - selectedSide) / 2f - offsetY / imageScale).toInt()
                    onConfirm(AvatarCrop(left, top, selectedSide))
                },
                enabled = viewport != IntSize.Zero,
            ) { Text("确认") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun RunStatus(status: String) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text = statusLabel(status),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun NoticeCard(
    message: String,
    error: Boolean,
    onDismiss: () -> Unit,
    actionLabel: String = "",
    onAction: () -> Unit = {},
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (error) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                message,
                color = if (error) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (actionLabel.isNotBlank()) {
                    TextButton(onClick = onAction) { Text(actionLabel) }
                }
                TextButton(onClick = onDismiss) { Text("知道了") }
            }
        }
    }
}

@Composable
private fun DetailLoading(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("正在打开书卷…")
        }
    }
}

@Composable
private fun DetailFailure(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("书卷没有打开", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
                Button(onClick = onRetry) { Text("重试") }
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

private fun statusCopy(status: String): String = when (status) {
    "ready" -> "人物资料和关系已经整理完成。"
    "running" -> "人物和关系正在这台手机上逐步浮现。"
    "draft" -> "正文已经导入，尚未调用模型。"
    "failed" -> "这次蒸馏没有完成，已有结果仍会保留。"
    "stopped" -> "蒸馏已停止，可以按原人物重新开始。"
    else -> "书卷状态正在更新。"
}

private fun graphLabel(status: String): String = when (status) {
    "ready", "completed", "complete" -> "已完成"
    "running" -> "生成中"
    "failed" -> "失败"
    "stopped" -> "已停止"
    else -> status.ifBlank { "未生成" }
}

private fun sourceMeta(source: NovelSourceDto): String = buildList {
    add(if (source.kind == "incremental_update") "增量书段" else "初始正文")
    when {
        source.charCount >= 10_000 -> add("约 ${source.charCount / 10_000.0f} 万字".replace(".0 ", " "))
        source.charCount > 0 -> add("约 ${source.charCount} 字")
        source.byteSize >= 1024 * 1024 -> add("%.1f MB".format(source.byteSize / (1024.0 * 1024.0)))
        source.byteSize >= 1024 -> add("%.1f KB".format(source.byteSize / 1024.0))
        source.byteSize > 0 -> add("${source.byteSize} B")
    }
    if (source.timestamp.isNotBlank()) {
        add(source.timestamp.toLocalDateTimeDisplay())
    }
}.joinToString(" · ")
