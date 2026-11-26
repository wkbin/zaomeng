package top.wkbin.zaomeng.feature.storyrecap

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
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardDefaults
import io.github.suqi8.coui.kmp.basic.CircularProgressIndicator
import io.github.suqi8.coui.kmp.basic.EmptyState
import io.github.suqi8.coui.kmp.basic.EmptyStateSizeType
import io.github.suqi8.coui.kmp.basic.HorizontalDivider
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.SnackbarHostState
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Refresh
import io.github.suqi8.coui.kmp.icon.extended.Share
import io.github.suqi8.coui.kmp.theme.COUITheme
import org.koin.compose.koinInject
import top.wkbin.zaomeng.data.api.StoryCharacterArcDto
import top.wkbin.zaomeng.data.api.StoryEventDto
import top.wkbin.zaomeng.data.api.StoryQuoteDto
import top.wkbin.zaomeng.data.api.StoryRecapDto
import top.wkbin.zaomeng.data.api.StoryRelationChangeDto
import top.wkbin.zaomeng.platform.NovelConversionForeground
import top.wkbin.zaomeng.platform.rememberNotificationPermissionRequester
import top.wkbin.zaomeng.platform.rememberShareText
import top.wkbin.zaomeng.ui.coui.CouiPageScaffold
import top.wkbin.zaomeng.ui.coui.CouiTopBarStyle
import top.wkbin.zaomeng.ui.coui.couiPageScroll
import top.wkbin.zaomeng.ui.coui.isWideWindow

@Composable
fun StoryRecapScreen(
    viewModel: StoryRecapViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recap = state.session?.storyRecap
    var novelConversionStarted by remember { mutableStateOf(false) }
    var pendingNovelConversion by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val novelConversionForeground: NovelConversionForeground = koinInject()
    val requestNotificationPermission = rememberNotificationPermissionRequester { granted ->
        if (pendingNovelConversion && granted) {
            novelConversionStarted = novelConversionForeground.start(
                viewModel.runId,
                viewModel.sessionId,
                "",
            )
        }
        pendingNovelConversion = false
    }
    val shareText = rememberShareText()

    fun startNovelConversion() {
        if (novelConversionForeground.hasNotificationPermission()) {
            novelConversionStarted = novelConversionForeground.start(
                viewModel.runId,
                viewModel.sessionId,
                "",
            )
        } else {
            pendingNovelConversion = true
            requestNotificationPermission()
        }
    }

    CouiPageScaffold(
        title = "剧情复盘",
        topBarStyle = CouiTopBarStyle.Small,
        onBack = onBack,
        wide = isWideWindow(),
        snackbarHostState = snackbarHostState,
        actions = {
            IconButton(
                onClick = { showExportDialog = true },
                enabled = !state.loading && recap != null && state.projectionStatus != "failed" && state.error.isBlank(),
            ) {
                Icon(COUIIcons.Share, contentDescription = "导出剧场与战报")
            }
            IconButton(
                onClick = viewModel::rebuild,
                enabled = !state.loading && !state.refreshing,
            ) {
                if (state.refreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), size = 20.dp, strokeWidth = 2.dp)
                } else {
                    Icon(COUIIcons.Refresh, contentDescription = "刷新复盘")
                }
            }
        },
    ) { scope ->
        Box(Modifier.fillMaxSize()) {
            when {
                state.loading || (state.projectionStatus == "building" && recap == null) -> Box(
                    Modifier
                        .fillMaxSize()
                        .padding(scope.contentPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                state.error.isNotBlank() && recap == null -> RecapLoadError(
                    message = state.error,
                    onRetry = viewModel::load,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(scope.contentPadding),
                )

                recap == null -> EmptyRecap(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(scope.contentPadding),
                )

                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(scope.contentPadding)
                        .couiPageScroll(scope),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (state.error.isNotBlank()) {
                        item(key = "projection-error") {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.defaultColors(
                                    color = COUITheme.colorScheme.errorTextBg,
                                ),
                            ) {
                                Text(
                                    text = "摘要已过期：${state.error}",
                                    modifier = Modifier.padding(12.dp),
                                    color = COUITheme.colorScheme.error,
                                    style = COUITheme.textStyles.bodyXS,
                                )
                            }
                        }
                    }
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

        if (showExportDialog && recap != null && state.projectionStatus != "failed" && state.error.isBlank()) {
            RecapExportDialog(
                recap = recap,
                onDismiss = { showExportDialog = false },
                snackbarHostState = snackbarHostState,
            )
        }
    }
}

@Composable
private fun RecapHeader(recap: StoryRecapDto) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = COUITheme.colorScheme.containerThemeHalftone,
        ),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = recap.title.ifBlank { "未命名章节" },
                style = COUITheme.textStyles.displayXS,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = recap.summary,
                style = COUITheme.textStyles.bodyXL,
                color = COUITheme.colorScheme.labelPrimary,
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
                            style = COUITheme.textStyles.buttonS,
                            color = COUITheme.colorScheme.labelPrimary,
                        )
                    }
                }
            }
            HorizontalDivider()
            Text(
                text = listOfNotNull(
                    "${recap.eventCount} 个事件",
                    "${recap.chapterCount} 幕",
                    "${recap.unresolvedHookCount} 条伏笔",
                ).joinToString(" · "),
                style = COUITheme.textStyles.buttonS,
                color = COUITheme.colorScheme.labelPrimary,
            )
        }
    }
}

@Composable
private fun EventTimelineCard(events: List<StoryEventDto>) {
    RecapSectionCard(title = "近期事件") {
        events.forEachIndexed { index, event ->
            if (index > 0) HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    event.title,
                    style = COUITheme.textStyles.bodyM,
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
                            style = COUITheme.textStyles.bodyXS,
                            color = COUITheme.colorScheme.labelSecondary,
                        )
                    }
                event.responses.forEach { response ->
                    Text(
                        "${response.speaker}：${response.message}",
                        style = COUITheme.textStyles.bodyXS,
                        color = COUITheme.colorScheme.labelSecondary,
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
            if (index > 0) HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    relation.label,
                    style = COUITheme.textStyles.bodyM,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val changes = relation.changes.joinToString(" · ") { change ->
                    "${change.label}${if (change.delta > 0) "+" else ""}${change.delta}"
                }
                if (changes.isNotBlank()) {
                    Text(changes, style = COUITheme.textStyles.buttonS, color = COUITheme.colorScheme.primary)
                }
                Text(
                    relation.reason,
                    style = COUITheme.textStyles.bodyXS,
                    color = COUITheme.colorScheme.labelSecondary,
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
            if (index > 0) HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    arc.name,
                    style = COUITheme.textStyles.bodyM,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    arc.growthSummary,
                    style = COUITheme.textStyles.bodyXS,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                arc.current.values
                    .filter(String::isNotBlank)
                    .takeIf { it.isNotEmpty() }
                    ?.let { current ->
                        Text(
                            current.joinToString(" · "),
                            style = COUITheme.textStyles.bodyXS,
                            color = COUITheme.colorScheme.labelSecondary,
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
            if (index > 0) HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = quote.speaker,
                    style = COUITheme.textStyles.buttonM,
                    color = COUITheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = quote.message,
                    modifier = Modifier.weight(1f),
                    style = COUITheme.textStyles.bodyM,
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
                style = COUITheme.textStyles.bodyM,
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
        colors = CardDefaults.defaultColors(
            color = COUITheme.colorScheme.backgroundWithCard,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = if (started) "小说生成已在后台开始" else "生成本章小说",
                style = COUITheme.textStyles.headlineXS,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (started) {
                    "完成后会通过通知提醒你。"
                } else {
                    "把这段对话改写成连贯的小说章节，至少需要 6 轮有效对话。"
                },
                style = COUITheme.textStyles.bodyXS,
                color = COUITheme.colorScheme.labelSecondary,
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
        colors = CardDefaults.defaultColors(
            color = COUITheme.colorScheme.containerThemeHalftone,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "下一拍",
                style = COUITheme.textStyles.bodyM,
                fontWeight = FontWeight.SemiBold,
                color = COUITheme.colorScheme.labelPrimary,
            )
            Text(
                text = nextHint,
                style = COUITheme.textStyles.bodyM,
                color = COUITheme.colorScheme.labelPrimary,
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
        colors = CardDefaults.defaultColors(
            color = COUITheme.colorScheme.backgroundWithCard,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = title,
                style = COUITheme.textStyles.headlineXS,
                fontWeight = FontWeight.SemiBold,
            )
            content()
        }
    }
}

@Composable
private fun EmptyRecap(modifier: Modifier = Modifier) {
    EmptyState(
        title = "这局还没有足够剧情",
        subtitle = "继续聊几轮后，再回来生成剧情复盘。",
        sizeType = EmptyStateSizeType.Small,
        modifier = modifier.padding(24.dp),
    )
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
                style = COUITheme.textStyles.bodyM,
                color = COUITheme.colorScheme.error,
            )
            Button(onClick = onRetry, modifier = Modifier.padding(top = 14.dp)) {
                Text("重新载入")
            }
        }
    }
}
