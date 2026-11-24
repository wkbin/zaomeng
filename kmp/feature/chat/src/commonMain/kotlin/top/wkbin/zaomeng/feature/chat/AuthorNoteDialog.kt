package top.wkbin.zaomeng.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import top.wkbin.zaomeng.data.api.AuthorNoteDto
import top.wkbin.zaomeng.data.api.AuthorNotePosition
import top.wkbin.zaomeng.data.api.WorldBookInjectionRole

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
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("作者注释") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 550.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                        value = enabled,
                        enabled = !saving,
                        role = Role.Switch,
                        onValueChange = { enabled = it },
                    ),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("启用作者注释")
                    Switch(enabled, onCheckedChange = null, enabled = !saving)
                }
                if (enabled) {
                    OutlinedTextField(
                        interval, { interval = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("注入间隔") }, singleLine = true, suffix = { Text("条用户消息") },
                        enabled = !saving, isError = intervalNumber == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        supportingText = { Text(if (intervalNumber == null) "请输入正整数。" else "1 表示每轮注入。") },
                    )
                }
                OutlinedTextField(
                    text, { text = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("注释内容") }, minLines = 3, maxLines = 8,
                    enabled = !saving, isError = textTooLong,
                    supportingText = { if (textTooLong) Text("最多 ${AuthorNoteDto.MAX_TEXT_LENGTH} 个字符。") },
                )
                Column {
                    Text("注入位置", style = MaterialTheme.typography.labelLarge)
                    Box {
                        OutlinedButton(
                            onClick = { positionMenuVisible = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !saving,
                        ) {
                            Text(position.label, modifier = Modifier.weight(1f))
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(positionMenuVisible, { positionMenuVisible = false }) {
                            AuthorNotePosition.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    onClick = {
                                        position = option
                                        positionMenuVisible = false
                                    },
                                )
                            }
                        }
                    }
                }
                if (inChat) {
                    OutlinedTextField(
                        depth, { depth = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("聊天深度") }, singleLine = true,
                        isError = depthNumber == null,
                        enabled = !saving, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        supportingText = {
                            Text(if (depthNumber == null) "请输入 0 至 10000 的整数。" else "0 在本轮输入后，1 在本轮输入前")
                        },
                    )
                    Column {
                        Text("注入角色", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WorldBookInjectionRole.entries.forEach { option ->
                                val label = when (option) {
                                    WorldBookInjectionRole.System -> "系统"
                                    WorldBookInjectionRole.User -> "用户"
                                    WorldBookInjectionRole.Assistant -> "助手"
                                }
                                FilterChip(role == option, { role = option }, { Text(label) }, enabled = !saving)
                            }
                        }
                    }
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(onClick = { onSave(draft) }, enabled = valid && draft != current && !saving) {
                Text(if (saving) "保存中…" else "保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") } },
    )
}

private val AuthorNotePosition.label: String
    get() = when (this) {
        AuthorNotePosition.BeforePrompt -> "主提示词前"
        AuthorNotePosition.AfterPrompt -> "主提示词后"
        AuthorNotePosition.InChat -> "聊天中"
    }
