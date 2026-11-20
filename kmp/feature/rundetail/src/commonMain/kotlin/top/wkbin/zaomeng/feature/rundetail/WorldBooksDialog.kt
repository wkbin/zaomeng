package top.wkbin.zaomeng.feature.rundetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wkbin.zaomeng.data.api.ManagedWorldBookDto
import top.wkbin.zaomeng.data.api.ManagedWorldBookEntryDto

@Composable
internal fun WorldBooksDialog(
    viewModel: RunDetailViewModel,
    exportEnabled: Boolean,
    onClose: () -> Unit,
    onExport: (ManagedWorldBookDto) -> Unit,
) {
    val state by viewModel.worldBooks.collectAsStateWithLifecycle()
    val books = state.data?.books.orEmpty()
    var testText by rememberSaveable { mutableStateOf("") }
    var selectedEntry by remember { mutableStateOf<ManagedWorldBookEntryDto?>(null) }
    var removeBook by remember { mutableStateOf<ManagedWorldBookDto?>(null) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("SillyTavern 世界书") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.busy) item { Text("正在处理…") }
                if (state.error.isNotBlank()) {
                    item {
                        Text(state.error, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = viewModel::loadWorldBooks, enabled = !state.busy) { Text("重新加载") }
                    }
                }
                if (state.data != null && books.isEmpty()) {
                    item { Text("这个书卷没有挂载世界书。可以从「导入书卷」添加独立世界书。") }
                }
                if (books.any { it.entries.isNotEmpty() }) {
                    item {
                        OutlinedTextField(
                            value = testText,
                            onValueChange = { testText = it.take(2000) },
                            label = { Text("输入一句测试台词") },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 3,
                            enabled = !state.busy,
                        )
                        OutlinedButton(
                            onClick = { viewModel.testWorldBooks(testText) },
                            enabled = testText.isNotBlank() && !state.busy,
                        ) { Text("用已挂载的设定试触发") }
                    }
                }
                books.forEach { book ->
                    item(key = book.source) {
                        HorizontalDivider()
                        Text(book.displayName(), style = MaterialTheme.typography.titleSmall)
                        val enabled = book.entries.count { it.enabled }
                        val scope = if (book.source == "character_book") "卡内世界书 · 角色参演时生效" else "独立世界书"
                        Text("$scope · 启用 $enabled / ${book.entries.size} 条")
                        TextButton(onClick = { onExport(book) }, enabled = !state.busy && exportEnabled) {
                            Text("导出世界书 JSON")
                        }
                        if (book.source == "world_book") {
                            TextButton(onClick = { removeBook = book }, enabled = !state.busy) { Text("移除独立世界书") }
                        }
                    }
                    items(book.entries, key = { "${book.source}/${it.index}" }) { entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = { selectedEntry = entry },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Switch(
                                checked = entry.enabled,
                                onCheckedChange = { viewModel.setWorldBookEntry(book, entry.index, it) },
                                enabled = !state.busy,
                                modifier = Modifier.semantics { contentDescription = "启用 ${entry.title}" },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("关闭") } },
    )

    selectedEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { selectedEntry = null },
            title = { Text(entry.title) },
            text = {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (entry.constant) item { Text("常驻条目") }
                    if (entry.keywords.isNotEmpty()) item { Text("主关键词：${entry.keywords.joinToString("、")}") }
                    if (entry.secondaryKeywords.isNotEmpty()) item {
                        Text("次关键词：${entry.secondaryKeywords.joinToString("、")}")
                    }
                    items(entry.content.chunked(2000)) { Text(it) }
                }
            },
            confirmButton = { TextButton(onClick = { selectedEntry = null }) { Text("返回条目列表") } },
        )
    }
    removeBook?.let { book ->
        AlertDialog(
            onDismissRequest = { removeBook = null },
            title = { Text("移除独立世界书？") },
            text = { Text("「${book.displayName()}」的 ${book.entries.size} 条设定将从这个书卷解绑。") },
            confirmButton = {
                TextButton(onClick = {
                    removeBook = null
                    viewModel.removeWorldBook(book)
                }) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { removeBook = null }) { Text("取消") } },
        )
    }
    state.result?.let { result ->
        val bookNames = state.data?.books.orEmpty().associateBy { it.source }
        AlertDialog(
            onDismissRequest = viewModel::clearWorldBookTest,
            title = { Text("试触发结果 · 选中 ${result.injectedCount} 条") },
            text = {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item { Text("使用已挂载的世界书与测试台词。正式对话还会根据参演角色、近期消息和锁定事实筛选。") }
                    items(result.entries) { entry ->
                        val reason = when (entry.status) {
                            "injected" -> if (entry.keywords.isEmpty()) "常驻，已选中" else "已选中"
                            "budget" -> "超出注入上限"
                            "disabled" -> "已禁用"
                            "empty" -> "内容为空"
                            "secondary" -> "次关键词条件未满足"
                            "duplicate" -> "内容重复"
                            else -> "主关键词未命中"
                        }
                        Text("${entry.title} · $reason", style = MaterialTheme.typography.titleSmall)
                        val scope = when (entry.source) {
                            "character_book" -> "卡内世界书"
                            "world_book" -> "独立世界书"
                            else -> ""
                        }
                        if (scope.isNotEmpty()) {
                            val name = bookNames[entry.source]?.name.orEmpty()
                            Text(
                                if (name.isBlank()) scope else "$scope · $name",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (entry.keywords.isNotEmpty()) Text("命中 ${entry.keywords.joinToString("、")}")
                        if (entry.status == "injected") Text(entry.excerpt)
                    }
                    if (result.entries.size < result.entryCount) {
                        item { Text("显示 ${result.entries.size} / ${result.entryCount} 条。") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = viewModel::clearWorldBookTest) { Text("返回世界书") } },
        )
    }
}

private fun ManagedWorldBookDto.displayName(): String = name.ifBlank {
    if (source == "character_book") "卡内世界书" else "独立世界书"
}
