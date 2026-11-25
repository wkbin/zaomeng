package top.wkbin.zaomeng.ui.coui

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.suqi8.coui.kmp.basic.TextField
import io.github.suqi8.coui.kmp.basic.TextFieldMode

/**
 * COUI 风格的单行 / 多行输入框。
 *
 * 对 [TextField] 的 `TextFieldValue` 重载做一层字符串封装：上层继续用 `String` 状态，
 * 光标位置由本组件内部维护，避免每次改造都要改写调用方的状态类型。
 *
 * [label] 在 COUI 里同时充当占位提示（`useLabelAsPlaceholder`），获得焦点或已有内容时
 * 会上浮成小标签，这与 ColorOS 设置页的输入框行为一致。
 *
 * @param value 当前文本。
 * @param onValueChange 文本变化回调。
 * @param label 标签文案，同时作为未输入时的占位提示。
 * @param singleLine 是否单行；为 false 时按多行展示。
 * @param minLines 最少显示行数。
 * @param maxLines 最多显示行数。
 * @param backgroundMode 背景样式，`Line` 是设置页/弹窗里常见的下划线样式。
 */
@Composable
fun CouiTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    isError: Boolean = false,
    maxCount: Int? = null,
    showClearButton: Boolean = false,
    backgroundMode: TextFieldMode = TextFieldMode.Line,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    onTextLayout: (androidx.compose.ui.text.TextLayoutResult) -> Unit = {},
) {
    var fieldValue by remember { mutableStateOf(TextFieldValue(text = value, selection = TextRange(value.length))) }
    // 外部改写内容时同步进来；光标落到末尾，避免残留的旧选区越界。
    LaunchedEffect(value) {
        if (fieldValue.text != value) {
            fieldValue = TextFieldValue(text = value, selection = TextRange(value.length))
        }
    }
    TextField(
        value = fieldValue,
        onValueChange = { updated ->
            fieldValue = updated
            if (updated.text != value) {
                onValueChange(updated.text)
            }
        },
        modifier = modifier,
        backgroundMode = backgroundMode,
        label = label,
        enabled = enabled,
        readOnly = readOnly,
        isError = isError,
        maxCount = maxCount,
        showClearButton = showClearButton,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        onTextLayout = onTextLayout,
    )
}
