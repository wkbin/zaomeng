package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable
import okio.Sink

/**
 * 导出文件到用户选择的位置：返回一个触发保存对话框的函数，
 * 用户确认后调用 [onSave]（写入 okio.Sink），取消则调用 [onCancelled]。
 */
@Composable
expect fun rememberFileExporter(
    onSave: suspend (Sink) -> Unit,
    onCancelled: () -> Unit,
): (suggestedName: String, mimeType: String) -> Unit
