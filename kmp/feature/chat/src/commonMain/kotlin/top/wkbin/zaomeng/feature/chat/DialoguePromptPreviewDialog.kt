package top.wkbin.zaomeng.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.theme.COUITheme
import top.wkbin.zaomeng.data.api.DialoguePromptPreviewDto
import top.wkbin.zaomeng.data.api.WorldBookInjectionPosition
import top.wkbin.zaomeng.ui.coui.CouiAlertDialog

@Composable
internal fun DialoguePromptPreviewDialog(preview: DialoguePromptPreviewDto, onDismiss: () -> Unit) {
    CouiAlertDialog(
        onDismissRequest = onDismiss,
        title = "模型提示词",
        summary = "第 ${preview.attempt} 次生成请求 · ${preview.messages.size} 条消息 · ${preview.worldBookMatches.size} 条世界书命中",
        confirmText = "关闭",
        onConfirm = onDismiss,
        dismissText = null,
        body = {
            key(preview.turnId, preview.attempt) {
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    itemsIndexed(preview.messages, key = { index, _ -> "message-$index" }) { index, message ->
                        val longMessage = message.text.length > 120 || '\n' in message.text
                        var expanded by rememberSaveable { mutableStateOf(false) }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("${index + 1}. ${message.role}", Modifier.weight(1f), style = COUITheme.textStyles.headlineXS)
                                if (longMessage) {
                                    TextButton(
                                        text = if (expanded) "收起" else "展开",
                                        onClick = { expanded = !expanded },
                                        minWidth = 0.dp,
                                    )
                                }
                            }
                            if (message.text.isNotEmpty()) {
                                if (longMessage && !expanded) {
                                    Text(message.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                } else {
                                    SelectionContainer { Text(message.text) }
                                }
                            }
                            if (message.truncated) {
                                Text(
                                    "预览已截断",
                                    style = COUITheme.textStyles.bodyXS,
                                    color = COUITheme.colorScheme.labelSecondary,
                                )
                            }
                        }
                    }
                    if (preview.worldBookMatches.isNotEmpty()) {
                        item("world-book-heading") { Text("世界书命中", style = COUITheme.textStyles.headlineXS) }
                        itemsIndexed(preview.worldBookMatches, key = { index, _ -> "world-book-$index" }) { _, entry ->
                            val source = when (entry.source) {
                                "character_book" -> "卡内世界书"
                                "world_book" -> "书卷世界书"
                                else -> entry.source
                            }
                            val placement = when (entry.position) {
                                WorldBookInjectionPosition.AtDepth -> "深度 ${entry.injectionDepth ?: 4} · ${entry.injectionRole?.chatRole ?: "system"}"
                                WorldBookInjectionPosition.Outlet -> "Outlet ${entry.outletName.orEmpty()}"
                                WorldBookInjectionPosition.BeforeCharacter -> "角色定义前"
                                WorldBookInjectionPosition.AfterCharacter -> "角色定义后"
                                WorldBookInjectionPosition.BeforeExamples -> "对话示例前"
                                WorldBookInjectionPosition.AfterExamples -> "对话示例后"
                                WorldBookInjectionPosition.BeforeAuthorNote -> "作者注释前"
                                WorldBookInjectionPosition.AfterAuthorNote -> "作者注释后"
                                null -> ""
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(entry.title.ifBlank { "未命名条目" }, style = COUITheme.textStyles.buttonS)
                                Text(listOf(source, placement).filter(String::isNotBlank).joinToString(" · "), style = COUITheme.textStyles.bodyXS)
                                SelectionContainer { Text(entry.excerpt) }
                            }
                        }
                    }
                }
            }
        },
    )
}
