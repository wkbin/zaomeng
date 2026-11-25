package top.wkbin.zaomeng.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Checkbox
import io.github.suqi8.coui.kmp.basic.Chip
import io.github.suqi8.coui.kmp.basic.HorizontalDivider
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Delete
import io.github.suqi8.coui.kmp.theme.COUITheme
import top.wkbin.zaomeng.data.api.DialogueMemoryDto
import top.wkbin.zaomeng.feature.chat.insights.*
import top.wkbin.zaomeng.ui.coui.CouiAlertDialog
import top.wkbin.zaomeng.ui.coui.CouiTextField

@Composable
internal fun MemoryRow(
    memory: DialogueMemoryDto,
    enabled: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(memory.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(
                    "${memory.category.memoryCategoryLabel()}${if (memory.pinned) " · 已置顶" else ""}${if (!memory.enabled) " · 已停用" else ""}",
                    style = COUITheme.textStyles.bodyXS,
                    color = COUITheme.colorScheme.labelSecondary,
                )
            }
            TextButton(
                text = "编辑",
                onClick = onEdit,
                enabled = enabled,
                minWidth = ButtonDefaults.MinWidthSmall,
            )
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(
                    COUIIcons.Delete,
                    contentDescription = "删除记忆",
                    tint = COUITheme.colorScheme.error,
                )
            }
        }
        HorizontalDivider()
    }
}

@Composable
internal fun AutomaticMemoryRow(
    memory: DialogueMemoryDto,
    enabled: Boolean,
    onUpdateStatus: (String, String) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(memory.text, maxLines = 4, overflow = TextOverflow.Ellipsis)
        Text(
            buildString {
                append("自动 · 来源轮次 ")
                append(memory.sourceTurnId.ifBlank { "未知" })
                append(" · 命中 ")
                append(memory.hitCount)
                append(" 次")
                if (memory.duplicateOf.isNotBlank()) append(" · 疑似重复")
            },
            style = COUITheme.textStyles.bodyXS,
            color = COUITheme.colorScheme.labelSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (memory.status == "active") {
                TextButton(
                    text = "标记过期",
                    onClick = { onUpdateStatus(memory.memoryId, "stale") },
                    enabled = enabled,
                    minWidth = ButtonDefaults.MinWidthSmall,
                )
                TextButton(
                    text = "标记冲突",
                    onClick = { onUpdateStatus(memory.memoryId, "conflict") },
                    enabled = enabled,
                    minWidth = ButtonDefaults.MinWidthSmall,
                )
            } else {
                Text(
                    if (memory.status == "stale") "已过期" else "有冲突",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    style = COUITheme.textStyles.buttonM,
                    color = COUITheme.colorScheme.error,
                )
                TextButton(
                    text = "恢复使用",
                    onClick = { onUpdateStatus(memory.memoryId, "active") },
                    enabled = enabled,
                    minWidth = ButtonDefaults.MinWidthSmall,
                )
            }
        }
        HorizontalDivider()
    }
}

@Composable
internal fun MemoryEditor(
    memory: DialogueMemoryDto,
    busy: Boolean,
    enabled: Boolean,
    onChange: (DialogueMemoryDto) -> Unit,
    onSave: (DialogueMemoryDto) -> Unit,
    onDismiss: () -> Unit,
) {
    CouiAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = if (memory.memoryId.isBlank()) "添加记忆" else "编辑记忆",
        confirmText = "保存",
        onConfirm = { onSave(memory) },
        confirmEnabled = memory.text.isNotBlank() && enabled,
        onDismiss = { if (!busy) onDismiss() },
        body = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CouiTextField(
                    value = memory.text,
                    onValueChange = { onChange(memory.copy(text = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                    label = "记忆内容",
                    singleLine = false,
                    minLines = 3,
                    maxLines = 6,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf("story", "relationship", "short_term", "long_term").forEach { category ->
                        Chip(
                            selected = memory.category == category,
                            onClick = { onChange(memory.copy(category = category)) },
                            enabled = enabled,
                            label = category.memoryCategoryLabel(),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        state = ToggleableState(memory.pinned),
                        onClick = { onChange(memory.copy(pinned = !memory.pinned)) },
                        enabled = enabled,
                    )
                    Text("置顶")
                    Checkbox(
                        state = ToggleableState(memory.enabled),
                        onClick = { onChange(memory.copy(enabled = !memory.enabled)) },
                        enabled = enabled,
                    )
                    Text("启用")
                }
            }
        },
    )
}
