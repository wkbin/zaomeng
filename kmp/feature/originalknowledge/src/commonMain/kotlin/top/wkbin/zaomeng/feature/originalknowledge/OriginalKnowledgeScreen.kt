package top.wkbin.zaomeng.feature.originalknowledge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardDefaults
import io.github.suqi8.coui.kmp.basic.Chip
import io.github.suqi8.coui.kmp.basic.CircularProgressIndicator
import io.github.suqi8.coui.kmp.basic.EmptyState
import io.github.suqi8.coui.kmp.basic.EmptyStateSizeType
import io.github.suqi8.coui.kmp.basic.HorizontalDivider
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.basic.TextField
import io.github.suqi8.coui.kmp.basic.TextFieldMode
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Refresh
import io.github.suqi8.coui.kmp.icon.extended.Search
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import top.wkbin.zaomeng.data.api.OriginalKnowledgeEntryDto
import top.wkbin.zaomeng.ui.coui.CouiAlertDialog
import top.wkbin.zaomeng.ui.coui.CouiPage
import top.wkbin.zaomeng.ui.coui.CouiTopBarStyle
import top.wkbin.zaomeng.ui.coui.isWideWindow

@Composable
fun OriginalKnowledgeScreen(
    viewModel: OriginalKnowledgeViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editingEntry by remember { mutableStateOf<OriginalKnowledgeEntryDto?>(null) }

    CouiPage(
        title = "原文证据中心",
        topBarStyle = CouiTopBarStyle.Small,
        onBack = onBack,
        wide = isWideWindow(),
        actions = {
            IconButton(
                onClick = viewModel::rebuild,
                enabled = !state.rebuilding && state.busyEntryId.isBlank(),
            ) {
                if (state.rebuilding) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                } else {
                    Icon(COUIIcons.Refresh, contentDescription = "重建原文证据索引")
                }
            }
        },
        overlays = {
            editingEntry?.let { entry ->
                BoundaryDialog(
                    entry = entry,
                    characters = state.characters,
                    busy = state.busyEntryId == entry.sourceId,
                    onDismiss = { editingEntry = null },
                    onSave = { visibility, knowers ->
                        editingEntry = null
                        viewModel.saveBoundary(entry, visibility, knowers)
                    },
                )
            }
        },
    ) {
        item(key = "search") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                colors = CardDefaults.defaultColors(
                    color = COUITheme.colorScheme.cardBackground,
                ),
            ) {
                Column(
                    Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "搜索、固定并校对角色可知边界",
                        style = COUITheme.textStyles.headlineXS,
                        fontWeight = FontWeight.SemiBold,
                        color = COUITheme.colorScheme.labelPrimary,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextField(
                            value = state.query,
                            onValueChange = viewModel::updateQuery,
                            label = "人物、事件或原文关键词",
                            backgroundMode = TextFieldMode.Rectangle,
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = viewModel::search,
                            enabled = !state.loading,
                            colors = ButtonDefaults.buttonColorsPrimary(),
                        ) {
                            Icon(COUIIcons.Search, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("搜索")
                        }
                    }
                    SwitchPreference(
                        title = "只看固定证据",
                        checked = state.pinnedOnly,
                        onCheckedChange = viewModel::setPinnedOnly,
                        insideMargin = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                    )
                }
            }
        }
        if (state.error.isNotBlank() || state.message.isNotBlank()) {
            item(key = "notice") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    colors = CardDefaults.defaultColors(
                        color = if (state.error.isNotBlank()) {
                            COUITheme.colorScheme.containerRed
                        } else {
                            COUITheme.colorScheme.containerThemeHalftone
                        },
                    ),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            state.error.ifBlank { state.message },
                            modifier = Modifier.weight(1f),
                            color = COUITheme.colorScheme.labelPrimary,
                            style = COUITheme.textStyles.bodyM,
                        )
                        TextButton(text = "关闭", onClick = viewModel::dismissNotice)
                    }
                }
            }
        }
        when {
            state.loading -> item(key = "loading") {
                Row(
                    Modifier.fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            state.items.isEmpty() -> item(key = "empty") {
                EmptyState(
                    title = "没有找到匹配的原文证据。",
                    sizeType = EmptyStateSizeType.Small,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                )
            }

            else -> items(state.items, key = OriginalKnowledgeEntryDto::sourceId) { entry ->
                EvidenceCard(
                    entry = entry,
                    busy = state.busyEntryId == entry.sourceId,
                    onTogglePinned = { viewModel.setPinned(entry, !entry.pinned) },
                    onEditBoundary = { editingEntry = entry },
                )
            }
        }
    }
}

@Composable
private fun EvidenceCard(
    entry: OriginalKnowledgeEntryDto,
    busy: Boolean,
    onTogglePinned: () -> Unit,
    onEditBoundary: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.defaultColors(
            color = COUITheme.colorScheme.backgroundWithCard,
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.title.ifBlank { entry.sourceId },
                        style = COUITheme.textStyles.headlineXS,
                        fontWeight = FontWeight.SemiBold,
                        color = COUITheme.colorScheme.labelPrimary,
                    )
                    Text(
                        "${entry.sourceId} · 字符 ${entry.location.startChar}–${entry.location.endChar} · ${entry.visibility.visibilityLabel()}",
                        style = COUITheme.textStyles.description,
                        color = COUITheme.colorScheme.labelSecondary,
                    )
                }
                if (busy) CircularProgressIndicator(strokeWidth = 2.dp)
            }
            Text(
                entry.excerpt,
                style = COUITheme.textStyles.bodyM,
                color = COUITheme.colorScheme.labelPrimary,
            )
            if (entry.knowers.isNotEmpty()) {
                Text(
                    "知情角色：${entry.knowers.joinToString("、")}",
                    style = COUITheme.textStyles.bodyXS,
                    color = COUITheme.colorScheme.labelSecondary,
                )
            }
            if (entry.characters.isNotEmpty()) {
                Text(
                    "片段涉及：${entry.characters.joinToString("、")}",
                    style = COUITheme.textStyles.bodyXS,
                    color = COUITheme.colorScheme.labelSecondary,
                )
            }
            HorizontalDivider()
            Row(modifier = Modifier.align(Alignment.End)) {
                TextButton(
                    text = if (entry.pinned) "取消固定" else "固定证据",
                    onClick = onTogglePinned,
                    enabled = !busy,
                )
                TextButton(
                    text = "校对可知边界",
                    onClick = onEditBoundary,
                    enabled = !busy,
                )
            }
        }
    }
}

@Composable
private fun BoundaryDialog(
    entry: OriginalKnowledgeEntryDto,
    characters: List<String>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, List<String>) -> Unit,
) {
    var visibility by remember(entry.sourceId) { mutableStateOf(entry.visibility) }
    var knowers by remember(entry.sourceId) { mutableStateOf(entry.knowers.toSet()) }
    CouiAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = "校对角色可知边界",
        confirmText = "保存边界",
        onConfirm = { onSave(visibility, knowers.toList()) },
        confirmEnabled = !busy && (visibility !in setOf("private", "scene") || knowers.isNotEmpty()),
        dismissText = "取消",
        onDismiss = onDismiss,
        body = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        entry.excerpt,
                        style = COUITheme.textStyles.bodyM,
                        color = COUITheme.colorScheme.labelPrimary,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                item {
                    Text(
                        "可见范围",
                        style = COUITheme.textStyles.headlineXS,
                        fontWeight = FontWeight.SemiBold,
                        color = COUITheme.colorScheme.labelPrimary,
                    )
                }
                items(VISIBILITIES) { option ->
                    Chip(
                        selected = visibility == option,
                        onClick = { visibility = option },
                        label = option.visibilityLabel(),
                    )
                }
                if (visibility in setOf("private", "scene")) {
                    item {
                        Text(
                            "知情角色",
                            style = COUITheme.textStyles.headlineXS,
                            fontWeight = FontWeight.SemiBold,
                            color = COUITheme.colorScheme.labelPrimary,
                        )
                    }
                    items(characters, key = { it }) { character ->
                        Chip(
                            selected = character in knowers,
                            onClick = {
                                knowers = if (character in knowers) {
                                    knowers - character
                                } else {
                                    knowers + character
                                }
                            },
                            label = character,
                        )
                    }
                }
            }
        },
    )
}

private val VISIBILITIES = listOf("public", "scene", "private", "uncertain")

private fun String.visibilityLabel(): String = when (this) {
    "public" -> "公开信息"
    "scene" -> "同场角色可知"
    "private" -> "指定角色私密可知"
    else -> "边界不确定"
}
