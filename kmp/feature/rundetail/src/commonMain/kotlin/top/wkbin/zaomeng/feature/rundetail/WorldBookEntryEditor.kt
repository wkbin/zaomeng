package top.wkbin.zaomeng.feature.rundetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
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
import top.wkbin.zaomeng.data.api.EditWorldBookEntryRequest
import top.wkbin.zaomeng.data.api.ManagedWorldBookDto
import top.wkbin.zaomeng.data.api.ManagedWorldBookEntryDto
import top.wkbin.zaomeng.data.api.WorldBookGroupSettings
import top.wkbin.zaomeng.data.api.WorldBookGenerationType
import top.wkbin.zaomeng.data.api.WorldBookKeywordMatching
import top.wkbin.zaomeng.data.api.WorldBookSecondaryLogic
import top.wkbin.zaomeng.data.api.WorldBookScanSource
import top.wkbin.zaomeng.data.api.WorldBookEntryPlacement
import top.wkbin.zaomeng.data.api.WorldBookInjectionPosition
import top.wkbin.zaomeng.data.api.WorldBookInjectionRole
import top.wkbin.zaomeng.data.api.isValidSillyTavernOutletName

@Composable
internal fun WorldBookEntryEditor(
    book: ManagedWorldBookDto,
    entry: ManagedWorldBookEntryDto?,
    saving: Boolean,
    error: String,
    onCancel: () -> Unit,
    onSave: (EditWorldBookEntryRequest) -> Unit,
) {
    val creating = entry == null
    val originalKeywords = entry?.keywords.orEmpty().joinToString("\n")
    val originalSecondary = entry?.secondaryKeywords.orEmpty().joinToString("\n")
    val originalConstant = entry?.constant == true
    val originalDelay = entry?.activationDelay ?: 0
    val originalSticky = entry?.sticky ?: 0
    val originalCooldown = entry?.cooldown ?: 0
    val originalIgnoreBudget = entry?.ignoreBudget == true
    var ignoreBudget by rememberSaveable(book.revision, book.source, entry?.index) { mutableStateOf(originalIgnoreBudget) }
    val originalGroup = entry?.group ?: WorldBookGroupSettings()
    val originalTriggers = entry?.triggers.orEmpty()
    val originalMatching = entry?.matching ?: WorldBookKeywordMatching()
    var matchingExpanded by rememberSaveable(book.revision, book.source, entry?.index) { mutableStateOf(false) }
    var scanDepth by rememberSaveable(book.revision, book.source, entry?.index) {
        mutableStateOf(originalMatching.scanDepth?.toString().orEmpty())
    }
    var caseSensitive by rememberSaveable(book.revision, book.source, entry?.index) { mutableStateOf(originalMatching.caseSensitive) }
    var wholeWords by rememberSaveable(book.revision, book.source, entry?.index) { mutableStateOf(originalMatching.wholeWords) }
    var useSecondary by rememberSaveable(book.revision, book.source, entry?.index) { mutableStateOf(originalMatching.useSecondary) }
    var secondaryLogic by rememberSaveable(book.revision, book.source, entry?.index) { mutableStateOf(originalMatching.secondaryLogic) }
    var scanSources by rememberSaveable(book.revision, book.source, entry?.index) { mutableStateOf(originalMatching.sources) }
    var triggers by rememberSaveable(book.revision, book.source, entry?.index) { mutableStateOf(originalTriggers) }
    var triggersExpanded by rememberSaveable(book.revision, book.source, entry?.index) { mutableStateOf(false) }
    var replaceUnknownTriggers by rememberSaveable(book.revision, book.source, entry?.index) { mutableStateOf(false) }
    val hasUnknownTriggers = entry?.hasUnknownTriggers == true && !replaceUnknownTriggers
    var comment by rememberSaveable(book.revision, book.source, entry?.index) { mutableStateOf(entry?.comment.orEmpty()) }
    var content by rememberSaveable(book.revision, book.source, entry?.index) { mutableStateOf(entry?.content.orEmpty()) }
    var keywords by rememberSaveable(book.revision, book.source, entry?.index) { mutableStateOf(originalKeywords) }
    var secondary by rememberSaveable(book.revision, book.source, entry?.index) { mutableStateOf(originalSecondary) }
    var constant by rememberSaveable(book.revision, book.source, entry?.index) { mutableStateOf(originalConstant) }
    var activationDelay by rememberSaveable(book.revision, book.source, entry?.index) {
        mutableStateOf(originalDelay.toString())
    }
    var sticky by rememberSaveable(book.revision, book.source, entry?.index) { mutableStateOf(originalSticky.toString()) }
    var cooldown by rememberSaveable(book.revision, book.source, entry?.index) { mutableStateOf(originalCooldown.toString()) }
    var groupNames by rememberSaveable(book.revision, book.source, entry?.index) { mutableStateOf(originalGroup.names) }
    var groupPriority by rememberSaveable(book.revision, book.source, entry?.index) { mutableStateOf(originalGroup.priority) }
    var groupWeight by rememberSaveable(book.revision, book.source, entry?.index) { mutableStateOf(originalGroup.weight.toString()) }
    var groupScoring by rememberSaveable(book.revision, book.source, entry?.index) { mutableStateOf(originalGroup.scoring) }
    val originalPlacement = entry?.position?.let { WorldBookEntryPlacement(it, entry.injectionDepth, entry.injectionRole, entry.outletName) }
    var position by rememberSaveable(book.revision, book.source, entry?.index) {
        mutableStateOf(if (creating) WorldBookInjectionPosition.BeforeCharacter else originalPlacement?.position)
    }
    var depth by rememberSaveable(book.revision, book.source, entry?.index) {
        mutableStateOf((originalPlacement?.depth ?: 4).toString())
    }
    var injectionRole by rememberSaveable(book.revision, book.source, entry?.index) {
        mutableStateOf(originalPlacement?.role ?: WorldBookInjectionRole.System)
    }
    var outletName by rememberSaveable(book.revision, book.source, entry?.index) {
        mutableStateOf(originalPlacement?.outletName.orEmpty())
    }
    val atDepth = position == WorldBookInjectionPosition.AtDepth
    val atOutlet = position == WorldBookInjectionPosition.Outlet
    val depthValue = depth.toIntOrNull()
    val activationDelayValue = activationDelay.toIntOrNull()?.takeIf { it >= 0 }
    val stickyValue = sticky.toIntOrNull()?.takeIf { it >= 0 }
    val cooldownValue = cooldown.toIntOrNull()?.takeIf { it >= 0 }
    val hasGroupNames = groupNames.any { !it.isWhitespace() && it != ',' }
    val groupWeightValue = groupWeight.toIntOrNull()?.takeIf { it in 0..10_000 }
    val invalidGroupNames = groupNames != originalGroup.names &&
        (groupNames.length > 512 || groupNames.any(Char::isISOControl))
    val invalidGroupWeight = hasGroupNames && groupWeightValue == null
    val group = WorldBookGroupSettings(groupNames, groupPriority, groupWeightValue ?: originalGroup.weight, groupScoring)
    val scanDepthValue = scanDepth.toIntOrNull()?.takeIf { it >= 0 }
    val invalidScanDepth = scanDepth.isNotBlank() && scanDepthValue == null
    val matching = WorldBookKeywordMatching(
        scanDepthValue, caseSensitive, wholeWords, useSecondary, secondaryLogic,
        WorldBookScanSource.entries.filter { it in scanSources },
    )
    val invalidDepth = atDepth && (depthValue == null || depthValue !in 0..10_000)
    val newOutletName = outletName.trim().takeIf(String::isNotEmpty)
    val invalidOutlet = atOutlet && (position != originalPlacement?.position || newOutletName != originalPlacement?.outletName) &&
        !newOutletName.orEmpty().isValidSillyTavernOutletName()
    val placement = position?.let {
        when (it) {
            WorldBookInjectionPosition.AtDepth -> WorldBookEntryPlacement(it, depthValue, injectionRole)
            WorldBookInjectionPosition.Outlet -> WorldBookEntryPlacement(it, outletName = newOutletName)
            else -> WorldBookEntryPlacement(it)
        }
    }
    val request = EditWorldBookEntryRequest(
        revision = book.revision,
        comment = comment.takeIf { it != entry?.comment }?.trim()?.takeIf { it != entry?.comment },
        content = content.takeIf { it != entry?.content },
        keywords = keywords.takeIf { creating || it != originalKeywords }?.toKeywords(),
        secondaryKeywords = secondary.takeIf { creating || it != originalSecondary }?.toKeywords(),
        constant = constant.takeIf { creating || it != originalConstant },
        placement = placement?.takeIf { creating || it != originalPlacement },
        activationDelay = activationDelayValue?.takeIf { it != originalDelay },
        sticky = stickyValue?.takeIf { it != originalSticky },
        cooldown = cooldownValue?.takeIf { it != originalCooldown },
        group = group.takeIf { it != originalGroup },
        triggers = triggers.takeIf { it.toSet() != originalTriggers.toSet() || replaceUnknownTriggers },
        matching = matching.takeIf { it != originalMatching },
        ignoreBudget = ignoreBudget.takeIf { it != originalIgnoreBudget },
    )
    val invalidTitle = request.comment?.let { it.length > 120 } == true
    val invalidContent = request.content?.let { it.length > 16_000 || it.isBlank() && (!creating || it.isNotEmpty()) } == true
    val invalidKeywords = request.keywords?.exceedLimits() == true
    val invalidSecondary = request.secondaryKeywords?.exceedLimits() == true
    val primaryRequired = !constant && (creating || originalConstant)
    val missingPrimary = primaryRequired && (request.keywords ?: entry?.keywords.orEmpty()).isEmpty()
    val valid = !invalidTitle && !invalidContent && !invalidKeywords && !invalidSecondary &&
        !invalidDepth && !invalidOutlet && activationDelayValue != null && stickyValue != null && cooldownValue != null &&
        !invalidGroupNames && !invalidGroupWeight && !invalidScanDepth
    val changed = listOf(
        request.comment, request.content, request.keywords, request.secondaryKeywords,
        request.constant, request.placement, request.activationDelay, request.sticky, request.cooldown,
        request.group, request.triggers, request.matching, request.ignoreBudget,
    )
        .any { it != null }
    val canSave = valid && !missingPrimary && (if (creating) content.isNotBlank() else changed)
    AlertDialog(
        onDismissRequest = { if (!saving) onCancel() },
        title = { Text(if (creating) "新增世界书条目" else "编辑世界书条目") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (error.isNotBlank()) {
                    item { Text(error, color = MaterialTheme.colorScheme.error) }
                }
                item {
                    OutlinedTextField(comment, { comment = it }, Modifier.fillMaxWidth(), enabled = !saving,
                        label = { Text("标题") }, placeholder = { Text("留空时自动命名") }, singleLine = true,
                        isError = invalidTitle,
                        supportingText = if (invalidTitle) ({ Text("标题不能超过 120 字符。") }) else null)
                }
                item {
                    OutlinedTextField(content, { content = it }, Modifier.fillMaxWidth(), enabled = !saving,
                        label = { Text(if (creating) "正文（必填）" else "正文") }, minLines = 3, maxLines = 8,
                        isError = invalidContent,
                        supportingText = if (invalidContent) ({ Text("正文须为 1 至 16000 字符。") }) else null)
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                            value = constant,
                            enabled = !saving,
                            role = Role.Switch,
                            onValueChange = { constant = it },
                        ),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("常驻条目")
                            Text(
                                if (constant) "不依赖关键词触发" else "命中关键词时触发",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = constant, onCheckedChange = null, enabled = !saving)
                    }
                }
                item {
                    val label = if (primaryRequired) "主关键词（必填）" else "主关键词"
                    val validation = when {
                        invalidKeywords -> keywordLimitMessage
                        !creating && missingPrimary -> "请至少填写一个主关键词。"
                        else -> null
                    }
                    KeywordField(label, keywords, !saving, validation) { keywords = it }
                }
                item {
                    KeywordField("次关键词", secondary, !saving, keywordLimitMessage.takeIf { invalidSecondary }) {
                        secondary = it
                    }
                }
                item {
                    OutlinedButton(
                        onClick = { matchingExpanded = !matchingExpanded },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !saving,
                    ) {
                        Text(if (matchingExpanded) "收起关键词匹配" else "关键词匹配", modifier = Modifier.weight(1f))
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                    }
                }
                if (matchingExpanded) {
                    item {
                        OutlinedTextField(
                            scanDepth, { scanDepth = it }, modifier = Modifier.fillMaxWidth(),
                            label = { Text("扫描深度") }, singleLine = true, enabled = !saving, isError = invalidScanDepth,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            supportingText = {
                                Text(
                                    if (invalidScanDepth) "请输入非负整数。"
                                    else "留空继承默认值，包含本轮输入，最多扫描 32 条。",
                                )
                            },
                        )
                    }
                    item {
                        PlacementMenu("大小写", caseSensitive, listOf(null, true, false), !saving, {
                            when (it) {
                                true -> "区分"
                                false -> "忽略"
                                null -> "默认"
                            }
                        }) { caseSensitive = it }
                    }
                    item {
                        PlacementMenu("整词匹配", wholeWords, listOf(null, true, false), !saving, {
                            when (it) {
                                true -> "启用"
                                false -> "关闭"
                                null -> "默认"
                            }
                        }) { wholeWords = it }
                    }
                    if (secondary.isNotBlank()) {
                        item {
                            FilterChip(useSecondary, { useSecondary = !useSecondary }, { Text("启用次关键词") }, enabled = !saving)
                        }
                        if (useSecondary) item {
                            PlacementMenu(
                                "次关键词条件", secondaryLogic, WorldBookSecondaryLogic.entries, !saving,
                                WorldBookSecondaryLogic::label,
                            ) { secondaryLogic = it }
                        }
                    }
                    item { Text("额外扫描来源", style = MaterialTheme.typography.labelLarge) }
                    for (row in WorldBookScanSource.entries.chunked(2)) {
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (source in row) {
                                    FilterChip(
                                        selected = source in scanSources,
                                        onClick = {
                                            scanSources = if (source in scanSources) scanSources - source else scanSources + source
                                        },
                                        label = { Text(source.label()) },
                                        enabled = !saving,
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    OutlinedButton(
                        onClick = { triggersExpanded = !triggersExpanded },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !saving,
                    ) {
                        val summary = when {
                            hasUnknownTriggers -> "含未知类型"
                            triggers.isEmpty() -> "不限"
                            else -> "已选 ${triggers.size} 种"
                        }
                        Text("生成类型 · $summary", modifier = Modifier.weight(1f))
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                    }
                    if (triggersExpanded) {
                        Text(
                            if (hasUnknownTriggers) "修改选项会移除未知类型。" else "全部取消时不限生成类型。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (hasUnknownTriggers) {
                            TextButton(onClick = { replaceUnknownTriggers = true }, enabled = !saving) {
                                Text("移除未知类型")
                            }
                        }
                        for (row in WorldBookGenerationType.entries.chunked(2)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (type in row) {
                                    FilterChip(
                                        selected = type in triggers,
                                        onClick = {
                                            replaceUnknownTriggers = entry?.hasUnknownTriggers == true
                                            triggers = if (type in triggers) triggers - type else triggers + type
                                        },
                                        label = { Text(type.generationLabel()) },
                                        enabled = !saving,
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        groupNames, { groupNames = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("互斥分组") }, singleLine = true, enabled = !saving, isError = invalidGroupNames,
                        supportingText = {
                            Text(if (invalidGroupNames) "请输入一行且不超过 512 个字符。" else "多个组名用英文逗号分隔。")
                        },
                    )
                }
                if (hasGroupNames) {
                    item {
                        FilterChip(groupPriority, { groupPriority = !groupPriority }, { Text("组内优先") }, enabled = !saving)
                        PlacementMenu(
                            "按命中评分", groupScoring, listOf(null, true, false), !saving,
                            { option ->
                                when (option) {
                                    true -> "启用"
                                    false -> "关闭"
                                    null -> "默认"
                                }
                            },
                        ) { groupScoring = it }
                        OutlinedTextField(
                            groupWeight, { groupWeight = it }, modifier = Modifier.fillMaxWidth(),
                            label = { Text("抽选权重") }, singleLine = true, enabled = !saving, isError = invalidGroupWeight,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            supportingText = {
                                Text(if (invalidGroupWeight) "请输入 0 至 10000 的整数。" else "默认 100，权重越高越容易抽中。")
                            },
                        )
                    }
                }
                item {
                    TimingField("延迟激活", activationDelay, !saving, activationDelayValue == null,
                        "达到消息数后才可触发，0 表示不限。") { activationDelay = it }
                }
                item {
                    TimingField("粘滞", sticky, !saving, stickyValue == null,
                        "触发后持续生效，0 表示关闭。") { sticky = it }
                }
                item {
                    TimingField("冷却", cooldown, !saving, cooldownValue == null,
                        "期间无法再次触发。设置粘滞时，冷却在粘滞结束后开始。") { cooldown = it }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                            value = ignoreBudget,
                            enabled = !saving,
                            role = Role.Switch,
                            onValueChange = { ignoreBudget = it },
                        ),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("忽略预算")
                            Text("跳过普通条目配额，仍受总注入上限约束。", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = ignoreBudget, onCheckedChange = null, enabled = !saving)
                    }
                }
                item {
                    val options = if (!creating && originalPlacement == null) listOf(null) + WorldBookInjectionPosition.entries
                    else WorldBookInjectionPosition.entries
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PlacementMenu("注入位置", position, options, !saving, { it.positionLabel() }) { position = it }
                        if (position == WorldBookInjectionPosition.BeforeExamples || position == WorldBookInjectionPosition.AfterExamples) {
                            Text(
                                "用 {{char}}: 和 {{user}}: 标记台词，多段示例用 <START> 分隔。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (atDepth) {
                            OutlinedTextField(
                                value = depth,
                                onValueChange = { depth = it },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !saving,
                                label = { Text("聊天深度") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                isError = invalidDepth,
                                supportingText = {
                                    Text(if (invalidDepth) "请输入 0 至 10000 的整数。" else "0 在本轮输入后，1 在本轮输入前")
                                },
                            )
                            PlacementMenu(
                                "注入角色", injectionRole, WorldBookInjectionRole.entries, !saving, WorldBookInjectionRole::roleLabel,
                            ) { injectionRole = it }
                        }
                        if (atOutlet) {
                            OutlinedTextField(
                                value = outletName,
                                onValueChange = { outletName = it },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !saving,
                                label = { Text("出口名称") },
                                singleLine = true,
                                isError = invalidOutlet && outletName.isNotEmpty(),
                                supportingText = {
                                    Text(
                                        if (invalidOutlet && outletName.isNotEmpty()) "请输入 1 至 120 个字符，避开花括号。"
                                        else "在角色卡里用 {{outlet::名称}} 引用",
                                    )
                                },
                            )
                        }
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onCancel, enabled = !saving) { Text("取消") } },
        confirmButton = {
            Button(onClick = { onSave(request) }, enabled = canSave && !saving) {
                Text(if (saving) "保存中…" else if (creating) "新增" else "保存")
            }
        },
    )
}

@Composable
internal fun <T> PlacementMenu(
    label: String,
    selected: T,
    options: List<T>,
    enabled: Boolean,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), enabled = enabled) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("$label · ${optionLabel(selected)}")
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (option in options) {
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun WorldBookInjectionPosition?.positionLabel(): String = when (this) {
    WorldBookInjectionPosition.BeforeCharacter -> "角色定义前"
    WorldBookInjectionPosition.AfterCharacter -> "角色定义后"
    WorldBookInjectionPosition.BeforeAuthorNote -> "作者注释前"
    WorldBookInjectionPosition.AfterAuthorNote -> "作者注释后"
    WorldBookInjectionPosition.BeforeExamples -> "对话示例前"
    WorldBookInjectionPosition.AfterExamples -> "对话示例后"
    WorldBookInjectionPosition.AtDepth -> "聊天深度"
    WorldBookInjectionPosition.Outlet -> "命名出口"
    null -> "原有位置"
}

internal fun WorldBookInjectionRole.roleLabel(): String = when (this) {
    WorldBookInjectionRole.System -> "系统"
    WorldBookInjectionRole.User -> "用户"
    WorldBookInjectionRole.Assistant -> "助手"
}

internal fun WorldBookGenerationType.generationLabel(): String = when (this) {
    WorldBookGenerationType.Normal -> "普通对话"
    WorldBookGenerationType.Continue -> "继续生成"
    WorldBookGenerationType.Regenerate -> "重新生成"
    WorldBookGenerationType.Impersonate -> "代写"
    WorldBookGenerationType.Quiet -> "后台建议"
    WorldBookGenerationType.Swipe -> "生成备选回复"
}

internal fun WorldBookSecondaryLogic.label(): String = when (this) {
    WorldBookSecondaryLogic.AndAny -> "任一命中"
    WorldBookSecondaryLogic.NotAll -> "至少一项未命中"
    WorldBookSecondaryLogic.NotAny -> "全部未命中"
    WorldBookSecondaryLogic.AndAll -> "全部命中"
}

internal fun WorldBookScanSource.label(): String = when (this) {
    WorldBookScanSource.PersonaDescription -> "玩家人设"
    WorldBookScanSource.CharacterDescription -> "角色描述"
    WorldBookScanSource.CharacterPersonality -> "角色性格"
    WorldBookScanSource.CharacterDepthPrompt -> "角色深度提示词"
    WorldBookScanSource.Scenario -> "场景"
    WorldBookScanSource.CreatorNotes -> "作者备注"
}

@Composable
private fun KeywordField(label: String, value: String, enabled: Boolean, validation: String?, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), enabled = enabled,
        label = { Text(label) }, isError = validation != null,
        supportingText = { Text(validation ?: "每行一个关键词") },
        maxLines = 4)
}

private const val keywordLimitMessage = "每组最多 32 个关键词，每个不能超过 120 字符。"

@Composable
private fun TimingField(
    label: String, value: String, enabled: Boolean, invalid: Boolean, help: String, onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value, onChange, modifier = Modifier.fillMaxWidth(), enabled = enabled,
        label = { Text(label) }, suffix = { Text("条消息") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = invalid,
        supportingText = { Text(if (invalid) "请输入非负整数。" else help) },
    )
}

private fun List<String>.exceedLimits(): Boolean = size > 32 || any { it.length > 120 }

private fun String.toKeywords(): List<String> = lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
