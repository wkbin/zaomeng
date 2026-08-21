package top.wkbin.zaomeng.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.feature.chat.insights.consistencyInsight

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
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onOpenTools),
        color = MaterialTheme.colorScheme.secondaryContainer,
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
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    summary.participants,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                consistencyIssueCount?.let { count ->
                    Text(
                        "$count 项一致性提醒，点击查看工具与详情",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Text(
                "工具",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (action == "fourth_wall") "第四面墙" else "剧情导演") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                directorActionOptions.chunked(2).forEach { actions ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        actions.forEach { option ->
                            FilterChip(
                                selected = action == option.value,
                                onClick = { action = option.value },
                                label = { Text(option.label) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = goal,
                    onValueChange = { goal = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (action == "fourth_wall") "以作者身份对角色下什么指令" else "这一幕希望怎样发展") },
                    placeholder = { Text(if (action == "fourth_wall") "例如：让他们和好，否则我删掉这段记忆" else "例如：让两人因为旧事发生正面冲突") },
                    minLines = 3,
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (enabled && goal.isNotBlank()) onGenerate(goal, action)
                        },
                    ),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onGenerate(goal, action) },
                enabled = enabled && goal.isNotBlank(),
            ) { Text("生成方案") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
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
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
        ),
        title = {
            Column {
                Text(
                    text = session?.participants?.joinToString("、")?.ifBlank { "人物会话" }
                        ?: "人物会话",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                session?.let {
                    Text(
                        text = "${it.mode.chineseMode()} · ${if (it.status == "ready") "可继续" else "待处理"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        },
        actions = {
            IconButton(onClick = onOpenSearch) {
                Icon(Icons.Default.Search, contentDescription = "搜索聊天记录")
            }
            IconButton(onClick = onOpenTools, enabled = toolsEnabled) {
                Icon(Icons.Default.MoreVert, contentDescription = "会话工具")
            }
            IconButton(onClick = onRefresh, enabled = refreshEnabled) {
                if (refreshing) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新聊天")
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title.ifBlank { "选择一个方案" }) },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(options, key = { "${it.label}-${it.value}" }) { option ->
                    OutlinedButton(
                        onClick = { onChoose(option) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = enabled,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
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
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (option.description.isNotBlank() && option.description != option.value) {
                                Text(
                                    option.description,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            Text("暂时无法打开会话", style = MaterialTheme.typography.titleMedium)
            if (error.isNotBlank()) {
                Text(
                    error,
                    modifier = Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) { Text("重试") }
        }
    }
}

