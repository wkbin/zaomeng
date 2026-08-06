package top.wkbin.zaomeng.feature.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSupportSettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val diagnosticsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> if (uri != null) viewModel.exportDiagnostics(uri) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("应用与支持") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            SettingsSupportGroup(
                modifier = Modifier.fillMaxWidth(),
                state = state,
                onExportDiagnostics = { diagnosticsLauncher.launch("zaomeng-diagnostics.json") },
                onOpenProject = {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            "https://github.com/wkbin/zaomeng".toUri()
                        )
                    )
                },
                onOpenPackageLibrary = {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            "https://github.com/wkbin/zaomeng-library".toUri()
                        )
                    )
                },
                onJoinQqGroup = {
                    try {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=1090225658&card_type=group&source=qrcode".toUri(),
                            ),
                        )
                    } catch (_: Exception) {
                        Toast.makeText(
                            context,
                            "未检测到 QQ，请搜索群号 1090225658 加入。",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                },
            )
        }
    }
}

@Composable
private fun SettingsSupportGroup(
    modifier: Modifier,
    state: SettingsUiState,
    onExportDiagnostics: () -> Unit,
    onOpenProject: () -> Unit,
    onOpenPackageLibrary: () -> Unit,
    onJoinQqGroup: () -> Unit,
) {
    androidx.compose.material3.Card(
        modifier = modifier,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        androidx.compose.foundation.layout.Column {
            SettingsRow(
                title = "运行诊断",
                subtitle = "导出启动自检、任务状态与模型连接摘要，不包含密钥或小说内容。",
                value = if (state.exportingDiagnostics) "导出中" else "导出",
                enabled = !state.exportingDiagnostics,
                onClick = onExportDiagnostics,
            )
            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsRow(
                title = "项目地址",
                subtitle = "github.com/wkbin/zaomeng",
                onClick = onOpenProject,
            )
            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsRow(
                title = "书卷包地址",
                subtitle = "github.com/wkbin/zaomeng-library",
                onClick = onOpenPackageLibrary,
            )
            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsRow(
                title = "加入 QQ 群",
                subtitle = "交流群：1090225658",
                onClick = onJoinQqGroup,
            )
        }
    }
}
