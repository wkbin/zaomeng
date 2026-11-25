package top.wkbin.zaomeng.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.Chip
import io.github.suqi8.coui.kmp.basic.CircularProgressIndicator
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.Surface
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.More
import io.github.suqi8.coui.kmp.icon.extended.Refresh
import io.github.suqi8.coui.kmp.icon.extended.Search
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import io.github.suqi8.coui.kmp.theme.COUIRoundCorner
import io.github.suqi8.coui.kmp.theme.COUITheme
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.feature.chat.insights.consistencyInsight
import top.wkbin.zaomeng.ui.coui.CouiAlertDialog
import top.wkbin.zaomeng.ui.coui.CouiPageTopBar
import top.wkbin.zaomeng.ui.coui.CouiTextField
import top.wkbin.zaomeng.ui.coui.CouiTopBarStyle

private data class DirectorActionOption(val value: String, val label: String)

private val directorActionOptions = listOf(
    DirectorActionOption("advance", "推进剧情"),
    DirectorActionOption("slow_emotion", "放慢情绪"),
    DirectorActionOption("conflict", "加强冲突"),
    DirectorActionOption("viewpoint", "切换视角"),
    DirectorActionOption("fourth_wall", "第四面墙"),
)

/** 桌面端聊天主从布局左侧面板：本卷会话列表，点击切换到其他会话。 */
@Composable
internal fun ChatContextStrip(session: DialogueSessionDto, onOpenTools: () -> Unit) {
    val summary = remember(session) { chatContextSummary(session) }
    val consistencyIssueCount = session.consistencyMonitor.consistencyInsight()
        ?.issueCount
        ?.takeIf { it > 0 }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(SquircleShape(COUIRoundCorner.Normal.M, COUIRoundCorner.Normal.Weight))
            .clickable(onClick = onOpenTools),
        color = COUITheme.colorScheme.containerThemeHalftone,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    "${summary.mode} · ${summary.scene}",
                    style = COUITheme.textStyles.buttonM,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    summary.participants,
                    style = COUITheme.textStyles.bodyXS,
                    color = COUITheme.colorScheme.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                consistencyIssueCount?.let { count ->
                    Text(
                        "$count 项一致性提醒，点击查看工具与详情",
                        style = COUITheme.textStyles.description,
                        color = COUITheme.colorScheme.error,
                    )
                }
            }
            Text(
                "工具",
                style = COUITheme.textStyles.description,
                color = COUITheme.colorScheme.primary,
            )
        }
    }
}

@Composable
internal fun DirectorDialog(
    enabled: Boolean,
    onDismiss: () -> Unit,
    onGenerate: (goal: String, action: String) -> Unit,
) {
    var goal by rememberSaveable { mutableStateOf("") }
    var action by rememberSaveable { mutableStateOf("advance") }
    CouiAlertDialog(
        onDismissRequest = onDismiss,
        title = if (action == "fourth_wall") "第四面墙" else "剧情导演",
        confirmText = "生成方案",
        onConfirm = { onGenerate(goal, action) },
        confirmEnabled = enabled && goal.isNotBlank(),
        body = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                directorActionOptions.chunked(2).forEach { actions ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        actions.forEach { option ->
                            Chip(
                                selected = action == option.value,
                                onClick = { action = option.value },
                                label = option.label,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                CouiTextField(
                    value = goal,
                    onValueChange = { goal = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = if (action == "fourth_wall") "以作者身份对角色下什么指令" else "这一幕希望怎样发展",
                    singleLine = false,
                    minLines = 3,
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (enabled && goal.isNotBlank()) onGenerate(goal, action)
                        },
                    ),
                )
                Text(
                    if (action == "fourth_wall") {
                        "例如：让他们和好，否则我删掉这段记忆"
                    } else {
                        "例如：让两人因为旧事发生正面冲突"
                    },
                    style = COUITheme.textStyles.description,
                    color = COUITheme.colorScheme.labelSecondary,
                )
            }
        },
    )
}

@Composable
internal fun ChatTopBar(
    session: DialogueSessionDto?,
    refreshing: Boolean,
    refreshEnabled: Boolean,
    toolsEnabled: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    CouiPageTopBar(
        title = session?.participants?.joinToString("、")?.ifBlank { "人物会话" }
            ?: "人物会话",
        scrollBehavior = null,
        subtitle = session?.let {
            "${it.mode.chineseMode()} · ${if (it.status == "ready") "可继续" else "待处理"}"
        }.orEmpty(),
        onBack = onBack,
        style = CouiTopBarStyle.Small,
        // 聊天页顶栏叠在背景图与半透明遮罩之上，由外层 Surface 负责底色，这里必须透明且不画分割线，
        // 否则 COUI 顶栏的实底会盖掉背景图。
        color = Color.Transparent,
        showDivider = false,
        actions = {
            IconButton(onClick = onOpenSearch) {
                Icon(COUIIcons.Search, contentDescription = "搜索聊天记录")
            }
            IconButton(onClick = onOpenTools, enabled = toolsEnabled) {
                Icon(COUIIcons.More, contentDescription = "会话工具")
            }
            IconButton(onClick = onRefresh, enabled = refreshEnabled) {
                if (refreshing) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Icon(COUIIcons.Refresh, contentDescription = "刷新聊天")
                }
            }
        },
    )
}

@Composable
internal fun ChatToolOptionsDialog(
    title: String,
    options: List<ChatToolOption>,
    enabled: Boolean,
    onChoose: (ChatToolOption) -> Unit,
    onDismiss: () -> Unit,
) {
    CouiAlertDialog(
        onDismissRequest = onDismiss,
        title = title.ifBlank { "选择一个方案" },
        confirmText = "取消",
        onConfirm = onDismiss,
        dismissText = null,
        body = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(options, key = { "${it.label}-${it.value}" }) { option ->
                    Button(
                        onClick = { onChoose(option) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = enabled,
                        cornerRadius = 10.dp,
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(option.label, fontWeight = FontWeight.SemiBold)
                            if (
                                option.pluginSelection.isBlank() &&
                                option.value.isNotBlank() &&
                                option.value != option.label
                            ) {
                                Text(
                                    option.value,
                                    style = COUITheme.textStyles.bodyXS,
                                    color = COUITheme.colorScheme.labelSecondary,
                                )
                            }
                            if (option.description.isNotBlank() && option.description != option.value) {
                                Text(
                                    option.description,
                                    style = COUITheme.textStyles.description,
                                    color = COUITheme.colorScheme.labelSecondary,
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
internal fun ChatLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                "正在打开这段故事…",
                modifier = Modifier.padding(top = 12.dp),
                color = COUITheme.colorScheme.labelSecondary,
            )
        }
    }
}

@Composable
internal fun MissingChat(error: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("暂时无法打开会话", style = COUITheme.textStyles.headlineXS)
            if (error.isNotBlank()) {
                Text(
                    error,
                    modifier = Modifier.padding(top = 8.dp),
                    color = COUITheme.colorScheme.error,
                    style = COUITheme.textStyles.bodyM,
                )
            }
            Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) { Text("重试") }
        }
    }
}
