package top.wkbin.zaomeng.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardDefaults
import io.github.suqi8.coui.kmp.basic.Chip
import io.github.suqi8.coui.kmp.basic.CircularProgressIndicator
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.Switch
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Add
import io.github.suqi8.coui.kmp.icon.extended.Refresh
import io.github.suqi8.coui.kmp.icon.extended.Tasks
import io.github.suqi8.coui.kmp.icon.extended.Update
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okio.Path
import org.koin.compose.viewmodel.koinViewModel
import top.wkbin.zaomeng.data.api.PluginDto
import top.wkbin.zaomeng.platform.rememberZipFilePicker
import top.wkbin.zaomeng.ui.coui.CouiAlertDialog
import top.wkbin.zaomeng.ui.coui.CouiPageScaffold
import top.wkbin.zaomeng.ui.coui.CouiTopBarStyle
import top.wkbin.zaomeng.ui.coui.couiPageScroll
import top.wkbin.zaomeng.ui.coui.isWideWindow
import top.wkbin.zaomeng.ui.theme.AppDimens

@Composable
fun PluginsScreen(
    viewModel: PluginsViewModel = koinViewModel(),
    filesDir: Path,
    onBack: () -> Unit,
    onOpenBuilder: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var sourceChooserOpen by remember { mutableStateOf(false) }
    var uninstallTarget by remember { mutableStateOf<PluginDto?>(null) }
    val zipPicker = rememberZipFilePicker(filesDir) { filename, bytes ->
        scope.launch {
            runCatching { viewModel.inspectPackage(filename, bytes) }
                .onFailure(viewModel::reportPackageReadError)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }

    CouiPageScaffold(
        title = "插件",
        topBarStyle = CouiTopBarStyle.Small,
        onBack = onBack,
        wide = isWideWindow(),
        actions = {
            IconButton(onClick = onOpenBuilder) {
                Icon(COUIIcons.Add, contentDescription = "制作插件")
            }
            IconButton(
                onClick = { sourceChooserOpen = true },
                enabled = !state.packageBusy && state.busyPluginId.isBlank(),
            ) {
                if (state.packageBusy) {
                    CircularProgressIndicator(modifier = Modifier.padding(10.dp), strokeWidth = 2.dp)
                } else {
                    Icon(COUIIcons.Update, contentDescription = "安装第三方插件包")
                }
            }
            IconButton(
                onClick = { viewModel.load(refresh = true) },
                enabled = !state.refreshing && state.busyPluginId.isBlank(),
            ) {
                if (state.refreshing) {
                    CircularProgressIndicator(modifier = Modifier.padding(10.dp), strokeWidth = 2.dp)
                } else {
                    Icon(COUIIcons.Refresh, contentDescription = "刷新插件")
                }
            }
        },
    ) { scope ->
        Box(Modifier.fillMaxSize()) {
            when {
                state.loading -> Box(
                    modifier = Modifier.fillMaxSize().padding(scope.contentPadding),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 380.dp),
                    modifier = Modifier.couiPageScroll(scope),
                    contentPadding = PaddingValues(
                        start = scope.contentPadding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr) + AppDimens.screenPadding,
                        top = scope.contentPadding.calculateTopPadding() + AppDimens.screenPadding,
                        end = scope.contentPadding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr) + AppDimens.screenPadding,
                        bottom = scope.contentPadding.calculateBottomPadding() + AppDimens.screenPadding,
                    ),
                    verticalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing),
                    horizontalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        PluginIntroductionCard(onOpenBuilder)
                    }
                    if (state.error.isNotBlank()) {
                        item(span = { GridItemSpan(maxLineSpan) }) { StatusCard(state.error, error = true) }
                    }
                    if (state.message.isNotBlank()) {
                        item(span = { GridItemSpan(maxLineSpan) }) { StatusCard(state.message, error = false) }
                    }
                    if (state.plugins.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                "当前没有发现插件。可以从 ZIP 安装，或打开插件工坊制作一个。",
                                style = COUITheme.textStyles.bodyM,
                                color = COUITheme.colorScheme.labelSecondary,
                            )
                        }
                    } else {
                        gridItems(state.plugins, key = PluginDto::id) { plugin ->
                            PluginCard(
                                plugin = plugin,
                                busy = state.busyPluginId == plugin.id,
                                interactionsEnabled = state.busyPluginId.isBlank() && !state.refreshing,
                                onEnabledChange = { enabled -> viewModel.setEnabled(plugin, enabled) },
                                onUninstall = { uninstallTarget = plugin },
                                onDetails = { viewModel.openDetails(plugin) },
                                onConfig = { viewModel.openConfig(plugin) },
                            )
                        }
                    }
                }
            }

        if (sourceChooserOpen) {
            CouiAlertDialog(
                onDismissRequest = { sourceChooserOpen = false },
                title = "安装第三方插件包",
                confirmText = "选择 ZIP",
                onConfirm = {
                    sourceChooserOpen = false
                    zipPicker()
                },
                dismissText = "取消",
                onDismiss = { sourceChooserOpen = false },
                // TODO: 目录安装（SAF tree -> zip）跨平台化后恢复
                body = {
                    Text(
                        "选择一个 ZIP 插件包。Plugin API 2 声明式插件可以在确认权限后安全运行；包内任意代码不会被执行。",
                        style = COUITheme.textStyles.bodyM,
                        color = COUITheme.colorScheme.labelSecondary,
                    )
                },
            )
        }

        state.packageInspection?.let { inspection ->
            PluginPermissionDialog(
                inspection = inspection,
                busy = state.packageBusy,
                onDismiss = viewModel::dismissPackageInspection,
                onConfirm = viewModel::installInspectedPackage,
            )
        }

        uninstallTarget?.let { plugin ->
            CouiAlertDialog(
                onDismissRequest = { uninstallTarget = null },
                title = "卸载「${plugin.name}」？",
                confirmText = "卸载",
                onConfirm = {
                    uninstallTarget = null
                    viewModel.uninstall(plugin)
                },
                dismissText = "取消",
                onDismiss = { uninstallTarget = null },
                body = {
                    Text(
                        "插件文件会移到应用的可恢复目录；该插件随后将不再出现在列表中。",
                        style = COUITheme.textStyles.bodyM,
                        color = COUITheme.colorScheme.labelSecondary,
                    )
                },
            )
        }

        state.detailPlugin?.let { plugin ->
            PluginDetailsDialog(
                plugin = plugin,
                logs = state.detailLogs,
                loading = state.detailLoading,
                onDismiss = viewModel::closeDetails,
            )
        }

        state.configPlugin?.let { plugin ->
            PluginConfigDialog(
                plugin = plugin,
                draft = state.configDraft,
                saving = state.configSaving,
                onValueChange = viewModel::updateConfigValue,
                onDismiss = viewModel::closeConfig,
                onSave = viewModel::saveConfig,
            )
        }
        }
    }
}

@Composable
private fun PluginIntroductionCard(onOpenBuilder: () -> Unit) {
    Card(colors = CardDefaults.defaultColors(color = COUITheme.colorScheme.secondaryContainer)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(AppDimens.cardPadding),
            horizontalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(COUIIcons.Tasks, contentDescription = null)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "扩展造梦能力",
                    style = COUITheme.textStyles.headlineXS,
                    fontWeight = FontWeight.SemiBold,
                    color = COUITheme.colorScheme.labelPrimary,
                )
                Text(
                    "官方插件和 Plugin API 2 声明式插件都由 Kotlin 宿主安全执行。你也可以在插件工坊里只写自然语言提示词，制作并导出自己的插件。",
                    style = COUITheme.textStyles.bodyS,
                    color = COUITheme.colorScheme.labelSecondary,
                )
                Button(onClick = onOpenBuilder) { Text("制作插件") }
            }
        }
    }
}

@Composable
private fun PluginCard(
    plugin: PluginDto,
    busy: Boolean,
    interactionsEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onUninstall: () -> Unit,
    onDetails: () -> Unit,
    onConfig: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AppDimens.cardPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            plugin.name,
                            style = COUITheme.textStyles.headlineXS,
                            fontWeight = FontWeight.SemiBold,
                            color = COUITheme.colorScheme.labelPrimary,
                        )
                        Text(
                            if (plugin.source == "official") {
                                "官方 · 可执行"
                            } else if (plugin.executable) {
                                "第三方 · 声明式"
                            } else {
                                "第三方 · 仅保存"
                            },
                            style = COUITheme.textStyles.bodyXS,
                            color = if (plugin.source == "official") COUITheme.colorScheme.primary else COUITheme.colorScheme.secondary,
                        )
                    }
                    Text(
                        "v${plugin.version} · API ${plugin.apiVersion}",
                        style = COUITheme.textStyles.bodyXS,
                        color = COUITheme.colorScheme.labelSecondary,
                    )
                }
                Switch(
                    checked = plugin.enabled,
                    onCheckedChange = onEnabledChange,
                    enabled = interactionsEnabled && plugin.executable && !busy,
                    isLoading = busy,
                )
            }
            if (!plugin.executable) {
                Text(
                    plugin.capabilityNotice.ifBlank { "该插件当前只能保存，不能执行。" },
                    style = COUITheme.textStyles.bodyS,
                    color = COUITheme.colorScheme.secondary,
                )
            }
            if (plugin.description.isNotBlank()) {
                Text(plugin.description, style = COUITheme.textStyles.bodyM)
            }
            if (plugin.contributes.chatActions.isNotEmpty()) {
                Text(
                    "${if (plugin.executable) "聊天动作" else "清单声明的聊天动作（当前不可用）"}：${plugin.contributes.chatActions.joinToString("、") { it.title }}",
                    style = COUITheme.textStyles.bodyS,
                    color = COUITheme.colorScheme.labelSecondary,
                )
            }
            if (plugin.contributes.generationEnhancers.isNotEmpty()) {
                Text(
                    "${if (plugin.executable) "聊天生成增强" else "清单声明的生成增强（当前不可用）"}：${plugin.contributes.generationEnhancers.joinToString("、") { it.title }}",
                    style = COUITheme.textStyles.bodyS,
                    color = COUITheme.colorScheme.labelSecondary,
                )
                if (plugin.executable) {
                    Text(
                        "具体开关在各聊天的“插件”菜单中设置。",
                        style = COUITheme.textStyles.bodyXS,
                        color = COUITheme.colorScheme.labelSecondary,
                    )
                }
            }
            if (plugin.permissions.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("权限", style = COUITheme.textStyles.bodyS)
                    Text(
                        plugin.permissions.joinToString(" · ") { it.permissionLabel() },
                        style = COUITheme.textStyles.bodyS,
                        color = COUITheme.colorScheme.labelSecondary,
                    )
                }
            }
            if (plugin.error.isNotBlank()) {
                Text(
                    plugin.error,
                    style = COUITheme.textStyles.bodyS,
                    color = COUITheme.colorScheme.error,
                )
            }
            Row(modifier = Modifier.align(Alignment.End)) {
                TextButton(
                    text = "日志与详情",
                    onClick = onDetails,
                    enabled = interactionsEnabled && !busy,
                )
                if (plugin.settings.isNotEmpty() && plugin.executable) {
                    TextButton(
                        text = "配置",
                        onClick = onConfig,
                        enabled = interactionsEnabled && !busy,
                    )
                }
                if (plugin.source == "third-party") {
                    TextButton(
                        text = "卸载插件",
                        onClick = onUninstall,
                        enabled = interactionsEnabled && !busy,
                    )
                }
            }
        }
    }
}

@Composable
private fun PluginConfigDialog(
    plugin: PluginDto,
    draft: JsonObject,
    saving: Boolean,
    onValueChange: (String, kotlinx.serialization.json.JsonElement) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    CouiAlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = "配置「${plugin.name}」",
        confirmText = if (saving) "保存中…" else "保存",
        onConfirm = onSave,
        confirmEnabled = !saving,
        dismissText = "取消",
        onDismiss = { if (!saving) onDismiss() },
        body = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                plugin.settings.forEach { setting ->
                    val value = draft[setting.key] ?: setting.default
                    when (setting.type) {
                        "boolean" -> SwitchPreference(
                            title = setting.title,
                            checked = value.jsonPrimitive.booleanOrNull ?: false,
                            onCheckedChange = { onValueChange(setting.key, JsonPrimitive(it)) },
                            enabled = !saving,
                            insideMargin = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                        )
                        "integer" -> {
                            val minimum = setting.min ?: 0
                            val maximum = setting.max ?: 100
                            val current = value.jsonPrimitive.intOrNull ?: minimum
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(setting.title, style = COUITheme.textStyles.bodyM)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(
                                        text = "－",
                                        onClick = { onValueChange(setting.key, JsonPrimitive((current - 1).coerceAtLeast(minimum))) },
                                        enabled = !saving && current > minimum,
                                    )
                                    Text(
                                        current.toString(),
                                        modifier = Modifier.padding(horizontal = 12.dp),
                                        style = COUITheme.textStyles.bodyM,
                                    )
                                    TextButton(
                                        text = "＋",
                                        onClick = { onValueChange(setting.key, JsonPrimitive((current + 1).coerceAtMost(maximum))) },
                                        enabled = !saving && current < maximum,
                                    )
                                }
                            }
                        }
                        "enum" -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(setting.title, style = COUITheme.textStyles.bodyM)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val current = value.jsonPrimitive.contentOrNull.orEmpty()
                                setting.options.forEach { option ->
                                    Chip(
                                        selected = current == option.value,
                                        onClick = { onValueChange(setting.key, JsonPrimitive(option.value)) },
                                        label = option.label,
                                        enabled = !saving,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun PluginDetailsDialog(
    plugin: PluginDto,
    logs: List<top.wkbin.zaomeng.data.api.PluginLogDto>,
    loading: Boolean,
    onDismiss: () -> Unit,
) {
    CouiAlertDialog(
        onDismissRequest = onDismiss,
        title = "${plugin.name} · ${if (plugin.executable) "运行详情" else "清单详情"}",
        confirmText = "关闭",
        onConfirm = onDismiss,
        dismissText = null,
        body = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        "${plugin.id}\nv${plugin.version} · API ${plugin.apiVersion}",
                        style = COUITheme.textStyles.bodyM,
                    )
                    if (plugin.error.isNotBlank()) {
                        Text(plugin.error, color = COUITheme.colorScheme.error)
                    }
                }
                when {
                    loading -> item { CircularProgressIndicator() }
                    logs.isEmpty() -> item {
                        Text(
                            if (plugin.executable) "还没有运行日志。" else "该第三方包未执行，因此没有运行日志。",
                            style = COUITheme.textStyles.bodyM,
                            color = COUITheme.colorScheme.labelSecondary,
                        )
                    }
                    else -> items(logs) { log ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "${log.level.uppercase()} · ${log.event}",
                                style = COUITheme.textStyles.bodyS,
                                color = if (log.level == "error") COUITheme.colorScheme.error else COUITheme.colorScheme.primary,
                            )
                            Text(log.message, style = COUITheme.textStyles.bodyS)
                            Text(log.timestamp, style = COUITheme.textStyles.bodyXS)
                            log.details["traceback"]?.jsonPrimitive?.contentOrNull
                                ?.takeIf(String::isNotBlank)
                                ?.let { trace ->
                                    Text(trace, style = COUITheme.textStyles.bodyXS)
                                }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun PluginPermissionDialog(
    inspection: top.wkbin.zaomeng.data.api.PluginPackageInspectionDto,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val plugin = inspection.plugin
    val blocked = inspection.operation == "blocked" || !inspection.compatible
    CouiAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = if (inspection.operation == "update") "更新「${plugin.name}」" else "安装「${plugin.name}」",
        confirmText = if (blocked) {
            "知道了"
        } else {
            if (busy) "处理中…" else if (inspection.operation == "update") "确认保存更新" else "确认保存"
        },
        onConfirm = if (blocked) onDismiss else onConfirm,
        confirmEnabled = !busy,
        dismissText = if (blocked) null else "取消",
        onDismiss = { if (!busy) onDismiss() },
        body = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${plugin.id}\nv${plugin.version} · API ${plugin.apiVersion}",
                    style = COUITheme.textStyles.bodyM,
                )
                if (inspection.operation == "update") {
                    Text(
                        "将替换当前 v${inspection.currentVersion}。",
                        style = COUITheme.textStyles.bodyM,
                    )
                }
                if (plugin.permissions.isEmpty()) {
                    Text("此插件未申请权限。", style = COUITheme.textStyles.bodyM)
                } else {
                    Text("申请的权限：", style = COUITheme.textStyles.bodyM)
                    plugin.permissions.forEach {
                        Text(
                            "• ${it.permissionLabel()}${it.permissionRiskLabel()}",
                            style = COUITheme.textStyles.bodyS,
                        )
                    }
                }
                Text(
                    if (plugin.executable) {
                        "此插件使用声明式运行时，可执行其声明的聊天动作；不会运行 Python 或其他任意代码。"
                    } else {
                        "当前版本只保存这个第三方包，不会执行其中的 Python 或其他代码。"
                    },
                    style = COUITheme.textStyles.bodyM,
                    color = COUITheme.colorScheme.labelSecondary,
                )
                if (inspection.blockedReason.isNotBlank()) {
                    Text(inspection.blockedReason, color = COUITheme.colorScheme.error)
                }
                if (!inspection.compatible) {
                    Text(
                        "该插件与当前宿主 API ${inspection.hostApiVersion} 不兼容。",
                        color = COUITheme.colorScheme.error,
                    )
                }
            }
        },
    )
}

@Composable
private fun StatusCard(message: String, error: Boolean) {
    Card(
        colors = CardDefaults.defaultColors(
            color = if (error) COUITheme.colorScheme.containerRed else COUITheme.colorScheme.containerThemeHalftone,
        ),
    ) {
        Text(
            message,
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            color = COUITheme.colorScheme.labelPrimary,
            style = COUITheme.textStyles.bodyM,
        )
    }
}

private fun String.permissionLabel(): String = when (this) {
    "chat.context.read" -> "读取聊天上下文"
    "chat.cast.write" -> "修改当前会话角色"
    "chat.draft.write" -> "写入聊天草稿"
    "generation.enhance" -> "增强回复生成"
    "run.personas.read" -> "读取当前书卷的已蒸馏人物"
    "session.modify" -> "修改当前会话人物状态"
    "model.invoke" -> "调用模型"
    "storage.read" -> "读取插件存储"
    "storage.write" -> "写入插件存储"
    "network.access" -> "访问网络"
    else -> this
}

private fun String.permissionRiskLabel(): String = when (this) {
    "chat.cast.write", "model.invoke", "run.personas.read", "session.modify" -> " · 敏感"
    "storage.read", "storage.write", "network.access" -> " · 高风险"
    else -> ""
}
