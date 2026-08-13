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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import kotlinx.coroutines.launch
import top.wkbin.zaomeng.platform.rememberZipFilePicker
import top.wkbin.zaomeng.data.api.PluginDto
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.Path
import top.wkbin.zaomeng.ui.theme.AppDimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsScreen(
    viewModel: PluginsViewModel = koinViewModel(),
    filesDir: Path,
    onBack: () -> Unit,
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

    if (sourceChooserOpen) {
        AlertDialog(
            onDismissRequest = { sourceChooserOpen = false },
            title = { Text("保存第三方插件包") },
            text = { Text("选择一个 ZIP 插件包。当前版本只保存清单与资源，不会执行包内代码。") },
            confirmButton = {
                Button(onClick = {
                    sourceChooserOpen = false
                    zipPicker()
                }) { Text("选择 ZIP") }
            },
            // TODO: 目录安装（SAF tree -> zip）跨平台化后恢复
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
        AlertDialog(
            onDismissRequest = { uninstallTarget = null },
            title = { Text("卸载「${plugin.name}」？") },
            text = { Text("插件文件会移到应用的可恢复目录；该插件随后将不再出现在列表中。") },
            confirmButton = {
                Button(onClick = {
                    uninstallTarget = null
                    viewModel.uninstall(plugin)
                }) { Text("卸载") }
            },
            dismissButton = {
                TextButton(onClick = { uninstallTarget = null }) { Text("取消") }
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
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("插件") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { sourceChooserOpen = true },
                        enabled = !state.packageBusy && state.busyPluginId.isBlank(),
                    ) {
                        if (state.packageBusy) {
                            CircularProgressIndicator(modifier = Modifier.padding(10.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Outlined.InstallMobile, contentDescription = "保存第三方插件包")
                        }
                    }
                    IconButton(
                        onClick = { viewModel.load(refresh = true) },
                        enabled = !state.refreshing && state.busyPluginId.isBlank(),
                    ) {
                        if (state.refreshing) {
                            CircularProgressIndicator(modifier = Modifier.padding(10.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Outlined.Refresh, contentDescription = "刷新插件")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            state.loading -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 380.dp),
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(AppDimens.screenPadding),
                verticalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing),
                horizontalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    PluginIntroductionCard()
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
                            "当前没有发现插件。把插件放入运行目录后点击右上角刷新。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    }
}

@Composable
private fun PluginIntroductionCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(AppDimens.cardPadding),
            horizontalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Outlined.Extension, contentDescription = null)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("扩展造梦能力", fontWeight = FontWeight.SemiBold)
                Text(
                    "官方内置插件可为聊天增加动作，并由 Kotlin 宿主安全执行。第三方 ZIP 当前只能保存，不能运行；后续仅支持经过能力授权的声明式插件协议。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
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
                        Text(plugin.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (plugin.source == "official") {
                                "官方 · 可执行"
                            } else if (plugin.executable) {
                                "第三方 · 声明式"
                            } else {
                                "第三方 · 仅保存"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (plugin.source == "official") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    Text(
                        "v${plugin.version} · API ${plugin.apiVersion}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.padding(10.dp), strokeWidth = 2.dp)
                } else {
                    Switch(
                        checked = plugin.enabled,
                        onCheckedChange = onEnabledChange,
                        enabled = interactionsEnabled && plugin.executable,
                    )
                }
            }
            if (!plugin.executable) {
                Text(
                    plugin.capabilityNotice.ifBlank { "该插件当前只能保存，不能执行。" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            if (plugin.description.isNotBlank()) {
                Text(plugin.description, style = MaterialTheme.typography.bodyMedium)
            }
            if (plugin.contributes.chatActions.isNotEmpty()) {
                Text(
                    "${if (plugin.executable) "聊天动作" else "清单声明的聊天动作（当前不可用）"}：${plugin.contributes.chatActions.joinToString("、") { it.title }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (plugin.contributes.generationEnhancers.isNotEmpty()) {
                Text(
                    "${if (plugin.executable) "聊天生成增强" else "清单声明的生成增强（当前不可用）"}：${plugin.contributes.generationEnhancers.joinToString("、") { it.title }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (plugin.executable) {
                    Text(
                        "具体开关在各聊天的“插件”菜单中设置。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (plugin.permissions.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("权限", style = MaterialTheme.typography.labelMedium)
                    Text(
                        plugin.permissions.joinToString(" · ") { it.permissionLabel() },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (plugin.error.isNotBlank()) {
                Text(
                    plugin.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(modifier = Modifier.align(Alignment.End)) {
                TextButton(
                    onClick = onDetails,
                    enabled = interactionsEnabled && !busy,
                ) { Text("日志与详情") }
                if (plugin.settings.isNotEmpty() && plugin.executable) {
                    TextButton(
                        onClick = onConfig,
                        enabled = interactionsEnabled && !busy,
                    ) { Text("配置") }
                }
                if (plugin.source == "third-party") {
                TextButton(
                    onClick = onUninstall,
                    enabled = interactionsEnabled && !busy,
                ) { Text("卸载插件") }
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
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("配置「${plugin.name}」") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                plugin.settings.forEach { setting ->
                    val value = draft[setting.key] ?: setting.default
                    when (setting.type) {
                        "boolean" -> Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(setting.title, modifier = Modifier.weight(1f))
                            Switch(
                                checked = value.jsonPrimitive.booleanOrNull ?: false,
                                onCheckedChange = { onValueChange(setting.key, JsonPrimitive(it)) },
                                enabled = !saving,
                            )
                        }
                        "integer" -> {
                            val minimum = setting.min ?: 0
                            val maximum = setting.max ?: 100
                            val current = value.jsonPrimitive.intOrNull ?: minimum
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(setting.title)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(
                                        onClick = { onValueChange(setting.key, JsonPrimitive((current - 1).coerceAtLeast(minimum))) },
                                        enabled = !saving && current > minimum,
                                    ) { Text("－") }
                                    Text(current.toString(), modifier = Modifier.padding(horizontal = 12.dp))
                                    TextButton(
                                        onClick = { onValueChange(setting.key, JsonPrimitive((current + 1).coerceAtMost(maximum))) },
                                        enabled = !saving && current < maximum,
                                    ) { Text("＋") }
                                }
                            }
                        }
                        "enum" -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(setting.title)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val current = value.jsonPrimitive.contentOrNull.orEmpty()
                                setting.options.forEach { option ->
                                    FilterChip(
                                        selected = current == option.value,
                                        onClick = { onValueChange(setting.key, JsonPrimitive(option.value)) },
                                        label = { Text(option.label) },
                                        enabled = !saving,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onSave, enabled = !saving) {
                Text(if (saving) "保存中…" else "保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") }
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${plugin.name} · ${if (plugin.executable) "运行详情" else "清单详情"}") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text("${plugin.id}\nv${plugin.version} · API ${plugin.apiVersion}")
                    if (plugin.error.isNotBlank()) {
                        Text(plugin.error, color = MaterialTheme.colorScheme.error)
                    }
                }
                when {
                    loading -> item { CircularProgressIndicator() }
                    logs.isEmpty() -> item {
                        Text(if (plugin.executable) "还没有运行日志。" else "该第三方包未执行，因此没有运行日志。")
                    }
                    else -> items(logs) { log ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "${log.level.uppercase()} · ${log.event}",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (log.level == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            )
                            Text(log.message, style = MaterialTheme.typography.bodySmall)
                            Text(log.timestamp, style = MaterialTheme.typography.labelSmall)
                            log.details["traceback"]?.jsonPrimitive?.contentOrNull
                                ?.takeIf(String::isNotBlank)
                                ?.let { trace ->
                                    Text(trace, style = MaterialTheme.typography.labelSmall)
                                }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("关闭") } },
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
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(if (inspection.operation == "update") "更新「${plugin.name}」" else "安装「${plugin.name}」")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${plugin.id}\nv${plugin.version} · API ${plugin.apiVersion}")
                if (inspection.operation == "update") {
                    Text("将替换当前 v${inspection.currentVersion}。")
                }
                if (plugin.permissions.isEmpty()) {
                    Text("此插件未申请权限。")
                } else {
                    Text("申请的权限：")
                    plugin.permissions.forEach { Text("• ${it.permissionLabel()}${it.permissionRiskLabel()}") }
                }
                Text(
                    if (plugin.executable) {
                        "此插件使用声明式运行时，可执行其声明的聊天动作；不会运行 Python 或其他任意代码。"
                    } else {
                        "当前版本只保存这个第三方包，不会执行其中的 Python 或其他代码。"
                    },
                )
                if (inspection.blockedReason.isNotBlank()) {
                    Text(inspection.blockedReason, color = MaterialTheme.colorScheme.error)
                }
                if (!inspection.compatible) {
                    Text("该插件与当前宿主 API ${inspection.hostApiVersion} 不兼容。", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            if (blocked) {
                Button(onClick = onDismiss, enabled = !busy) { Text("知道了") }
            } else {
                Button(onClick = onConfirm, enabled = !busy) {
                    Text(if (busy) "处理中…" else if (inspection.operation == "update") "确认保存更新" else "确认保存")
                }
            }
        },
        dismissButton = if (blocked) null else {
            { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } }
        },
    )
}

@Composable
private fun StatusCard(message: String, error: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Text(
            message,
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

private fun String.permissionLabel(): String = when (this) {
    "chat.context.read" -> "读取聊天上下文"
    "chat.cast.write" -> "修改当前会话角色"
    "chat.draft.write" -> "写入聊天草稿"
    "generation.enhance" -> "增强回复生成"
    "run.personas.read" -> "读取当前书卷的已蒸馏人物"
    "model.invoke" -> "调用模型"
    "storage.read" -> "读取插件存储"
    "storage.write" -> "写入插件存储"
    "network.access" -> "访问网络"
    else -> this
}

private fun String.permissionRiskLabel(): String = when (this) {
    "chat.cast.write", "model.invoke", "run.personas.read" -> " · 敏感"
    "storage.read", "storage.write", "network.access" -> " · 高风险"
    else -> ""
}
