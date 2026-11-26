package top.wkbin.zaomeng.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.Chip
import io.github.suqi8.coui.kmp.basic.DropdownImpl
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.ListPopupColumn
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.ExpandMore
import io.github.suqi8.coui.kmp.overlay.OverlayListPopup
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import top.wkbin.zaomeng.data.api.AuthorNoteDto
import top.wkbin.zaomeng.data.api.AuthorNotePosition
import top.wkbin.zaomeng.data.api.WorldBookInjectionRole
import top.wkbin.zaomeng.ui.coui.CouiAlertDialog
import top.wkbin.zaomeng.ui.coui.CouiTextField

@Composable
internal fun AuthorNoteDialog(
    current: AuthorNoteDto,
    saving: Boolean,
    error: String,
    onSave: (AuthorNoteDto) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(current.text) }
    var enabled by rememberSaveable { mutableStateOf(current.enabled) }
    var interval by rememberSaveable { mutableStateOf(current.interval.toString()) }
    var depth by rememberSaveable { mutableStateOf(current.depth.toString()) }
    var role by rememberSaveable { mutableStateOf(current.role) }
    var position by rememberSaveable { mutableStateOf(current.position) }
    var positionMenuVisible by remember { mutableStateOf(false) }
    val depthNumber = depth.toIntOrNull()?.takeIf { it in 0..10_000 }
    val intervalNumber = interval.toIntOrNull()?.takeIf { it > 0 }
    val inChat = position == AuthorNotePosition.InChat
    val textTooLong = text.length > AuthorNoteDto.MAX_TEXT_LENGTH
    val valid = (!inChat || depthNumber != null) && (!enabled || intervalNumber != null) && !textTooLong
    val draft = AuthorNoteDto(
        text = text,
        enabled = enabled,
        depth = depthNumber ?: current.depth,
        role = role,
        interval = intervalNumber ?: current.interval,
        position = position,
    )
    CouiAlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = "作者注释",
        confirmText = if (saving) "保存中…" else "保存",
        onConfirm = { onSave(draft) },
        confirmEnabled = valid && draft != current && !saving,
        onDismiss = { if (!saving) onDismiss() },
        body = {
            Column(
                modifier = Modifier.heightIn(max = 550.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SwitchPreference(
                    title = "启用作者注释",
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                    enabled = !saving,
                    insideMargin = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                )
                if (enabled) {
                    CouiTextField(
                        value = interval,
                        onValueChange = { interval = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = "注入间隔",
                        singleLine = true,
                        enabled = !saving,
                        isError = intervalNumber == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Text(
                        if (intervalNumber == null) {
                            "请输入正整数，单位是条用户消息。"
                        } else {
                            "单位为条用户消息，1 表示每轮注入。"
                        },
                        style = COUITheme.textStyles.description,
                        color = if (intervalNumber == null) {
                            COUITheme.colorScheme.error
                        } else {
                            COUITheme.colorScheme.labelSecondary
                        },
                    )
                }
                CouiTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "注释内容",
                    singleLine = false,
                    minLines = 3,
                    maxLines = 8,
                    enabled = !saving,
                    isError = textTooLong,
                )
                if (textTooLong) {
                    Text(
                        "最多 ${AuthorNoteDto.MAX_TEXT_LENGTH} 个字符。",
                        style = COUITheme.textStyles.description,
                        color = COUITheme.colorScheme.error,
                    )
                }
                Column {
                    Text("注入位置", style = COUITheme.textStyles.buttonM)
                    Box {
                        Button(
                            onClick = { positionMenuVisible = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !saving,
                        ) {
                            Text(position.label, modifier = Modifier.weight(1f))
                            Icon(COUIIcons.ExpandMore, contentDescription = null)
                        }
                        OverlayListPopup(
                            show = positionMenuVisible,
                            onDismissRequest = { positionMenuVisible = false },
                        ) {
                            ListPopupColumn {
                                val options = AuthorNotePosition.entries
                                options.forEachIndexed { index, option ->
                                    DropdownImpl(
                                        text = option.label,
                                        optionSize = options.size,
                                        isSelected = position == option,
                                        index = index,
                                        onSelectedIndexChange = {
                                            position = option
                                            positionMenuVisible = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                if (inChat) {
                    CouiTextField(
                        value = depth,
                        onValueChange = { depth = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = "聊天深度",
                        singleLine = true,
                        isError = depthNumber == null,
                        enabled = !saving,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Text(
                        if (depthNumber == null) "请输入 0 至 10000 的整数。" else "0 在本轮输入后，1 在本轮输入前",
                        style = COUITheme.textStyles.description,
                        color = if (depthNumber == null) {
                            COUITheme.colorScheme.error
                        } else {
                            COUITheme.colorScheme.labelSecondary
                        },
                    )
                    Column {
                        Text("注入角色", style = COUITheme.textStyles.buttonM)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WorldBookInjectionRole.entries.forEach { option ->
                                val label = when (option) {
                                    WorldBookInjectionRole.System -> "系统"
                                    WorldBookInjectionRole.User -> "用户"
                                    WorldBookInjectionRole.Assistant -> "助手"
                                }
                                Chip(
                                    selected = role == option,
                                    onClick = { role = option },
                                    label = label,
                                    enabled = !saving,
                                )
                            }
                        }
                    }
                }
                if (error.isNotBlank()) Text(error, color = COUITheme.colorScheme.error)
            }
        },
    )
}

private val AuthorNotePosition.label: String
    get() = when (this) {
        AuthorNotePosition.BeforePrompt -> "主提示词前"
        AuthorNotePosition.AfterPrompt -> "主提示词后"
        AuthorNotePosition.InChat -> "聊天中"
    }
