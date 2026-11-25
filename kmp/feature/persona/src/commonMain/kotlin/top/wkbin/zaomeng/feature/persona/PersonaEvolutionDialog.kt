package top.wkbin.zaomeng.feature.persona

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Checkbox
import io.github.suqi8.coui.kmp.basic.EmptyState
import io.github.suqi8.coui.kmp.basic.EmptyStateSizeType
import io.github.suqi8.coui.kmp.basic.Surface
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.layout.DialogButtonBar
import io.github.suqi8.coui.kmp.layout.DialogButtonBarAction
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import io.github.suqi8.coui.kmp.theme.COUIRoundCorner
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.window.WindowDialog
import top.wkbin.zaomeng.data.api.PersonaEvolutionChangeDto
import top.wkbin.zaomeng.data.api.PersonaEvolutionProposalDto

@Composable
fun PersonaEvolutionDialog(
    proposal: PersonaEvolutionProposalDto,
    isApplying: Boolean,
    onApply: (List<PersonaEvolutionChangeDto>) -> Unit,
    onDismiss: () -> Unit,
) {
    val selectedIndices = remember {
        mutableStateListOf<Int>().apply {
            proposal.changes.indices.forEach { add(it) }
        }
    }

    val selectedChanges = remember(selectedIndices.size) {
        selectedIndices.sorted().mapNotNull { proposal.changes.getOrNull(it) }
    }

    // 调用方在页面 Scaffold 之外渲染本弹窗，因此用 Window 版弹层（自带平台 Dialog），不依赖 COUIPopupHost。
    WindowDialog(
        show = true,
        title = "角色长线成长提炼 · ${proposal.character}",
        onDismissRequest = { if (!isApplying) onDismiss() },
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, top = 16.dp, end = 24.dp),
                ) {
                    Text(
                        text = proposal.evolutionSummary.ifBlank { "基于近期剧情与对话提炼出以下成长变更：" },
                        style = COUITheme.textStyles.bodyM,
                        color = COUITheme.colorScheme.labelSecondary,
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    if (proposal.changes.isEmpty()) {
                        EmptyState(
                            title = "暂无可提炼的角色成长项。",
                            sizeType = EmptyStateSizeType.Small,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "已选 ${selectedChanges.size} / ${proposal.changes.size} 项",
                                style = COUITheme.textStyles.description,
                                color = COUITheme.colorScheme.primary,
                            )
                            TextButton(
                                text = if (selectedIndices.size == proposal.changes.size) "取消全选" else "全选",
                                onClick = {
                                    if (selectedIndices.size == proposal.changes.size) {
                                        selectedIndices.clear()
                                    } else {
                                        selectedIndices.clear()
                                        proposal.changes.indices.forEach { selectedIndices.add(it) }
                                    }
                                },
                            )
                        }

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(proposal.changes.size) { index ->
                                val change = proposal.changes[index]
                                val isSelected = selectedIndices.contains(index)

                                Surface(
                                    onClick = {
                                        if (isSelected) {
                                            selectedIndices.remove(index)
                                        } else {
                                            selectedIndices.add(index)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = SquircleShape(COUIRoundCorner.Normal.S, COUIRoundCorner.Normal.Weight),
                                    color = if (isSelected) {
                                        COUITheme.colorScheme.containerThemeHalftone
                                    } else {
                                        COUITheme.colorScheme.containerSolid
                                    },
                                    contentColor = COUITheme.colorScheme.labelPrimary,
                                    shadowElevation = if (isSelected) 2.dp else 0.dp,
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.Top,
                                    ) {
                                        Checkbox(
                                            state = if (isSelected) ToggleableState.On else ToggleableState.Off,
                                            onClick = {
                                                if (isSelected) {
                                                    selectedIndices.remove(index)
                                                } else {
                                                    selectedIndices.add(index)
                                                }
                                            },
                                            modifier = Modifier.padding(top = 2.dp),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(
                                                    text = change.fieldLabel.ifBlank { change.field },
                                                    style = COUITheme.textStyles.headlineXS,
                                                    fontWeight = FontWeight.Bold,
                                                    color = COUITheme.colorScheme.labelPrimary,
                                                )
                                                val categoryTag = when (change.category) {
                                                    "bond" -> "🔗 羁绊"
                                                    "conflict" -> "🌱 心境"
                                                    "quote" -> "💬 台词"
                                                    else -> "🌟 成长"
                                                }
                                                Text(
                                                    text = categoryTag,
                                                    style = COUITheme.textStyles.description,
                                                    color = COUITheme.colorScheme.primary,
                                                )
                                            }

                                            if (change.currentValue.isNotBlank()) {
                                                Spacer(Modifier.height(4.dp))
                                                Text(
                                                    text = "原设定：${change.currentValue}",
                                                    style = COUITheme.textStyles.bodyXS,
                                                    color = COUITheme.colorScheme.labelSecondary,
                                                    maxLines = 2,
                                                )
                                            }

                                            Spacer(Modifier.height(4.dp))
                                            Surface(
                                                shape = SquircleShape(COUIRoundCorner.Normal.XS, COUIRoundCorner.Normal.Weight),
                                                color = COUITheme.colorScheme.containerThemeHalftone,
                                                contentColor = COUITheme.colorScheme.primary,
                                            ) {
                                                Text(
                                                    text = "演化为：${change.proposedValue}",
                                                    style = COUITheme.textStyles.bodyM,
                                                    fontWeight = FontWeight.Medium,
                                                    color = COUITheme.colorScheme.primary,
                                                    modifier = Modifier.padding(6.dp),
                                                )
                                            }

                                            if (change.reason.isNotBlank()) {
                                                Spacer(Modifier.height(4.dp))
                                                Text(
                                                    text = "依据：${change.reason}",
                                                    style = COUITheme.textStyles.description,
                                                    color = COUITheme.colorScheme.outline,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                DialogButtonBar(
                    negative = DialogButtonBarAction(
                        text = "取消",
                        enabled = !isApplying,
                        onClick = onDismiss,
                    ),
                    positive = DialogButtonBarAction(
                        text = if (isApplying) "正在同步..." else "应用选中的 ${selectedChanges.size} 项成长",
                        enabled = !isApplying && selectedChanges.isNotEmpty(),
                        onClick = { onApply(selectedChanges) },
                    ),
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
            }
        },
    )
}
