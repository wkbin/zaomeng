package top.wkbin.zaomeng.feature.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardListGroup
import top.wkbin.zaomeng.app.shared.AppMetadata
import top.wkbin.zaomeng.platform.rememberFileExporter
import top.wkbin.zaomeng.platform.rememberOpenExternalUrl
import top.wkbin.zaomeng.platform.rememberToast
import top.wkbin.zaomeng.ui.coui.CouiPage
import top.wkbin.zaomeng.ui.coui.CouiTopBarStyle
import top.wkbin.zaomeng.ui.coui.isWideWindow

@Composable
fun AppSupportSettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val fileExporter = rememberFileExporter(
        onSave = { sink -> viewModel.exportDiagnostics(sink) },
        onCancelled = {},
    )
    val openExternalUrl = rememberOpenExternalUrl()
    val toast = rememberToast()
    CouiPage(
        title = "应用与支持",
        topBarStyle = CouiTopBarStyle.Small,
        onBack = onBack,
        wide = isWideWindow(),
    ) {
        item {
            SettingsSupportGroup(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp),
                state = state,
                onExportDiagnostics = { fileExporter("zaomeng-diagnostics.json", "application/json") },
                onOpenOfficialWebsite = { openExternalUrl("https://wkbin.github.io/zaomeng/") },
                onOpenProject = { openExternalUrl("https://github.com/wkbin/zaomeng") },
                onOpenPackageLibrary = { openExternalUrl("https://github.com/wkbin/zaomeng-library") },
                onJoinQqGroup = {
                    try {
                        openExternalUrl(
                            "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=1090225658&card_type=group&source=qrcode",
                        )
                    } catch (_: Exception) {
                        toast("未检测到 QQ，请搜索群号 1090225658 加入。")
                    }
                },
            )
        }
    }
}

@Composable
private fun SettingsSupportGroup(
    modifier: Modifier = Modifier,
    state: SettingsUiState,
    onExportDiagnostics: () -> Unit,
    onOpenOfficialWebsite: () -> Unit,
    onOpenProject: () -> Unit,
    onOpenPackageLibrary: () -> Unit,
    onJoinQqGroup: () -> Unit,
) {
    Card(modifier = modifier) {
        CardListGroup {
            item {
                SettingsRow(
                    title = "运行诊断",
                    subtitle = "导出启动自检、任务状态与模型连接摘要，不包含密钥或小说内容。",
                    value = if (state.exportingDiagnostics) "导出中" else "导出",
                    enabled = !state.exportingDiagnostics,
                    onClick = onExportDiagnostics,
                )
            }
            item {
                SettingsRow(
                    title = "官方网站",
                    subtitle = "wkbin.github.io/zaomeng",
                    onClick = onOpenOfficialWebsite,
                )
            }
            item {
                SettingsRow(
                    title = "项目地址",
                    subtitle = "github.com/wkbin/zaomeng",
                    onClick = onOpenProject,
                )
            }
            item {
                SettingsRow(
                    title = "书卷包地址",
                    subtitle = "github.com/wkbin/zaomeng-library",
                    onClick = onOpenPackageLibrary,
                )
            }
            item {
                SettingsRow(
                    title = "版本信息",
                    subtitle = "v${AppMetadata.VERSION_NAME}（${AppMetadata.VERSION_CODE}）· 构建于 ${AppMetadata.BUILD_TIME_TEXT}",
                )
            }
            item {
                SettingsRow(
                    title = "加入 QQ 群",
                    subtitle = "交流群：1090225658",
                    onClick = onJoinQqGroup,
                )
            }
        }
    }
}
