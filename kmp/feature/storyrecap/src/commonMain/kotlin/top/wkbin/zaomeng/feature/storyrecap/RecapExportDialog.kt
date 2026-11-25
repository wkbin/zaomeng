package top.wkbin.zaomeng.feature.storyrecap

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.suqi8.coui.kmp.basic.Chip
import io.github.suqi8.coui.kmp.basic.SnackbarHostState
import io.github.suqi8.coui.kmp.basic.Surface
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import io.github.suqi8.coui.kmp.theme.COUIRoundCorner
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.launch
import top.wkbin.zaomeng.data.api.StoryRecapDto
import top.wkbin.zaomeng.platform.rememberClipboardTextWriter
import top.wkbin.zaomeng.platform.rememberShareText
import top.wkbin.zaomeng.ui.coui.CouiAlertDialog

enum class RecapExportFormat(val label: String, val iconDesc: String) {
    THEATRE("🎭 剧场剧本", "标准舞台剧本排版"),
    MARKDOWN("📝 Markdown 战报", "结构化长文与数据表"),
    HTML_CARD("🌐 HTML 卡片", "高质感自包含网页"),
    QUICK_TEXT("💬 纯文本速览", "轻量短文本"),
}

@Composable
fun RecapExportDialog(
    recap: StoryRecapDto,
    onDismiss: () -> Unit,
    snackbarHostState: SnackbarHostState? = null,
) {
    var selectedFormat by remember { mutableStateOf(RecapExportFormat.THEATRE) }
    val clipboardWriter = rememberClipboardTextWriter()
    val shareText = rememberShareText()
    val coroutineScope = rememberCoroutineScope()

    val formattedContent = remember(selectedFormat, recap) {
        when (selectedFormat) {
            RecapExportFormat.THEATRE -> RecapTheatreFormatter.formatAsTheatreScript(recap)
            RecapExportFormat.MARKDOWN -> RecapTheatreFormatter.formatAsMarkdownReport(recap)
            RecapExportFormat.HTML_CARD -> RecapTheatreFormatter.formatAsHtmlCard(recap)
            RecapExportFormat.QUICK_TEXT -> recap.shareText.ifBlank { recap.summary }
        }
    }

    CouiAlertDialog(
        onDismissRequest = onDismiss,
        title = "导出剧情剧场与战报",
        confirmText = "复制内容",
        onConfirm = {
            coroutineScope.launch {
                clipboardWriter(formattedContent)
                snackbarHostState?.showSnackbar("已复制${selectedFormat.label}到剪贴板")
                onDismiss()
            }
        },
        dismissText = "关闭",
        onDismiss = onDismiss,
        body = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "选择导出格式：",
                    style = COUITheme.textStyles.description,
                    color = COUITheme.colorScheme.labelSecondary,
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RecapExportFormat.entries.forEach { format ->
                        Chip(
                            selected = selectedFormat == format,
                            onClick = { selectedFormat = format },
                            label = format.label,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "内容预览（${formattedContent.length} 字）：",
                    style = COUITheme.textStyles.description,
                    color = COUITheme.colorScheme.labelSecondary,
                )
                Spacer(modifier = Modifier.height(6.dp))

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp),
                    shape = SquircleShape(COUIRoundCorner.Normal.S, COUIRoundCorner.Normal.Weight),
                    color = COUITheme.colorScheme.containerSolid.copy(alpha = 0.6f),
                ) {
                    Box(
                        modifier = Modifier
                            .padding(10.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = formattedContent,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = COUITheme.colorScheme.labelSecondary,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        text = "系统分享",
                        onClick = { shareText(formattedContent) },
                    )
                }
            }
        },
    )
}
