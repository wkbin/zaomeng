package top.wkbin.zaomeng.feature.chat
import top.wkbin.zaomeng.feature.chat.insights.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import top.wkbin.zaomeng.data.api.DialogueMemoryDto
import top.wkbin.zaomeng.data.api.ReusableCardDto
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

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
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onEdit, enabled = enabled) { Text("编辑") }
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "删除记忆",
                    tint = MaterialTheme.colorScheme.error,
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
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (memory.status == "active") {
                TextButton(
                    onClick = { onUpdateStatus(memory.memoryId, "stale") },
                    enabled = enabled,
                ) { Text("标记过期") }
                TextButton(
                    onClick = { onUpdateStatus(memory.memoryId, "conflict") },
                    enabled = enabled,
                ) { Text("标记冲突") }
            } else {
                Text(
                    if (memory.status == "stale") "已过期" else "有冲突",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(
                    onClick = { onUpdateStatus(memory.memoryId, "active") },
                    enabled = enabled,
                ) { Text("恢复使用") }
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
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (memory.memoryId.isBlank()) "添加记忆" else "编辑记忆") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = memory.text,
                    onValueChange = { onChange(memory.copy(text = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                    label = { Text("记忆内容") },
                    minLines = 3,
                    maxLines = 6,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf("story", "relationship", "short_term", "long_term").forEach { category ->
                        FilterChip(
                            selected = memory.category == category,
                            onClick = { onChange(memory.copy(category = category)) },
                            enabled = enabled,
                            label = { Text(category.memoryCategoryLabel()) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = memory.pinned,
                        onCheckedChange = { onChange(memory.copy(pinned = it)) },
                        enabled = enabled,
                    )
                    Text("置顶")
                    Checkbox(
                        checked = memory.enabled,
                        onCheckedChange = { onChange(memory.copy(enabled = it)) },
                        enabled = enabled,
                    )
                    Text("启用")
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(memory) }, enabled = memory.text.isNotBlank() && enabled) {
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } },
    )
}
