package top.wkbin.zaomeng.feature.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardListGroup
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Backup
import io.github.suqi8.coui.kmp.icon.extended.Help
import io.github.suqi8.coui.kmp.icon.extended.Messages
import io.github.suqi8.coui.kmp.icon.extended.Tasks
import io.github.suqi8.coui.kmp.icon.extended.Theme
import io.github.suqi8.coui.kmp.icon.extended.Tune
import io.github.suqi8.coui.kmp.icon.extended.Update
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.preference.PreferenceCategory
import io.github.suqi8.coui.kmp.theme.COUITheme
import org.koin.compose.koinInject
import top.wkbin.zaomeng.data.preferences.AppPreferences
import top.wkbin.zaomeng.data.preferences.AppPreferencesRepository
import top.wkbin.zaomeng.data.preferences.ThemeMode
import top.wkbin.zaomeng.ui.coui.CouiPage
import top.wkbin.zaomeng.ui.coui.CouiTopBarStyle
import top.wkbin.zaomeng.ui.coui.isWideWindow

@Composable
fun SettingsHomeScreen(
    showBackButton: Boolean = true,
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
        initialValue = AppPreferences(),
    )
    CouiPage(
        title = "设置",
        onBack = if (showBackButton) onBack else null,
        topBarStyle = CouiTopBarStyle.SettingsHome,
        wide = isWideWindow(),
    ) {
        item(key = "model_chat") {
            PreferenceCategory(title = "模型与对话")
            Card(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                CardListGroup {
                    item {
                        SettingsArrowRow(
                            title = "模型设置",
                            summary = "管理模型档案、服务商和 API Key。",
                            icon = COUIIcons.Tune,
                            onClick = onOpenModelSettings,
                        )
                    }
                    item {
                        SettingsArrowRow(
                            title = "插件",
                            summary = "管理聊天扩展、权限与运行状态。",
                            icon = COUIIcons.Tasks,
                            onClick = onOpenPlugins,
                        )
                    }
                }
            }
        }
        item(key = "appearance") {
            PreferenceCategory(title = "外观")
            Card(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                CardListGroup {
                    item {
                        SettingsArrowRow(
                            title = "主题模式",
                            summary = "切换深浅模式、主题色和界面大小。",
                            icon = COUIIcons.Theme,
                            value = preferences.themeMode.displayName,
                            onClick = onOpenAppearance,
                        )
                    }
                    item {
                        SettingsArrowRow(
                            title = "聊天显示",
                            summary = "调整消息字号、显示密度和推理内容。",
                            icon = COUIIcons.Messages,
                            onClick = onOpenChatDisplay,
                        )
                    }
                }
            }
        }
        item(key = "app") {
            PreferenceCategory(title = "应用")
            Card(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                CardListGroup {
                    item {
                        SettingsArrowRow(
                            title = "启动与恢复",
                            summary = "打开应用后执行的操作。",
                            icon = COUIIcons.Backup,
                            onClick = onOpenStartupRecovery,
                        )
                    }
                    item {
                        SettingsArrowRow(
                            title = "检查更新",
                            summary = "检查 GitHub Release 中的最新应用版本。",
                            icon = COUIIcons.Update,
                            onClick = onOpenAppUpdate,
                        )
                    }
                    item {
                        SettingsArrowRow(
                            title = "应用与支持",
                            summary = "导出脱敏运行诊断并查看项目资源。",
                            icon = COUIIcons.Help,
                            onClick = onOpenAppSupport,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsArrowRow(
    title: String,
    summary: String,
    icon: ImageVector,
    value: String? = null,
    onClick: () -> Unit,
) {
    ArrowPreference(
        title = title,
        summary = summary,
        startAction = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = COUITheme.colorScheme.primary,
            )
        },
        endActions = {
            if (value != null) {
                io.github.suqi8.coui.kmp.basic.Text(
                    text = value,
                    style = COUITheme.textStyles.bodyM,
                    color = COUITheme.colorScheme.labelTertiary,
                )
            }
        },
        onClick = onClick,
    )
}

val ThemeMode.displayName: String
    get() = when (this) {
        ThemeMode.SYSTEM -> "跟随系统"
        ThemeMode.LIGHT -> "浅色模式"
        ThemeMode.DARK -> "深色模式"
    }
