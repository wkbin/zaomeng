package top.wkbin.zaomeng.ui.coui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.layout.DialogButtonBar
import io.github.suqi8.coui.kmp.layout.DialogButtonBarAction
import io.github.suqi8.coui.kmp.layout.DialogDefaults
import io.github.suqi8.coui.kmp.window.WindowDialog

/**
 * 与 [CouiAlertDialog] 视觉一致的对话框，但用 [WindowDialog] 承载。
 *
 * [CouiAlertDialog] 依赖 COUIPopupHost，只能在 `CouiPage` / COUI `Scaffold` 内使用；
 * 挂在 Activity 级别、页面 Scaffold 之外的弹窗（例如启动时的内容声明）用这个版本，
 * 它自建平台 Dialog。
 */
@Composable
fun CouiWindowAlertDialog(
    onDismissRequest: () -> Unit,
    title: String,
    confirmText: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    dismissText: String? = "取消",
    onDismiss: (() -> Unit)? = null,
    summary: String? = null,
    confirmEnabled: Boolean = true,
    dismissEnabled: Boolean = true,
    show: Boolean = true,
    body: (@Composable ColumnScope.() -> Unit)? = null,
) {
    WindowDialog(
        show = show,
        modifier = modifier,
        title = title,
        summary = summary,
        onDismissRequest = onDismissRequest,
        customContent = if (body == null) null else {
            {
                Column(
                    // 与 CouiAlertDialog 同一套间距：水平取面板 insideMargin.width，
                    // 顶部 16dp 配合标题的 6dp 下边距，凑成 COUI 官方的 22dp。
                    modifier = Modifier.padding(
                        start = DialogDefaults.insideMargin.width,
                        top = 16.dp,
                        end = DialogDefaults.insideMargin.width,
                        bottom = 16.dp,
                    ),
                    content = body,
                )
            }
        },
        content = {
            DialogButtonBar(
                negative = dismissText?.let { text ->
                    DialogButtonBarAction(
                        text = text,
                        enabled = dismissEnabled,
                        onClick = onDismiss ?: onDismissRequest,
                    )
                },
                positive = DialogButtonBarAction(
                    text = confirmText,
                    enabled = confirmEnabled,
                    onClick = onConfirm,
                ),
            )
        },
    )
}
