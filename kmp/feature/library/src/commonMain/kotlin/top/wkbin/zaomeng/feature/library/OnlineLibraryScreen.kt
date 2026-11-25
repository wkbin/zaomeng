package top.wkbin.zaomeng.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardDefaults
import io.github.suqi8.coui.kmp.basic.CircularProgressIndicator
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.LinearProgressIndicator
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Download
import io.github.suqi8.coui.kmp.icon.extended.Refresh
import io.github.suqi8.coui.kmp.theme.COUITheme
import top.wkbin.zaomeng.data.library.OnlineLibraryBook
import top.wkbin.zaomeng.platform.formatNoDecimal
import top.wkbin.zaomeng.platform.formatOneDecimal
import top.wkbin.zaomeng.ui.coui.CouiPageScaffold
import top.wkbin.zaomeng.ui.coui.CouiTopBarStyle
import top.wkbin.zaomeng.ui.coui.couiPageScroll
import top.wkbin.zaomeng.ui.coui.isWideWindow

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
    CouiPageScaffold(
        title = "在线书卷包",
        topBarStyle = CouiTopBarStyle.Small,
        onBack = if (showBackButton) onBack else null,
        wide = isWideWindow(),
        actions = {
            IconButton(
                onClick = viewModel::refresh,
                enabled = !state.loading && state.importingBookId.isBlank(),
            ) {
                Icon(COUIIcons.Refresh, contentDescription = "刷新书卷包")
            }
        },
    ) { scope ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 380.dp),
            modifier = Modifier.couiPageScroll(scope).padding(horizontal = 20.dp),
            contentPadding = scope.contentPadding,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("造梦在线书卷包", style = COUITheme.textStyles.headlineM)
                    Text(
                        "这是已蒸馏的 .zaomeng-run.zip 包。下载并校验后会写入本机书架，不会上传你的小说或聊天数据。",
                        style = COUITheme.textStyles.bodyM,
                        color = COUITheme.colorScheme.labelSecondary,
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
                        CircularProgressIndicator(size = 28.dp, strokeWidth = 2.dp)
                        Text(
                            "正在读取书卷包...",
                            style = COUITheme.textStyles.bodyM,
                            color = COUITheme.colorScheme.labelSecondary,
                        )
                    }
                }
            }
            if (state.error.isNotBlank()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Card(
                        colors = CardDefaults.defaultColors(
                            color = COUITheme.colorScheme.error,
                            contentColor = COUITheme.colorScheme.onError,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                state.error,
                                style = COUITheme.textStyles.bodyM,
                                color = COUITheme.colorScheme.onError,
                            )
                            Button(
                                onClick = viewModel::refresh,
                                enabled = state.importingBookId.isBlank(),
                                minWidth = 0.dp,
                            ) {
                                Text("重试", style = COUITheme.textStyles.buttonM)
                            }
                        }
                    }
                }
            }
            if (!state.loading && state.books.isEmpty() && state.error.isBlank()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "暂时没有可下载的在线书卷包。",
                        style = COUITheme.textStyles.bodyM,
                        color = COUITheme.colorScheme.labelSecondary,
                    )
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(book.title, style = COUITheme.textStyles.headlineXS)
            val metadata = buildList {
                if (book.createdBy.isNotBlank()) add(book.createdBy)
                if (book.version.isNotBlank()) add("v${book.version}")
                if (book.sizeBytes > 0) add(readableLibrarySize(book.sizeBytes))
            }.joinToString(" · ")
            if (metadata.isNotBlank()) {
                Text(
                    metadata,
                    style = COUITheme.textStyles.bodyXS,
                    color = COUITheme.colorScheme.labelSecondary,
                )
            }
            if (book.summary.isNotBlank()) {
                Text(book.summary, style = COUITheme.textStyles.bodyM)
            }
            if (book.releaseNotes.isNotBlank()) {
                Text(
                    book.releaseNotes,
                    style = COUITheme.textStyles.bodyXS,
                    color = COUITheme.colorScheme.labelSecondary,
                )
            }
            if (installedVersion.isNotBlank()) {
                val updateAvailable = isPackageVersionNewer(book.version, installedVersion)
                Text(
                    if (updateAvailable) "已安装 v$installedVersion，可更新至 v${book.version}" else "已安装 v$installedVersion，已是最新版本",
                    style = COUITheme.textStyles.bodyXS,
                    color = if (updateAvailable) {
                        COUITheme.colorScheme.primary
                    } else {
                        COUITheme.colorScheme.labelSecondary
                    },
                )
            }
            Button(
                onClick = onImport,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                if (importing) {
                    CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
                } else {
                    Icon(COUIIcons.Download, contentDescription = null)
                }
                Text(
                    when {
                        importing -> "下载并导入中..."
                        installedVersion.isNotBlank() && isPackageVersionNewer(book.version, installedVersion) -> "更新为新副本"
                        installedVersion.isNotBlank() -> "再次导入新副本"
                        else -> "下载并导入"
                    },
                    style = COUITheme.textStyles.buttonM,
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
                    LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                    Text(
                        "${readableLibrarySize(downloadedBytes)} / ${readableLibrarySize(downloadTotalBytes)} (${(progress * 100).toInt()}%)",
                        style = COUITheme.textStyles.bodyXS,
                        color = COUITheme.colorScheme.labelSecondary,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        "正在下载书卷包...",
                        style = COUITheme.textStyles.bodyXS,
                        color = COUITheme.colorScheme.labelSecondary,
                    )
                }
                Button(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text("取消下载", style = COUITheme.textStyles.buttonM)
                }
            }
        }
    }
}

private fun readableLibrarySize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "${formatOneDecimal(bytes / (1024.0 * 1024.0))} MB"
    bytes >= 1024 -> "${formatNoDecimal(bytes / 1024.0)} KB"
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
