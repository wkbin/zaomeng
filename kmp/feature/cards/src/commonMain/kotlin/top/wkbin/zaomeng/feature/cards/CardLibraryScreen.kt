package top.wkbin.zaomeng.feature.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardDefaults
import io.github.suqi8.coui.kmp.basic.Chip
import io.github.suqi8.coui.kmp.basic.CircularProgressIndicator
import io.github.suqi8.coui.kmp.basic.DropdownImpl
import io.github.suqi8.coui.kmp.basic.EmptyState
import io.github.suqi8.coui.kmp.basic.EmptyStateSizeType
import io.github.suqi8.coui.kmp.basic.FloatingActionButton
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.ListPopupColumn
import io.github.suqi8.coui.kmp.basic.TabRow
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Add
import io.github.suqi8.coui.kmp.icon.extended.Create
import io.github.suqi8.coui.kmp.icon.extended.Delete
import io.github.suqi8.coui.kmp.icon.extended.Refresh
import io.github.suqi8.coui.kmp.overlay.OverlayListPopup
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import top.wkbin.zaomeng.data.ReusableCardKind
import top.wkbin.zaomeng.data.api.ReusableCardDto
import top.wkbin.zaomeng.ui.coui.CouiAlertDialog
import top.wkbin.zaomeng.ui.coui.CouiPageScaffold
import top.wkbin.zaomeng.ui.coui.CouiTextField
import top.wkbin.zaomeng.ui.coui.CouiTopBarStyle
import top.wkbin.zaomeng.ui.coui.couiGridScroll
import top.wkbin.zaomeng.ui.coui.isWideWindow

private val cardKinds = listOf(
    ReusableCardKind.Scene,
    ReusableCardKind.Self,
    ReusableCardKind.Opening,
)

@Composable
fun CardLibraryScreen(
    viewModel: CardLibraryViewModel,
    showBackButton: Boolean = true,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<ReusableCardDto?>(null) }

    CouiPageScaffold(
        title = "创作资料库",
        topBarStyle = CouiTopBarStyle.Small,
        contentTopSpacing = 8.dp,
        contentBottomSpacing = 80.dp,
        onBack = if (showBackButton) onBack else null,
        wide = isWideWindow(),
        actions = {
            if (state.kind != ReusableCardKind.Opening) {
                IconButton(onClick = viewModel::generate, enabled = !state.generating) {
                    if (state.generating) {
                        CircularProgressIndicator(size = 22.dp, strokeWidth = 2.dp)
                    } else {
                        Icon(COUIIcons.Create, contentDescription = "自动生成")
                    }
                }
            }
            IconButton(onClick = viewModel::load, enabled = !state.loading && !state.refreshing) {
                Icon(COUIIcons.Refresh, contentDescription = "刷新")
            }
        },
        topBarBottomContent = {
            TabRow(
                tabs = cardKinds.map { it.displayName },
                selectedTabIndex = cardKinds.indexOf(state.kind).coerceAtLeast(0),
                onTabSelected = { viewModel.selectKind(cardKinds[it]) },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::createCard) {
                Icon(COUIIcons.Add, contentDescription = "新建${state.kind.displayName}")
            }
        },
    ) { scope ->
        pendingDelete?.let { card ->
            CouiAlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = "删除${state.kind.displayName}？",
                confirmText = "删除",
                onConfirm = {
                    pendingDelete = null
                    viewModel.delete(card.cardId)
                },
                dismissText = "取消",
                onDismiss = { pendingDelete = null },
                body = {
                    Text(
                        "已经使用它创建的会话不会被删除，但以后不能再选择这张卡。",
                        style = COUITheme.textStyles.bodyM,
                    )
                },
            )
        }

        if (state.editorVisible) {
            CardEditorDialog(
                state = state,
                onFieldChange = viewModel::updateField,
                onSave = viewModel::save,
                onDismiss = viewModel::closeEditor,
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 380.dp),
            modifier = Modifier.couiGridScroll(scope).padding(horizontal = 16.dp),
            contentPadding = scope.contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.error.isNotBlank() || state.message.isNotBlank()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Card(
                        colors = CardDefaults.defaultColors(
                            color = if (state.error.isNotBlank()) {
                                COUITheme.colorScheme.errorTextBg
                            } else {
                                COUITheme.colorScheme.cardBackground
                            },
                            contentColor = if (state.error.isNotBlank()) {
                                COUITheme.colorScheme.error
                            } else {
                                COUITheme.colorScheme.labelPrimary
                            },
                        ),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                state.error.ifBlank { state.message },
                                style = COUITheme.textStyles.bodyM,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                text = "知道了",
                                onClick = viewModel::dismissNotice,
                                minWidth = 0.dp,
                                textStyle = COUITheme.textStyles.bodyM,
                            )
                        }
                    }
                }
            }
            if (state.loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (state.cards.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(
                        title = "还没有${state.kind.displayName}",
                        subtitle = if (state.kind == ReusableCardKind.Opening) {
                            "新建一个预设，把会话模式、人物与卡片组合保存下来。"
                        } else {
                            "可以手动新建，也可以用右上角的生成按钮起草一张。"
                        },
                        sizeType = EmptyStateSizeType.Small,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                }
            } else {
                items(state.cards, key = ReusableCardDto::cardId) { card ->
                    ReusableCardRow(
                        kind = state.kind,
                        card = card,
                        deleting = state.deletingCardId == card.cardId,
                        onEdit = { viewModel.editCard(card) },
                        onDelete = { pendingDelete = card },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReusableCardRow(
    kind: ReusableCardKind,
    card: ReusableCardDto,
    deleting: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val titleKey = when (kind) {
        ReusableCardKind.Scene, ReusableCardKind.Opening -> "title"
        ReusableCardKind.Self -> "display_name"
    }
    val detailKeys = when (kind) {
        ReusableCardKind.Scene -> listOf("location", "atmosphere", "scene_drive")
        ReusableCardKind.Self -> listOf("scene_identity", "core_identity", "speech_style")
        ReusableCardKind.Opening -> listOf("note", "mode")
    }
    val title = card.preview[titleKey]?.jsonPrimitive?.contentOrNull
        ?: card.fields[titleKey]?.jsonPrimitive?.contentOrNull
        ?: kind.displayName
    val details = detailKeys.mapNotNull { key ->
        card.preview[key]?.jsonPrimitive?.contentOrNull
            ?: card.fields[key]?.jsonPrimitive?.contentOrNull
    }.filter(String::isNotBlank).joinToString(" · ")

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = { if (!deleting) onEdit() },
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = COUITheme.textStyles.headlineXS,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    details.ifBlank { "点按查看和编辑" },
                    style = COUITheme.textStyles.bodyM,
                    color = COUITheme.colorScheme.labelSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDelete, enabled = !deleting) {
                if (deleting) {
                    CircularProgressIndicator(size = 20.dp, strokeWidth = 2.dp)
                } else {
                    Icon(
                        COUIIcons.Delete,
                        contentDescription = "删除",
                        tint = COUITheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun CardEditorDialog(
    state: CardLibraryUiState,
    onFieldChange: (String, String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val editing = state.draft.cardId.isNotBlank()
    CouiAlertDialog(
        onDismissRequest = onDismiss,
        title = if (editing) "编辑${state.kind.displayName}" else "新建${state.kind.displayName}",
        confirmText = if (state.saving) "保存中" else "保存",
        onConfirm = onSave,
        confirmEnabled = !state.saving,
        dismissText = "取消",
        onDismiss = { if (!state.saving) onDismiss() },
        body = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 570.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(definitions(state.kind), key = CardFieldDefinition::key) { field ->
                    when {
                        state.kind == ReusableCardKind.Opening && field.key == "mode" -> {
                            OpeningModeSelector(
                                selected = state.draft.values[field.key].orEmpty(),
                                enabled = !state.saving,
                                onSelected = { onFieldChange(field.key, it) },
                            )
                        }
                        state.kind == ReusableCardKind.Opening && field.key == "scene_card_id" -> {
                            ReusableCardSelector(
                                label = "场景卡",
                                kind = ReusableCardKind.Scene,
                                cards = state.sceneCards,
                                selectedId = state.draft.values[field.key].orEmpty(),
                                enabled = !state.saving,
                                onSelected = { onFieldChange(field.key, it) },
                            )
                        }
                        state.kind == ReusableCardKind.Opening && field.key == "self_card_id" -> {
                            ReusableCardSelector(
                                label = "自设卡",
                                kind = ReusableCardKind.Self,
                                cards = state.selfCards,
                                selectedId = state.draft.values[field.key].orEmpty(),
                                enabled = !state.saving,
                                onSelected = { onFieldChange(field.key, it) },
                            )
                        }
                        else -> CouiTextField(
                            value = state.draft.values[field.key].orEmpty(),
                            onValueChange = { onFieldChange(field.key, it) },
                            modifier = Modifier.fillMaxWidth(),
                            label = field.label + if (field.required) " *" else "",
                            enabled = !state.saving,
                            singleLine = field.singleLine,
                            minLines = if (field.singleLine) 1 else 2,
                            maxLines = if (field.singleLine) 1 else 5,
                        )
                    }
                }
                if (state.error.isNotBlank()) {
                    item {
                        Text(
                            state.error,
                            style = COUITheme.textStyles.bodyXS,
                            color = COUITheme.colorScheme.error,
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun OpeningModeSelector(
    selected: String,
    enabled: Boolean,
    onSelected: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("会话模式 *", style = COUITheme.textStyles.bodyXS)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("observe" to "旁观", "act" to "扮演", "insert" to "入场").forEach { (value, label) ->
                Chip(
                    selected = selected == value,
                    onClick = { onSelected(value) },
                    enabled = enabled,
                    label = label,
                )
            }
        }
    }
}

@Composable
private fun ReusableCardSelector(
    label: String,
    kind: ReusableCardKind,
    cards: List<ReusableCardDto>,
    selectedId: String,
    enabled: Boolean,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = cards.firstOrNull { it.cardId == selectedId }
    val options = listOf("不使用${kind.displayName}" to "") +
        cards.map { it.cardTitle(kind) to it.cardId }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = COUITheme.textStyles.bodyXS)
        Box(Modifier.fillMaxWidth()) {
            Button(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled && cards.isNotEmpty(),
                minWidth = 0.dp,
            ) {
                Text(
                    when {
                        selected != null -> selected.cardTitle(kind)
                        selectedId.isNotBlank() -> "原卡片已不存在"
                        cards.isEmpty() -> "资料库中还没有${kind.displayName}"
                        else -> "不使用${kind.displayName}"
                    },
                    style = COUITheme.textStyles.buttonM,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            OverlayListPopup(show = expanded, onDismissRequest = { expanded = false }) {
                ListPopupColumn {
                    options.forEachIndexed { index, option ->
                        DropdownImpl(
                            text = option.first,
                            optionSize = options.size,
                            isSelected = option.second == selectedId,
                            index = index,
                            onSelectedIndexChange = {
                                expanded = false
                                onSelected(option.second)
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun ReusableCardDto.cardTitle(kind: ReusableCardKind): String {
    val key = if (kind == ReusableCardKind.Self) "display_name" else "title"
    return preview[key]?.jsonPrimitive?.contentOrNull
        ?.takeIf(String::isNotBlank)
        ?: fields[key]?.jsonPrimitive?.contentOrNull
            ?.takeIf(String::isNotBlank)
        ?: kind.displayName
}
