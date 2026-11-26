package top.wkbin.zaomeng.feature.rundetail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardDefaults
import io.github.suqi8.coui.kmp.basic.CardListGroup
import io.github.suqi8.coui.kmp.basic.Checkbox
import io.github.suqi8.coui.kmp.basic.CircularProgressIndicator
import io.github.suqi8.coui.kmp.basic.DropdownImpl
import io.github.suqi8.coui.kmp.basic.EmptyState
import io.github.suqi8.coui.kmp.basic.EmptyStateSizeType
import io.github.suqi8.coui.kmp.basic.HorizontalDivider
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.LinearProgressIndicator
import io.github.suqi8.coui.kmp.basic.ListPopupColumn
import io.github.suqi8.coui.kmp.basic.Surface
import io.github.suqi8.coui.kmp.basic.TagView
import io.github.suqi8.coui.kmp.basic.TagViewDefaults
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Add
import io.github.suqi8.coui.kmp.icon.extended.Close
import io.github.suqi8.coui.kmp.icon.extended.Contacts
import io.github.suqi8.coui.kmp.icon.extended.ContactsCircle
import io.github.suqi8.coui.kmp.icon.extended.Create
import io.github.suqi8.coui.kmp.icon.extended.Delete
import io.github.suqi8.coui.kmp.icon.extended.Download
import io.github.suqi8.coui.kmp.icon.extended.ExpandLess
import io.github.suqi8.coui.kmp.icon.extended.ExpandMore
import io.github.suqi8.coui.kmp.icon.extended.More
import io.github.suqi8.coui.kmp.icon.extended.Play
import io.github.suqi8.coui.kmp.icon.extended.Remove
import io.github.suqi8.coui.kmp.overlay.OverlayListPopup
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import io.github.suqi8.coui.kmp.theme.COUIRoundCorner
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlin.math.roundToLong
import org.koin.compose.koinInject
import top.wkbin.zaomeng.data.api.BetaFeatureDto
import top.wkbin.zaomeng.data.api.NovelSourceDto
import top.wkbin.zaomeng.data.api.OnlineLibrarySourceDto
import top.wkbin.zaomeng.data.api.PersonaIndexDto
import top.wkbin.zaomeng.data.api.RunManifestDto
import top.wkbin.zaomeng.domain.run.RunReviewOverview
import top.wkbin.zaomeng.platform.DistillationForeground
import top.wkbin.zaomeng.platform.PlatformBackHandler
import top.wkbin.zaomeng.platform.rememberDocumentPicker
import top.wkbin.zaomeng.platform.rememberFileExporter
import top.wkbin.zaomeng.platform.rememberNotificationPermissionRequester
import top.wkbin.zaomeng.platform.rememberOpenExternalUrl
import top.wkbin.zaomeng.ui.coui.CouiAlertDialog
import top.wkbin.zaomeng.ui.coui.CouiPageScaffold
import top.wkbin.zaomeng.ui.coui.CouiTopBarStyle
import top.wkbin.zaomeng.ui.coui.couiGridScroll
import top.wkbin.zaomeng.ui.coui.isWideWindow
import top.wkbin.zaomeng.ui.format.toLocalDateTimeDisplay
import top.wkbin.zaomeng.ui.graphics.decodeImageBitmap
import top.wkbin.zaomeng.ui.theme.AppDimens

private const val AVATAR_CROP_FRAME_FRACTION = 0.8f
private const val AVATAR_ZOOM_MIN = 1f
private const val AVATAR_ZOOM_MAX = 4f
private const val AVATAR_ZOOM_STEP = 1.25f
private const val AVATAR_ZOOM_WHEEL_STEP = 1.1f

@Composable
fun RunDetailScreen(
    viewModel: RunDetailViewModel,
    refreshEpoch: Int = 0,
    onBack: () -> Unit,
    onOpenPersona: (runId: String, character: String) -> Unit,
    onOpenSessions: (runId: String) -> Unit,
    onOpenChat: (runId: String, sessionId: String) -> Unit,
    onOpenChapters: (runId: String) -> Unit,
    onOpenRelations: (runId: String) -> Unit,
    onOpenWorldTimeline: (runId: String) -> Unit,
    onOpenOriginalKnowledge: (runId: String) -> Unit,
    onOpenRedistill: (runId: String) -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val cardPersona = state.run?.takeIf { it.isSillyTavernCharacterCard }
        ?.let { run -> run.artifactIndex.characters.singleOrNull { it.name in run.availableCharacters } }
    LaunchedEffect(state.startedCardChat) {
        val chat = state.startedCardChat ?: return@LaunchedEffect
        viewModel.consumeStartedCardChat()
        onOpenChat(chat.runId, chat.sessionId)
    }
    val distillationForeground: DistillationForeground = koinInject()
    var confirmStop by rememberSaveable { mutableStateOf(false) }
    var confirmRedistill by rememberSaveable { mutableStateOf(false) }
    var confirmResume by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var chooseExportFormat by rememberSaveable { mutableStateOf(false) }
    var confirmExport by rememberSaveable { mutableStateOf(false) }
    var chooseExportCharacter by rememberSaveable { mutableStateOf(false) }
    var exportCardCharacter by rememberSaveable { mutableStateOf<String?>(null) }
    var showWorldBooks by rememberSaveable { mutableStateOf(false) }
    val detailStateHolder = rememberSaveableStateHolder()
    LaunchedEffect(showWorldBooks) {
        if (showWorldBooks) viewModel.loadWorldBooks()
    }
    PlatformBackHandler(enabled = showWorldBooks) { showWorldBooks = false }
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
        onCancelled = { viewModel.cancelExportDestination() },
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

    LaunchedEffect(state.exportRequestId, state.exportDestinationPending) {
        val exported = state.exportedPackage ?: return@LaunchedEffect
        if (!state.exportDestinationPending) return@LaunchedEffect
        viewModel.consumeExportDestinationRequest(state.exportRequestId)
        fileExporter(exported.filename, exported.mimeType)
    }

    LaunchedEffect(state.deleted) {
        if (state.deleted) onDeleted()
    }

    LaunchedEffect(refreshEpoch) {
        if (refreshEpoch > 0) viewModel.refresh()
    }

    if (showWorldBooks) {
        WorldBooksScreen(
            viewModel = viewModel,
            exportState = state,
            onClose = { showWorldBooks = false },
            modifier = modifier,
        )
        return
    }
    detailStateHolder.SaveableStateProvider("detail") {
        CouiPageScaffold(
            modifier = modifier,
            title = if (cardPersona == null) "书卷详情" else "角色卡",
            topBarStyle = CouiTopBarStyle.Small,
            contentTopSpacing = 8.dp,
            onBack = onBack,
            wide = isWideWindow(),
            actions = {
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(COUIIcons.More, contentDescription = "书卷选项")
                    }
                    OverlayListPopup(show = menuOpen, onDismissRequest = { menuOpen = false }) {
                        ListPopupColumn {
                            DropdownImpl(
                                text = "世界书",
                                optionSize = 3,
                                isSelected = false,
                                index = 0,
                                enabled = state.run != null,
                                onSelectedIndexChange = {
                                    menuOpen = false
                                    showWorldBooks = true
                                },
                            )
                            DropdownImpl(
                                text = "导出",
                                optionSize = 3,
                                isSelected = false,
                                index = 1,
                                enabled = state.run != null && !state.exporting,
                                onSelectedIndexChange = {
                                    menuOpen = false
                                    chooseExportFormat = true
                                },
                            )
                            DropdownImpl(
                                text = if (state.refreshing) "正在刷新…" else "刷新详情",
                                optionSize = 3,
                                isSelected = false,
                                index = 2,
                                enabled = !state.refreshing && !state.loading,
                                onSelectedIndexChange = {
                                    menuOpen = false
                                    viewModel.refresh()
                                },
                            )
                        }
                    }
                }
            },
        ) { scope ->
            if (confirmStop) {
                CouiAlertDialog(
                    onDismissRequest = { confirmStop = false },
                    title = "停止这次蒸馏？",
                    confirmText = "停止蒸馏",
                    onConfirm = {
                        confirmStop = false
                        viewModel.stop()
                    },
                    dismissText = "继续等待",
                    onDismiss = { confirmStop = false },
                    body = {
                        Text("已经完成的人物资料会保留，当前步骤结束后停止。")
                    },
                )
            }

            if (confirmRedistill) {
                val characterCount = state.run
                    ?.let { it.lockedCharacters.ifEmpty { it.availableCharacters }.size }
                    ?: 0
                CouiAlertDialog(
                    onDismissRequest = { confirmRedistill = false },
                    title = "按原人物重新蒸馏？",
                    confirmText = "重新开始",
                    onConfirm = {
                        confirmRedistill = false
                        startOriginalRedistill()
                    },
                    dismissText = "取消",
                    body = {
                        Text("将沿用当前正文和 $characterCount 位原人物，重新生成资料与关系。")
                    },
                )
            }

            if (confirmResume) {
                val characterCount = state.run
                    ?.let { run -> run.lockedCharacters.count { character -> character !in run.progress.completedCharacters } }
                    ?: 0
                CouiAlertDialog(
                    onDismissRequest = { confirmResume = false },
                    title = "继续未完成人物",
                    confirmText = "继续蒸馏",
                    onConfirm = {
                        confirmResume = false
                        startResumeDistill()
                    },
                    dismissText = "取消",
                    body = {
                        Text("将保留已完成人物，只继续蒸馏剩余 $characterCount 位人物。")
                    },
                )
            }

            if (chooseExportFormat) {
                CouiAlertDialog(
                    onDismissRequest = { chooseExportFormat = false },
                    title = "导出",
                    confirmText = "取消",
                    onConfirm = { chooseExportFormat = false },
                    dismissText = null,
                    body = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    chooseExportFormat = false
                                    confirmExport = true
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(),
                            ) {
                                Text("造梦书卷包")
                            }
                            Button(
                                onClick = {
                                    chooseExportFormat = false
                                    val singleCharacter = state.run?.availableCharacters?.singleOrNull()
                                    if (singleCharacter != null) exportCardCharacter = singleCharacter
                                    else chooseExportCharacter = true
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = state.run?.availableCharacters.orEmpty().isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(),
                            ) {
                                Text("SillyTavern 角色卡")
                            }
                        }
                    },
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

            if (chooseExportCharacter) {
                CouiAlertDialog(
                    onDismissRequest = { chooseExportCharacter = false },
                    title = "导出 SillyTavern 角色卡",
                    confirmText = "取消",
                    onConfirm = { chooseExportCharacter = false },
                    dismissText = null,
                    body = {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                            items(state.run?.availableCharacters.orEmpty(), key = { it }) { character ->
                                TextButton(
                                    text = character,
                                    modifier = Modifier.fillMaxWidth(),
                                    minWidth = 0.dp,
                                    onClick = {
                                        chooseExportCharacter = false
                                        exportCardCharacter = character
                                    },
                                )
                            }
                        }
                    },
                )
            }

            exportCardCharacter?.let { character ->
                val avatarVersion = state.run?.artifactIndex?.characters
                    ?.firstOrNull { it.name == character }?.avatarVersion.orEmpty()
                CouiAlertDialog(
                    onDismissRequest = { exportCardCharacter = null },
                    title = "导出「$character」",
                    confirmText = "取消",
                    onConfirm = { exportCardCharacter = null },
                    dismissText = null,
                    body = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    exportCardCharacter = null
                                    viewModel.exportSillyTavernPngCard(character, avatarVersion)
                                },
                                enabled = avatarVersion.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(),
                            ) {
                                Text("PNG · 含头像")
                            }
                            if (avatarVersion.isBlank()) {
                                Text("添加头像后即可导出 PNG。", style = COUITheme.textStyles.bodyXS)
                            }
                            Button(
                                onClick = {
                                    exportCardCharacter = null
                                    viewModel.exportSillyTavernCard(character)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(),
                            ) {
                                Text("JSON · 角色数据")
                            }
                        }
                    },
                )
            }

            if (confirmDelete) {
                CouiAlertDialog(
                    onDismissRequest = { if (!state.deleting) confirmDelete = false },
                    title = "删除整本书卷？",
                    confirmText = "确认删除",
                    onConfirm = {
                        confirmDelete = false
                        viewModel.deleteRun()
                    },
                    confirmEnabled = !state.deleting,
                    dismissText = "取消",
                    onDismiss = { if (!state.deleting) confirmDelete = false },
                    body = {
                        Text("人物资料、关系图和这本书的全部会话都会一起删除，且无法恢复。")
                    },
                )
            }

            selectedAvatarPersona?.let { persona ->
                CouiAlertDialog(
                    onDismissRequest = { if (state.updatingAvatar.isBlank()) selectedAvatarPersona = null },
                    title = persona.name,
                    confirmText = if (state.updatingAvatar == persona.name) "正在保存" else "修改头像",
                    onConfirm = { avatarPicker() },
                    confirmEnabled = state.updatingAvatar.isBlank(),
                    dismissText = "取消",
                    onDismiss = { if (state.updatingAvatar.isBlank()) selectedAvatarPersona = null },
                    body = {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            PersonaAvatar(
                                bytes = state.avatarBytes[persona.name],
                                name = persona.name,
                                modifier = Modifier.size(144.dp),
                            )
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

            val contentModifier = Modifier.padding(scope.contentPadding)
            when {
                state.loading -> DetailLoading(contentModifier)
                state.run == null -> DetailFailure(
                    message = state.error.ifBlank { "没有找到这份书卷。" },
                    onRetry = viewModel::load,
                    modifier = contentModifier,
                )
                cardPersona != null -> {
                    val run = requireNotNull(state.run)
                    CardPlayScreen(
                        persona = cardPersona,
                        avatar = state.avatarBytes[cardPersona.name],
                        refreshEpoch = refreshEpoch,
                        viewModel = viewModel,
                        state = state,
                        onAvatarClick = { selectedAvatarPersona = cardPersona },
                        onEdit = { onOpenPersona(run.runId, cardPersona.name) },
                        onHistory = { onOpenSessions(run.runId) },
                        onWorldBooks = { showWorldBooks = true },
                        onExport = { chooseExportFormat = true },
                        onDismissNotice = viewModel::dismissNotice,
                        onRetryExportDestination = viewModel::retryExportDestination,
                        modifier = contentModifier,
                        pageScope = scope,
                        management = {
                            ActionCard(
                                run = run,
                                stopping = state.stopping,
                                redistilling = state.redistilling,
                                exporting = state.exporting,
                                deleting = state.deleting,
                                onStop = { confirmStop = true },
                                onResume = { confirmResume = true },
                                onRedistill = { confirmRedistill = true },
                                onExport = { confirmExport = true },
                                onOpenRelations = { onOpenRelations(run.runId) },
                                onOpenWorldTimeline = { onOpenWorldTimeline(run.runId) },
                                onOpenOriginalKnowledge = { onOpenOriginalKnowledge(run.runId) },
                                onOpenRedistill = { onOpenRedistill(run.runId) },
                                onDelete = { confirmDelete = true },
                            )
                        },
                    )
                }
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
                    onOpenOriginalKnowledge = { onOpenOriginalKnowledge(viewModel.runId) },
                    onOpenRedistill = { onOpenRedistill(viewModel.runId) },
                    onDelete = { confirmDelete = true },
                    contentPadding = scope.contentPadding,
                    modifier = Modifier.couiGridScroll(scope),
                )
            }
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
    onOpenOriginalKnowledge: () -> Unit,
    onOpenRedistill: () -> Unit,
    onDelete: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val run = checkNotNull(state.run)
    var showAllSources by rememberSaveable(run.runId) { mutableStateOf(false) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 380.dp),
        modifier = modifier.fillMaxSize().padding(horizontal = AppDimens.screenPadding),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing),
        horizontalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { RunHero(run) }

        if (state.error.isNotBlank() || state.message.isNotBlank()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
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

        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(AppDimens.iconTextGap)) {
                Card {
                    CardListGroup {
                        if (run.availableCharacters.isNotEmpty()) {
                            item { ArrowPreference(title = "进入本书对话", onClick = onOpenSessions) }
                        }
                        item { ArrowPreference(title = "查看章节", onClick = onOpenChapters) }
                        if (run.status == "running") {
                            item {
                                ArrowPreference(
                                    title = if (state.stopping) "正在停止…" else "停止蒸馏",
                                    onClick = onStop,
                                    enabled = !state.stopping,
                                )
                            }
                        }
                    }
                }
                if (run.status != "ready" && nextActionFor(run).target != RunNextActionTarget.OpenSessions) {
                    NextActionCard(
                        run = run,
                        onResume = onResume,
                        onOpenRedistill = onOpenRedistill,
                        onOpenPersona = onOpenPersona,
                        onOpenRelations = onOpenRelations,
                        onOpenSessions = onOpenSessions,
                    )
                }
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            var showTools by rememberSaveable(run.runId) { mutableStateOf(false) }
            Column(verticalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing)) {
                Card {
                    BasicComponent(
                        title = if (showTools) "收起书卷工具" else "书卷工具与管理",
                        onClick = { showTools = !showTools },
                        endActions = {
                            Icon(if (showTools) COUIIcons.ExpandLess else COUIIcons.ExpandMore, contentDescription = null)
                        },
                    )
                }
                if (showTools) {
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
                        onOpenRelations = onOpenRelations,
                        onOpenWorldTimeline = onOpenWorldTimeline,
                        onOpenOriginalKnowledge = onOpenOriginalKnowledge,
                        onOpenRedistill = onOpenRedistill,
                        onDelete = onDelete,
                    )
                    if (state.reviewLoading || state.reviewOverview != null) {
                        BookReviewCard(
                            loading = state.reviewLoading,
                            overview = state.reviewOverview,
                            onOpenPersona = onOpenPersona,
                            onOpenRelations = onOpenRelations,
                            onOpenWorldTimeline = onOpenWorldTimeline,
                        )
                    }
                    run.betaFeature?.takeIf { it.kind == "cross_book_crossover" }?.let { CrossoverBetaCard(it) }
                    run.importedFrom.onlineLibrary?.takeIf { it.id.isNotBlank() }?.let { OnlinePackageSourceCard(it) }
                    if (run.novelSources.isNotEmpty()) {
                        SourceHistoryCard(run, showAllSources) { showAllSources = !showAllSources }
                    }
                }
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (run.isSillyTavernCharacterCard) "角色卡" else "可用人物",
                    style = COUITheme.textStyles.headlineM,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!run.isSillyTavernCharacterCard) {
                    Text(
                        text = if (run.availableCharacters.isEmpty()) {
                            if (run.status == "running") "人物资料完成后会陆续出现在这里。" else "目前没有可用的人物资料。"
                        } else if (run.artifactIndex.characters.isEmpty()) {
                            "${run.availableCharacters.size} 位人物可用于对话"
                        } else {
                            "${run.availableCharacters.size} 位人物可以查看和校对"
                        },
                        color = COUITheme.colorScheme.labelSecondary,
                        style = COUITheme.textStyles.bodyM,
                    )
                }
            }
        }

        if (run.artifactIndex.characters.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                if (run.status == "running") {
                    Card {
                        Text(
                            text = "正在等待第一位人物完成…",
                            modifier = Modifier.padding(20.dp),
                            color = COUITheme.colorScheme.labelSecondary,
                        )
                    }
                } else {
                    EmptyState(
                        title = "还没有人物档案。",
                        sizeType = EmptyStateSizeType.Small,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    )
                }
            }
        } else {
            items(run.artifactIndex.characters, key = PersonaIndexDto::name) { persona ->
                PersonaCard(
                    persona = persona,
                    isSillyTavernCard = run.isSillyTavernCharacterCard,
                    avatarBytes = state.avatarBytes[persona.name],
                    onAvatarClick = { onAvatarClick(persona) },
                    onClick = { onOpenPersona(persona.name) },
                )
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(12.dp)) }
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
    modifier: Modifier = Modifier,
) {
    val action = nextActionFor(run)
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("推荐下一步", style = COUITheme.textStyles.buttonM, color = COUITheme.colorScheme.labelPrimary)
            Text(action.title, style = COUITheme.textStyles.headlineXS, fontWeight = FontWeight.SemiBold)
            Text(
                action.description,
                style = COUITheme.textStyles.bodyM,
                color = COUITheme.colorScheme.labelPrimary,
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
                colors = ButtonDefaults.buttonColorsPrimary(),
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
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("作品体检", style = COUITheme.textStyles.headlineXS, fontWeight = FontWeight.SemiBold)
            if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), size = 18.dp, strokeWidth = 2.dp)
                    Text("正在汇总人物、关系与时间线的待校对项…", style = COUITheme.textStyles.bodyXS)
                }
            } else {
                val report = checkNotNull(overview)
                val hasIssues = report.relationConflictCount > 0 || report.timelineWarningCount > 0 ||
                    report.charactersNeedingReview.isNotEmpty()
                if (!hasIssues) {
                    Text("当前没有发现需要优先处理的关系冲突或一致性提醒。", style = COUITheme.textStyles.bodyM)
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
                        style = COUITheme.textStyles.bodyXS,
                        color = COUITheme.colorScheme.labelSecondary,
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
            style = COUITheme.textStyles.bodyM,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(
            text = label,
            onClick = onClick,
            minWidth = 0.dp,
            maxLines = 1,
        )
    }
}

@Composable
private fun CrossoverBetaCard(beta: BetaFeatureDto) {
    Card {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("跨书卷共演 · Beta", style = COUITheme.textStyles.headlineXS, fontWeight = FontWeight.SemiBold)
            Text("这是独立人物快照空间。这里产生的关系、记忆、修正和会话不会写回来源书卷；测试功能的角色表现可能不稳定。", style = COUITheme.textStyles.bodyXS)
            if (beta.worldSetting.isNotBlank()) {
                Text("世界设定：${beta.worldSetting}", style = COUITheme.textStyles.bodyXS)
            }
            if (beta.sourceSnapshots.isNotEmpty()) {
                Text("人物来源：${beta.sourceSnapshots.joinToString(" · ") { it.character }}", style = COUITheme.textStyles.bodyXS)
            }
        }
    }
}

@Composable
private fun OnlinePackageSourceCard(source: OnlineLibrarySourceDto) {
    val openExternalUrl = rememberOpenExternalUrl()
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("在线书卷包", style = COUITheme.textStyles.headlineXS, fontWeight = FontWeight.SemiBold)
            Text(
                buildList {
                    if (source.title.isNotBlank()) add(source.title)
                    if (source.version.isNotBlank()) add("v${source.version}")
                    if (source.id.isNotBlank()) add(source.id)
                }.joinToString(" · "),
                style = COUITheme.textStyles.bodyXS,
                color = COUITheme.colorScheme.labelPrimary,
            )
            if (source.downloadUrl.isNotBlank()) {
                TextButton(
                    text = "打开书卷包来源",
                    onClick = { openExternalUrl(source.downloadUrl) },
                    minWidth = 0.dp,
                )
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

    Card {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("正文来源", style = COUITheme.textStyles.headlineXS, fontWeight = FontWeight.SemiBold)
                Text(
                    "初始正文和后续增量书段都会保留在这里。",
                    style = COUITheme.textStyles.bodyXS,
                    color = COUITheme.colorScheme.labelSecondary,
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
                            style = COUITheme.textStyles.bodyXL,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (isCurrent) {
                            Surface(
                                color = COUITheme.colorScheme.containerThemeHalftone,
                                shape = SquircleShape(COUIRoundCorner.Normal.XS, COUIRoundCorner.Normal.Weight),
                            ) {
                                Text(
                                    "当前",
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                    style = COUITheme.textStyles.bodyXS,
                                )
                            }
                        }
                    }
                    Text(
                        sourceMeta(source),
                        style = COUITheme.textStyles.bodyXS,
                        color = COUITheme.colorScheme.labelSecondary,
                    )
                }
            }

            if (sortedSources.size > 3) {
                TextButton(
                    text = if (expanded) "收起" else "查看全部 ${sortedSources.size} 份",
                    onClick = onToggleExpanded,
                    modifier = Modifier.align(Alignment.End),
                    minWidth = 0.dp,
                )
            }
        }
    }
}

@Composable
private fun RunHero(run: RunManifestDto) {
    val total = maxOf(run.progress.totalCharacters, run.lockedCharacters.size)
    val completed = maxOf(run.progress.completedCount, run.availableCharacters.size)
    val progress = if (total > 0) (completed.toFloat() / total).coerceIn(0f, 1f) else null

    Card {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        run.title,
                        style = COUITheme.textStyles.headlineS,
                        color = COUITheme.colorScheme.labelPrimary,
                    )
                }
                Spacer(Modifier.width(12.dp))
                RunStatus(run.status)
            }

            if (run.status == "running") {
                if (progress == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                }
            }

            Text(
                text = run.progress.message.ifBlank {
                    run.summary.statusText.ifBlank {
                        if (run.isSillyTavernCharacterCard && run.status == "ready") "已导入 SillyTavern 角色卡。"
                        else statusCopy(run.status)
                    }
                }.let { if (it == "crossover_beta_ready") "跨书卷共演已就绪。" else it },
                color = COUITheme.colorScheme.labelSecondary,
                style = COUITheme.textStyles.bodyM,
            )
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Metric(
                    "人物",
                    if (run.status == "running" && total > 0) "$completed / $total"
                    else run.availableCharacters.size.toString(),
                    Modifier.weight(1f),
                )
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
                    style = COUITheme.textStyles.buttonM,
                    color = COUITheme.colorScheme.labelPrimary,
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
            style = COUITheme.textStyles.bodyXS,
            color = COUITheme.colorScheme.labelPrimary.copy(alpha = 0.72f),
        )
        Text(
            value,
            style = COUITheme.textStyles.bodyM,
            fontWeight = FontWeight.SemiBold,
            color = COUITheme.colorScheme.labelPrimary,
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
    CouiAlertDialog(
        onDismissRequest = onDismiss,
        title = "导出造梦书卷包",
        confirmText = "生成分享包",
        onConfirm = { onConfirm(includeDialogue) },
        dismissText = "取消",
        body = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("选择是否把已有会话一起写入书卷包。")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Checkbox(
                        state = ToggleableState(includeDialogue),
                        onClick = { includeDialogue = !includeDialogue },
                    )
                    Text("携带会话记录")
                }
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
    onOpenRelations: () -> Unit,
    onOpenWorldTimeline: () -> Unit,
    onOpenOriginalKnowledge: () -> Unit,
    onOpenRedistill: () -> Unit,
    onDelete: () -> Unit,
) {
    Card {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("书卷操作", style = COUITheme.textStyles.headlineXS, fontWeight = FontWeight.SemiBold)
            val showAdvancedManagement = rememberSaveable(run.runId) { mutableStateOf(false) }
            Text(
                "故事资料",
                style = COUITheme.textStyles.buttonM,
                color = COUITheme.colorScheme.labelSecondary,
            )
            Button(onClick = onOpenRelations, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors()) {
                Icon(COUIIcons.ContactsCircle, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("查看与校对人物关系")
            }
            Button(onClick = onOpenWorldTimeline, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors()) {
                Text("查看故事时间线与剧情事实")
            }
            Button(onClick = onOpenOriginalKnowledge, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors()) {
                Text("搜索与校对原文证据")
            }
            HorizontalDivider()
            Text(
                "书卷管理",
                style = COUITheme.textStyles.buttonM,
                color = COUITheme.colorScheme.labelSecondary,
            )
            ManagementActions(
                run = run,
                stopping = stopping,
                redistilling = redistilling,
                exporting = exporting,
                deleting = deleting,
                onStop = onStop,
                onResume = onResume,
                onRedistill = onRedistill,
                onExport = onExport,
                onOpenRedistill = onOpenRedistill,
                onDelete = onDelete,
                showAdvanced = showAdvancedManagement.value,
                onToggleAdvanced = { showAdvancedManagement.value = !showAdvancedManagement.value },
            )
        }
    }
}

/** 书卷管理区：导出 / 更多操作（高级操作、停止、删除）。窄宽两种布局共用。 */
@Composable
private fun ManagementActions(
    run: RunManifestDto,
    stopping: Boolean,
    redistilling: Boolean,
    exporting: Boolean,
    deleting: Boolean,
    onStop: () -> Unit,
    onResume: () -> Unit,
    onRedistill: () -> Unit,
    onExport: () -> Unit,
    onOpenRedistill: () -> Unit,
    onDelete: () -> Unit,
    showAdvanced: Boolean,
    onToggleAdvanced: () -> Unit,
) {
    Button(
        onClick = onExport,
        modifier = Modifier.fillMaxWidth(),
        enabled = !exporting,
        colors = ButtonDefaults.buttonColors(),
    ) {
        if (exporting) CircularProgressIndicator(modifier = Modifier.size(18.dp), size = 18.dp, strokeWidth = 2.dp)
        else Icon(COUIIcons.Download, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(if (exporting) "正在打包…" else "导出书卷包")
    }

    TextButton(
        text = if (showAdvanced) "收起高级操作" else "更多书卷操作",
        onClick = onToggleAdvanced,
        modifier = Modifier.fillMaxWidth(),
    )
    if (showAdvanced) {
        Button(
            onClick = onOpenRedistill,
            modifier = Modifier.fillMaxWidth(),
            enabled = !redistilling && run.status != "running" &&
                (run.lockedCharacters.isNotEmpty() || run.availableCharacters.isNotEmpty()),
            colors = ButtonDefaults.buttonColors(),
        ) {
            if (redistilling) CircularProgressIndicator(modifier = Modifier.size(18.dp), size = 18.dp, strokeWidth = 2.dp)
            else Icon(COUIIcons.Create, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("继续蒸馏或换入新书段")
        }

        val unfinishedCount = run.lockedCharacters.count { it !in run.progress.completedCharacters }
        if (unfinishedCount > 0 && run.status != "running") {
            Button(
                onClick = onResume,
                modifier = Modifier.fillMaxWidth(),
                enabled = !redistilling,
                colors = ButtonDefaults.buttonColors(),
            ) {
                if (redistilling) CircularProgressIndicator(modifier = Modifier.size(18.dp), size = 18.dp, strokeWidth = 2.dp)
                else Icon(COUIIcons.Play, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("继续未完成人物（$unfinishedCount 人）")
            }
        }

        TextButton(
            text = if (redistilling) "正在重新开始…" else "直接沿用原人物与正文重跑",
            onClick = onRedistill,
            modifier = Modifier.fillMaxWidth(),
            enabled = !redistilling && run.status != "running" &&
                (run.lockedCharacters.isNotEmpty() || run.availableCharacters.isNotEmpty()),
        )
    }

    if (run.status == "running") {
        HorizontalDivider()
        Button(
            onClick = onStop,
            modifier = Modifier.fillMaxWidth(),
            enabled = !stopping && !run.control.stopRequested,
            colors = ButtonDefaults.buttonColors(),
        ) {
            if (stopping) CircularProgressIndicator(modifier = Modifier.size(18.dp), size = 18.dp, strokeWidth = 2.dp)
            else Icon(COUIIcons.Close, contentDescription = null)
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
        Button(
            onClick = onDelete,
            modifier = Modifier.fillMaxWidth(),
            enabled = !deleting,
            colors = ButtonDefaults.buttonColors(),
        ) {
            if (deleting) CircularProgressIndicator(modifier = Modifier.size(18.dp), size = 18.dp, strokeWidth = 2.dp)
            else Icon(
                COUIIcons.Delete,
                contentDescription = null,
                tint = COUITheme.colorScheme.error,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (deleting) "正在删除…" else "删除这本书及其会话",
                color = COUITheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun PersonaCard(
    persona: PersonaIndexDto,
    isSillyTavernCard: Boolean,
    avatarBytes: ByteArray?,
    onAvatarClick: () -> Unit,
    onClick: () -> Unit,
) {
    val preview = persona.preview
    val summary = listOf(preview.coreIdentity, preview.storyRole, preview.soulGoal)
        .firstOrNull(String::isNotBlank)
        .orEmpty()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = COUITheme.colorScheme.surface),
        onClick = onClick,
    ) {
        Row(modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onAvatarClick, modifier = Modifier.size(44.dp)) {
                PersonaAvatar(bytes = avatarBytes, name = persona.name, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(persona.name, style = COUITheme.textStyles.headlineXS, fontWeight = FontWeight.SemiBold)
                Text(
                    text = summary.ifBlank { if (isSillyTavernCard) "查看与编辑角色卡" else "资料已生成，点此查看和校对。" },
                    style = COUITheme.textStyles.bodyXS,
                    color = COUITheme.colorScheme.labelSecondary,
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
    Surface(modifier = modifier.clip(CircleShape), shape = CircleShape, color = COUITheme.colorScheme.containerThemeHalftone) {
        val bitmap = remember(bytes) { bytes?.let { decodeImageBitmap(it) } }
        if (bitmap == null) {
            Box(contentAlignment = Alignment.Center) {
                val initial = name.trim().take(1)
                if (initial.isBlank()) {
                    Icon(COUIIcons.Contacts, contentDescription = "人物头像")
                } else {
                    Text(
                        text = initial,
                        style = COUITheme.textStyles.headlineXS,
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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun AvatarCropDialog(
    bytes: ByteArray,
    onDismiss: () -> Unit,
    onConfirm: (AvatarCrop) -> Unit,
) {
    val bitmap = remember(bytes) { decodeImageBitmap(bytes) }
    if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) {
        CouiAlertDialog(
            onDismissRequest = onDismiss,
            title = "无法读取图片",
            confirmText = "关闭",
            onConfirm = onDismiss,
            dismissText = null,
        )
        return
    }

    var zoom by remember(bytes) { mutableStateOf(1f) }
    var offsetX by remember(bytes) { mutableStateOf(0f) }
    var offsetY by remember(bytes) { mutableStateOf(0f) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val transformableState = rememberTransformableState { _, zoomChange, panChange, _ ->
        zoom = (zoom * zoomChange).coerceIn(AVATAR_ZOOM_MIN, AVATAR_ZOOM_MAX)
        val (clampedX, clampedY) = clampCropOffsets(
            viewport = viewport,
            bitmapWidth = bitmap.width,
            bitmapHeight = bitmap.height,
            zoom = zoom,
            offsetX = offsetX + panChange.x,
            offsetY = offsetY + panChange.y,
        )
        offsetX = clampedX
        offsetY = clampedY
    }
    // 桌面端没有双指手势，提供按钮与鼠标滚轮缩放（触屏仍可双指缩放）。
    fun zoomBy(factor: Float) {
        val newZoom = (zoom * factor).coerceIn(AVATAR_ZOOM_MIN, AVATAR_ZOOM_MAX)
        if (newZoom == zoom) return
        zoom = newZoom
        val (clampedX, clampedY) = clampCropOffsets(
            viewport = viewport,
            bitmapWidth = bitmap.width,
            bitmapHeight = bitmap.height,
            zoom = zoom,
            offsetX = offsetX,
            offsetY = offsetY,
        )
        offsetX = clampedX
        offsetY = clampedY
    }

    CouiAlertDialog(
        onDismissRequest = onDismiss,
        title = "调整头像取景",
        confirmText = "确认",
        onConfirm = {
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
        confirmEnabled = viewport != IntSize.Zero,
        dismissText = "取消",
        onDismiss = onDismiss,
        body = {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(COUITheme.colorScheme.backgroundElevatedWithCard)
                        .clipToBounds()
                        .transformable(transformableState)
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.type == PointerEventType.Scroll) {
                                        val deltaY = event.changes.firstOrNull()?.scrollDelta?.y ?: continue
                                        zoomBy(
                                            // AWT：滚轮向上为负（away），向下为正；CMP 直接映射为 scrollDelta.y。
                                            if (deltaY > 0) 1f / AVATAR_ZOOM_WHEEL_STEP
                                            else AVATAR_ZOOM_WHEEL_STEP,
                                        )
                                    }
                                }
                            }
                        }
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { zoomBy(1f / AVATAR_ZOOM_STEP) },
                        enabled = zoom > AVATAR_ZOOM_MIN,
                    ) {
                        Icon(COUIIcons.Remove, contentDescription = "缩小")
                    }
                    Text(
                        text = "${(zoom * 100).toInt()}%",
                        modifier = Modifier.width(56.dp),
                        textAlign = TextAlign.Center,
                        style = COUITheme.textStyles.buttonM,
                    )
                    IconButton(
                        onClick = { zoomBy(AVATAR_ZOOM_STEP) },
                        enabled = zoom < AVATAR_ZOOM_MAX,
                    ) {
                        Icon(COUIIcons.Add, contentDescription = "放大")
                    }
                }
                Text(
                    text = "双指/滚轮缩放 · 拖拽调整位置",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    textAlign = TextAlign.Center,
                    style = COUITheme.textStyles.bodyXS,
                    color = COUITheme.colorScheme.labelSecondary,
                )
            }
        },
    )
}

/** 按当前缩放与取景框尺寸重新夹取偏移量，保证裁剪框始终落在图片范围内。 */
private fun clampCropOffsets(
    viewport: IntSize,
    bitmapWidth: Int,
    bitmapHeight: Int,
    zoom: Float,
    offsetX: Float,
    offsetY: Float,
): Pair<Float, Float> {
    if (viewport == IntSize.Zero || bitmapWidth <= 0 || bitmapHeight <= 0) {
        return offsetX to offsetY
    }
    val baseScale = minOf(
        viewport.width.toFloat() / bitmapWidth,
        viewport.height.toFloat() / bitmapHeight,
    )
    val cropDiameter = minOf(viewport.width, viewport.height) * AVATAR_CROP_FRAME_FRACTION
    val scaledWidth = bitmapWidth * baseScale * zoom
    val scaledHeight = bitmapHeight * baseScale * zoom
    val maxX = ((scaledWidth - cropDiameter) / 2f).coerceAtLeast(0f)
    val maxY = ((scaledHeight - cropDiameter) / 2f).coerceAtLeast(0f)
    return offsetX.coerceIn(-maxX, maxX) to offsetY.coerceIn(-maxY, maxY)
}

@Composable
private fun RunStatus(status: String) {
    TagView(
        text = statusLabel(status),
        colors = TagViewDefaults.tagViewColors(
            containerColor = COUITheme.colorScheme.surface.copy(alpha = 0.82f),
            contentColor = COUITheme.colorScheme.labelPrimary,
        ),
    )
}

@Composable
internal fun NoticeCard(
    message: String,
    error: Boolean,
    onDismiss: () -> Unit,
    actionLabel: String = "",
    onAction: () -> Unit = {},
) {
    Card(
        colors = CardDefaults.defaultColors(
            color = if (error) COUITheme.colorScheme.errorTextBg
            else COUITheme.colorScheme.containerThemeHalftone,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                message,
                color = if (error) COUITheme.colorScheme.error
                else COUITheme.colorScheme.labelPrimary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (actionLabel.isNotBlank()) {
                    TextButton(text = actionLabel, onClick = onAction, minWidth = 0.dp)
                }
                TextButton(text = "知道了", onClick = onDismiss, minWidth = 0.dp)
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
        Card(colors = CardDefaults.defaultColors(color = COUITheme.colorScheme.errorTextBg)) {
            Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("书卷没有打开", style = COUITheme.textStyles.headlineM, fontWeight = FontWeight.SemiBold)
                Text(message, color = COUITheme.colorScheme.error)
                Button(onClick = onRetry, colors = ButtonDefaults.buttonColorsPrimary()) { Text("重试") }
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
        source.byteSize >= 1024 * 1024 -> add("${oneDecimal(source.byteSize / (1024.0 * 1024.0))} MB")
        source.byteSize >= 1024 -> add("${oneDecimal(source.byteSize / 1024.0)} KB")
        source.byteSize > 0 -> add("${source.byteSize} B")
    }
    if (source.timestamp.isNotBlank()) {
        add(source.timestamp.toLocalDateTimeDisplay())
    }
}.joinToString(" · ")

/** 保留一位小数：commonMain 没有 JVM 的 `String.format`。 */
private fun oneDecimal(value: Double): String {
    val scaled = (value * 10.0).roundToLong()
    return "${scaled / 10}.${scaled % 10}"
}
