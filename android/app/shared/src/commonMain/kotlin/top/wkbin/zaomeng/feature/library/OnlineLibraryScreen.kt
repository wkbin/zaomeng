package top.wkbin.zaomeng.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wkbin.zaomeng.data.library.OnlineLibraryBook

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineLibraryScreen(
    viewModel: OnlineLibraryViewModel,
    showBackButton: Boolean = true,
    onBack: () -> Unit,
    onRunImported: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.createdRunId) {
        if (state.createdRunId.isNotBlank()) {
            val runId = state.createdRunId
            viewModel.consumeCreatedRun()
            onRunImported(runId)
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("在线书卷包") },
                navigationIcon = {
                    if (showBackButton) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.loading && state.importingBookId.isBlank()) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新书卷包")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 380.dp),
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("造梦在线书卷包", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "这是已蒸馏的 .zaomeng-run.zip 包。下载并校验后会写入本机书架，不会上传你的小说或聊天数据。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                        Text("正在读取书卷包...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (state.error.isNotBlank()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(state.error, color = MaterialTheme.colorScheme.onErrorContainer)
                            OutlinedButton(onClick = viewModel::refresh, enabled = state.importingBookId.isBlank()) {
                                Text("重试")
                            }
                        }
                    }
                }
            }
            if (!state.loading && state.books.isEmpty() && state.error.isBlank()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("暂时没有可下载的在线书卷包。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(state.books, key = OnlineLibraryBook::id) { book ->
                OnlineLibraryBookCard(
                    book = book,
                    importing = state.importingBookId == book.id,
                    installedVersion = state.installedVersions[book.id].orEmpty(),
                    downloadedBytes = state.downloadedBytes,
                    downloadTotalBytes = state.downloadTotalBytes,
                    enabled = state.importingBookId.isBlank(),
                    onImport = { viewModel.importBook(book) },
                    onCancel = viewModel::cancelImport,
                )
            }
        }
    }
}

@Composable
private fun OnlineLibraryBookCard(
    book: OnlineLibraryBook,
    importing: Boolean,
    installedVersion: String,
    downloadedBytes: Long,
    downloadTotalBytes: Long,
    enabled: Boolean,
    onImport: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(book.title, style = MaterialTheme.typography.titleMedium)
            val metadata = buildList {
                if (book.createdBy.isNotBlank()) add(book.createdBy)
                if (book.version.isNotBlank()) add("v${book.version}")
                if (book.sizeBytes > 0) add(readableLibrarySize(book.sizeBytes))
            }.joinToString(" · ")
            if (metadata.isNotBlank()) {
                Text(metadata, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (book.summary.isNotBlank()) {
                Text(book.summary, style = MaterialTheme.typography.bodyMedium)
            }
            if (book.releaseNotes.isNotBlank()) {
                Text(book.releaseNotes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (installedVersion.isNotBlank()) {
                val updateAvailable = isPackageVersionNewer(book.version, installedVersion)
                Text(
                    if (updateAvailable) "已安装 v$installedVersion，可更新至 v${book.version}" else "已安装 v$installedVersion，已是最新版本",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (updateAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onImport, modifier = Modifier.fillMaxWidth(), enabled = enabled) {
                if (importing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.CloudDownload, contentDescription = null)
                }
                Text(
                    when {
                        importing -> "下载并导入中..."
                        installedVersion.isNotBlank() && isPackageVersionNewer(book.version, installedVersion) -> "更新为新副本"
                        installedVersion.isNotBlank() -> "再次导入新副本"
                        else -> "下载并导入"
                    },
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (importing) {
                val progress = if (downloadTotalBytes > 0) {
                    (downloadedBytes.toFloat() / downloadTotalBytes).coerceIn(0f, 1f)
                } else {
                    0f
                }
                if (downloadTotalBytes > 0) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "${readableLibrarySize(downloadedBytes)} / ${readableLibrarySize(downloadTotalBytes)} (${(progress * 100).toInt()}%)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("正在下载书卷包...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text("取消下载")
                }
            }
        }
    }
}

private fun readableLibrarySize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024f * 1024f))
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024f)
    else -> "$bytes B"
}

internal fun isPackageVersionNewer(remote: String, installed: String): Boolean {
    fun parts(value: String): List<Int>? = value.removePrefix("v").substringBefore('-').split('.')
        .map { it.toIntOrNull() ?: return null }
    val remoteParts = parts(remote) ?: return false
    val installedParts = parts(installed) ?: return false
    repeat(maxOf(remoteParts.size, installedParts.size)) { index ->
        val comparison = remoteParts.getOrElse(index) { 0 }.compareTo(installedParts.getOrElse(index) { 0 })
        if (comparison != 0) return comparison > 0
    }
    return false
}
