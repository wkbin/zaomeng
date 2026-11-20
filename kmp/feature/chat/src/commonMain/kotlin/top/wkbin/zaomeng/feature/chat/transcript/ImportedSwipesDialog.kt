package top.wkbin.zaomeng.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import top.wkbin.zaomeng.data.api.ImportedSwipePageDto

@Composable
internal fun ImportedSwipesDialog(
    messageIndex: String,
    load: suspend (String, Int) -> ImportedSwipePageDto,
    onBranch: ((Int) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val latestLoad by rememberUpdatedState(load)
    var offset by rememberSaveable(messageIndex) { mutableStateOf(0) }
    var retry by remember(messageIndex) { mutableStateOf(0) }
    var page by remember(messageIndex, offset, retry) { mutableStateOf<ImportedSwipePageDto?>(null) }
    var error by remember(messageIndex, offset, retry) { mutableStateOf(false) }
    LaunchedEffect(messageIndex, offset, retry) {
        try {
            page = latestLoad(messageIndex, offset)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = true
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("SillyTavern 回复版本") },
        text = {
            val current = page
            when {
                error -> Column {
                    Text("读取回复版本失败。", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { retry++ }) { Text("重试") }
                }
                current == null -> Text("正在读取…")
                current.total == 0 -> Text("这条消息没有备用回复。")
                else -> Column {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(current.items, key = { it.index }) { item ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                val label = if (item.index == current.selectedIndex) " · 当前版本" else ""
                                Text("版本 ${item.index + 1}$label", style = MaterialTheme.typography.labelLarge)
                                SelectionContainer { Text(item.text) }
                                if (item.truncated) Text("这里只显示前 16,000 字符，导出会保留全文。")
                                if (onBranch != null && !item.truncated && item.text.isNotBlank()) {
                                    TextButton(onClick = { onBranch(item.index) }) { Text("从此版本开分支") }
                                }
                            }
                        }
                    }
                    if (offset > 0 || offset + current.items.size < current.total) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { offset = (offset - 4).coerceAtLeast(0) }, enabled = offset > 0) {
                                Text("上一页")
                            }
                            TextButton(
                                onClick = { offset += current.items.size },
                                enabled = offset + current.items.size < current.total,
                            ) { Text("下一页") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
