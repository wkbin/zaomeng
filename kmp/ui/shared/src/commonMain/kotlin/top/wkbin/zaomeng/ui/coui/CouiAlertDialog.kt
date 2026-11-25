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
import io.github.suqi8.coui.kmp.overlay.OverlayDialog

/**
 * COUI 风格的警告/确认弹窗。
 *
 * 用 [OverlayDialog] 提供 ColorOS 17 的对话框面板（标题、摘要、圆角与安全边距），
 * 底部按钮交给 [DialogButtonBar] 排布——它会在两个按钮放不下时自动改为纵向堆叠。
 *
 * 必须在 [CouiPage] / COUI `Scaffold` 内调用（`OverlayDialog` 依赖 `COUIPopupHost`）。
 *
 * @param onDismissRequest 点击遮罩或返回键时触发。
 * @param title 标题；为空时面板不渲染标题区。
 * @param confirmText 确认按钮文案。
 * @param onConfirm 确认按钮回调。
 * @param dismissText 取消按钮文案；传 null 则只有一个按钮。
 * @param onDismiss 取消按钮回调，默认等同 [onDismissRequest]。
 * @param summary 标题下方的灰色摘要文本。
 * @param confirmEnabled 确认按钮是否可用。
 * @param confirmRecommended 是否把确认按钮标记为推荐操作（COUI 会高亮）。
 * @param body 面板内容区（输入框、选项列表等）。
 */
@Composable
fun CouiAlertDialog(
    onDismissRequest: () -> Unit,
    title: String,
    confirmText: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    dismissText: String? = "取消",
    onDismiss: (() -> Unit)? = null,
    summary: String? = null,
    confirmEnabled: Boolean = true,
    confirmRecommended: Boolean = false,
    show: Boolean = true,
    body: (@Composable ColumnScope.() -> Unit)? = null,
) {
    OverlayDialog(
        show = show,
        modifier = modifier,
        title = title,
        summary = summary,
        onDismissRequest = onDismissRequest,
        customContent = if (body == null) null else {
            {
                Column(
                    // COUI 的 insideMargin 只作用在标题与摘要上，正文槽是刻意留白的
                    // （让底部按钮栏能通栏），所以正文要自己补内边距：
                    // - 水平取面板的 insideMargin.width（24dp）；
                    // - 顶部 16dp，配合标题自身的 6dp 下边距，凑成 COUI 官方
                    //   「标题 + 正文」的 22dp，否则输入框浮起的标签会顶到标题上。
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
                    DialogButtonBarAction(text = text, onClick = onDismiss ?: onDismissRequest)
                },
                positive = DialogButtonBarAction(
                    text = confirmText,
                    enabled = confirmEnabled,
                    recommended = confirmRecommended,
                    onClick = onConfirm,
                ),
            )
        },
    )
}
