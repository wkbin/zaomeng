package top.wkbin.zaomeng.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import top.wkbin.zaomeng.data.preferences.AppPreferencesRepository
import top.wkbin.zaomeng.data.preferences.ThemeMode
import top.wkbin.zaomeng.ui.theme.AppDimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHomeScreen(
    onBack: () -> Unit,
    onOpenModelSettings: () -> Unit,
    onOpenChatDisplay: () -> Unit,
    onOpenPlugins: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenStartupRecovery: () -> Unit,
    onOpenAppSupport: () -> Unit,
    onOpenAppUpdate: () -> Unit,
    preferencesRepository: AppPreferencesRepository = koinInject(),
) {
    val preferences by preferencesRepository.preferences.collectAsStateWithLifecycle(
        initialValue = top.wkbin.zaomeng.data.preferences.AppPreferences(),
    )
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            item { SectionTitle("模型与对话", AppDimens.screenPadding) }
            item {
                SettingsHomeGroup {
                    SettingsHomeRow(
                        title = "模型设置",
                        subtitle = "管理模型档案、服务商和 API Key。",
                        icon = Icons.Outlined.Settings,
                        onClick = onOpenModelSettings,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsHomeRow(
                        title = "插件",
                        subtitle = "管理聊天扩展、权限与运行状态。",
                        icon = Icons.Outlined.Extension,
                        onClick = onOpenPlugins,
                    )
                }
            }
            item { SectionTitle("外观") }
            item {
                SettingsHomeGroup {
                    SettingsHomeRow(
                        title = "主题模式",
                        subtitle = "浅色、深色或跟随系统。",
                        icon = Icons.Outlined.Palette,
                        value = preferences.themeMode.displayName,
                        onClick = onOpenAppearance,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsHomeRow(
                        title = "聊天显示",
                        subtitle = "调整消息字号、显示密度和推理内容。",
                        icon = Icons.Outlined.Chat,
                        onClick = onOpenChatDisplay,
                    )
                }
            }
            item { SectionTitle("应用") }
            item {
                SettingsHomeGroup {
                    SettingsHomeRow(
                        title = "启动与恢复",
                        subtitle = "打开应用后执行的操作。",
                        icon = Icons.Outlined.Settings,
                        onClick = onOpenStartupRecovery,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsHomeRow(
                        title = "检查更新",
                        subtitle = "检查 GitHub Release 中的最新应用版本。",
                        icon = Icons.Outlined.SystemUpdate,
                        onClick = onOpenAppUpdate,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsHomeRow(
                        title = "应用与支持",
                        subtitle = "导出脱敏运行诊断并查看项目资源。",
                        icon = Icons.Outlined.SupportAgent,
                        onClick = onOpenAppSupport,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, topPadding: androidx.compose.ui.unit.Dp = AppDimens.sectionSpacing) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            start = AppDimens.screenPadding,
            top = topPadding,
            end = AppDimens.screenPadding,
            bottom = AppDimens.iconTextGap,
        ),
    )
}

@Composable
private fun SettingsHomeGroup(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = AppDimens.screenPadding),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) { Column(content = { content() }) }
}

@Composable
private fun SettingsHomeRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = AppDimens.cardPadding, vertical = AppDimens.itemSpacing),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        value?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal val ThemeMode.displayName: String
    get() = when (this) {
        ThemeMode.SYSTEM -> "跟随系统"
        ThemeMode.LIGHT -> "浅色模式"
        ThemeMode.DARK -> "深色模式"
    }
