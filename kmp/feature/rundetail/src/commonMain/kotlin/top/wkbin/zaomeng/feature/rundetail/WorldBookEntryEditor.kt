package top.wkbin.zaomeng.feature.rundetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Chip
import io.github.suqi8.coui.kmp.basic.DropdownImpl
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.ListPopupColumn
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.ExpandMore
import io.github.suqi8.coui.kmp.overlay.OverlayListPopup
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
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
import top.wkbin.zaomeng.ui.coui.CouiAlertDialog
import top.wkbin.zaomeng.ui.coui.CouiTextField

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
    CouiAlertDialog(
        onDismissRequest = { if (!saving) onCancel() },
        title = if (creating) "新增世界书条目" else "编辑世界书条目",
        confirmText = if (saving) "保存中…" else if (creating) "新增" else "保存",
        onConfirm = { onSave(request) },
        confirmEnabled = canSave && !saving,
        dismissText = "取消",
        body = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (error.isNotBlank()) {
                    item { Text(error, color = COUITheme.colorScheme.error) }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        CouiTextField(
                            value = comment,
                            onValueChange = { comment = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = "标题",
                            enabled = !saving,
                            isError = invalidTitle,
                        )
                        FieldHint(
                            text = if (invalidTitle) "标题不能超过 120 字符。" else "留空时自动命名",
                            error = invalidTitle,
                        )
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        CouiTextField(
                            value = content,
                            onValueChange = { content = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = if (creating) "正文（必填）" else "正文",
                            enabled = !saving,
                            singleLine = false,
                            minLines = 3,
                            maxLines = 8,
                            isError = invalidContent,
                        )
                        if (invalidContent) FieldHint("正文须为 1 至 16000 字符。", error = true)
                    }
                }
                item {
                    SwitchPreference(
                        title = "常驻条目",
                        summary = if (constant) "不依赖关键词触发" else "命中关键词时触发",
                        checked = constant,
                        onCheckedChange = { constant = it },
                        enabled = !saving,
                        insideMargin = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                    )
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
                    Button(
                        onClick = { matchingExpanded = !matchingExpanded },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !saving,
                        colors = ButtonDefaults.buttonColors(),
                    ) {
                        Text(if (matchingExpanded) "收起关键词匹配" else "关键词匹配", modifier = Modifier.weight(1f))
                        Icon(COUIIcons.ExpandMore, contentDescription = null)
                    }
                }
                if (matchingExpanded) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            CouiTextField(
                                value = scanDepth,
                                onValueChange = { scanDepth = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = "扫描深度",
                                enabled = !saving,
                                isError = invalidScanDepth,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                            FieldHint(
                                text = if (invalidScanDepth) "请输入非负整数。"
                                else "留空继承默认值，包含本轮输入，最多扫描 32 条。",
                                error = invalidScanDepth,
                            )
                        }
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
                            Chip(
                                selected = useSecondary,
                                onClick = { useSecondary = !useSecondary },
                                label = "启用次关键词",
                                enabled = !saving,
                            )
                        }
                        if (useSecondary) item {
                            PlacementMenu(
                                "次关键词条件", secondaryLogic, WorldBookSecondaryLogic.entries, !saving,
                                WorldBookSecondaryLogic::label,
                            ) { secondaryLogic = it }
                        }
                    }
                    item { Text("额外扫描来源", style = COUITheme.textStyles.buttonM) }
                    for (row in WorldBookScanSource.entries.chunked(2)) {
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (source in row) {
                                    Chip(
                                        selected = source in scanSources,
                                        onClick = {
                                            scanSources = if (source in scanSources) scanSources - source else scanSources + source
                                        },
                                        label = source.label(),
                                        enabled = !saving,
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    Button(
                        onClick = { triggersExpanded = !triggersExpanded },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !saving,
                        colors = ButtonDefaults.buttonColors(),
                    ) {
                        val summary = when {
                            hasUnknownTriggers -> "含未知类型"
                            triggers.isEmpty() -> "不限"
                            else -> "已选 ${triggers.size} 种"
                        }
                        Text("生成类型 · $summary", modifier = Modifier.weight(1f))
                        Icon(COUIIcons.ExpandMore, contentDescription = null)
                    }
                    if (triggersExpanded) {
                        Text(
                            if (hasUnknownTriggers) "修改选项会移除未知类型。" else "全部取消时不限生成类型。",
                            style = COUITheme.textStyles.bodyXS,
                        )
                        if (hasUnknownTriggers) {
                            TextButton(
                                text = "移除未知类型",
                                onClick = { replaceUnknownTriggers = true },
                                enabled = !saving,
                            )
                        }
                        for (row in WorldBookGenerationType.entries.chunked(2)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (type in row) {
                                    Chip(
                                        selected = type in triggers,
                                        onClick = {
                                            replaceUnknownTriggers = entry?.hasUnknownTriggers == true
                                            triggers = if (type in triggers) triggers - type else triggers + type
                                        },
                                        label = type.generationLabel(),
                                        enabled = !saving,
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        CouiTextField(
                            value = groupNames,
                            onValueChange = { groupNames = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = "互斥分组",
                            enabled = !saving,
                            isError = invalidGroupNames,
                        )
                        FieldHint(
                            text = if (invalidGroupNames) "请输入一行且不超过 512 个字符。" else "多个组名用英文逗号分隔。",
                            error = invalidGroupNames,
                        )
                    }
                }
                if (hasGroupNames) {
                    item {
                        Chip(
                            selected = groupPriority,
                            onClick = { groupPriority = !groupPriority },
                            label = "组内优先",
                            enabled = !saving,
                        )
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
                        CouiTextField(
                            value = groupWeight,
                            onValueChange = { groupWeight = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = "抽选权重",
                            enabled = !saving,
                            isError = invalidGroupWeight,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        FieldHint(
                            text = if (invalidGroupWeight) "请输入 0 至 10000 的整数。" else "默认 100，权重越高越容易抽中。",
                            error = invalidGroupWeight,
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
                    SwitchPreference(
                        title = "忽略预算",
                        summary = "跳过普通条目配额，仍受总注入上限约束。",
                        checked = ignoreBudget,
                        onCheckedChange = { ignoreBudget = it },
                        enabled = !saving,
                        insideMargin = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                    )
                }
                item {
                    val options = if (!creating && originalPlacement == null) listOf(null) + WorldBookInjectionPosition.entries
                    else WorldBookInjectionPosition.entries
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PlacementMenu("注入位置", position, options, !saving, { it.positionLabel() }) { position = it }
                        if (position == WorldBookInjectionPosition.BeforeExamples || position == WorldBookInjectionPosition.AfterExamples) {
                            Text(
                                "用 {{char}}: 和 {{user}}: 标记台词，多段示例用 <START> 分隔。",
                                style = COUITheme.textStyles.bodyXS,
                                color = COUITheme.colorScheme.labelSecondary,
                            )
                        }
                        if (atDepth) {
                            CouiTextField(
                                value = depth,
                                onValueChange = { depth = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = "聊天深度",
                                enabled = !saving,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                isError = invalidDepth,
                            )
                            FieldHint(
                                text = if (invalidDepth) "请输入 0 至 10000 的整数。" else "0 在本轮输入后，1 在本轮输入前",
                                error = invalidDepth,
                            )
                            PlacementMenu(
                                "注入角色", injectionRole, WorldBookInjectionRole.entries, !saving, WorldBookInjectionRole::roleLabel,
                            ) { injectionRole = it }
                        }
                        if (atOutlet) {
                            CouiTextField(
                                value = outletName,
                                onValueChange = { outletName = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = "出口名称",
                                enabled = !saving,
                                isError = invalidOutlet && outletName.isNotEmpty(),
                            )
                            FieldHint(
                                text = if (invalidOutlet && outletName.isNotEmpty()) "请输入 1 至 120 个字符，避开花括号。"
                                else "在角色卡里用 {{outlet::名称}} 引用",
                                error = invalidOutlet && outletName.isNotEmpty(),
                            )
                        }
                    }
                }
            }
        },
    )
}

/** 输入框下方的辅助说明（COUI TextField 没有 supportingText）。 */
@Composable
private fun FieldHint(text: String, error: Boolean = false) {
    if (text.isBlank()) return
    Text(
        text,
        style = COUITheme.textStyles.bodyXS,
        color = if (error) COUITheme.colorScheme.error else COUITheme.colorScheme.labelSecondary,
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
        Button(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("$label · ${optionLabel(selected)}")
                Icon(COUIIcons.ExpandMore, contentDescription = null)
            }
        }
        OverlayListPopup(show = expanded, onDismissRequest = { expanded = false }) {
            ListPopupColumn {
                options.forEachIndexed { index, option ->
                    DropdownImpl(
                        text = optionLabel(option),
                        optionSize = options.size,
                        isSelected = option == selected,
                        index = index,
                        onSelectedIndexChange = {
                            expanded = false
                            onSelect(option)
                        },
                    )
                }
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
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CouiTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            label = label,
            enabled = enabled,
            singleLine = false,
            maxLines = 4,
            isError = validation != null,
        )
        FieldHint(validation ?: "每行一个关键词", error = validation != null)
    }
}

private const val keywordLimitMessage = "每组最多 32 个关键词，每个不能超过 120 字符。"

@Composable
private fun TimingField(
    label: String, value: String, enabled: Boolean, invalid: Boolean, help: String, onChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CouiTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            label = label,
            enabled = enabled,
            isError = invalid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            trailingIcon = {
                Text(
                    "条消息",
                    style = COUITheme.textStyles.bodyXS,
                    color = COUITheme.colorScheme.labelSecondary,
                )
            },
        )
        FieldHint(if (invalid) "请输入非负整数。" else help, error = invalid)
    }
}

private fun List<String>.exceedLimits(): Boolean = size > 32 || any { it.length > 120 }

private fun String.toKeywords(): List<String> = lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
