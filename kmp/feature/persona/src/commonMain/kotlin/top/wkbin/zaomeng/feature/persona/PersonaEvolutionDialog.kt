package top.wkbin.zaomeng.feature.persona

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    AlertDialog(
        onDismissRequest = { if (!isApplying) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "角色长线成长提炼 · ${proposal.character}",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = proposal.evolutionSummary.ifBlank { "基于近期剧情与对话提炼出以下成长变更：" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(10.dp))

                if (proposal.changes.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "暂无可提炼的角色成长项。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "已选 ${selectedChanges.size} / ${proposal.changes.size} 项",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        TextButton(
                            onClick = {
                                if (selectedIndices.size == proposal.changes.size) {
                                    selectedIndices.clear()
                                } else {
                                    selectedIndices.clear()
                                    proposal.changes.indices.forEach { selectedIndices.add(it) }
                                }
                            },
                        ) {
                            Text(if (selectedIndices.size == proposal.changes.size) "取消全选" else "全选")
                        }
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
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isSelected) selectedIndices.remove(index) else selectedIndices.add(index)
                                    },
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                },
                                tonalElevation = if (isSelected) 2.dp else 0.dp,
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = { checked ->
                                            if (checked) selectedIndices.add(index) else selectedIndices.remove(index)
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
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                            )
                                            val categoryTag = when (change.category) {
                                                "bond" -> "🔗 羁绊"
                                                "conflict" -> "🌱 心境"
                                                "quote" -> "💬 台词"
                                                else -> "🌟 成长"
                                            }
                                            Text(
                                                text = categoryTag,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }

                                        if (change.currentValue.isNotBlank()) {
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                text = "原设定：${change.currentValue}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                            )
                                        }

                                        Spacer(Modifier.height(4.dp))
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.surface,
                                        ) {
                                            Text(
                                                text = "演化为：${change.proposedValue}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(6.dp),
                                            )
                                        }

                                        if (change.reason.isNotBlank()) {
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                text = "依据：${change.reason}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.outline,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onApply(selectedChanges) },
                enabled = !isApplying && selectedChanges.isNotEmpty(),
            ) {
                if (isApplying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("正在同步...")
                } else {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("应用选中的 ${selectedChanges.size} 项成长")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isApplying,
            ) {
                Text("取消")
            }
        },
    )
}
