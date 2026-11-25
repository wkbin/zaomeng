package top.wkbin.zaomeng.feature.importbook

import io.github.suqi8.coui.kmp.theme.COUITheme
import top.wkbin.zaomeng.ui.roleplay.RoleplayMessageContent

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.wkbin.zaomeng.data.api.WorldBookMatchDto
import top.wkbin.zaomeng.ui.coui.CouiTextField
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
        // COUI Card 的 insideMargin 默认为 0dp，内容需要自己留出内边距。
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("导入预览", style = COUITheme.textStyles.headlineXS)
            if (state.previewBusy) Text("正在分析文件…")
            if (state.previewError.isNotBlank()) {
                Text(state.previewError, color = COUITheme.colorScheme.error)
                TextButton(
                    text = "重试预览",
                    onClick = { onPreview(false) },
                    enabled = !state.previewBusy && !state.submitting,
                )
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
                            Text(preview.characterName, style = COUITheme.textStyles.buttonM)
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
                        Text("首句", style = COUITheme.textStyles.buttonM)
                        RoleplayMessageContent(
                            text = preview.greeting,
                            style = COUITheme.textStyles.bodyM,
                            baseColor = COUITheme.colorScheme.labelPrimary,
                        )
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
                        color = COUITheme.colorScheme.labelSecondary)
                }
                if (preview.entryCount > 0) {
                    CouiTextField(
                        value = state.testText,
                        onValueChange = onTestTextChange,
                        label = "输入一句台词",
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                        enabled = !state.previewBusy && !state.submitting,
                    )
                    TextButton(
                        text = "试触发世界书",
                        onClick = { onPreview(true) },
                        enabled = state.testText.isNotBlank() && !state.previewBusy && !state.submitting,
                    )
                    preview.testedEntries?.let { entries ->
                        Text("本次试触发选中 ${preview.injectedCount} 条。每次试触发会重新抽样，实际对话还会扫描近期消息并优先保留锁定的世界事实。")
                        entries.forEach { entry ->
                            Text("${entry.title} · ${entry.reasonLabel()}", style = COUITheme.textStyles.buttonM)
                            if (entry.status == "injected" && entry.recursionDepth > 0) Text("递归触发 · 第 ${entry.recursionDepth} 层")
                            if (entry.keywords.isNotEmpty()) Text("命中 ${entry.keywords.joinToString("、")}")
                            if (entry.status == "injected") Text(entry.excerpt)
                            if (entry.status == "injected") entry.outletName?.let { Text("命名出口 · $it") }
                        }
                        if (entries.size < preview.entryCount) Text("显示 ${entries.size} / ${preview.entryCount} 条。")
                    }
                }
            }
        }
    }
}

private fun WorldBookMatchDto.reasonLabel(): String = when (status) {
    "injected" -> when {
        forcedActivation -> "强制触发，已选中"
        keywords.isEmpty() -> "常驻，已选中"
        else -> "已选中"
    }
    "budget" -> "超出注入上限"
    "total_budget" -> "达到世界书总注入上限"
    "content_budget" -> "剩余正文预算不足"
    "disabled" -> "已禁用"
    "decorator_suppressed" -> "正文指令禁止触发"
    "empty" -> "内容为空"
    "missing_outlet" -> "缺少有效的出口名称"
    "secondary" -> "次关键词条件未满足"
    "recursion_only" -> "等待递归触发"
    "activation_delay" -> "未达到激活所需的消息数"
    "group_suppressed" -> "同组竞争未选中"
    "generation_type" -> "生成类型不符"
    "probability" -> triggerProbability?.let { "未通过 ${it.toString().removeSuffix(".0")}% 触发概率" } ?: "未通过触发概率"
    "duplicate" -> "内容重复"
    else -> "主关键词未命中"
}
