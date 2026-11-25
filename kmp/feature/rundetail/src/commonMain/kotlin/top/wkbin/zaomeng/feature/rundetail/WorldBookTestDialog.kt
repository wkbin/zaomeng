package top.wkbin.zaomeng.feature.rundetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.theme.COUITheme
import top.wkbin.zaomeng.data.api.TestWorldBooksRequest
import top.wkbin.zaomeng.data.api.WORLD_BOOK_CONTENT_CHAR_LIMIT
import top.wkbin.zaomeng.data.api.WorldBookGenerationType
import top.wkbin.zaomeng.data.api.WorldBookKeywordMatching
import top.wkbin.zaomeng.data.api.WorldBookScanSource
import top.wkbin.zaomeng.data.api.WorldBookTestContext
import top.wkbin.zaomeng.ui.coui.CouiAlertDialog
import top.wkbin.zaomeng.ui.coui.CouiTextField

@Composable
internal fun WorldBookTestDialog(viewModel: RunDetailViewModel, state: WorldBooksUiState, onClose: () -> Unit) {
    val books = state.data?.books.orEmpty()
    var testText by rememberSaveable { mutableStateOf("") }
    var testHistoryExpanded by rememberSaveable { mutableStateOf(false) }
    var testHistoryText by rememberSaveable { mutableStateOf("") }
    var testContextExpanded by rememberSaveable { mutableStateOf(false) }
    var testPersona by rememberSaveable { mutableStateOf("") }
    var testScenario by rememberSaveable { mutableStateOf("") }
    val hasTestContext = books.any { book ->
        book.entries.any { entry ->
            WorldBookScanSource.Scenario in entry.matching.sources || WorldBookScanSource.PersonaDescription in entry.matching.sources
        }
    }
    val testHistory = testHistoryText.lineSequence().filterNot(String::isBlank).toList()
    val invalidHistory = testHistory.size >= WorldBookKeywordMatching.MAX_SCAN_DEPTH || testHistory.any { it.length > 2000 }
    var testMessageCount by rememberSaveable { mutableStateOf("1") }
    var testGenerationType by rememberSaveable { mutableStateOf(WorldBookGenerationType.Normal) }
    val hasGenerationTriggers = books.any { book -> book.entries.any { it.triggers.isNotEmpty() || it.hasUnknownTriggers } }
    val hasActivationDelay = books.any { book -> book.entries.any { it.activationDelay > 0 } }
    val messageCount = if (hasActivationDelay) testMessageCount.toIntOrNull()?.takeIf { it > 0 } else 1
    val result = state.result
    if (result != null) {
        val bookNames = state.data?.books.orEmpty().associateBy { it.source }
        CouiAlertDialog(
            onDismissRequest = onClose,
            title = "试触发结果 · 选中 ${result.injectedCount} 条",
            confirmText = "继续测试",
            onConfirm = viewModel::clearWorldBookTest,
            dismissText = "返回世界书",
            onDismiss = onClose,
            body = {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        val characterCount = result.entries.filter { it.status == "injected" }.sumOf { it.excerpt.length }
                        Text("正文合计 $characterCount / $WORLD_BOOK_CONTENT_CHAR_LIMIT 字符")
                        Text("每次试触发会重新抽样。正式对话还会根据参演角色、近期消息和锁定事实筛选。")
                    }
                    if (result.entries.any { it.status == "injected" && it.outletName != null }) {
                        item { Text("命名出口在角色卡引用它的位置注入。") }
                    }
                    items(result.entries) { entry ->
                        val reason = when (entry.status) {
                            "injected" -> when {
                                entry.forcedActivation -> "强制触发，已选中"
                                entry.keywords.isEmpty() -> "常驻，已选中"
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
                            "probability" -> entry.triggerProbability?.let {
                                "未通过 ${it.toString().removeSuffix(".0")}% 触发概率"
                            } ?: "未通过触发概率"
                            "duplicate" -> "内容重复"
                            else -> "主关键词未命中"
                        }
                        Text("${entry.title} · $reason", style = COUITheme.textStyles.buttonM)
                        val scope = when (entry.source) {
                            "character_book" -> "卡内世界书"
                            "world_book" -> "独立世界书"
                            else -> ""
                        }
                        if (scope.isNotEmpty()) {
                            val name = bookNames[entry.source]?.name.orEmpty()
                            Text(
                                if (name.isBlank()) scope else "$scope · $name",
                                color = COUITheme.colorScheme.labelSecondary,
                                style = COUITheme.textStyles.bodyXS,
                            )
                        }
                        if (entry.keywords.isNotEmpty()) Text("命中 ${entry.keywords.joinToString("、")}")
                        if (entry.status == "injected" && entry.ignoreBudget) Text("忽略普通条目预算")
                        if (entry.status == "injected") Text(entry.excerpt)
                        if (entry.status == "injected") entry.position?.let {
                            Text(it.label(entry.injectionDepth, entry.injectionRole, entry.outletName))
                        }
                        if (entry.status == "injected" && entry.recursionDepth > 0) Text("递归触发 · 第 ${entry.recursionDepth} 层")
                    }
                    if (result.entries.size < result.entryCount) {
                        item { Text("显示 ${result.entries.size} / ${result.entryCount} 条。") }
                    }
                }
            },
        )
        return
    }

    CouiAlertDialog(
        onDismissRequest = { if (!state.busy) onClose() },
        title = "试触发世界书",
        confirmText = if (state.busy) "测试中…" else "试触发",
        onConfirm = {
            val count = messageCount
            if (count != null) {
                val generationType = if (hasGenerationTriggers) testGenerationType else WorldBookGenerationType.Normal
                val context = if (hasTestContext) WorldBookTestContext(testPersona, testScenario) else WorldBookTestContext()
                viewModel.testWorldBooks(TestWorldBooksRequest(testText, count, generationType, testHistory, context))
            }
        },
        confirmEnabled = testText.isNotBlank() && messageCount != null && !state.busy && !invalidHistory,
        dismissText = "取消",
        onDismiss = { if (!state.busy) onClose() },
        body = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.error.isNotBlank()) item { Text(state.error, color = COUITheme.colorScheme.error) }
                item {
                    TextButton(
                        text = when {
                            testHistoryExpanded -> "收起测试历史"
                            testHistory.isEmpty() -> "添加测试历史"
                            else -> "测试历史 · ${testHistory.size} 条"
                        },
                        onClick = { testHistoryExpanded = !testHistoryExpanded },
                        enabled = !state.busy,
                        minWidth = 0.dp,
                    )
                    if (testHistoryExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            CouiTextField(
                                value = testHistoryText,
                                onValueChange = { testHistoryText = it },
                                label = "测试历史消息",
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = false,
                                maxLines = 4,
                                enabled = !state.busy,
                                isError = invalidHistory,
                            )
                            Text(
                                if (invalidHistory) "最多 31 条，每条最多 2000 个字符。"
                                else "每行一条，按从早到晚排列。",
                                style = COUITheme.textStyles.bodyXS,
                                color = if (invalidHistory) COUITheme.colorScheme.error
                                else COUITheme.colorScheme.labelSecondary,
                            )
                        }
                    }
                }
                item {
                    CouiTextField(
                        value = testText,
                        onValueChange = { testText = it.take(2000) },
                        label = "输入一句测试台词",
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        maxLines = 3,
                        enabled = !state.busy,
                    )
                }
                if (hasTestContext) item {
                    TextButton(
                        text = when {
                            testContextExpanded -> "收起测试人设和场景"
                            testPersona.isNotBlank() || testScenario.isNotBlank() -> "测试人设和场景 · 已填写"
                            else -> "添加测试人设和场景"
                        },
                        onClick = { testContextExpanded = !testContextExpanded },
                        enabled = !state.busy,
                        minWidth = 0.dp,
                    )
                    if (testContextExpanded) {
                        CouiTextField(
                            value = testPersona,
                            onValueChange = { testPersona = it.take(4000) },
                            modifier = Modifier.fillMaxWidth(),
                            label = "测试玩家人设",
                            singleLine = false,
                            maxLines = 3,
                            enabled = !state.busy,
                        )
                        CouiTextField(
                            value = testScenario,
                            onValueChange = { testScenario = it.take(4000) },
                            modifier = Modifier.fillMaxWidth(),
                            label = "测试场景",
                            singleLine = false,
                            maxLines = 3,
                            enabled = !state.busy,
                        )
                    }
                }
                if (hasActivationDelay) item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        CouiTextField(
                            value = testMessageCount,
                            onValueChange = { testMessageCount = it },
                            label = "模拟聊天消息数",
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            isError = messageCount == null,
                        )
                        Text(
                            if (messageCount == null) "请输入正整数。" else "包含这句测试台词。",
                            style = COUITheme.textStyles.bodyXS,
                            color = if (messageCount == null) COUITheme.colorScheme.error
                            else COUITheme.colorScheme.labelSecondary,
                        )
                    }
                }
                if (hasGenerationTriggers) item {
                    PlacementMenu(
                        "模拟生成", testGenerationType, WorldBookGenerationType.entries, !state.busy,
                        WorldBookGenerationType::generationLabel,
                    ) { testGenerationType = it }
                }
            }
        },
    )
}
