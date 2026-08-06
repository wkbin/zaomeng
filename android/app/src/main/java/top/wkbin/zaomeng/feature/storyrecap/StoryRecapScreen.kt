package top.wkbin.zaomeng.feature.storyrecap

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wkbin.zaomeng.backend.NovelConversionForegroundController
import top.wkbin.zaomeng.data.api.StoryCharacterArcDto
import top.wkbin.zaomeng.data.api.StoryEventDto
import top.wkbin.zaomeng.data.api.StoryQuoteDto
import top.wkbin.zaomeng.data.api.StoryRelationChangeDto
import top.wkbin.zaomeng.data.api.StoryRecapDto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryRecapScreen(
    viewModel: StoryRecapViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val recap = state.session?.storyRecap
    var novelConversionStarted by remember { mutableStateOf(false) }
    var pendingNovelConversion by remember { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (pendingNovelConversion && granted) {
            novelConversionStarted = NovelConversionForegroundController.start(
                context,
                viewModel.runId,
                viewModel.sessionId,
            )
        }
        pendingNovelConversion = false
    }

    fun startNovelConversion() {
        if (NovelConversionForegroundController.hasNotificationPermission(context)) {
            novelConversionStarted = NovelConversionForegroundController.start(
                context,
                viewModel.runId,
                viewModel.sessionId,
            )
        } else {
            pendingNovelConversion = true
            notificationPermissionLauncher.launch(
                NovelConversionForegroundController.NOTIFICATION_PERMISSION,
            )
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("剧情复盘") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { shareRecap(context, recap) },
                        enabled = !state.loading && recap?.shareText?.isNotBlank() == true,
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "分享剧情复盘")
                    }
                    IconButton(
                        onClick = viewModel::load,
                        enabled = !state.loading && !state.refreshing,
                    ) {
                        if (state.refreshing) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新复盘")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        when {
            state.loading -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            state.error.isNotBlank() && recap == null -> RecapLoadError(
                message = state.error,
                onRetry = viewModel::load,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            recap == null -> EmptyRecap(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "header") { RecapHeader(recap) }
                if (recap.events.isNotEmpty()) {
                    item(key = "events") { EventTimelineCard(recap.events) }
                }
                if (recap.relations.isNotEmpty()) {
                    item(key = "relations") { RelationChangesCard(recap.relations) }
                }
                if (recap.characterArcs.isNotEmpty()) {
                    item(key = "arcs") { CharacterArcsCard(recap.characterArcs) }
                }
                if (recap.quotes.isNotEmpty()) {
                    item(key = "quotes") { QuoteCard(recap.quotes) }
                }
                if (recap.hooks.isNotEmpty()) {
                    item(key = "hooks") { HooksCard(recap.hooks) }
                }
                if (recap.nextHint.isNotBlank()) {
                    item(key = "next") { NextHintCard(recap.nextHint) }
                }
                item(key = "convert") {
                    NovelConversionCard(
                        started = novelConversionStarted,
                        onClick = ::startNovelConversion,
                    )
                }
                item(key = "footer-space") { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun RecapHeader(recap: StoryRecapDto) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = recap.title.ifBlank { "未命名章节" },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = recap.summary,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(
                recap.timeHint.takeIf(String::isNotBlank),
                recap.location.takeIf(String::isNotBlank),
                recap.atmosphere.takeIf(String::isNotBlank),
            )
            if (meta.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    meta.forEach { item ->
                        Text(
                            text = item,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = listOfNotNull(
                    "${recap.eventCount} 个事件",
                    "${recap.chapterCount} 幕",
                    "${recap.unresolvedHookCount} 条伏笔",
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun EventTimelineCard(events: List<StoryEventDto>) {
    RecapSectionCard(title = "近期事件") {
        events.forEachIndexed { index, event ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    event.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                listOf(event.timeHint, event.location)
                    .filter(String::isNotBlank)
                    .takeIf { it.isNotEmpty() }
                    ?.let { meta ->
                        Text(
                            meta.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                event.responses.forEach { response ->
                    Text(
                        "${response.speaker}：${response.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun RelationChangesCard(relations: List<StoryRelationChangeDto>) {
    RecapSectionCard(title = "关系变化") {
        relations.forEachIndexed { index, relation ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    relation.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val changes = relation.changes.joinToString(" · ") { change ->
                    "${change.label}${if (change.delta > 0) "+" else ""}${change.delta}"
                }
                if (changes.isNotBlank()) {
                    Text(changes, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    relation.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CharacterArcsCard(arcs: List<StoryCharacterArcDto>) {
    RecapSectionCard(title = "人物状态") {
        arcs.forEachIndexed { index, arc ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    arc.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    arc.growthSummary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                arc.current.values
                    .filter(String::isNotBlank)
                    .takeIf { it.isNotEmpty() }
                    ?.let { current ->
                        Text(
                            current.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
            }
        }
    }
}

@Composable
private fun QuoteCard(quotes: List<StoryQuoteDto>) {
    RecapSectionCard(title = "片段") {
        quotes.forEachIndexed { index, quote ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = quote.speaker,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = quote.message,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun HooksCard(hooks: List<String>) {
    RecapSectionCard(title = "待续伏笔") {
        hooks.forEach { hook ->
            Text(
                text = "· $hook",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun NovelConversionCard(
    started: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = if (started) "小说生成已在后台开始" else "生成本章小说",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (started) {
                    "完成后会通过通知提醒你。"
                } else {
                    "把这段对话改写成连贯的小说章节，至少需要 6 轮有效对话。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!started) {
                Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
                    Text("开始生成")
                }
            }
        }
    }
}

@Composable
private fun NextHintCard(nextHint: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "下一拍",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = nextHint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RecapSectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            content()
        }
    }
}

@Composable
private fun EmptyRecap(modifier: Modifier = Modifier) {
    Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "这局还没有足够剧情",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "继续聊几轮后，再回来生成剧情复盘。",
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecapLoadError(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Button(onClick = onRetry, modifier = Modifier.padding(top = 14.dp)) {
                Text("重新载入")
            }
        }
    }
}

private fun shareRecap(context: Context, recap: StoryRecapDto?) {
    val text = recap?.shareText?.takeIf(String::isNotBlank) ?: return
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    if (sendIntent.resolveActivity(context.packageManager) != null) {
        context.startActivity(Intent.createChooser(sendIntent, "分享剧情复盘"))
    }
}
