package top.wkbin.zaomeng.feature.importbook

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.wkbin.zaomeng.data.api.WorldBookMatchDto
import top.wkbin.zaomeng.ui.graphics.decodeImageBitmap

@Composable
internal fun SillyTavernPreview(
    state: ImportBookUiState,
    onTestTextChange: (String) -> Unit,
    onPreview: (Boolean) -> Unit,
) {
    val preview = state.sillyTavernPreview
    val avatar by produceState<ImageBitmap?>(null, state.previewAvatar) {
        value = state.previewAvatar?.let { bytes ->
            withContext(Dispatchers.Default) { decodeImageBitmap(bytes) }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("导入预览", style = MaterialTheme.typography.titleMedium)
            if (state.previewBusy) Text("正在分析文件…")
            if (state.previewError.isNotBlank()) {
                Text(state.previewError, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { onPreview(false) }, enabled = !state.previewBusy && !state.submitting) {
                    Text("重试预览")
                }
            }
            if (preview != null) {
                if (preview.characterName.isNotBlank()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        avatar?.let {
                            Image(it, contentDescription = "角色卡头像", modifier = Modifier.size(56.dp), contentScale = ContentScale.Crop)
                        }
                        Column {
                            Text(preview.characterName, style = MaterialTheme.typography.titleSmall)
                            Text(when (preview.format) {
                                "chara_card_v2" -> "V2 角色卡"
                                "chara_card_v3" -> "V3 角色卡"
                                else -> "传统角色卡"
                            })
                        }
                    }
                    val fields = preview.mappedFields.mapNotNull { key ->
                        when (key) {
                            "core_identity" -> "人物描述"
                            "temperament_type" -> "性格"
                            "story_role" -> "场景设定"
                            "speech_style" -> "示例对话"
                            else -> null
                        }
                    }
                    if (fields.isNotEmpty()) Text("将带入 ${fields.joinToString("、")}")
                    if (preview.greeting.isNotBlank()) {
                        Text("首句", style = MaterialTheme.typography.labelLarge)
                        Text(preview.greeting)
                    }
                    if (preview.alternateGreetingCount > 0) {
                        Text("备用开场 ${preview.alternateGreetingCount} 个，新建单人会话时可选。")
                    }
                }
                if (preview.characterName.isBlank() || preview.entryCount > 0 || preview.bookName.isNotBlank()) {
                    val book = preview.bookName.ifBlank { "未命名世界书" }
                    Text("$book · ${preview.entryCount} 条")
                }
                preview.previousEntryCount?.let {
                    Text("将替换「${preview.previousBookName.orEmpty().ifBlank { "未命名世界书" }}」的 $it 条设定。")
                }
                if (preview.unsupportedRules.isNotEmpty()) {
                    Text("暂不生效：${preview.unsupportedRules.joinToString("、")}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (preview.entryCount > 0) {
                    OutlinedTextField(
                        value = state.testText,
                        onValueChange = onTestTextChange,
                        label = { Text("输入一句台词") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                        enabled = !state.previewBusy && !state.submitting,
                    )
                    OutlinedButton(
                        onClick = { onPreview(true) },
                        enabled = state.testText.isNotBlank() && !state.previewBusy && !state.submitting,
                    ) { Text("试触发世界书") }
                    preview.testedEntries?.let { entries ->
                        Text("当前文件中 ${preview.injectedCount} 条会进入上下文。实际对话还会扫描近期消息并优先保留锁定的世界事实。")
                        entries.forEach { entry ->
                            Text("${entry.title} · ${entry.reasonLabel()}", style = MaterialTheme.typography.labelLarge)
                            if (entry.keywords.isNotEmpty()) Text("命中 ${entry.keywords.joinToString("、")}")
                            if (entry.status == "injected") Text(entry.excerpt)
                        }
                        if (entries.size < preview.entryCount) Text("显示 ${entries.size} / ${preview.entryCount} 条。")
                    }
                }
            }
        }
    }
}

private fun WorldBookMatchDto.reasonLabel(): String = when (status) {
    "injected" -> if (keywords.isEmpty()) "常驻，已选中" else "已选中"
    "budget" -> "超出注入上限"
    "disabled" -> "已禁用"
    "empty" -> "内容为空"
    "secondary" -> "次关键词条件未满足"
    "duplicate" -> "内容重复"
    else -> "主关键词未命中"
}
