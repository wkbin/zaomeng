package top.wkbin.zaomeng.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import top.wkbin.zaomeng.data.api.DialogueMemoryDto

import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.SuggestionChip
import androidx.compose.ui.text.font.FontWeight

private data class DirectorAction(val value: String, val label: String)

private val directorActions = listOf(
    DirectorAction("advance", "推进剧情"),
    DirectorAction("slow_emotion", "放慢情绪"),
    DirectorAction("conflict", "加强冲突"),
    DirectorAction("viewpoint", "切换视角"),
    DirectorAction("fourth_wall", "第四面墙"),
)

private data class PresetEventOption(
    val icon: String,
    val title: String,
    val description: String,
    val action: String,
)

private val presetEvents = listOf(
    PresetEventOption("🐴", "急促马蹄声", "门外长街上突然传来急促由远及近的马蹄声与勒马嘶鸣，打断了室内的交谈。", "advance"),
    PresetEventOption("📜", "暗格信函跌落", "衣袖拂动间，袖中暗藏的一封未拆信函（或带有特殊标记的信物）滑落掷地。", "conflict"),
    PresetEventOption("⚡", "窗外骤降暴雨", "夜空骤亮一道惨白闪电，紧接着滚雷炸响，倾盆暴雨猛烈拍打窗棂。", "advance"),
    PresetEventOption("👀", "窗纸人影驻足", "窗纸上隐隐映出一个悄无声息驻足倾听的黑色人影，刀鞘在月光下泛着冷光。", "advance"),
    PresetEventOption("🕯️", "烛火蓦然熄灭", "一阵穿堂阴风掠过，案头烛火猛烈摇曳后骤然熄灭，室内陷入短暂昏暗。", "advance"),
    PresetEventOption("💔", "克制后退半步", "在视线交汇的刹那，对方眼神微颤，下意识移开目光并克制地后退了半步。", "slow_emotion"),
    PresetEventOption("💬", "直截试探底线", "语气忽然放缓，直截了当地问出了那句一直被彼此心照不宣回避的关键问题。", "conflict"),
    PresetEventOption("🔔", "巡夜锣声逼近", "更夫急促的铜锣声与杂乱的脚步声正由远及近地朝院门逼近。", "advance"),
)

@Composable
internal fun DirectorToolDialog(
    visible: Boolean,
    goal: String,
    action: String,
    enabled: Boolean,
    onGoalChange: (String) -> Unit,
    onActionChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (action == "fourth_wall") "第四面墙" else "剧情导演") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                directorActions.chunked(2).forEach { actions ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        actions.forEach { item ->
                            FilterChip(
                                selected = action == item.value,
                                onClick = { onActionChange(item.value) },
                                label = { Text(item.label) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                Text(
                    text = "🎲 突发意外与灵感节拍：",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(presetEvents) { item ->
                        SuggestionChip(
                            onClick = {
                                onActionChange(item.action)
                                onGoalChange(item.description)
                            },
                            label = { Text("${item.icon} ${item.title}") },
                        )
                    }
                }

                OutlinedTextField(
                    value = goal,
                    onValueChange = onGoalChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(if (action == "fourth_wall") "以作者身份对角色下什么指令" else "这一幕希望怎样发展")
                    },
                    placeholder = {
                        Text(
                            if (action == "fourth_wall") {
                                "例如：让他们和好，否则我删掉这段记忆"
                            } else {
                                "例如：让两人因为旧事发生正面冲突"
                            },
                        )
                    },
                    minLines = 3,
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { if (goal.isNotBlank() && enabled) onConfirm() },
                    ),
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = goal.isNotBlank() && enabled) {
                Text("生成方案")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun RelationLockHelpDialog(visible: Boolean, onDismiss: () -> Unit) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("会话关系锁") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("锁定后，这对人物在当前会话中的信任、好感、敌意、暧昧及相关关系事件，不会再随后续对话自动变化。")
                Text("人物仍会继续对话，剧情和人物状态也会正常推进。它不会改写书卷的基础关系资料。")
                Text("关系锁只作用于当前会话；从这里创建的分支会继承锁定状态。解除后从下一轮起恢复自动演化，锁定期间的变化不会补算。")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}

@Composable
internal fun MemoryDeletionDialog(
    memory: DialogueMemoryDto?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    memory ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除这条记忆？") },
        text = { Text(memory.text) },
        confirmButton = {
            TextButton(onClick = { onConfirm(memory.memoryId) }) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun RelationLockConfirmationDialog(
    change: Pair<String, Boolean>?,
    onConfirm: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val (pairKey, shouldLock) = change ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (shouldLock) "锁定人物关系？" else "解除人物关系锁定？") },
        text = {
            Text(
                if (shouldLock) {
                    "锁定后，后续剧情不会自动改写这组关系：$pairKey"
                } else {
                    "解除锁定后，后续剧情可以再次更新这组关系：$pairKey"
                },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pairKey, shouldLock) }) {
                Text(if (shouldLock) "锁定" else "解除锁定")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun BranchMetaDialog(
    visible: Boolean,
    label: String,
    mainline: Boolean,
    enabled: Boolean,
    onLabelChange: (String) -> Unit,
    onMainlineChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分支信息") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = onLabelChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("分支名称") },
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = mainline, onCheckedChange = onMainlineChange)
                    Text("设为主线分支")
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = enabled) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
